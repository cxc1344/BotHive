package app.opengrok

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context

/**
 * Claude-style design tokens for the vNext reskin.
 *
 * Split of responsibilities (work order 总原则 2):
 *  - the **skeleton** (page background, cards, pills, round buttons, text inks) uses these
 *    flat, brightness-layered colours — that is the Claude look;
 *  - the **floating surfaces** (composer capsule, mode capsule, bottom sheets) keep the app's
 *    liquid-glass material on top of these tokens: they pass an explicit `tint` into
 *    [glassSurface] because the algorithm file (LiquidGlass.kt) must not change its own default.
 *
 * All values are sampled from the reference screenshots (see _claude_ui_refs/STUDY-CLAUDE-UI.md §1).
 */
object UiTokens {
    // ---- colours (existing global names in Ui.kt are re-pointed to these values) ----
    const val PAGE_BG = 0xFF151515        // Bg — warm near-black page background
    const val CARD_BG = 0xFF20201E        // Card — grouped card / glass body
    const val PILL_BG = 0xFF272727        // plan pill, model chip
    const val ROUND_BTN_BG = 0xFF313131   // circular "+" / mic buttons
    const val TEXT_MAIN = 0xFFF5F5F2      // Fg — near-white
    const val TEXT_SUB = 0xFF9B9B97      // Sub — secondary grey
    const val CORAL = 0xFFD47755          // brand accent (BotHive bee stays its own colours)
    const val SELECT_BLUE = 0xFF74A6E6    // selected check / outline / label
    const val USER_BUBBLE = 0xFF2A2A2D    // right-aligned user bubble
    const val HAIRLINE = 0xFF2E2E2C       // hairline separators inside cards

    // ---- lens params (shared by BackdropLens & ModeCapsule) ----
    const val LENS_BEVEL_PX = 30f         // refraction band width along SDF normal (~30–60px)
    const val LENS_REFR_PX = 34f          // max edge displacement inside the band
    const val LENS_DISP_PX = 0.5f         // RGB separation along the normal (dispersion)
    const val LENS_BLUR_RADIUS = 4f       // backdrop Gaussian blur radius for frosted interior
    const val LENS_TINT_R = 255f          // warm tint RGB (255,232,196)
    const val LENS_TINT_G = 232f
    const val LENS_TINT_B = 196f
    const val LENS_TINT_A = 0.08f         // tint blend alpha
    const val LENS_ZOOM_CAPSULE = 1.15f   // center magnification for capsule lens
    const val LENS_ZOOM_SWITCH = 1.55f    // center magnification for switch knob lens

    // ---- corner scale ----
    val RadiusCard = 20.dp
    val RadiusSheet = 28.dp
    val RadiusChip = 999.dp               // full-round pills and buttons

    // ---- type moments that use the serif face ----
    // greeting / page title / bot name / sheet title / thinking word (STUDY §3)
}

/** Serif family + style bundle for the display moments; see [hiveTypography] for the split. */
object HiveSerif {
    val family: FontFamily = HiveFonts.Roman
    /** Greeting / page title: large serif, near-white. */
    @Composable
    fun Title(size: TextUnit = 28.sp) = androidx.compose.ui.text.TextStyle(
        fontFamily = family, fontSize = size, lineHeight = size * 1.25f,
        fontWeight = FontWeight.Normal, color = Fg
    )
    /** Sheet title: serif, bold, centred. */
    @Composable
    fun SheetTitle() = androidx.compose.ui.text.TextStyle(
        fontFamily = family, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Fg
    )
    /** Thinking-state word: serif italic grey. */
    @Composable
    fun Thinking() = androidx.compose.ui.text.TextStyle(
        fontFamily = family, fontSize = 15.sp, fontStyle = FontStyle.Italic, color = Sub
    )
}

// ---------------------------------------------------------------------------
// Colour tokens as Compose values. The legacy global names (Bg, Card, Fg, …)
// that ~230 call sites use are re-pointed to these same values in Ui.kt, so
// every screen picks up the Claude palette without touching each call site.
// ---------------------------------------------------------------------------
val PILL = Color(0xFF272727)          // plan pill / model chip background
val HAIRLINE = Color(0xFF2E2E2C)      // hairline separator inside cards
val Coral = Color(0xFFD47755)         // Legacy Claude coral (kept for semantic uses: tool failure badge, etc.)
val SelectBlue = Color(0xFF74A6E6)    // selected check / outline / label blue
val UserBubble = Color(0xFF2A2A2D)    // right-aligned user bubble
val SheetGlassTint = Color(0xFF3A3A38).copy(alpha = 0.20f) // glass tint for floating sheets
val PillWhite = Color(0xFFF9F9F7)     // white pill with black text ("+ New chat")

/**
 * The shared bottom sheet used by the Summary timeline and the model picker (STUDY §2.5).
 *
 * Same anatomy every time: scrim + upward slide, top-rounded glass body with a drag handle,
 * ✕ on the left, serif bold centred title. Content is provided by the caller. The glass material
 * goes through the existing [glassSurface] pass — tint passed explicitly, algorithm untouched.
 */
