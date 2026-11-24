package app.opengrok

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

/**
 * Global visual-effects tier, chosen by the user in Settings.
 *
 *  - [Off]      no glass, no motion. Flat colours only. For old phones / battery saving.
 *  - [Medium]   sculptural 3-band Liquid Glass material (studio sheen + TIR inner bevel + Fresnel rim)
 *               drawn via multi-layer Compose gradients, standard animation speeds. API 26+.
 *  - [Highest]  full physical optical Liquid Glass: 16-tap Vogel-disk bokeh frost, real Snell
 *               meniscus refraction, RGB chromatic edge dispersion, TIR crystal thickness trough,
 *               studio softbox specular dome, liquid switch morphs, and full-speed spring physics.
 *
 * v0.7.0: the tier is DERIVED from a continuous glass intensity (iOS 27 style transparency slider),
 * see [GlassStyle.intensity]. Existing callers keep reading [GlassStyle.tier].
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
    /**
     * The tier derived from [effectiveIntensity]. Kept as a MutableState so every existing
     * `GlassStyle.tier.value` reader recomposes when the slider crosses a tier boundary.
     * Do not write it directly: use [set] or [setIntensity].
     */
    val tier = mutableStateOf(GlassTier.Highest)

    /** The user's glass intensity: 0 = solid tint (flat), 1 = full clear lens. Persisted. */
    val intensity = mutableFloatStateOf(1f)

    /** System "Remove animations" (Settings.Global.ANIMATOR_DURATION_SCALE == 0). */
    val reduceMotion = mutableStateOf(false)

    /** System battery saver (PowerManager.isPowerSaveMode). */
    val powerSave = mutableStateOf(false)

    /** Below this intensity everything is flat (tier Off). */
    const val OFF_BELOW = 0.04f
    /** From this intensity on the GPU lens is used (tier Highest). */
    const val SHADER_FROM = 0.6f
    /** Intensity cap while battery saver or "remove animations" is on (Medium, no shader). */
    const val LIMITED_CAP = 0.5f

    const val PRESET_OFF = 0f
    const val PRESET_MEDIUM = 0.45f
    const val PRESET_HIGHEST = 1f

    private const val PREFS = "glass_style"
    private const val KEY = "tier"
    private const val KEY_INTENSITY = "intensity"

    @Volatile
    private var watching = false

    /** Current cap imposed by system state (1 = none). */
    val cap: Float
        get() = if (reduceMotion.value || powerSave.value) LIMITED_CAP else 1f

    /** True when the system currently lowers the user's chosen intensity. */
    val capped: Boolean
        get() = intensity.floatValue > cap + 0.001f

    /** The intensity actually rendered: the user's value limited by [cap]. */
    val effectiveIntensity: Float
        get() = intensity.floatValue.coerceIn(0f, 1f).coerceAtMost(cap)

    /** Position 0..1 inside the GPU lens range; 0 when the lens is not used. */
    val lensAmount: Float
        get() = ((effectiveIntensity - SHADER_FROM) / (1f - SHADER_FROM)).coerceIn(0f, 1f)

    /** Position 0..1 inside the gradient-glass (Medium) range. */
    val layerAmount: Float
        get() = ((effectiveIntensity - OFF_BELOW) / (SHADER_FROM - OFF_BELOW)).coerceIn(0f, 1f)

    /** Whether decorative motion should run (tier allows it and the system did not remove animations). */
    val motionEnabled: Boolean
        get() = tier.value.motion && !reduceMotion.value

    fun tierFor(value: Float): GlassTier = when {
        value < OFF_BELOW -> GlassTier.Off
        value < SHADER_FROM -> GlassTier.Medium
        else -> GlassTier.Highest
    }

    fun presetFor(t: GlassTier): Float = when (t) {
        GlassTier.Off -> PRESET_OFF
        GlassTier.Medium -> PRESET_MEDIUM
        GlassTier.Highest -> PRESET_HIGHEST
    }

    /** The tier the user's own (uncapped) slider value maps to, for the settings picker. */
    fun userTier(): GlassTier = tierFor(intensity.floatValue)

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = if (prefs.contains(KEY_INTENSITY)) {
            prefs.getFloat(KEY_INTENSITY, PRESET_HIGHEST)
        } else {
            // Migrate the pre-0.7.0 three-way setting.
            when (prefs.getString(KEY, null)) {
                "off" -> PRESET_OFF
                "medium" -> PRESET_MEDIUM
                else -> PRESET_HIGHEST
            }
        }
        intensity.floatValue = if (stored.isNaN()) PRESET_HIGHEST else stored.coerceIn(0f, 1f)
        refreshSystemState(context)
        watchSystem(context.applicationContext)
        recompute()
    }

    /** Legacy tier setter: jumps the slider to the tier's preset. */
    fun set(context: Context, t: GlassTier) {
        setIntensity(context, presetFor(t), persist = true)
    }

    /**
     * Sets the glass intensity. While the user drags a slider pass `persist = false` and persist once
     * on release.
     */
    fun setIntensity(context: Context, value: Float, persist: Boolean = true) {
        val v = if (value.isNaN()) PRESET_HIGHEST else value.coerceIn(0f, 1f)
        intensity.floatValue = v
        recompute()
        if (persist) {
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putFloat(KEY_INTENSITY, v)
                    .putString(KEY, when (tierFor(v)) {
                        GlassTier.Off -> "off"; GlassTier.Medium -> "medium"; GlassTier.Highest -> "high"
                    })
                    .apply()
            }
        }
    }

    /** Re-reads "remove animations" and battery saver. Safe to call any time (e.g. on resume). */
    fun refreshSystemState(context: Context) {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        val saver = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        }.getOrDefault(false)
        if (reduceMotion.value != (scale == 0f)) reduceMotion.value = scale == 0f
        if (powerSave.value != saver) powerSave.value = saver
        recompute()
    }

    /** Short Chinese label for an intensity value (slider readout). */
    fun intensityLabel(value: Float): String = when {
        value < OFF_BELOW -> "纯色"
        value < 0.25f -> "浓郁"
        value < SHADER_FROM -> "柔和"
        value < 0.85f -> "清透"
        else -> "极透"
    }

    private fun recompute() {
        val t = tierFor(effectiveIntensity)
        if (tier.value != t) tier.value = t
    }

    private fun watchSystem(app: Context) {
        if (watching) return
        watching = true
        val main = Handler(Looper.getMainLooper())
        runCatching {
            val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
            app.contentResolver.registerContentObserver(uri, false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    refreshSystemState(app)
                }
            })
        }
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    refreshSystemState(app)
                }
            }
            // ACTION_POWER_SAVE_MODE_CHANGED is a protected system broadcast.
            ContextCompat.registerReceiver(
                app, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }
}

/**
 * Global animation helpers: durations scale with the tier so Off = instant, Highest = full springs.
 * v0.7.0: everything runs ~15% quicker (iOS 27 tempo) and collapses to 0 when the system removed
 * animations.
 */
object GlassMotion {
    const val TEMPO = 0.85f

    fun ms(base: Int): Int =
        if (GlassStyle.reduceMotion.value) 0 else (base * GlassStyle.tier.value.speed * TEMPO).toInt()

    fun screenSlide(): Int = ms(280)
    fun screenFade(): Int = ms(220)
    fun itemEnter(): Int = ms(240)
    fun drawer(): Int = ms(300)
    fun crossFade(): Int = ms(420)
}
