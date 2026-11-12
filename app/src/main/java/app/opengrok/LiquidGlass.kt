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
import androidx.compose.ui.graphics.drawscope.DrawScope

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

    // Base glass body: sheen from the light side + honey tint. All math in float3; convert once
    // at the end (AGSL/SkSL will not implicitly mix half and float vectors).
    float2 L = normalize(float2(uLightX, uLightY));
    float facing = 0.5 + 0.5 * dot(normalize(-p + float2(0.0, 0.0001)), L);
    float3 body = mix(float3(0.10, 0.075, 0.03), float3(0.24, 0.19, 0.08), facing);
    body = mix(body, float3(uTint.rgb), uTintA * 0.6);

    // Interior depth shading: brightens toward the rim like a convex lens.
    float depth = clamp(-sdf / max(minHalf * 0.35, 1.0), 0.0, 1.0);
    float curvature = 1.0 - depth;
    body += curvature * curvature * 0.10;

    // Prismatic rim: per-channel weights give the edge a subtle rainbow fringe.
    float2 n = sdfNormal(p, c, r);
    float rimBand = smoothstep(-4.0, -0.5, sdf);
    float3 L3 = float3(L, 0.6);
    float rimBase = pow(max(dot(float3(n, 0.0), L3), 0.0), 3.0) * rimBand;
    float3 rim = float3(rimBase * 1.15, rimBase, rimBase * 0.70);

    // Specular: rim glint + drifting focal pool.
    float drift = sin(uTime * 0.7) * 0.06;
    float2 focal = L * minHalf * (0.22 + drift);
    float pool = 1.0 - smoothstep(0.0, minHalf * 0.5, length(p - focal));
    float spec = rimBase * uSpec + pool * pool * uSpec * 0.5;

    float3 col = body + rim * (uSpec * 0.8) + spec * 0.55;

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
    light: Offset = Offset(-0.55f, -0.85f)
): Modifier {
    if (!tier.glassLayers) return this

    val useShader = tier.shader && GlassShader.supported()

    // drawWithCache keeps the compiled RuntimeShader and the clip path across frames; only the
    // uniforms are refreshed per draw. ShaderBrush is the documented way to draw an AGSL shader
    // in Compose (Brush.glRuntimeShader does not exist in the current BOM).
    if (useShader) {
        // The glass draws BEHIND the content (onDrawBehind): text stays crisp, the material lives
        // under it. Uniforms refresh per frame; the shader itself is cached by drawWithCache.
        return this.then(
            Modifier.drawWithCache {
                val shader = runCatching { GlassShader.newShader() }.getOrNull()
                val brush = shader?.let { ShaderBrush(it) }
                onDrawBehind {
                    if (shader == null || brush == null) {
                        drawGlassLayers(tint, radiusPx, light)
                        return@onDrawBehind
                    }
                    shader.setFloatUniform("uSize", size.width, size.height, 0f, 0f)
                    shader.setFloatUniform("uRadius", radiusPx)
                    shader.setFloatUniform("uTintA", tint.alpha)
                    shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue, 1f)
                    shader.setFloatUniform("uLightX", light.x)
                    shader.setFloatUniform("uLightY", light.y)
                    shader.setFloatUniform("uSpec", 0.9f)
                    shader.setFloatUniform("uTime", (System.nanoTime() / 1_000_000_000f) % 60f)
                    drawRect(brush, topLeft = Offset.Zero, size = size)
                }
            }
        )
    }
    // Gradient-only path (Medium tier, or Highest tier on pre-13 devices).
    return this.then(
        Modifier.drawWithContent {
            drawContent()
            drawGlassLayers(tint, radiusPx, light)
        }
    )
}

/** Gradient-only glass (tier Medium, or non-33 devices at Highest tier). */
private fun DrawScope.drawGlassLayers(tint: Color, radiusPx: Float, light: Offset) {
    val w = size.width
    val h = size.height
    // Vertical sheen: brighter at the light side, dimmer toward the opposite edge.
    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.55f to Color.White.copy(alpha = 0.03f),
            1f to Color.Black.copy(alpha = 0.06f)
        )
    )
    // Tint wash.
    drawRect(Brush.radialGradient(listOf(tint, Color.Transparent), center = Offset(w * 0.5f, h * 0.1f), radius = maxOf(w, h)))
    // Rim light on the light-facing edges.
    val rim = Color.White.copy(alpha = 0.16f)
    drawRect(Brush.linearGradient(
        0f to rim,
        0.25f to Color.Transparent
    ), size = Size(w, 2.5f))
}
