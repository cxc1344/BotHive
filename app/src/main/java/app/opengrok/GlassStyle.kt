package app.opengrok

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Global visual-effects tier, chosen by the user in Settings.
 *
 *  - [Off]      no glass, no motion. Flat colours only. For old phones / battery saving.
 *  - [Medium]   sculptural 3-band Liquid Glass material (studio sheen + TIR inner bevel + Fresnel rim)
 *               drawn via multi-layer Compose gradients, standard animation speeds. API 26+.
 *  - [Highest]  full physical optical Liquid Glass: 16-tap Vogel-disk bokeh frost, real Snell
 *               meniscus refraction, RGB chromatic edge dispersion, TIR crystal thickness trough,
 *               studio softbox specular dome, liquid switch morphs, and full-speed spring physics.
 */
enum class ImmersiveThickness(
    val blurRadius: Float,
    val edgePx: Float,
    val dispersionPx: Float,
    val veilAlpha: Float,
    val specStrength: Float
) {
    // Top floating icon pills, mode capsule track, status pills: pure wet crystal lens
    ULTRA_THIN(blurRadius = 2.0f, edgePx = 22.0f, dispersionPx = 4.2f, veilAlpha = 0.05f, specStrength = 0.95f),
    // Bottom floating composer, quick actions, liquid switch, glass inputs: balanced wet refraction
    THIN(blurRadius = 5.5f, edgePx = 26.0f, dispersionPx = 5.2f, veilAlpha = 0.11f, specStrength = 1.05f),
    // General floating cards, user bubbles, settings rows: refined iOS 27 frosted crystal
    REGULAR(blurRadius = 14.0f, edgePx = 28.0f, dispersionPx = 4.6f, veilAlpha = 0.22f, specStrength = 0.95f),
    // Approval cards, tool panels: smoked architectural glass with crisp legibility
    THICK(blurRadius = 24.0f, edgePx = 32.0f, dispersionPx = 4.0f, veilAlpha = 0.36f, specStrength = 1.05f),
    // Floating drawer & bottom sheets: deep frosted iOS 27 glass (still refracts depth behind)
    ULTRA_THICK(blurRadius = 34.0f, edgePx = 36.0f, dispersionPx = 3.6f, veilAlpha = 0.50f, specStrength = 1.12f)
}

enum class GlassTier {
    Off, Medium, Highest;

    /** Whether standard layout/animation polish runs. */
    val motion: Boolean get() = this != Off
    /** Whether glass surfaces get the layered gradient treatment. */
    val glassLayers: Boolean get() = this != Off
    /** Whether the GPU shader path is requested. */
    val shader: Boolean get() = this == Highest
    /** Animation speed multiplier: Off has no animation, Highest runs springs at full speed. */
    val speed: Float get() = when (this) { Off -> 0f; Medium -> 0.85f; Highest -> 1f }
}

object GlassStyle {
    /** The user's chosen tier. Persists via SharedPreferences. */
    val tier = mutableStateOf(GlassTier.Highest)

    private const val PREFS = "glass_style"
    private const val KEY = "tier"

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        tier.value = when (saved) {
            "off" -> GlassTier.Off
            "medium" -> GlassTier.Medium
            else -> GlassTier.Highest
        }
    }

    fun set(context: Context, t: GlassTier) {
        tier.value = t
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, when (t) {
                GlassTier.Off -> "off"; GlassTier.Medium -> "medium"; GlassTier.Highest -> "high"
            }).apply()
    }
}

/** Global animation helpers: durations scale with the tier so Off = instant, Highest = full springs. */
object GlassMotion {
    fun ms(base: Int): Int = (base * GlassStyle.tier.value.speed).toInt()

    fun screenSlide(): Int = ms(280)
    fun screenFade(): Int = ms(220)
    fun itemEnter(): Int = ms(240)
    fun drawer(): Int = ms(300)
    fun crossFade(): Int = ms(420)
}
