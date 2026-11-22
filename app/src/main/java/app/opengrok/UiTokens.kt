package app.opengrok

import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Refined Titanium Obsidian & Liquid Glass design tokens (BotHive LiquidGlass Pro).
 * High-contrast neutral studio dark skeleton so Liquid Glass optics take center stage
 * with zero muddy brown/grey cast and zero cliché AI gradients.
 */
object UiTokens {
    const val PAGE_BG = 0xFF0B0C0E        // Deep Titanium Obsidian
    const val CARD_BG = 0xFF16181D        // Smoked Crystal Glass Fallback
    const val PILL_BG = 0xFF20232A        // Elevated Pill Fallback
    const val ROUND_BTN_BG = 0xFF23262E   // Recessed Glass Button Fallback
    const val TEXT_MAIN = 0xFFF6F7F9      // Crisp Pearl White
    const val TEXT_SUB = 0xFF969BA6       // Cool Titanium Secondary
    const val CORAL = 0xFFD47755          // Semantic warm accent
    const val SELECT_BLUE = 0xFF74A6E6    // Selected check / semantic outline
    const val USER_BUBBLE = 0xFF1E222A    // User glass bubble fallback
    const val HAIRLINE = 0x1FFFFFFF       // Crisp 12% white optical hairline

    // Lens parameters
    const val LENS_BEVEL_PX = 28f
    const val LENS_REFR_PX = 42f
    const val LENS_DISP_PX = 4.6f
    const val LENS_BLUR_RADIUS = 5.5f
    const val LENS_TINT_R = 235f
    const val LENS_TINT_G = 242f
    const val LENS_TINT_B = 255f
    const val LENS_TINT_A = 0.08f
    const val LENS_ZOOM_CAPSULE = 1.18f
    const val LENS_ZOOM_SWITCH = 1.55f

    val RadiusCard = 22.dp
    val RadiusSheet = 30.dp
    val RadiusChip = 999.dp
}

object HiveSerif {
    val family: FontFamily = HiveFonts.Roman

    @Composable
    fun Title(size: TextUnit = 28.sp) = androidx.compose.ui.text.TextStyle(
        fontFamily = family, fontSize = size, lineHeight = size * 1.25f,
        fontWeight = FontWeight.Normal, color = Fg
    )

    @Composable
    fun SheetTitle() = androidx.compose.ui.text.TextStyle(
        fontFamily = family, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Fg
    )

    @Composable
    fun Thinking() = androidx.compose.ui.text.TextStyle(
        fontFamily = family, fontSize = 15.sp, fontStyle = FontStyle.Italic, color = Sub
    )
}

val PILL = Color(0xFF20232A)
val HAIRLINE = Color(0x1FFFFFFF)
val Coral = Color(0xFFD47755)
val SelectBlue = Color(0xFF74A6E6)
val UserBubble = Color(0xFF1E222A)
val SheetGlassTint = Color(0xFF141820).copy(alpha = 0.14f)
val PillWhite = Color(0xFFF6F7F9)

/**
 * Floating iOS 26/27 Liquid Glass bottom sheet: inset margins, sculptural rounded crystal corners,
 * recessed glass close button, and deep optical frost so blurred depth shines behind the sheet.
 */
@Composable
fun ClaudeSheet(
    title: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(visible) { onDismiss() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(GlassMotion.drawer())), exit = fadeOut(tween(GlassMotion.drawer()))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f))
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
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 18.dp, start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .harmonyGlass(
                        thickness = ImmersiveThickness.ULTRA_THICK,
                        radius = UiTokens.RadiusSheet,
                        tint = SheetGlassTint,
                        fallbackBg = Card
                    )
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(38.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.34f)))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).pressBounce { onDismiss() }
                            .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Ic.Close, "关闭", tint = Fg, modifier = Modifier.size(18.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(title, style = HiveSerif.SheetTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(38.dp))
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    content = content
                )
            }
        }
    }
}

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
                Box(Modifier.size(26.dp).clip(CircleShape).background(ThemeAccent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(ThemeAccent))
                }
            } else {
                Box(
                    Modifier.size(28.dp)
                        .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 9.dp, fallbackBg = PILL)
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, tint = Fg, modifier = Modifier.size(15.dp)) }
            }
            if (!last) Box(Modifier.width(1.5.dp).weight(1f).background(HAIRLINE))
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

@Composable
fun NewChatPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.pressBounce(onClick = onClick)
            .clip(RoundedCornerShape(UiTokens.RadiusChip))
            .background(PillWhite)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Ic.Add, null, tint = Ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

// ============================================================
// ThemeColor — user-selectable accent color (with Acid-Volt & Titanium!)
// ============================================================

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
    private const val PREFS = "theme_color"
    private const val KEY_HUE = "hue"
    private const val KEY_SAT = "saturation"
    private const val KEY_VAL = "value"
    private const val KEY_CUSTOM = "is_custom"

    val presets = listOf(
        ThemePreset("蜂蜜黄", 45f, 0.76f, 1.00f),       // #FFC83D - default
        ThemePreset("酸柠荧光", 73f, 1.00f, 1.00f),      // #C8FF00 - signature Acid-Volt
        ThemePreset("极光钛银", 215f, 0.08f, 0.96f),     // #E2E7F5 - optical titanium
        ThemePreset("Claude 橙", 18f, 0.65f, 0.83f),     // #D47755
        ThemePreset("天空蓝", 210f, 0.55f, 0.90f),       // #74A6E6
        ThemePreset("抹茶绿", 145f, 0.50f, 0.85f),       // #6DB87B
        ThemePreset("薄荷青", 170f, 0.50f, 0.88f),       // #6DD8C8
        ThemePreset("樱花粉", 340f, 0.55f, 0.90f)        // #E8A8B8
    )

    var hue by mutableStateOf(presets[0].hue)
    var saturation by mutableStateOf(presets[0].saturation)
    var value by mutableStateOf(presets[0].value)
    var isCustom by mutableStateOf(false)

    val currentColor: Color
        get() = Color.hsv(hue, saturation, value)

    val currentDeep: Color
        get() = Color.hsv(hue, saturation, (value * 0.85f).coerceIn(0f, 1f))

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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
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
