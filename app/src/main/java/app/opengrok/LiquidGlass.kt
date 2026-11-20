package app.opengrok

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid-glass material for BotHive (v0.5.7 style, self-drawn).
 *
 * IMPORTANT — what this file is and is not (naming kept honest after v0.5.8):
 *  - This is a **self-drawn material**: gradient sheen + tint + curvature shading + specular, all
 *    procedurally painted. It does NOT sample the pixels behind the surface. Anywhere the phrase
 *    "GPU refraction" or "samples the backdrop" applies, that is [BackdropLens]'s territory, not
 *    this file's.
 *  - The earlier doc here claimed the shader "samples content behind the surface" — that was wrong
 *    (a ShaderBrush has no access to backdrop content) and is corrected here. True backdrop
 *    refraction lives in BackdropLens.kt and only runs on surfaces that provide a captured
 *    backdrop bitmap at the Highest tier on API 33+.
 *
 * Still used by: every surface at the Medium tier, every surface without a captured backdrop at
 * the Highest tier, and as the silent fallback when the lens path fails.
 */
object GlassShader {

    // Self-written AGSL. Self-contained: it draws the glass material itself (sheen, tint, rim
    // light, specular, prismatic edge) rather than refracting backdrop pixels — a ShaderBrush has
    // no access to the content behind it, and adding a RenderEffect blur of the whole layer stack
    // is disproportionate for chat surfaces. The distortion look comes from edge curvature
    // shading instead of true sampling, which keeps cost near zero on mid phones.
    const val SRC = """
uniform float2 uSize;        // surface size in px
uniform float uRadius;       // corner radius in px
uniform float uTintA;        // tint alpha (glass opacity)
uniform half4 uTint;         // tint color
uniform float uLightX;       // light direction, normalized
uniform float uLightY;
uniform float uSpec;         // specular intensity
uniform float uTime;         // seconds; slow drift of the sheen so glass feels alive
uniform float uVeil;         // thickness veil alpha (HarmonyOS ULTRA_THIN..ULTRA_THICK)
uniform float uRim;          // Fresnel rim intensity

const float SMOOTH = 1.5;

// Signed distance to a rounded rectangle (negative inside).
float sdfRounded(float2 p, float2 half, float r) {
    float2 d = abs(p) - half + float2(r, r);
    float outside = length(max(d, float2(0.0, 0.0)));
    float inside = min(max(d.x, d.y), 0.0);
    return outside + inside - r;
}

// Outward normal of the rounded rect at p.
float2 sdfNormal(float2 p, float2 half, float r) {
    float2 d = abs(p) - half + float2(r, r);
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

    // Base glass body: HarmonyOS Immersive Light diffused optical veil + tint.
    float2 L = normalize(float2(uLightX, uLightY));
    float facing = 0.5 + 0.5 * dot(normalize(-p + float2(0.0, 0.0001)), L);
    float topGrad = clamp(1.0 - (xy.y / max(uSize.y, 1.0)), 0.0, 1.0);
    float3 body = mix(float3(0.09, 0.075, 0.045), float3(0.22, 0.18, 0.10), facing * 0.7 + topGrad * 0.3);
    body = mix(body, float3(uTint.rgb), clamp(uTintA * 0.65 + uVeil * 0.55, 0.0, 0.82));

    // Interior depth shading: subtle convex Fresnel brightening toward edges.
    float depth = clamp(-sdf / max(minHalf * 0.38, 1.0), 0.0, 1.0);
    float curvature = 1.0 - depth;
    body += curvature * curvature * (0.09 * uRim);

    // Dual-layer Fresnel rim: crisp 1.5px inner edge glow + directional prismatic highlight.
    float2 n = sdfNormal(p, c, r);
    float rimBand = smoothstep(-4.5, -0.4, sdf);
    float innerEdge = smoothstep(-2.0, -0.3, sdf);
    float3 L3 = float3(L, 0.65);
    float rimBase = pow(max(dot(float3(n, 0.0), L3), 0.0), 2.6) * rimBand;
    float3 rim = float3(rimBase * 1.12, rimBase * 1.02, rimBase * 0.82) * uRim
        + float3(0.18, 0.17, 0.15) * innerEdge * (0.45 * uRim);

    // Specular: rim glint + gentle drifting ambient light pool.
    float drift = sin(uTime * 0.65) * 0.055;
    float2 focal = L * minHalf * (0.24 + drift);
    float pool = 1.0 - smoothstep(0.0, minHalf * 0.55, length(p - focal));
    float spec = rimBase * uSpec + pool * pool * uSpec * 0.42;

    float3 col = body + rim * (uSpec * 0.78) + spec * 0.48;

    // Soft edge anti-aliasing.
    float alpha = 1.0 - smoothstep(0.0, SMOOTH, sdf);
    return half4(half3(col), half(alpha));
}
"""