@Composable
fun ClaudeSheet(
    title: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val tier = GlassStyle.tier.value
    val density = androidx.compose.ui.platform.LocalDensity.current
    val radiusPx = with(density) { UiTokens.RadiusSheet.toPx() }
    BackHandler(visible) { onDismiss() }
    Box(Modifier.fillMaxSize()) {
        // Scrim: press to dismiss; the page behind darkens (Off tier keeps a plain scrim).
        AnimatedVisibility(visible, enter = fadeIn(tween(GlassMotion.drawer())), exit = fadeOut(tween(GlassMotion.drawer()))) {
            Box(
                Modifier.fillMaxSize().background(Ink.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInVertically(tween(GlassMotion.drawer(), easing = FastOutSlowInEasing)) { it } +
                fadeIn(tween(GlassMotion.screenFade())),
            exit = slideOutVertically(tween(GlassMotion.drawer(), easing = FastOutSlowInEasing)) { it } +
                fadeOut(tween(GlassMotion.screenFade()))
        ) {
            Column(
                Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .background(if (tier.glassLayers) Brush.verticalGradient(listOf(Card.copy(alpha = 0.55f), Card)) else Card)
                    .glassSurface(tier = tier, radiusPx = radiusPx, tint = SheetGlassTint)
                    .clip(RoundedCornerShape(topStart = UiTokens.RadiusSheet, topEnd = UiTokens.RadiusSheet))
                    .statusBarsPadding()
            ) {
                // Drag handle.
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(Sub.copy(alpha = 0.5f)))
                }
                // Title row: ✕ left, serif centred title, spacer keeps the title truly centred.
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Ic.Close, "关闭", tint = Sub) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(title, style = HiveSerif.SheetTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(48.dp))
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    content = content
                )
            }
        }
    }
}

/**
 * One row of the Summary timeline (STUDY §2.5): a rounded square icon tile, a vertical connector
 * line, bold white step name and grey description underneath. [running] renders the coral dot +
 * serif italic "thinking…" state instead of the static icon.
 */
@Composable
fun SummaryStepRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    detail: String,
    running: Boolean,
    last: Boolean,
    onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(44.dp).fillMaxSize().padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (running) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Coral.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Coral))
                }
            } else {
                Box(
                    Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(PILL)
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, tint = Sub, modifier = Modifier.size(15.dp)) }
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(HAIRLINE))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
            if (running) {
                Text(name, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("思考中…", style = HiveSerif.Thinking(), modifier = Modifier.padding(top = 2.dp))
            } else {
                Text(name, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) Text(
                    detail, color = Sub, fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** Full-round white pill with black text — the "+ New chat" / "+ New project" signature button. */
@Composable
fun NewChatPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(UiTokens.RadiusChip)).background(PillWhite)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Ic.Add, null, tint = Ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

// ============================================================
// ThemeColor — user-selectable accent color (persisted, not in Store.kt)
// ============================================================

/**
 * Theme color preset with HSV values for the custom picker.
 * Hue in [0,360), Saturation in [0,1], Value in [0,1].
 */
data class ThemePreset(
    val name: String,
    val hue: Float,
    val saturation: Float,
    val value: Float
) {
    val argb: Int
        get() = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))

    val composeColor: Color
        get() = Color(argb)
}

object ThemeColor {
    // Default: Honey Yellow #FFC83D (hue ~45, sat ~0.76, val ~1.0)
    private const val PREFS = "theme_color"
    private const val KEY_HUE = "hue"
    private const val KEY_SAT = "saturation"
    private const val KEY_VAL = "value"
    private const val KEY_CUSTOM = "is_custom"

    // 8 presets at matched lightness/saturation for harmony
    val presets = listOf(
        ThemePreset("蜂蜜黄", 45f, 0.76f, 1.00f),      // #FFC83D - default
        ThemePreset("Claude 橙", 18f, 0.65f, 0.83f),    // #D47755
        ThemePreset("天空蓝", 210f, 0.55f, 0.90f),      // #74A6E6
        ThemePreset("抹茶绿", 145f, 0.50f, 0.85f),      // ~#6DB87B
        ThemePreset("葡萄紫", 275f, 0.50f, 0.80f),      // ~#A678D4
        ThemePreset("樱花粉", 340f, 0.55f, 0.90f),      // #E8A8B8
        ThemePreset("薄荷青", 170f, 0.50f, 0.88f),      // #6DD8C8
        ThemePreset("珍珠白", 60f, 0.10f, 0.95f)        // #F0EAD6 (warm off-white)
    )

    // Current live state (mutable for Compose)
    var hue by mutableStateOf(presets[0].hue)
    var saturation by mutableStateOf(presets[0].saturation)
    var value by mutableStateOf(presets[0].value)
    var isCustom by mutableStateOf(false)

    val currentColor: Color
        get() = Color.hsv(hue, saturation, value)

    /** Deep/pressed variant (lower value) */
    val currentDeep: Color
        get() = Color.hsv(hue, saturation, (value * 0.85f).coerceIn(0f, 1f))

    /** Pale/muted variant (lower saturation) */
    val currentPale: Color
        get() = Color.hsv(hue, (saturation * 0.45f).coerceIn(0f, 1f), value)

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hue = prefs.getFloat(KEY_HUE, presets[0].hue)
        saturation = prefs.getFloat(KEY_SAT, presets[0].saturation)
        value = prefs.getFloat(KEY_VAL, presets[0].value)
        isCustom = prefs.getBoolean(KEY_CUSTOM, false)
    }

    fun set(context: Context, h: Float, s: Float, v: Float, custom: Boolean = false) {
        hue = h
        saturation = s
        value = v
        isCustom = custom
        prefs.edit()
            .putFloat(KEY_HUE, h)
            .putFloat(KEY_SAT, s)
            .putFloat(KEY_VAL, v)
            .putBoolean(KEY_CUSTOM, custom)
            .apply()
    }

    fun setPreset(context: Context, preset: ThemePreset) {
        set(context, preset.hue, preset.saturation, preset.value, custom = false)
    }

    fun setCustom(context: Context, h: Float, s: Float, v: Float) {
        set(context, h, s, v, custom = true)
    }
}
