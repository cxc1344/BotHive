package app.opengrok

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import android.graphics.BitmapShader
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
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
uniform float2 uBackdropScale;   // (capturedW / rootW, capturedH / rootH) explicit scale mapping
uniform float2 uSurfaceTopLeft;
uniform float uZoom;
uniform float uEdgePx;
uniform float uRefractPx;
uniform float uDispersionPx;
uniform float uTintA;
uniform float uVeil;            // thickness veil alpha from ImmersiveThickness.veilAlpha
uniform float uLightX;
uniform float uLightY;
uniform float uSpec;            // specular intensity from ImmersiveThickness.specStrength
uniform float uGlowBoost;       // live interactive boost (0..1) on press/drag
uniform float uTime;
uniform float uBlurRadius;      // Gaussian blur radius from ImmersiveThickness.blurRadius
uniform float uTintR;           // tint R (0-255)
uniform float uTintG;           // tint G (0-255)
uniform float uTintB;           // tint B (0-255)
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

// 9-tap dual-ring circular bokeh blur with explicit downsampling scale mapping (no cross ghosting)
half4 sampleBackdropBlurred(float2 localXY, float blurRadius) {
    float2 bmpXY = (uSurfaceTopLeft + localXY) * uBackdropScale;
    float2 lo = float2(0.5, 0.5);
    float2 hi = max(lo, uBackdropSize - float2(0.5, 0.5));
    if (blurRadius <= 0.15) {
        return uBackdrop.eval(clamp(bmpXY, lo, hi));
    }
    float2 r1 = uBackdropScale * (blurRadius * 0.52);
    float2 r2 = uBackdropScale * (blurRadius * 0.92);
    half4 sum = uBackdrop.eval(clamp(bmpXY, lo, hi)) * half(0.24);
    // Inner orthogonal ring
    sum += uBackdrop.eval(clamp(bmpXY + float2(r1.x, 0.0), lo, hi)) * half(0.11);
    sum += uBackdrop.eval(clamp(bmpXY - float2(r1.x, 0.0), lo, hi)) * half(0.11);
    sum += uBackdrop.eval(clamp(bmpXY + float2(0.0, r1.y), lo, hi)) * half(0.11);
    sum += uBackdrop.eval(clamp(bmpXY - float2(0.0, r1.y), lo, hi)) * half(0.11);
    // Outer diagonal ring (0.7071 = cos(45 deg))
    float2 d2 = r2 * 0.7071;
    sum += uBackdrop.eval(clamp(bmpXY + float2(d2.x, d2.y), lo, hi)) * half(0.08);
    sum += uBackdrop.eval(clamp(bmpXY + float2(-d2.x, d2.y), lo, hi)) * half(0.08);
    sum += uBackdrop.eval(clamp(bmpXY + float2(d2.x, -d2.y), lo, hi)) * half(0.08);
    sum += uBackdrop.eval(clamp(bmpXY + float2(-d2.x, -d2.y), lo, hi)) * half(0.08);
    return sum;
}

// System-grade optical vibrancy: lifts color richness & translucency so dark-mode glass glows instead of muddying
half3 applyVibrancy(half3 rgb, float satBoost, float lumLift) {
    half luma = dot(rgb, half3(0.2126, 0.7152, 0.0722));
    half3 sat = mix(half3(luma), rgb, half(satBoost));
    return clamp(sat + half3(lumLift), half3(0.0), half3(1.0));
}

