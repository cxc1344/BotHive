package app.opengrok

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas

/**
 * Physical Optical Liquid Glass Lens Engine (v0.6.5 LiquidGlass Pro).
 *
 * Upgraded AGSL optical model inspired by Apple iOS 26 / iOS 27 Liquid Glass:
 *  1. 16-tap Golden-Angle Vogel Disk bokeh blur (eliminates cross-shaped ghosting on text).
 *  2. Sculptural meniscus bevel refraction (22..36px) with spherical center dome magnification.
 *  3. True opposing R/G/B chromatic dispersion concentrated along the curved glass rim.
 *  4. Physical 3-band glass edge: razor-sharp Fresnel outer hairline + Total Internal Reflection
 *     (TIR) inner dark thickness trough + studio softbox specular reflection + opposite-corner
 *     caustic bounce.
 */
object BackdropLens {

    const val SRC = """
uniform float2 uSize;
uniform float uRadius;
uniform float2 uBackdropSize;
uniform float2 uBackdropScale;
uniform float2 uSurfaceTopLeft;
uniform float uZoom;
uniform float uEdgePx;
uniform float uRefractPx;
uniform float uDispersionPx;
uniform float uTintA;
uniform float uVeil;
uniform float uLightX;
uniform float uLightY;
uniform float uSpec;
uniform float uGlowBoost;
uniform float uTime;
uniform float uBlurRadius;
uniform float uTintR;
uniform float uTintG;
uniform float uTintB;
uniform float uIntensity;
uniform shader uBackdrop;

const float SMOOTH = 1.5;

float sdfRounded(float2 p, float2 halfSize, float r) {
    float2 d = abs(p) - halfSize + float2(r, r);
    float outside = length(max(d, float2(0.0, 0.0)));
    float inside = min(max(d.x, d.y), 0.0);
    return outside + inside - r;
}

float2 sdfNormal(float2 p, float2 halfSize, float r) {
    float2 d = abs(p) - halfSize + float2(r, r);
    float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    if (max(d.x, d.y) > 0.0) {
        return s * normalize(max(d, float2(0.0, 0.0)));
    }
    return d.x > d.y ? float2(s.x, 0.0) : float2(0.0, s.y);
}

// 16-tap Golden-Angle Vogel Disk bokeh blur: silky isotropic frosting with zero cross-ringing
half4 sampleBackdropBlurred(float2 localXY, float blurRadius) {
    float2 bmpXY = (uSurfaceTopLeft + localXY) * uBackdropScale;
    float2 lo = float2(0.5, 0.5);
    float2 hi = max(lo, uBackdropSize - float2(0.5, 0.5));
    if (blurRadius <= 0.45) {
        return uBackdrop.eval(clamp(bmpXY, lo, hi));
    }
    float2 rad = uBackdropScale * blurRadius;
    half4 acc = uBackdrop.eval(clamp(bmpXY, lo, hi)) * half(0.12);
    // 15 golden-angle spiral taps (angle step = 2.39996 rad)
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2( 0.258,  0.000), lo, hi)) * half(0.085);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2(-0.270,  0.247), lo, hi)) * half(0.080);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2( 0.039, -0.445), lo, hi)) * half(0.075);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2( 0.343,  0.386), lo, hi)) * half(0.070);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2(-0.574, -0.056), lo, hi)) * half(0.068);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2( 0.431, -0.459), lo, hi)) * half(0.064);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2(-0.044,  0.681), lo, hi)) * half(0.060);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2(-0.464, -0.562), lo, hi)) * half(0.058);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2( 0.770,  0.095), lo, hi)) * half(0.055);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2(-0.620,  0.531), lo, hi)) * half(0.052);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2( 0.132, -0.846), lo, hi)) * half(0.048);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2( 0.506,  0.742), lo, hi)) * half(0.045);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2(-0.927, -0.165), lo, hi)) * half(0.042);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2( 0.778, -0.588), lo, hi)) * half(0.040);
    acc += uBackdrop.eval(clamp(bmpXY + rad * float2(-0.205,  0.978), lo, hi)) * half(0.038);
    return acc;
}

half3 applyOpticalVibrancy(half3 rgb, float satBoost, float lumLift) {
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

    // 1. Spherical water-lens dome magnification in the center
    float depth = clamp(-sdf / max(minHalf * 0.65, 1.0), 0.0, 1.0);
    float dome = sqrt(max(0.0, 1.0 - (1.0 - depth) * (1.0 - depth)));
    float zoomHere = mix(1.0, uZoom, dome);
    float2 fromCenter = p / zoomHere;

    // 2. Proportional meniscus bevel refraction (clamped to minHalf so small 36-42dp pills stay jewel-clean)
    float effEdge = min(max(uEdgePx, 1.0), max(minHalf * 0.36, 4.0));
    float band = 1.0 - smoothstep(0.0, effEdge, -sdf);
    float meniscus = band * band * (1.35 - 0.35 * band);
    float effRefract = min(uRefractPx, minHalf * 0.42);
    float push = meniscus * effRefract;
    float2 sampleLocal = fromCenter + c - n * push;

    // 3. Adaptive optical frosting + refined subtle RGB dispersion (Apple crystal, zero glitch fringe)
    float blurHere = uBlurRadius * mix(0.32, 1.0, smoothstep(0.0, 0.65, depth));
    half4 g = sampleBackdropBlurred(sampleLocal, blurHere);
    half4 outC = g;
    half3 prismBoost = half3(0.0);

    if (uDispersionPx > 0.0) {
        float dispW = 0.05 + 0.95 * band * band;
        float2 shift = n * (uDispersionPx * 0.42) * dispW;
        half4 rr = sampleBackdropBlurred(sampleLocal - shift, blurHere * 0.65);
        half4 bb = sampleBackdropBlurred(sampleLocal + shift, blurHere * 0.65);
        outC = half4(rr.r, g.g, bb.b, g.a);
        prismBoost = half3(abs(rr.r - g.g), 0.0, abs(bb.b - g.g)) * half(band * 0.28);
    }

    // 4. Optical vibrancy & refined iOS 27 smoked crystal veil (never muddy opaque grey)
    float clarity = 1.0 - smoothstep(0.08, 0.58, uVeil);
    outC.rgb = applyOpticalVibrancy(outC.rgb, 1.18 + clarity * 0.22 + uGlowBoost * 0.14, 0.018 * clarity);
    outC.rgb += prismBoost;

    half3 tintCol = half3(uTintR / 255.0, uTintG / 255.0, uTintB / 255.0);
    float veilMix = clamp(uVeil * 0.88 + uTintA * (0.08 + 0.22 * (1.0 - clarity)), 0.0, 0.62);
    outC.rgb = mix(outC.rgb, tintCol, half(veilMix));

    // 5. Physical 3-Band Crystal Edge:
    //    (a) Total Internal Reflection (TIR) inner dark contour (-3px..-13px) for real 3D glass thickness
    float tirTrough = exp(-pow((sdf + 7.5) / 4.2, 2.0));
    outC.rgb *= half(1.0 - tirTrough * (0.26 + clarity * 0.14));

    //    (a2) iOS 27 legibility rim: thin darkened contour just inside the bright outer hairline
    float dr = (sdf + 3.0) / 1.7;
    float darkRim = exp(-dr * dr);
    outC.rgb *= half(1.0 - darkRim * (0.14 + 0.26 * uIntensity));
    //    brighter specular highlights, scaled by the user's glass intensity
    float specGain = 1.0 + 0.38 * uIntensity;

    //    (b) Directional studio softbox specular reflection across the curved bevel + top shoulder
    float2 L = normalize(float2(uLightX, uLightY));
    float2 L2 = -L;
    float bevelMask = smoothstep(-max(uEdgePx * 0.42, 6.0), -0.4, sdf);
    float rimHair = smoothstep(-2.2, -0.2, sdf);

    float3 N3 = normalize(float3(n * (band * 0.92), sqrt(max(0.08, 1.0 - band * band * 0.85))));
    float3 L3 = normalize(float3(L.x * 0.72, L.y * 0.72, 0.68));
    float3 H3 = normalize(L3 + float3(0.0, 0.0, 1.0));
    float softboxSpec = pow(max(dot(N3, H3), 0.0), 18.0) * bevelMask;

    float primaryRim = pow(max(dot(n, L), 0.0), 3.2) * bevelMask;
    float oppositeBounce = pow(max(dot(n, L2), 0.0), 2.8) * bevelMask;

    //    (c) Overhead curved studio reflection sheen along the top 28% of the glass face
    float topSheen = pow(clamp(1.0 - (xy.y / max(uSize.y * 0.38, 1.0)), 0.0, 1.0), 2.6) * (0.055 + 0.045 * clarity);

    float flowAngle = atan(p.y, p.x);
    float shimmer = 0.78 + 0.22 * sin(flowAngle * 2.0 - uTime * 0.95);
    float boost = 1.0 + uGlowBoost * 0.65;

    // Neutral studio-white primary specular + cool optical opposite-edge bounce (zero cliché purple cast)
    outC.rgb += half3(1.00, 1.00, 0.99) * half((primaryRim * 0.58 + softboxSpec * 0.75) * uSpec * shimmer * boost * specGain);
    outC.rgb += half3(0.84, 0.94, 1.00) * half(oppositeBounce * uSpec * (0.36 + clarity * 0.20) * boost * specGain);
    outC.rgb += half3(1.00, 1.00, 1.00) * half(rimHair * (0.34 + uGlowBoost * 0.28) * (0.85 + 0.25 * uIntensity));
    outC.rgb += half3(1.00, 1.00, 1.00) * half(topSheen * uSpec * boost * specGain);

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

inline fun recordBackdrop(width: Int, height: Int, block: (Canvas) -> Unit): Picture =
    Picture().also { block(it.beginRecording(width, height)) }.apply { endRecording() }

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdrop(picture: Picture) {
    drawContext.canvas.nativeCanvas.drawPicture(picture)
}

@Composable
fun Modifier.backdropLens(
    surfaceTopLeft: Offset,
    radiusPx: Float,
    thickness: ImmersiveThickness = ImmersiveThickness.THIN,
    zoom: Float = 1.14f,
    glowBoost: Float = 0f,
    tint: Color = Honey.copy(alpha = 0.08f),
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
            val compat = GlassCompatBrushes(tint, thickness, glowBoost, size)
            onDrawBehind {
                if (AppBackdrop.isCapturing) return@onDrawBehind
                @Suppress("UNUSED_VARIABLE")
                val tick = AppBackdrop.frameTick.intValue
                val frame = AppBackdrop.currentFrame()
                if (shader == null || brush == null || frame == null || frame.bitmap.isRecycled) {
                    drawGlassLayersCompat(compat)
                    return@onDrawBehind
                }
                val ok = runCatching {
                    val effectiveSpec = thickness.specStrength * (1f + glowBoost * 0.70f)
                    // Continuous glass intensity: lower lens amount = more frost + tint, less refraction.
                    val lens = GlassStyle.lensAmount
                    val solid = 1f - lens
                    shader.setFloatUniform("uSize", size.width, size.height)
                    shader.setFloatUniform("uRadius", radiusPx)
                    shader.setFloatUniform("uBackdropSize", frame.bitmap.width.toFloat(), frame.bitmap.height.toFloat())
                    shader.setFloatUniform("uBackdropScale", frame.scaleX, frame.scaleY)
                    shader.setFloatUniform("uSurfaceTopLeft", surfaceTopLeft.x, surfaceTopLeft.y)
                    shader.setFloatUniform("uZoom", zoom)
                    shader.setFloatUniform("uEdgePx", thickness.edgePx)
                    shader.setFloatUniform("uRefractPx", thickness.edgePx * 1.05f * (0.70f + 0.30f * lens))
                    shader.setFloatUniform("uDispersionPx", thickness.dispersionPx * (0.55f + 0.45f * lens))
                    shader.setFloatUniform("uBlurRadius", thickness.blurRadius * (1f + solid * 0.9f))
                    shader.setFloatUniform("uVeil", (thickness.veilAlpha + solid * 0.22f).coerceAtMost(0.62f))
                    shader.setFloatUniform("uIntensity", GlassStyle.effectiveIntensity)
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
                    drawGlassLayersCompat(compat)
                }
            }
        }
    )
}

/** Pre-built brushes for the shader-unavailable fallback (built once per size in drawWithCache). */
private class GlassCompatBrushes(tint: Color, thickness: ImmersiveThickness, glowBoost: Float, size: Size) {
    val dome: Brush
    val pool: Brush
    val hairline: Brush
    val hairSize: Size = Size(size.width, 2.5f)

    init {
        val w = size.width
        val h = size.height
        val veil = (thickness.veilAlpha * 0.95f).coerceIn(0.06f, 0.48f)
        val hi = (0.11f + thickness.specStrength * (0.09f + glowBoost * 0.08f)).coerceAtMost(0.36f)
        // Studio top-to-bottom specular dome shading
        dome = Brush.verticalGradient(
            0f to Color.White.copy(alpha = hi),
            0.22f to Color.White.copy(alpha = hi * 0.25f),
            0.55f to Color.Transparent,
            0.90f to Color.Black.copy(alpha = veil * 0.50f),
            1f to Color.White.copy(alpha = hi * 0.35f)
        )
        // Subtle top-left studio softbox pool
        pool = Brush.radialGradient(
            listOf(tint.copy(alpha = (tint.alpha + veil * 0.28f).coerceAtMost(0.38f)), Color.Transparent),
            center = Offset(w * 0.22f, h * 0.08f),
            radius = maxOf(maxOf(w, h) * 0.85f, 1f)
        )
        // Crisp top specular hairline
        hairline = Brush.linearGradient(
            0f to Color.White.copy(alpha = (hi * 1.6f).coerceAtMost(0.55f)),
            0.45f to Color.White.copy(alpha = hi * 0.35f),
            1f to Color.Transparent
        )
    }
}

/** Sculptural 3-band Liquid Glass fallback when shader is unavailable. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassLayersCompat(b: GlassCompatBrushes) {
    val k = GlassStyle.effectiveIntensity
    drawRect(b.dome, alpha = (0.80f + 0.30f * k).coerceAtMost(1f))
    drawRect(b.pool)
    drawRect(b.hairline, size = b.hairSize, alpha = (0.75f + 0.35f * k).coerceAtMost(1f))
}

object AppBackdrop {
    const val CAPTURE_SCALE = 0.5f

    data class CapturedFrame(
        val bitmap: Bitmap,
        val shader: BitmapShader,
        val scaleX: Float,
        val scaleY: Float,
        val isLive: Boolean
    )

    @Volatile
    var isCapturing: Boolean = false

    @Volatile
    var dirtySeq: Long = 0L
        private set

    @Volatile
    var width: Int = 0
        private set
    @Volatile
    var height: Int = 0
        private set

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

    fun publishLiveFrame(bitmap: Bitmap, rootW: Int, rootH: Int) {
        if (bitmap.width <= 0 || bitmap.height <= 0 || rootW <= 0 || rootH <= 0) return
        var target = bitmap
        var shader = runCatching {
            BitmapShader(target, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }.getOrNull()
        if (shader == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && target.config == Bitmap.Config.HARDWARE) {
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

    fun currentFrame(): CapturedFrame? {
        liveFrame?.takeIf { !it.bitmap.isRecycled }?.let { return it }
        fallbackFrame?.takeIf { !it.bitmap.isRecycled }?.let { return it }
        val p = fallbackPicture ?: return null
        if (width <= 0 || height <= 0) return null
        val bmp = BackdropLens.rasterize(p, width, height) ?: return null
        val sh = runCatching { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }.getOrNull() ?: return null
        return CapturedFrame(bmp, sh, 1f, 1f, isLive = false).also { fallbackFrame = it }
    }

    fun recordFallback(widthPx: Int, heightPx: Int, honeyAlpha: Float) {
        if (widthPx <= 0 || heightPx <= 0) return
        val p = recordBackdrop(widthPx, heightPx) { canvas ->
            canvas.drawColor(android.graphics.Color.parseColor("#0B0C0E"), android.graphics.PorterDuff.Mode.SRC)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                color = android.graphics.Color.argb((255 * honeyAlpha * 0.16f).toInt(), 0xE8, 0xEC, 0xF4)
            }
            val radius = 54f
            val columnStep = radius * 1.732f
            val rowStep = radius * 1.5f
            fun hexPath(cx: Float, cy: Float): android.graphics.Path =
                android.graphics.Path().apply {
                    var i = 0
                    while (i < 6) {
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
                    canvas.drawPath(hexPath(col * columnStep + rowOffset, y), paint)
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
