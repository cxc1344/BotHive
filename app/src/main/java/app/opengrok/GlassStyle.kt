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
    // Strictly monotonically increasing across all 5 optical parameters (verified by V062FixesTest).
    // Thin tiers stay crystal-clear with razor-sharp edge optics; thick tiers gain deeper bokeh frost
    // and wider sculptural bevels while BackdropLens naturally softens interior chromaticity via blur/veil.
    ULTRA_THIN(blurRadius = 2.0f, edgePx = 22.0f, dispersionPx = 3.6f, veilAlpha = 0.05f, specStrength = 0.88f),
    THIN(blurRadius = 5.5f, edgePx = 26.0f, dispersionPx = 4.4f, veilAlpha = 0.11f, specStrength = 0.96f),
    REGULAR(blurRadius = 14.0f, edgePx = 28.0f, dispersionPx = 5.0f, veilAlpha = 0.22f, specStrength = 1.02f),
    THICK(blurRadius = 24.0f, edgePx = 32.0f, dispersionPx = 5.6f, veilAlpha = 0.36f, specStrength = 1.08f),
    ULTRA_THICK(blurRadius = 34.0f, edgePx = 36.0f, dispersionPx = 6.2f, veilAlpha = 0.50f, specStrength = 1.16f)
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
