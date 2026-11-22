package app.opengrok

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sculptural Liquid Glass surface & unified [harmonyGlass] modifier.
 *
 * - Highest tier (API 33+): true live-content AGSL refraction via [backdropLens] + dual-layer
 *   optical specular rim (top studio reflection + bottom caustic bounce).
 * - Medium tier / API < 33: 3-band sculptural glass (studio top dome sheen + TIR inner depth +
 *   dual-stop Fresnel rim) so Liquid Glass remains unmistakable even without RuntimeShader.
 * - Off tier: clean flat [fallbackBg] with zero alpha artifacts.
 */
object GlassShader {

    const val SRC = """
uniform float2 uSize;
uniform float uRadius;
uniform float uTintA;
uniform half4 uTint;
uniform float uLightX;
uniform float uLightY;
uniform float uSpec;
uniform float uTime;
uniform float uVeil;
uniform float uRim;

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

half4 main(float2 xy) {
    float2 c = uSize * 0.5;
    float2 p = xy - c;
    float r = min(uRadius, min(c.x, c.y));
    float sdf = sdfRounded(p, c, r);
    float minHalf = min(c.x, c.y);

    // Neutral titanium-obsidian glass body (zero muddy yellow/brown cast)
    float2 L = normalize(float2(uLightX, uLightY));
    float topGrad = clamp(1.0 - (xy.y / max(uSize.y, 1.0)), 0.0, 1.0);
    float3 body = mix(float3(0.075, 0.078, 0.088), float3(0.155, 0.160, 0.175), topGrad * 0.72);
    body = mix(body, float3(uTint.rgb), clamp(uTintA * 0.65 + uVeil * 0.42, 0.0, 0.70));

    // Physical TIR dark inner thickness trough just inside the outer bevel
    float tirTrough = exp(-pow((sdf + 7.0) / 4.0, 2.0));
    body *= (1.0 - tirTrough * 0.28);

    // Sculptural Fresnel rim & opposite-corner optical bounce
    float2 n = sdfNormal(p, c, r);
    float rimBand = smoothstep(-10.0, -0.4, sdf);
    float innerHair = smoothstep(-2.0, -0.2, sdf);
    float rimPrimary = pow(max(dot(n, L), 0.0), 3.0) * rimBand;
    float rimBounce = pow(max(dot(n, -L), 0.0), 3.0) * rimBand;

    float drift = sin(uTime * 0.65) * 0.06;
    float2 focal = L * minHalf * (0.28 + drift);
    float pool = 1.0 - smoothstep(0.0, minHalf * 0.65, length(p - focal));
    float topSheen = pow(clamp(1.0 - (xy.y / max(uSize.y * 0.35, 1.0)), 0.0, 1.0), 2.5) * 0.08;

    float3 col = body
        + float3(1.0, 1.0, 0.99) * (rimPrimary * uSpec * 0.58 * uRim + pool * pool * uSpec * 0.16 + topSheen)
        + float3(0.84, 0.94, 1.0) * (rimBounce * uSpec * 0.32 * uRim)
        + float3(1.0, 1.0, 1.0) * (innerHair * 0.28 * uRim);

    float alpha = 1.0 - smoothstep(0.0, SMOOTH, sdf);
    return half4(half3(clamp(col, float3(0.0), float3(1.0))), half(alpha));
}
"""

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun newShader(): RuntimeShader = RuntimeShader(SRC)
}

@Composable
fun Modifier.glassSurface(
    tier: GlassTier = GlassStyle.tier.value,
    radiusPx: Float,
    tint: Color = Honey.copy(alpha = 0.08f),
    light: Offset = Offset(-0.55f, -0.85f),
    thickness: ImmersiveThickness = ImmersiveThickness.REGULAR,
    lightEffect: Boolean = true,
    glowBoost: Float = 0f
): Modifier {
    if (!tier.glassLayers) return this

    val useShader = tier.shader && GlassShader.supported()

    if (useShader) {
        return this.then(
            Modifier.drawWithCache {
                val shader = runCatching { GlassShader.newShader() }.getOrNull()
                val brush = shader?.let { ShaderBrush(it) }
                onDrawBehind {
                    if (AppBackdrop.isCapturing) return@onDrawBehind
                    if (shader == null || brush == null) {
                        drawGlassLayers(tint, thickness, glowBoost)
                        return@onDrawBehind
                    }
                    val spec = if (lightEffect) thickness.specStrength * (1f + glowBoost * 0.65f) else 0.30f
                    val rim = if (lightEffect) (0.70f + thickness.specStrength * 0.40f) else 0.45f
                    shader.setFloatUniform("uSize", size.width, size.height)
                    shader.setFloatUniform("uRadius", radiusPx)
                    shader.setFloatUniform("uTintA", tint.alpha)
                    shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue, 1f)
                    shader.setFloatUniform("uLightX", light.x)
                    shader.setFloatUniform("uLightY", light.y)
                    shader.setFloatUniform("uSpec", spec)
                    shader.setFloatUniform("uTime", (System.nanoTime() / 1_000_000_000f) % 60f)
                    shader.setFloatUniform("uVeil", thickness.veilAlpha)
                    shader.setFloatUniform("uRim", rim)
                    drawRect(brush, topLeft = Offset.Zero, size = size)
                }
            }
        )
    }
    return this.then(
        Modifier.drawWithContent {
            drawContent()
            if (!AppBackdrop.isCapturing) {
                drawGlassLayers(tint, thickness, glowBoost)
            }
        }
    )
}