    /** True when this device can run the GPU path at all. */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Cached per-composition; RuntimeShader compiles once and uniforms are cheap. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun newShader(): RuntimeShader = RuntimeShader(SRC)
}

/**
 * Draws a liquid-glass surface over this modifier's content.
 *
 * @param tier   visual tier; [GlassTier.Off] disables everything (flat Card look is the caller's job)
 * @param radius corner radius in px (from a RoundedCornerShape * density)
 * @param tint   glass tint colour (alpha carries the opacity)
 * @param light  light direction; default top-left like iOS
 */
@Composable
fun Modifier.glassSurface(
    tier: GlassTier = GlassStyle.tier.value,
    radiusPx: Float,
    tint: Color = Honey.copy(alpha = 0.10f),
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
                    if (shader == null || brush == null) {
                        drawGlassLayers(tint, thickness, glowBoost)
                        return@onDrawBehind
                    }
                    val spec = if (lightEffect) thickness.specStrength * (1f + glowBoost * 0.65f) else 0.25f
                    val rim = if (lightEffect) (0.55f + thickness.specStrength * 0.45f) else 0.35f
                    shader.setFloatUniform("uSize", size.width, size.height, 0f, 0f)
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
            drawGlassLayers(tint, thickness, glowBoost)
        }
    )
}

/** Gradient-only glass (tier Medium, or non-33 devices at Highest tier), distinctly scaled by [ImmersiveThickness]. */
private fun DrawScope.drawGlassLayers(
    tint: Color,
    thickness: ImmersiveThickness = ImmersiveThickness.REGULAR,
    glowBoost: Float = 0f
) {
    val w = size.width
    val h = size.height
    val topAlpha = (0.05f + thickness.veilAlpha * 0.45f + glowBoost * 0.08f).coerceAtMost(0.32f)
    val botAlpha = (0.04f + thickness.veilAlpha * 0.55f).coerceAtMost(0.28f)
    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = topAlpha),
            0.52f to Color.White.copy(alpha = thickness.veilAlpha * 0.14f),
            1f to Color.Black.copy(alpha = botAlpha)
        )
    )
    drawRect(Brush.radialGradient(listOf(tint, Color.Transparent), center = Offset(w * 0.5f, h * 0.1f), radius = maxOf(w, h)))
    val rim = Color.White.copy(alpha = (0.10f + thickness.specStrength * 0.14f + glowBoost * 0.08f).coerceAtMost(0.38f))
    drawRect(
        Brush.linearGradient(0f to rim, 0.35f to Color.Transparent),
        size = Size(w, 2.0f)
    )
}

/**
 * Unified HarmonyOS NEXT Immersive Light glass modifier.
 *
 * Follows the Immersive Light visual & power contract:
 *  - Thickness selects one of the 5 semantic optical tiers (ULTRA_THIN .. ULTRA_THICK).
 *  - Off tier cleanly degrades to [fallbackBg] with zero transparent-backing glitches.
 *  - Medium tier / API < 33 uses layered optical gradients + 1dp Fresnel edge highlight.
 *  - Highest tier on API 33+ runs the AGSL Immersive Light shader behind crisp content.
 *  - Never nest harmonyGlass inside an already glass-backed parent container.
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
    val resolvedTint = if (tint.alpha > 0.001f) tint else ThemeAccent.copy(alpha = (0.04f + thickness.veilAlpha * 0.30f).coerceAtMost(0.16f))
    val rimAlpha = (0.12f + thickness.specStrength * 0.12f + glowBoost * 0.10f).coerceAtMost(0.36f)
    val backdrop = remember(AppBackdrop.version) { AppBackdrop.bitmap() }
    var surfaceTopLeft by remember { mutableStateOf(Offset.Zero) }
    val useTrueLens = tier.shader && BackdropLens.supported() && backdrop != null

    val base = this
        .onGloballyPositioned { coords -> surfaceTopLeft = coords.localToRoot(Offset.Zero) }
        .clip(shape)

    val surfaceMod = if (useTrueLens) {
        // True AGSL backdrop-sampling lens driven directly by ImmersiveThickness + live glowBoost.
        base.backdropLens(
            backdrop = backdrop,
            surfaceTopLeft = surfaceTopLeft,
            radiusPx = radiusPx,
            thickness = thickness,
            zoom = 1.06f + thickness.veilAlpha * 0.30f,
            glowBoost = if (lightEffect) glowBoost else 0f,
            tint = resolvedTint,
            light = light
        )
    } else {
        // API < 33 or Medium tier fallback: thickness-differentiated veil + gradient sheen.
        val baseAlpha = (0.28f + thickness.veilAlpha * 1.50f).coerceIn(0.32f, 0.85f)
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

    return surfaceMod.border(
        width = 0.85.dp,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = rimAlpha),
            0.45f to Color.White.copy(alpha = rimAlpha * 0.32f),
            1f to Color.White.copy(alpha = rimAlpha * 0.14f)
        ),
        shape = shape
    )
}
