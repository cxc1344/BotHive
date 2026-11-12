package app.opengrok

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Global visual-effects tier, chosen by the user in Settings.
 *
 * iOS 27 ships a system-wide Liquid Glass intensity control; this is the same idea, mapped to
 * what this app can actually render on wildly different Android hardware:
 *
 *  - [Off]      no glass, no motion. Flat colours only. For old phones / battery saving.
 *  - [Medium]   glass surfaces drawn with gradient + rim-light layers (no GPU shader), standard
 *               animation speeds. Safe on any device from API 26 up.
 *  - [Highest]  everything on: true backdrop refraction on surfaces that provide a captured
 *               backdrop (mode capsule indicator, switch knob while dragged — see BackdropLens.kt),
 *               AGSL specular highlights, liquid switch morphs, full-speed spring motion.
 *               **No downgrade**: the user picked this tier knowing their phone can carry it. The
 *               only platform gate is API 33 for RuntimeShader — below that the shader surfaces
 *               render the Medium-style material instead, and everything else stays at full spec.
 */
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

/** Global animation helpers: durations scale with the tier so 关 = instant, 最高 = full springs. */
object GlassMotion {
    /** Scales a base duration by the tier speed; Off yields 0 (jump-cut). */
    fun ms(base: Int): Int = (base * GlassStyle.tier.value.speed).toInt()

    /** Screen-transition slide, ms. */
    fun screenSlide(): Int = ms(280)
    /** Screen-transition fade, ms. */
    fun screenFade(): Int = ms(220)
    /** List item entrance, ms. */
    fun itemEnter(): Int = ms(240)
    /** Drawer slide, ms. */
    fun drawer(): Int = ms(300)
    /** Small cross-fade (bee moods etc.), ms. */
    fun crossFade(): Int = ms(420)
}
