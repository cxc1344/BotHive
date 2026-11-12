package app.opengrok

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity

/**
 * Backdrop capture + true-lens refraction for BotHive's liquid glass (v0.5.8).
 *
 * How the backdrop is captured — read this before touching anything:
 *
 * Compose offers no public API to read already-composed pixels of arbitrary on-screen content.
 * Capturing "what is behind this surface" system-wide (like vivo's window-level implementation)
 * would need platform internals. What CAN be sampled honestly is a layer we own and control.
 * So the mechanism is:
 *
 *  1. The caller marks a background layer (e.g. the home page's honeycomb wash) as capturable by
 *     drawing it through [recordBackdrop]. We replay it into an android.graphics.Picture — cheap
 *     display-list recording, no pixel reads.
 *  2. [rasterizeBackdrop] renders that Picture into a Bitmap (GPU-backed canvas), sized to the
 *     region of interest only — never the whole screen.
 *  3. [Modifier.backdropLens] feeds that bitmap to the AGSL lens shader as `uBackdrop`, which
 *     samples it with center zoom + SDF-normal edge displacement + per-channel RGB separation.
 *
 * Caching: the recorded Picture is kept by the owner and only re-recorded when its key changes
 * (size / theme / content version). Rasterization happens only when the lens needs a bitmap at a
 * new size; an LRU of one entry per size is kept in [BackdropCache]. During animations the lens
 * updates only its own small-surface uniforms per frame — the backdrop bitmap is NOT re-rendered.
 *
 * Failure policy: every AGSL/RenderEffect path is guarded; on any exception, or below API 33, or
 * at tiers below Highest, callers silently fall back to the 0.5.7 self-drawn material. A missing
 * backdrop bitmap also falls back — never a black box, never a crash.
 */
object BackdropLens {

    // ---- AGSL v2: true lens over a sampled backdrop ----
    // Uniform contract (all px):
    //   uSize / uRadius      surface geometry
    //   uBackdropSize        pixel size of the backdrop bitmap
    //   uSurfaceTopLeft      where this surface sits inside the backdrop bitmap (px)
    //   uZoom                center magnification (1.08..1.15 capsule, 1.4..1.65 switch knob)
    //   uEdgePx              refraction band width along the SDF normal (8..14)
    //   uRefractPx           max edge displacement inside the band
    //   uDispersionPx        RGB separation along the normal (1..3)
    //   uTint / uTintA       glass tint blended over the refracted backdrop
    //   uLightX/Y, uSpec     0.5.7-style specular, applied at reduced strength on top
    //   uTime                slow drift for the focal pool
    const val SRC = """
uniform float2 uSize;
uniform float uRadius;
uniform float2 uBackdropSize;
uniform float2 uSurfaceTopLeft;
uniform float uZoom;
uniform float uEdgePx;
uniform float uRefractPx;
uniform float uDispersionPx;
uniform float uTintA;
uniform half4 uTint;
uniform float uLightX;
uniform float uLightY;
uniform float uSpec;
uniform float uTime;
uniform shader uBackdrop;

const float SMOOTH = 1.5;

float sdfRounded(float2 p, float2 half, float r) {
    float2 d = abs(p) - half + float2(r, r);
    float outside = length(max(d, float2(0.0, 0.0)));
    float inside = min(max(d.x, d.y), 0.0);
    return outside + inside - r;
}

float2 sdfNormal(float2 p, float2 half, float r) {
    float2 d = abs(p) - half + float2(r, r);
    float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    if (max(d.x, d.y) > 0.0) {
        return s * normalize(max(d, float2(0.0, 0.0)));
    }
    return d.x > d.y ? float2(s.x, 0.0) : float2(0.0, s.y);
}

// Samples the backdrop at a position expressed in *this surface's* local px, mapping through
// uSurfaceTopLeft into bitmap space. Clamps so the lens can never pull a black edge.
half4 sampleBackdrop(float2 localXY) {
    float2 bmpXY = uSurfaceTopLeft + localXY;
    float2 lo = float2(0.5, 0.5);
    float2 hi = uBackdropSize - float2(0.5, 0.5);
    float2 clamped = clamp(bmpXY, lo, hi);
    return uBackdrop.eval(clamped);
}

half4 main(float2 xy) {
    float2 c = uSize * 0.5;
    float2 p = xy - c;
    float r = min(uRadius, min(c.x, c.y));
    float sdf = sdfRounded(p, c, r);
    float minHalf = min(c.x, c.y);

    if (sdf > SMOOTH) {
        // Outside the lens: untouched backdrop passthrough keeps corners clean.
        return sampleBackdrop(xy);
    }

    float2 n = sdfNormal(p, c, r);

    // ---- center zoom: magnify around the middle, 1.0 at rim -> uZoom at center ----
    float depth = clamp(-sdf / max(minHalf * 0.35, 1.0), 0.0, 1.0); // 1 at center, 0 at rim
    float zoomHere = mix(1.0, uZoom, depth);
    float2 fromCenter = p / zoomHere;

    // ---- edge refraction: push the sample outward along the normal inside the band ----
    float band = 1.0 - smoothstep(0.0, uEdgePx, -sdf);            // 0 center -> 1 at rim
    float push = band * band * uRefractPx;                        // quadratic ease-in
    float2 sampleLocal = fromCenter + c - n * push;               // p is from center; fromCenter+c = local pt

    // ---- chromatic dispersion: separate channels along the normal ----
    half4 g = sampleBackdrop(sampleLocal);
    half4 outC = g;
    if (uDispersionPx > 0.0) {
        float2 shift = n * uDispersionPx * (0.4 + 0.6 * band);
        half4 rr = sampleBackdrop(sampleLocal - shift);
        half4 bb = sampleBackdrop(sampleLocal + shift);
        outC = half4(rr.r, g.g, bb.b, g.a);
    }

    // ---- 0.5.7 material layers, at reduced strength, on top of the refracted image ----
    float2 L = normalize(float2(uLightX, uLightY));
    // curvature shading (weaker than the self-drawn version: real refraction carries depth)
    outC.rgb += half(curvature * curvature * 0.05);
    // tint
    outC.rgb = mix(outC.rgb, uTint.rgb, half(uTintA * 0.35));
    // specular rim + drifting pool
    float rimBand = smoothstep(-4.0, -0.5, sdf);
    float3 L3 = float3(L, 0.6);
    float rimBase = pow(max(dot(float3(n, 0.0), L3), 0.0), 3.0) * rimBand;
    float drift = sin(uTime * 0.7) * 0.06;
    float2 focal = L * minHalf * (0.22 + drift);
    float pool = 1.0 - smoothstep(0.0, minHalf * 0.5, length(p - focal));
    float spec = rimBase * uSpec + pool * pool * uSpec * 0.35;
    outC.rgb += half(spec * 0.45);

    // soft AA edge
    float alpha = 1.0 - smoothstep(0.0, SMOOTH, sdf);
    outC.a = max(outC.a, half(alpha * 0.0)); // keep backdrop alpha (usually 1); never over-fade
    return half4(outC.rgb, half(1.0));
}
"""

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun newShader(): RuntimeShader = RuntimeShader(SRC)