/** Sculptural 3-band Liquid Glass fallback for Medium tier & API < 33. */
private fun DrawScope.drawGlassLayers(
    tint: Color,
    thickness: ImmersiveThickness = ImmersiveThickness.REGULAR,
    glowBoost: Float = 0f
) {
    val w = size.width
    val h = size.height
    val topAlpha = (0.10f + thickness.specStrength * 0.08f + glowBoost * 0.08f).coerceAtMost(0.32f)
    val botAlpha = (0.06f + thickness.veilAlpha * 0.48f).coerceAtMost(0.30f)
    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = topAlpha),
            0.26f to Color.White.copy(alpha = topAlpha * 0.22f),
            0.60f to Color.Transparent,
            0.90f to Color.Black.copy(alpha = botAlpha),
            1f to Color.White.copy(alpha = topAlpha * 0.38f)
        )
    )
    drawRect(
        Brush.radialGradient(
            listOf(tint.copy(alpha = (tint.alpha * 0.9f).coerceAtMost(0.28f)), Color.Transparent),
            center = Offset(w * 0.22f, h * 0.08f),
            radius = maxOf(w, h) * 0.85f
        )
    )
    val rim = Color.White.copy(alpha = (0.20f + thickness.specStrength * 0.16f + glowBoost * 0.12f).coerceAtMost(0.55f))
    drawRect(
        Brush.linearGradient(0f to rim, 0.55f to Color.Transparent),
        size = Size(w, 2.2f)
    )
}

/**
 * Unified Liquid Glass modifier used across every floating control, card, input, drawer, and sheet.
 */
@Composable
fun Modifier.harmonyGlass(
    thickness: ImmersiveThickness = ImmersiveThickness.REGULAR,
    radius: Dp = 20.dp,
    tint: Color = Color.Transparent,
    fallbackBg: Color = Card,
    lightEffect: Boolean = true,
    glowBoost: Float = 0f,
    light: Offset = Offset(-0.55f, -0.85f)
): Modifier {
    val tier = GlassStyle.tier.value
    val shape = RoundedCornerShape(radius)
    if (!tier.glassLayers) {
        return this.clip(shape).background(fallbackBg)
    }
    val density = LocalDensity.current
    val radiusPx = with(density) { if (radius >= 900.dp) 48.dp.toPx() else radius.toPx() }
    val resolvedTint = if (tint.alpha > 0.001f && thickness.veilAlpha < 0.42f) {
        tint.copy(alpha = tint.alpha.coerceAtMost(0.18f))
    } else {
        Color(0xFF101216).copy(alpha = (0.05f + thickness.veilAlpha * 0.18f).coerceAtMost(0.26f))
    }
    val rimAlpha = (0.24f + thickness.specStrength * 0.18f + glowBoost * 0.14f).coerceAtMost(0.58f)
    var surfaceTopLeft by remember { mutableStateOf(Offset.Zero) }
    val useTrueLens = tier.shader && BackdropLens.supported()

    val base = this
        .onGloballyPositioned { coords -> surfaceTopLeft = coords.localToRoot(Offset.Zero) }
        .clip(shape)

    val domeZoom = (1.18f - thickness.veilAlpha * 0.20f).coerceIn(1.04f, 1.18f)
    val surfaceMod = if (useTrueLens) {
        base.backdropLens(
            surfaceTopLeft = surfaceTopLeft,
            radiusPx = radiusPx,
            thickness = thickness,
            zoom = domeZoom,
            glowBoost = if (lightEffect) glowBoost else 0f,
            tint = resolvedTint,
            light = light
        )
    } else {
        val baseAlpha = (0.26f + thickness.veilAlpha * 1.15f).coerceIn(0.28f, 0.78f)
        base
            .background(fallbackBg.copy(alpha = baseAlpha))
            .glassSurface(
                tier = tier,
                radiusPx = radiusPx,
                tint = resolvedTint,
                light = light,
                thickness = thickness,
                lightEffect = lightEffect,
                glowBoost = glowBoost
            )
    }

    // Crisp dual-stop studio Fresnel border (top-left bright white -> mid clear -> bottom-right cool bounce)
    return surfaceMod.drawWithContent {
        if (AppBackdrop.isCapturing) return@drawWithContent
        drawContent()
        val cr = minOf(radiusPx, minOf(size.width, size.height) * 0.5f)
        drawRoundRect(
            brush = Brush.linearGradient(
                0f to Color.White.copy(alpha = rimAlpha),
                0.35f to Color.White.copy(alpha = rimAlpha * 0.28f),
                0.68f to Color.White.copy(alpha = rimAlpha * 0.12f),
                1f to Color(0xFFD6EAFF).copy(alpha = rimAlpha * 0.52f)
            ),
            cornerRadius = CornerRadius(cr, cr),
            style = Stroke(width = 1.0.dp.toPx())
        )
    }
}