half4 main(float2 xy) {
    float2 c = uSize * 0.5;
    float2 p = xy - c;
    float r = min(uRadius, min(c.x, c.y));
    float sdf = sdfRounded(p, c, r);
    float minHalf = min(c.x, c.y);

    if (sdf > SMOOTH) {
        return sampleBackdropBlurred(xy, 0.0);
    }

    float2 n = sdfNormal(p, c, r);

    // ---- 1. Optical center zoom (convex lens core: 1.0 at rim -> uZoom at center) ----
    float depth = clamp(-sdf / max(minHalf * 0.42, 1.0), 0.0, 1.0);
    float zoomHere = mix(1.0, uZoom, depth * depth);
    float2 fromCenter = p / zoomHere;

    // ---- 2. Snell bevel edge refraction along SDF normal ----
    float band = 1.0 - smoothstep(0.0, uEdgePx, -sdf);
    float bevelCurve = band * band * (1.25 - 0.25 * band);
    float push = bevelCurve * uRefractPx;
    float2 sampleLocal = fromCenter + c - n * push;

    // ---- 3. Full-interior frost + stronger bevel chromatic dispersion ----
    float blurHere = uBlurRadius * mix(1.0, 0.62, depth);
    half4 g = sampleBackdropBlurred(sampleLocal, blurHere);
    half4 outC = g;
    if (uDispersionPx > 0.0) {
        float2 shift = n * uDispersionPx * (0.28 + 0.72 * band);
        half4 rr = sampleBackdropBlurred(sampleLocal - shift, blurHere * 0.92);
        half4 bb = sampleBackdropBlurred(sampleLocal + shift, blurHere * 0.92);
        outC = half4(rr.r, g.g, bb.b, g.a);
    }

    // ---- 4. HarmonyOS Immersive Light vibrancy + diffused optical veil ----
    outC.rgb = applyVibrancy(outC.rgb, 1.24 + uGlowBoost * 0.12, 0.025 + uVeil * 0.08);
    half3 tintCol = half3(uTintR / 255.0, uTintG / 255.0, uTintB / 255.0);
    outC.rgb = mix(outC.rgb, tintCol, half(clamp(uVeil * 0.78 + uTintA * 0.30, 0.0, 0.56)));

    // ---- 5. Dual-light Fresnel bevel rim (top-left primary warm + bottom-right cool bounce) ----
    float2 L = normalize(float2(uLightX, uLightY));
    float2 L2 = -L;
    float rimOuter = smoothstep(-5.0, -0.35, sdf);
    float rimCrisp = smoothstep(-1.8, -0.25, sdf);
    float primaryRim = pow(max(dot(n, L), 0.0), 2.2) * rimOuter;
    float secondaryRim = pow(max(dot(n, L2), 0.0), 2.8) * rimOuter;

    // Subtle flowing edge streamer animated along perimeter
    float flowAngle = atan(p.y, p.x);
    float streamer = 0.5 + 0.5 * sin(flowAngle * 2.0 - uTime * 1.15);
    float edgeGlow = (primaryRim * (0.85 + 0.35 * streamer) + secondaryRim * 0.32) * uSpec;

    // Interactive caustic light pool (expands & brightens smoothly on press/drag via uGlowBoost)
    float drift = sin(uTime * 0.65) * 0.07 + uGlowBoost * 0.16;
    float2 focal = L * minHalf * (0.24 + drift);
    float pool = 1.0 - smoothstep(0.0, minHalf * (0.58 + uGlowBoost * 0.32), length(p - focal));
    float caustic = pool * pool * uSpec * (0.22 + uGlowBoost * 0.42);

    // Subtle convex depth sheen
    float curvature = 1.0 - depth;
    outC.rgb += half3(0.98, 0.96, 0.92) * half(edgeGlow * 0.46 + caustic * 0.40);
    outC.rgb += half3(0.76, 0.88, 1.00) * half(secondaryRim * uSpec * 0.16);
    outC.rgb += half3(1.00, 0.99, 0.97) * half(rimCrisp * (0.14 + uGlowBoost * 0.22));
    outC.rgb += half(curvature * curvature * 0.045);

    return half4(clamp(outC.rgb, half3(0.0), half3(1.0)), half(1.0));
}
"""

    @Volatile
    private var shaderFailureLogged = false

    internal fun logShaderFailureOnce(stage: String, error: Throwable) {
        if (!shaderFailureLogged) {
            shaderFailureLogged = true
            Log.d("BackdropLens", "AGSL lens fallback at $stage: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

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
    surfaceTopLeft: Offset,
    radiusPx: Float,
    thickness: ImmersiveThickness = ImmersiveThickness.THIN,
    zoom: Float = 1.12f,
    glowBoost: Float = 0f,
    tint: Color = Honey.copy(alpha = 0.10f),
    light: Offset = Offset(-0.55f, -0.85f)
): Modifier {
    val tier = GlassStyle.tier.value
    if (!tier.shader || !BackdropLens.supported()) {
        return glassSurface(tier = tier, radiusPx = radiusPx, tint = tint, light = light, thickness = thickness, glowBoost = glowBoost)
    }
    return this.then(
        Modifier.drawWithCache {
            val shader = runCatching { BackdropLens.newShader() }
                .onFailure { BackdropLens.logShaderFailureOnce("compile", it) }
                .getOrNull()
            val brush = shader?.let { ShaderBrush(it) }
            val path = Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, radiusPx, radiusPx))
            }
            onDrawBehind {
                // Exclude glass surfaces while the root content layer is capturing the backdrop.
                if (AppBackdrop.isCapturing) return@onDrawBehind
                // Read frameTick ONLY in the Draw phase so new live frames invalidate glass draw
                // without triggering recomposition or parent content redraws (zero feedback loop).
                @Suppress("UNUSED_VARIABLE")
                val tick = AppBackdrop.frameTick.intValue
                val frame = AppBackdrop.currentFrame()
                if (shader == null || brush == null || frame == null || frame.bitmap.isRecycled) {
                    drawGlassLayersCompat(tint, thickness, glowBoost)
                    return@onDrawBehind
                }
                val ok = runCatching {
                    val effectiveSpec = thickness.specStrength * (1f + glowBoost * 0.75f)
                    shader.setFloatUniform("uSize", size.width, size.height)
                    shader.setFloatUniform("uRadius", radiusPx)
                    shader.setFloatUniform("uBackdropSize", frame.bitmap.width.toFloat(), frame.bitmap.height.toFloat())
                    shader.setFloatUniform("uBackdropScale", frame.scaleX, frame.scaleY)
                    shader.setFloatUniform("uSurfaceTopLeft", surfaceTopLeft.x, surfaceTopLeft.y)
                    shader.setFloatUniform("uZoom", zoom)
                    shader.setFloatUniform("uEdgePx", thickness.edgePx)
                    shader.setFloatUniform("uRefractPx", thickness.edgePx * 0.9f)
                    shader.setFloatUniform("uDispersionPx", thickness.dispersionPx)
                    shader.setFloatUniform("uBlurRadius", thickness.blurRadius)
                    shader.setFloatUniform("uVeil", thickness.veilAlpha)
                    shader.setFloatUniform("uTintR", tint.red * 255f)
                    shader.setFloatUniform("uTintG", tint.green * 255f)
                    shader.setFloatUniform("uTintB", tint.blue * 255f)
                    shader.setFloatUniform("uTintA", tint.alpha)
                    shader.setFloatUniform("uLightX", light.x)
                    shader.setFloatUniform("uLightY", light.y)
                    shader.setFloatUniform("uSpec", effectiveSpec)
                    shader.setFloatUniform("uGlowBoost", glowBoost)
                    shader.setFloatUniform("uTime", (System.nanoTime() / 1_000_000_000f) % 60f)
                    shader.setInputShader("uBackdrop", frame.shader)
                    true
                }.onFailure { BackdropLens.logShaderFailureOnce("uniforms", it) }
                    .getOrDefault(false)
                if (ok) {
                    clipPath(path) {
                        drawRect(brush, topLeft = Offset.Zero, size = size)
                    }
                } else {
                    drawGlassLayersCompat(tint, thickness, glowBoost)
                }
            }
        }
    )
}

/** Compat material drawn inside a DrawScope when the lens shader fails — preserves 5-tier veil/spec differences. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassLayersCompat(
    tint: Color, thickness: ImmersiveThickness, glowBoost: Float
) {
    val w = size.width
    val h = size.height
    val veil = (thickness.veilAlpha * 1.15f).coerceIn(0.06f, 0.45f)
    val hi = (0.07f + thickness.specStrength * (0.08f + glowBoost * 0.08f)).coerceAtMost(0.30f)
    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = hi),
            0.52f to Color.White.copy(alpha = veil * 0.18f),
            1f to Color.Black.copy(alpha = veil * 0.55f)
        )
    )
    drawRect(
        Brush.radialGradient(
            listOf(tint.copy(alpha = (tint.alpha + veil * 0.35f).coerceAtMost(0.45f)), Color.Transparent),
            center = Offset(w * 0.5f, h * 0.12f),
            radius = maxOf(w, h)
        )
    )
    drawRect(
        Brush.linearGradient(0f to Color.White.copy(alpha = (hi * 1.35f).coerceAtMost(0.38f)), 0.35f to Color.Transparent),
        size = Size(w, 2.0f)
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
    /** Downsample factor for live content capture (0.5 = 1/4 pixel area); passed explicitly as uBackdropScale. */
    const val CAPTURE_SCALE = 0.5f

    data class CapturedFrame(
        val bitmap: Bitmap,
        val shader: BitmapShader,
        val scaleX: Float,
        val scaleY: Float,
        val isLive: Boolean
    )

    /** True ONLY while the root content layer is recording into its offscreen GraphicsLayer. */
    @Volatile
    var isCapturing: Boolean = false

    /** Non-Compose dirty counter incremented when the root content layer actually redraws. */
    @Volatile
    var dirtySeq: Long = 0L
        private set

    /** Root content pixel dimensions captured on the latest content draw pass. */
    @Volatile
    var width: Int = 0
        private set
    @Volatile
    var height: Int = 0
        private set

    /**
     * Draw-phase-only invalidation tick. Read ONLY inside BackdropLens onDrawBehind (never in
     * composition or parent content draw), so publishing a live frame refreshes glass refraction
     * without causing recomposition or content-capture loops.
     */
    val frameTick = mutableIntStateOf(0)

    @Volatile
    private var liveFrame: CapturedFrame? = null

    @Volatile
    private var fallbackPicture: Picture? = null
    @Volatile
    private var fallbackFrame: CapturedFrame? = null

    fun markContentDirty(rootW: Int, rootH: Int) {
        width = rootW
        height = rootH
        dirtySeq++
    }

    /** Publishes a real-content bitmap captured asynchronously from the root GraphicsLayer. */
    fun publishLiveFrame(bitmap: Bitmap, rootW: Int, rootH: Int) {
        if (bitmap.width <= 0 || bitmap.height <= 0 || rootW <= 0 || rootH <= 0) return
        var target = bitmap
        var shader = runCatching {
            BitmapShader(target, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }.getOrNull()
        if (shader == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && target.config == Bitmap.Config.HARDWARE) {
            // Some OEM GPUs reject HARDWARE bitmaps inside RuntimeShader BitmapShader; copy to ARGB_8888 once.
            val soft = runCatching { target.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            if (soft != null) {
                target = soft
                shader = runCatching {
                    BitmapShader(target, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }.onFailure { BackdropLens.logShaderFailureOnce("bitmapShader", it) }.getOrNull()
            }
        }
        val finalShader = shader ?: return
        val sx = target.width.toFloat() / rootW.toFloat()
        val sy = target.height.toFloat() / rootH.toFloat()
        liveFrame = CapturedFrame(target, finalShader, sx, sy, isLive = true)
        frameTick.intValue = frameTick.intValue + 1
    }

    fun clearLiveFrame() {
        liveFrame = null
    }

    /** Primary path: live real UI content. Fallback path: synthetic honeycomb only if liveFrame is unavailable. */
    fun currentFrame(): CapturedFrame? {
        liveFrame?.takeIf { !it.bitmap.isRecycled }?.let { return it }
        fallbackFrame?.takeIf { !it.bitmap.isRecycled }?.let { return it }
        val p = fallbackPicture ?: return null
        if (width <= 0 || height <= 0) return null
        val bmp = BackdropLens.rasterize(p, width, height) ?: return null
        val sh = runCatching { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }.getOrNull() ?: return null
        return CapturedFrame(bmp, sh, 1f, 1f, isLive = false).also { fallbackFrame = it }
    }

    /** Emergency fallback synthetic honeycomb wash (never used as primary sampling source). */
    fun recordFallback(widthPx: Int, heightPx: Int, honeyAlpha: Float) {
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
        fallbackPicture = p
        fallbackFrame = null
        width = widthPx
        height = heightPx
    }
}