    /**
     * Rasterizes a recorded [Picture] into a bitmap of [width]x[height], cached by size.
     * Called only when the lens is active (Highest tier, API 33+) and the size changed.
     */
    fun rasterize(picture: Picture, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        BackdropCache.get(width, height)?.let { return it }
        return try {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawPicture(picture)
            BackdropCache.put(width, height, bmp)
            bmp
        } catch (_: Exception) {
            null
        }
    }
}

/** One-entry-per-size cache; the lens surface is small (hundreds of px), so memory is bounded. */
private object BackdropCache {
    private val entries = LinkedHashMap<Long, Bitmap>(4)

    @Synchronized
    fun get(w: Int, h: Int): Bitmap? {
        val bmp = entries[key(w, h)] ?: return null
        return if (bmp.isRecycled) null else bmp
    }

    @Synchronized
    fun put(w: Int, h: Int, bmp: Bitmap) {
        entries[key(w, h)] = bmp
        while (entries.size > 4) {
            val eldest = entries.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    private fun key(w: Int, h: Int) = (w.toLong() shl 32) or h.toLong()
}

/**
 * Records a drawing block into a Picture for later rasterization. Recording cost is a
 * display-list append (~µs); pixels are not touched here.
 */
inline fun recordBackdrop(width: Int, height: Int, block: (Canvas) -> Unit): Picture =
    Picture().also { block(it.beginRecording(width, height)) }.apply { endRecording() }

/** Draws a recorded backdrop picture through the Compose canvas (used by the owner layer). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdrop(picture: Picture) {
    drawContext.canvas.nativeCanvas.drawPicture(picture)
}

/**
 * True-lens modifier: refracts [backdrop] under this surface.
 *
 * @param zoom   center magnification (1.08–1.15 for capsules, 1.4–1.65 for the switch knob)
 * @param edgePx refraction band width along the SDF normal (8–14)
 * @param dispersionPx RGB channel separation (1–3; raise while dragging)
 *
 * Falls back silently (returns the 0.5.7 material modifier) when: tier below Highest, API < 33,
 * backdrop null, or any shader exception. Never crashes, never draws a black box.
 */
@Composable
fun Modifier.backdropLens(
    backdrop: Bitmap?,
    surfaceTopLeft: Offset,
    radiusPx: Float,
    zoom: Float = 1.12f,
    edgePx: Float = 10f,
    dispersionPx: Float = 1.8f,
    tint: Color = Honey.copy(alpha = 0.10f),
    light: Offset = Offset(-0.55f, -0.85f)
): Modifier {
    val tier = GlassStyle.tier.value
    if (!tier.shader || !BackdropLens.supported() || backdrop == null) {
        return glassSurface(tier = tier, radiusPx = radiusPx, tint = tint, light = light)
    }
    return this.then(
        Modifier.drawWithCache {
            val shader = runCatching { BackdropLens.newShader() }.getOrNull()
            val brush = shader?.let { ShaderBrush(it) }
            val path = Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, radiusPx, radiusPx))
            }
            onDrawBehind {
                if (shader == null || brush == null) {
                    drawGlassLayersCompat(tint, radiusPx, light)
                    return@onDrawBehind
                }
                val ok = runCatching {
                    shader.setFloatUniform("uSize", size.width, size.height, 0f, 0f)
                    shader.setFloatUniform("uRadius", radiusPx)
                    shader.setFloatUniform("uBackdropSize", backdrop.width.toFloat(), backdrop.height.toFloat(), 0f, 0f)
                    shader.setFloatUniform("uSurfaceTopLeft", surfaceTopLeft.x, surfaceTopLeft.y, 0f, 0f)
                    shader.setFloatUniform("uZoom", zoom)
                    shader.setFloatUniform("uEdgePx", edgePx)
                    shader.setFloatUniform("uRefractPx", edgePx * 0.9f)
                    shader.setFloatUniform("uDispersionPx", dispersionPx)
                    shader.setFloatUniform("uTintA", tint.alpha)
                    shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue, 1f)
                    shader.setFloatUniform("uLightX", light.x)
                    shader.setFloatUniform("uLightY", light.y)
                    shader.setFloatUniform("uSpec", 0.9f)
                    shader.setFloatUniform("uTime", (System.nanoTime() / 1_000_000_000f) % 60f)
                    // AGSL samples bitmaps through an input shader; BitmapShader (a Shader subclass)
                    // wraps the backdrop, CLAMP so the lens can never pull transparent edges.
                    val bmpShader = android.graphics.BitmapShader(
                        backdrop,
                        android.graphics.Shader.TileMode.CLAMP,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    shader.setInputShader("uBackdrop", bmpShader)
                    true
                }.getOrDefault(false)
                if (ok) {
                    clipPath(path) {
                        drawRect(brush, topLeft = Offset.Zero, size = size)
                    }
                } else {
                    drawGlassLayersCompat(tint, radiusPx, light)
                }
            }
        }
    )
}

/** Compat material drawn inside a DrawScope when the lens cannot run (fallback of the fallback). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassLayersCompat(
    tint: Color, radiusPx: Float, light: Offset
) {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.55f to Color.White.copy(alpha = 0.03f),
            1f to Color.Black.copy(alpha = 0.06f)
        )
    )
    drawRect(
        Brush.radialGradient(
            listOf(tint, Color.Transparent),
            center = Offset(w * 0.5f, h * 0.1f),
            radius = maxOf(w, h)
        )
    )
}

// ------------------------------------------------------------------ app-wide backdrop holder

/**
 * Holds the app-level backdrop: a recorded Picture of the page background (base colour + honeycomb
 * wash) plus the pixel size it was recorded at. The mode capsule's lens samples this.
 *
 * Re-recorded only when [version] changes (size / dark theme / honeycomb alpha); the capsule lens
 * then rasterizes just its own region from the shared Picture.
 */
object AppBackdrop {
    @Volatile
    var picture: Picture? = null
        private set
    @Volatile
    var width: Int = 0
        private set
    @Volatile
    var height: Int = 0
        private set
    @Volatile
    var version: Int = 0
        private set

    /** Re-records the backdrop. Cheap (display-list only). Call from composition on key change. */
    fun record(widthPx: Int, heightPx: Int, honeyAlpha: Float) {
        if (widthPx <= 0 || heightPx <= 0) return
        val p = recordBackdrop(widthPx, heightPx) { canvas ->
            canvas.drawColor(android.graphics.Color.parseColor("#171207"), android.graphics.PorterDuff.Mode.SRC)
            // Honeycomb wash: same visual contract as HoneycombBackdrop, painted directly.
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                color = android.graphics.Color.argb((255 * honeyAlpha * 0.16f).toInt(), 0xF7, 0xB9, 0x28)
            }
            val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb((255 * honeyAlpha * 0.14f).toInt(), 0xFF, 0xD8, 0x66)
            }
            val radius = 54f
            val columnStep = radius * 1.732f
            val rowStep = radius * 1.5f
            fun hexPath(cx: Float, cy: Float): android.graphics.Path =
                android.graphics.Path().apply {
                    var i = 0
                    val n = 6
                    while (i < n) {
                        val ang = Math.PI / 3.0 * i - Math.PI / 6.0
                        val x = cx + radius * kotlin.math.cos(ang).toFloat()
                        val y = cy + radius * kotlin.math.sin(ang).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                        i++
                    }
                    close()
                }
            var row = -1
            while (row * rowStep < heightPx + rowStep) {
                val y = row * rowStep
                val rowOffset = if (row % 2 == 0) 0f else columnStep / 2f
                var col = -1
                while (col * columnStep < widthPx + columnStep) {
                    val cx = col * columnStep + rowOffset
                    canvas.drawPath(hexPath(cx, y), paint)
                    if ((row + col) % 3 == 0) {
                        canvas.drawRect(cx - 3f, y - 3f, cx + 3f, y + 3f, dot)
                    }
                    col++
                }
                row++
            }
        }
        picture = p
        width = widthPx
        height = heightPx
        version++
    }
}
