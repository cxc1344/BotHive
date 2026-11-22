package app.opengrok

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bix — BotHive's 8-bit pixel bee mascot companion engine.
 *
 * Pure Compose Canvas pixel scenes (zero external bitmaps, zero edits to BeeUi.kt):
 *  - Coding: Bix typing on a glowing 8-bit pixel computer.
 *  - Thinking: Bix pondering with floating pixel thought bubbles & lightbulb.
 *  - Searching: Bix sweeping left & right with an 8-bit pixel magnifying glass.
 *  - DrillingSquad: Commander Bix briefing / lecturing a row of mini-Bix bees when dispatching sub-agents or bots.
 *  - ApprovalGuard: Bix holding an 8-bit crimson STOP sign above pending command approval cards.
 *  - Done / Dizzy / Idle: Lightweight reactive moods.
 */
enum class BixAction {
    Idle,
    Thinking,
    Coding,
    Searching,
    DrillingSquad,
    ApprovalGuard,
    Done,
    Dizzy
}

/** User-selectable 8-bit pixel accessories synced across every Bix appearance in the app. */
enum class BixAccessory(val label: String) {
    None("原味"),
    Shades("墨镜"),
    HardHat("安全帽"),
    Crown("皇冠"),
    Headset("耳机")
}

object BixPrefs {
    private const val PREFS = "bix_companion"
    private const val KEY_ACC = "accessory"

    var accessory by mutableStateOf(BixAccessory.None)

    fun init(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACC, null)
        accessory = BixAccessory.entries.firstOrNull { it.name == raw } ?: BixAccessory.None
    }

    fun setAccessory(context: Context, acc: BixAccessory) {
        accessory = acc
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACC, acc.name)
            .apply()
    }
}

// ============================================================
// 1. Thinking & Tool Summary Intelligence
// ============================================================

/**
 * Splits optional `<think>...</think>` blocks (both closed and live-streaming unclosed) out of an
 * assistant message so they can live cleanly inside the unified Bix execution summary box.
 */
fun splitThinkingContent(raw: String): Pair<String, String> {
    if (raw.isBlank()) return "" to ""
    val startIdx = raw.indexOf("<think>", ignoreCase = true)
    if (startIdx < 0) return "" to raw
    val openEnd = startIdx + "<think>".length
    val closeIdx = raw.indexOf("</think>", startIndex = openEnd, ignoreCase = true)
    return if (closeIdx >= 0) {
        val thought = raw.substring(openEnd, closeIdx).trim()
        val before = raw.substring(0, startIdx)
        val after = raw.substring(closeIdx + "</think>".length)
        val answer = (before + after).trimStart()
        thought to answer
    } else {
        val thought = raw.substring(openEnd).trim()
        val answer = raw.substring(0, startIdx).trim()
        thought to answer
    }
}

/**
 * Extracts ONLY the last non-blank line of the thinking process and prefixes it with "…",
 * matching the compact classic Agent preview: "…xxxxxxxxxx".
 */
fun formatLastThinkingLine(thought: String): String {
    val lines = thought.lines().map { it.trim() }.filter { it.isNotBlank() }
    val last = lines.lastOrNull() ?: return "…正在整理思路"
    val clean = last.replace(Regex("\\s+"), " ")
    val clipped = if (clean.length > 42) clean.takeLast(42) else clean
    return if (clipped.startsWith("…")) clipped else "…$clipped"
}

/** Classifies a tool call into the matching Bix pixel animation state. */
fun classifyToolBixAction(toolName: String): BixAction = when (toolName) {
    "ask_bot", "run_workflow", "schedule_task" -> BixAction.DrillingSquad
    "screen_shot", "screen_dump", "fs_scan", "fs_list", "recall",
    "list_apps", "recent_notifications", "app_usage" -> BixAction.Searching
    else -> BixAction.Coding
}

/**
 * Produces a concise, human-readable Chinese description of what a tool command is doing,
 * preferring any explicit summary/description field if provided by the model.
 */
fun humanToolSummary(t: ToolCall): String {
    val o = try { JSONObject(t.args) } catch (_: Exception) { null }
    val explicit = o?.let { obj ->
        listOf("summary", "description", "reason", "intent", "purpose").firstNotNullOfOrNull { k ->
            obj.optString(k, "").trim().takeIf { it.isNotBlank() }
        }
    }
    if (!explicit.isNullOrBlank()) return explicit.take(48)

    fun arg(vararg keys: String): String {
        if (o == null) return ""
        for (k in keys) {
            val v = o.optString(k, "").trim()
            if (v.isNotBlank()) return v.replace(Regex("\\s+"), " ")
        }
        return ""
    }

    return when (t.name) {
        "device_status" -> "检查设备状态、电量与存储空间"
        "app_usage" -> "统计今日各应用使用时长"
        "list_apps" -> "列出手机已安装应用"
        "launch_app" -> {
            val target = arg("app", "name", "package")
            if (target.isNotBlank()) "打开应用「${target.take(20)}」" else "打开指定应用"
        }
        "browser_open" -> {
            val u = arg("url")
            if (u.isNotBlank()) "独立浏览器打开 ${u.removePrefix("https://").removePrefix("http://").take(24)}" else "启动 AI 独立浏览器"
        }
        "browser_mouse" -> {
            val act = arg("action").lowercase()
            val btn = when (arg("button").lowercase()) {
                "right" -> "右键"
                "middle" -> "中键"
                else -> "左键"
            }
            when (act) {
                "long_press" -> "浏览器${btn}长按"
                "drag" -> "浏览器${btn}按住拖动"
                "wheel" -> "浏览器滚轮滚动"
                "double_click" -> "浏览器${btn}双击"
                "move", "hover" -> "移动浏览器光标"
                else -> "浏览器${btn}点击"
            }
        }
        "browser_type" -> {
            val txt = arg("text")
            if (txt.isNotBlank()) "浏览器输入「${txt.take(18)}」" else "向浏览器输入文字"
        }
        "browser_nav" -> when (arg("action").lowercase()) {
            "back" -> "浏览器后退"
            "forward" -> "浏览器前进"
            "reload" -> "刷新浏览器页面"
            else -> "查看浏览器实况画面"
        }
        "browser_close" -> "关闭独立 AI 浏览器"
        "open_url" -> {
            val u = arg("url")
            if (u.isNotBlank()) "打开网页 ${u.take(28)}" else "打开网页链接"
        }
        "recent_notifications" -> "查看最近收到的手机通知"
        "ask_bot" -> {
            val target = arg("bot_name", "bot", "target", "name")
            val task = arg("message", "task", "prompt", "text")
            when {
                target.isNotBlank() && task.isNotBlank() -> "派发给「${target.take(12)}」：${task.take(22)}"
                target.isNotBlank() -> "向「${target.take(16)}」派发子任务"
                else -> "向子 Bot 派发协作任务"
            }
        }
        "run_workflow" -> {
            val wf = arg("name", "workflow", "title")
            if (wf.isNotBlank()) "启动工作流「${wf.take(20)}」" else "启动自动化工作流"
        }
        "schedule_task" -> {
            val task = arg("task", "message", "title", "prompt")
            if (task.isNotBlank()) "安排定时任务：${task.take(22)}" else "安排后台定时任务"
        }
        "list_tasks" -> "查看待执行的定时任务"
        "remember" -> "写入一条长期记忆"
        "recall" -> {
            val q = arg("query", "category")
            if (q.isNotBlank()) "检索记忆「${q.take(20)}」" else "读取长期记忆"
        }
        "list_skills" -> "查看可用技能包"
        "create_skill" -> {
            val n = arg("name", "title")
            if (n.isNotBlank()) "创建新技能包「${n.take(18)}」" else "创建新技能包"
        }
        "fs_list" -> {
            val p = arg("path")
            if (p.isNotBlank()) "浏览目录 ${p.take(28)}" else "列出目录文件"
        }
        "fs_read" -> {
            val p = arg("path")
            if (p.isNotBlank()) "读取文件 ${p.substringAfterLast('/').take(24)}" else "读取文本文件"
        }
        "fs_write" -> {
            val p = arg("path")
            if (p.isNotBlank()) "写入文件 ${p.substringAfterLast('/').take(24)}" else "写入文件内容"
        }
        "fs_delete" -> {
            val p = arg("path")
            if (p.isNotBlank()) "删除路径 ${p.substringAfterLast('/').take(24)}" else "删除文件或目录"
        }
        "fs_scan" -> {
            val p = arg("path")
            if (p.isNotBlank()) "扫描空间占用 ${p.take(24)}" else "扫描目录存储占用"
        }
        "screen_shot" -> "截取当前屏幕画面"
        "screen_dump" -> "解析当前屏幕界面结构"
        "tap_at" -> "点击屏幕指定坐标"
        "long_press" -> "长按屏幕目标位置"
        "drag_at" -> "在屏幕上滑动或拖动"
        "tap_text" -> {
            val txt = arg("text")
            if (txt.isNotBlank()) "点击文字「${txt.take(16)}」" else "点击屏幕文字元素"
        }
        "input_text" -> {
            val txt = arg("text")
            if (txt.isNotBlank()) "输入内容「${txt.take(18)}」" else "向输入框写入文字"
        }
        "press_key" -> {
            val k = arg("key")
            if (k.isNotBlank()) "按下系统按键 ($k)" else "按下系统按键"
        }
        "screen_mode" -> "检查或切换虚拟屏模式"
        "phone_task" -> {
            val task = arg("task", "goal", "instruction", "text")
            if (task.isNotBlank()) "自动操屏：${task.take(24)}" else "调用手机模型连续操作屏幕"
        }
        "shizuku_shell" -> {
            val cmd = arg("command")
            summarizeShellCommand(cmd, privileged = true)
        }
        "shell" -> {
            val cmd = arg("command")
            summarizeShellCommand(cmd, privileged = false)
        }
        else -> {
            val fallback = arg("command", "path", "query", "url", "name", "text")
            if (fallback.isNotBlank()) "${t.name} · ${fallback.take(26)}" else "执行 ${t.name}"
        }
    }
}

private fun summarizeShellCommand(cmd: String, privileged: Boolean): String {
    val c = cmd.trim()
    val prefix = if (privileged) "Shizuku " else ""
    if (c.isBlank()) return "${prefix}执行终端命令"
    val lower = c.lowercase()
    return when {
        lower.startsWith("python") -> "${prefix}运行 Python 脚本"
        lower.startsWith("node ") || lower.startsWith("npm ") -> "${prefix}运行 Node/JS 任务"
        lower.startsWith("git ") -> "${prefix}执行 Git 版本操作"
        lower.startsWith("ls") || lower.startsWith("find ") -> "${prefix}查看目录文件"
        lower.startsWith("cat ") || lower.startsWith("head ") || lower.startsWith("tail ") -> "${prefix}读取文件内容"
        lower.startsWith("grep ") || lower.startsWith("rg ") -> "${prefix}搜索文本内容"
        lower.startsWith("curl ") || lower.startsWith("wget ") -> "${prefix}请求网络数据"
        lower.startsWith("apt") || lower.startsWith("pip") -> "${prefix}安装或管理软件包"
        lower.startsWith("rm ") -> "${prefix}清理删除文件"
        lower.startsWith("mkdir ") || lower.startsWith("cp ") || lower.startsWith("mv ") -> "${prefix}整理目录与文件"
        lower.startsWith("dumpsys ") -> "读取系统服务状态"
        lower.startsWith("input ") -> "模拟屏幕点击或输入"
        lower.startsWith("screencap") -> "截取系统屏幕画面"
        lower.startsWith("pm ") || lower.startsWith("am ") -> "管理手机应用进程"
        else -> "${prefix}执行：${c.take(28)}"
    }
}

/** Time-of-day witty greeting line for Bix on empty chat screens. */
fun bixTimeGreeting(isAgent: Boolean): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour in 0..5 -> if (isAgent) "夜深了还在折腾手机？Bix 陪你顶着。" else "凌晨还在肝？有事直接扔给 Bix。"
        hour in 6..10 -> if (isAgent) "早安，Bix 已经擦亮屏幕等你吩咐了。" else "早安，蜂巢刚醒，今天先干哪件？"
        hour in 11..13 -> "午休时间到，琐事交给 Bix 跑就行。"
        hour in 14..18 -> if (isAgent) "下午好，查文件、看屏或跑沙盒随时叫 Bix。" else "下午好，Bix 和小分队随时待命。"
        else -> if (isAgent) "晚上好，把繁琐操作丢给 Bix 吧。" else "晚上好，今天想聊点什么或派什么活？"
    }
}

private val BIX_POKE_QUIPS = listOf(
    "嗡嗡！别戳了，键盘都要冒火星子了",
    "Bix #001 在岗，随时可以派活！",
    "再戳我就把小蜜蜂分队全喊出来开会了",
    "液态玻璃擦得很亮，但别把我戳滑下去",
    "收到！核心频率已超频至 8-bit 满血模式"
)

val BIX_SWITCH_QUIPS = listOf(
    "别拨了，液态玻璃要磨出火星子了！",
    "喂喂，这开关弹得我都快晕车了…",
    "行行行，丝滑度满分，手下留情吧！"
)

// ============================================================
// 2. Pure 8-Bit Pixel Art Bix Scene Renderer
// ============================================================

private val BixOutline = Color(0xFF2B2118)
private val BixGold = Color(0xFFFFC83D)
private val BixGoldLight = Color(0xFFFFE88A)
private val BixStripe = Color(0xFF4A3322)
private val BixCoral = Color(0xFFFF8F5C)
private val BixWing = Color(0xFFEAF8FB)
private val BixWingEdge = Color(0xFF9AD3E3)
private val BixCheek = Color(0xFFFF9AA8)
private val BixLaptopBody = Color(0xFF2A313D)
private val BixLaptopEdge = Color(0xFF6B778D)
private val BixScreenBg = Color(0xFF0E1B16)
private val BixCodeGreen = Color(0xFF5AF78E)
private val BixCodeVolt = Color(0xFFC8FF00)
private val BixStopRed = Color(0xFFE84A3C)
private val BixCapRed = Color(0xFFD94A38)
private val BixGlassCyan = Color(0xFF8BE9FD)
private val BixHatYellow = Color(0xFFFFD60A)
private val BixHeadsetBlue = Color(0xFF74A6E6)

/**
 * Renders Bix performing the requested [action] in crisp 8-bit pixel style.
 * Automatically renders the user's chosen [BixPrefs.accessory] across the entire app!
 */
@Composable
fun BixScene(
    action: BixAction,
    compact: Boolean = false,
    accessoryOverride: BixAccessory? = null,
    modifier: Modifier = Modifier
) {
    val motion = GlassStyle.tier.value.motion
    val acc = accessoryOverride ?: BixPrefs.accessory
    val inf = rememberInfiniteTransition(label = "bix-scene")
    val tick by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (action) {
                    BixAction.Coding -> 520
                    BixAction.DrillingSquad -> 900
                    BixAction.Searching -> 1150
                    BixAction.Thinking -> 1400
                    BixAction.ApprovalGuard -> 760
                    BixAction.Dizzy -> 900
                    else -> 1300
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "bix-tick"
    )
    val phase = if (motion) tick else 0.25f

    val width: Dp
    val height: Dp
    when {
        action == BixAction.DrillingSquad && compact -> { width = 48.dp; height = 28.dp }
        action == BixAction.DrillingSquad && !compact -> { width = 136.dp; height = 56.dp }
        action == BixAction.Coding && compact -> { width = 38.dp; height = 28.dp }
        action == BixAction.Coding && !compact -> { width = 92.dp; height = 54.dp }
        action == BixAction.Searching && compact -> { width = 36.dp; height = 28.dp }
        action == BixAction.Searching && !compact -> { width = 88.dp; height = 54.dp }
        compact -> { width = 30.dp; height = 28.dp }
        else -> { width = 76.dp; height = 54.dp }
    }

    Canvas(modifier = modifier.size(width, height)) {
        when (action) {
            BixAction.Coding -> drawBixCoding(phase, compact, acc)
            BixAction.Thinking -> drawBixThinking(phase, compact, acc)
            BixAction.Searching -> drawBixSearching(phase, compact, acc)
            BixAction.DrillingSquad -> drawBixDrillingSquad(phase, compact, acc)
            BixAction.ApprovalGuard -> drawBixApprovalGuard(phase, compact, acc)
            BixAction.Dizzy -> drawBixDizzy(phase, compact, acc)
            BixAction.Done -> drawBixDone(phase, compact, acc)
            BixAction.Idle -> drawBixIdle(phase, compact, acc)
        }
    }
}

private fun DrawScope.px(x: Float, y: Float, w: Float = 1f, h: Float = 1f, unit: Float, originX: Float, originY: Float, color: Color) {
    drawRect(
        color = color,
        topLeft = Offset(originX + x * unit, originY + y * unit),
        size = Size(w * unit, h * unit)
    )
}

/**
 * Draws the recognizable 8-bit Bix core (14x13 logical pixels) with selectable wing flap,
 * expression, and optional 8-bit accessory from [BixPrefs].
 */
private fun DrawScope.drawCoreBix(
    originX: Float,
    originY: Float,
    unit: Float,
    wingUp: Boolean,
    face: Int = 0, // 0=cheer, 1=focused, 2=happy, 3=dizzy, 4=alert
    commanderCap: Boolean = false,
    accessory: BixAccessory = BixAccessory.None,
    accentGlow: Color? = null
) {
    fun p(x: Float, y: Float, w: Float = 1f, h: Float = 1f, c: Color) =
        px(x, y, w, h, unit, originX, originY, c)

    if (accentGlow != null) {
        p(2f, 3f, 10f, 8f, accentGlow.copy(alpha = 0.22f))
    }

    // Wings
    val wy = if (wingUp) 2f else 4f
    p(0f, wy, 3f, 3f, BixWingEdge)
    p(0.6f, wy + 0.5f, 2f, 2f, BixWing)
    p(11f, wy, 3f, 3f, BixWingEdge)
    p(11.4f, wy + 0.5f, 2f, 2f, BixWing)

    // Headgear: Commander Cap takes precedence in DrillingSquad; otherwise user accessory
    when {
        commanderCap -> {
            p(3f, 0f, 8f, 2f, BixCapRed)
            p(4f, 0f, 6f, 1f, BixGold)
            p(2f, 2f, 11f, 1f, BixOutline)
        }
        accessory == BixAccessory.HardHat -> {
            p(3f, 0.2f, 8f, 2.2f, BixHatYellow)
            p(5.5f, 0f, 3f, 0.9f, BixGoldLight)
            p(2f, 2.2f, 10f, 0.9f, BixOutline)
        }
        accessory == BixAccessory.Crown -> {
            p(3.5f, 0.4f, 1.4f, 2.2f, BixGoldLight)
            p(6.3f, 0f, 1.4f, 2.6f, BixGoldLight)
            p(9.1f, 0.4f, 1.4f, 2.2f, BixGoldLight)
            p(3.5f, 1.8f, 7.0f, 1.1f, BixGold)
            p(6.6f, 1.9f, 0.8f, 0.8f, BixStopRed)
        }
        else -> {
            p(4f, 0f, 1f, 2f, BixCoral)
            p(9f, 0f, 1f, 2f, BixCoral)
            p(4f, 2f, 1f, 1f, BixOutline)
            p(9f, 2f, 1f, 1f, BixOutline)
        }
    }

    // Body silhouette & warm honey fill
    p(3f, 3f, 8f, 1f, BixOutline)
    p(2f, 4f, 10f, 6f, BixOutline)
    p(3f, 10f, 8f, 2f, BixOutline)
    p(5f, 12f, 4f, 1f, BixOutline)

    p(3f, 4f, 8f, 2f, BixGoldLight)
    p(3f, 6f, 8f, 3f, BixGold)
    p(3f, 9f, 8f, 1.3f, BixStripe)
    p(4f, 10.3f, 6f, 1f, BixGold)

    // Cheeks
    p(3.2f, 7.6f, 1.2f, 0.9f, BixCheek)
    p(9.6f, 7.6f, 1.2f, 0.9f, BixCheek)

    // Face
    when (face) {
        1 -> {
            p(4.4f, 5.4f, 1.6f, 0.7f, BixOutline)
            p(8.0f, 5.4f, 1.6f, 0.7f, BixOutline)
            p(4.8f, 6.2f, 1.2f, 1.4f, BixOutline)
            p(8.0f, 6.2f, 1.2f, 1.4f, BixOutline)
            p(6.2f, 8.0f, 1.6f, 0.7f, BixOutline)
        }
        2 -> {
            p(4.6f, 6.3f, 1.6f, 0.8f, BixOutline)
            p(7.8f, 6.3f, 1.6f, 0.8f, BixOutline)
            p(6.0f, 7.8f, 2.0f, 0.9f, BixCoral)
        }
        3 -> {
            p(4.6f, 6.0f, 1.4f, 1.4f, BixStopRed)
            p(8.0f, 6.0f, 1.4f, 1.4f, BixStopRed)
            p(6.0f, 8.0f, 2.0f, 0.8f, BixOutline)
        }
        4 -> {
            p(4.6f, 5.8f, 1.3f, 1.7f, BixOutline)
            p(8.1f, 5.8f, 1.3f, 1.7f, BixOutline)
            p(4.8f, 6.0f, 0.5f, 0.5f, Color.White)
            p(8.3f, 6.0f, 0.5f, 0.5f, Color.White)
            p(6.4f, 8.0f, 1.2f, 0.9f, BixOutline)
        }
        else -> {
            p(4.7f, 6.0f, 1.3f, 1.5f, BixOutline)
            p(8.0f, 6.0f, 1.3f, 1.5f, BixOutline)
            p(4.9f, 6.1f, 0.5f, 0.5f, Color.White)
            p(8.2f, 6.1f, 0.5f, 0.5f, Color.White)
            p(6.2f, 7.9f, 1.6f, 0.7f, BixOutline)
        }
    }

    // Face / side accessories
    if (accessory == BixAccessory.Shades) {
        p(3.2f, 5.8f, 7.6f, 0.6f, Color(0xFF14161A))
        p(3.7f, 6.2f, 2.8f, 1.4f, Color(0xFF14161A))
        p(7.5f, 6.2f, 2.8f, 1.4f, Color(0xFF14161A))
        p(4.2f, 6.4f, 0.9f, 0.5f, Color.White.copy(alpha = 0.75f))
        p(8.0f, 6.4f, 0.9f, 0.5f, Color.White.copy(alpha = 0.75f))
    } else if (accessory == BixAccessory.Headset) {
        // Headband arch + glowing ear cups
        p(3.0f, 2.4f, 8.0f, 0.8f, BixLaptopEdge)
        p(1.5f, 5.5f, 1.2f, 2.8f, BixHeadsetBlue)
        p(11.3f, 5.5f, 1.2f, 2.8f, BixHeadsetBlue)
        p(9.6f, 8.4f, 2.4f, 0.6f, BixLaptopEdge) // mic boom
    }
}

/** Scene 1: Bix hammering code on a glowing 8-bit pixel laptop! */
private fun DrawScope.drawBixCoding(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bob = if (phase < 0.5f) 0f else -0.45f * u
    val bx = if (compact) 0.5f * u else (size.width - 23f * u) * 0.5f
    val by = size.height - 13.5f * u + bob

    val glowAlpha = 0.14f + 0.10f * sin(phase * 2f * PI).toFloat()
    drawRect(
        color = BixCodeGreen.copy(alpha = glowAlpha.coerceIn(0.05f, 0.28f)),
        topLeft = Offset(bx + 10f * u, by + 3f * u),
        size = Size(12f * u, 9f * u)
    )

    drawCoreBix(bx, by, u, wingUp = (phase * 4f).toInt() % 2 == 0, face = 1, accessory = acc)

    val leftPawDown = (phase * 4f).toInt() % 2 == 0
    val ox = bx + 11.5f * u
    val oy = size.height - 10.5f * u
    px(0f, if (leftPawDown) 6.4f else 5.2f, 1.6f, 1.1f, u, ox, oy, BixGold)
    px(1.8f, if (!leftPawDown) 6.4f else 5.2f, 1.6f, 1.1f, u, ox, oy, BixGoldLight)

    px(2.5f, 0.5f, 8.5f, 6.5f, u, ox, oy, BixLaptopEdge)
    px(3.2f, 1.2f, 7.1f, 5.1f, u, ox, oy, BixScreenBg)

    val scroll = (phase * 4f).toInt()
    val lineWidths = floatArrayOf(4.2f, 5.6f, 3.1f, 4.9f)
    for (i in 0..2) {
        val w = lineWidths[(i + scroll) % lineWidths.size]
        val c = if ((i + scroll) % 3 == 0) BixCodeVolt else BixCodeGreen
        px(3.9f, 2.0f + i * 1.3f, w, 0.75f, u, ox, oy, c)
    }
    if ((phase * 2f).toInt() % 2 == 0) {
        px(3.9f + lineWidths[(2 + scroll) % 4] + 0.4f, 4.6f, 0.8f, 0.8f, u, ox, oy, Color.White)
    }

    px(0.5f, 7.0f, 10.5f, 1.6f, u, ox, oy, BixLaptopBody)
    px(1.0f, 7.0f, 9.5f, 0.5f, u, ox, oy, BixLaptopEdge)
    px(1.8f, 7.6f, 1.3f, 0.5f, u, ox, oy, Color.White.copy(alpha = 0.45f))
    px(3.6f, 7.6f, 1.8f, 0.5f, u, ox, oy, Color.White.copy(alpha = 0.45f))
    px(5.9f, 7.6f, 1.4f, 0.5f, u, ox, oy, Color.White.copy(alpha = 0.45f))
}

/** Scene 2: Bix pondering with rising 8-bit thought bubbles & pulsing lightbulb. */
private fun DrawScope.drawBixThinking(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bob = sin(phase * 2f * PI).toFloat() * 0.6f * u
    val bx = if (compact) 1.5f * u else (size.width - 19f * u) * 0.5f
    val by = size.height - 13.5f * u + bob

    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 1, accessory = acc)

    val pulse = 0.55f + 0.45f * sin(phase * 2f * PI).toFloat()
    val tx = bx + 11.8f * u
    val ty = by - 1.5f * u
    px(0.5f, 5.2f, 1.2f, 1.2f, u, tx, ty, Color.White.copy(alpha = 0.65f))
    px(2.0f, 3.5f, 1.6f, 1.6f, u, tx, ty, Color.White.copy(alpha = 0.80f))
    px(3.2f, 0.5f, 3.6f, 3.0f, u, tx, ty, BixGold.copy(alpha = pulse))
    px(4.1f, 1.1f, 1.8f, 1.6f, u, tx, ty, Color.White.copy(alpha = pulse))
    px(4.3f, 3.5f, 1.4f, 0.8f, u, tx, ty, BixLaptopEdge)
}

/** Scene 2b: Bix sweeping left & right with an 8-bit pixel magnifying glass (scanning files/screen/search). */
private fun DrawScope.drawBixSearching(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val sweep = sin(phase * 2f * PI).toFloat() * 1.4f * u
    val bx = if (compact) 0.8f * u else (size.width - 21f * u) * 0.5f
    val by = size.height - 13.5f * u

    drawCoreBix(bx, by, u, wingUp = (phase * 2f).toInt() % 2 == 0, face = 4, accessory = acc)

    // 8-bit Magnifying glass on the right sweeping back and forth
    val mx = bx + 12.2f * u + sweep
    val my = by + 3.0f * u
    // Outer rim
    px(1f, 0f, 4f, 1f, u, mx, my, BixLaptopEdge)
    px(1f, 5f, 4f, 1f, u, mx, my, BixLaptopEdge)
    px(0f, 1f, 1f, 4f, u, mx, my, BixLaptopEdge)
    px(5f, 1f, 1f, 4f, u, mx, my, BixLaptopEdge)
    // Cyan lens glass + specular pixel
    px(1f, 1f, 4f, 4f, u, mx, my, BixGlassCyan.copy(alpha = 0.55f))
    px(1.5f, 1.5f, 1.2f, 1.2f, u, mx, my, Color.White.copy(alpha = 0.90f))
    // Handle
    px(4.8f, 5.2f, 1.2f, 1.2f, u, mx, my, BixStripe)
    px(5.8f, 6.2f, 1.4f, 1.4f, u, mx, my, BixOutline)
}

/**
 * Scene 3: Commander Bix drilling a squad of 3 Mini-Bix bees!
 * Used whenever dispatching sub-Agents, cloud Bots (`ask_bot`), workflows (`run_workflow`), or active projects.
 */
private fun DrawScope.drawBixDrillingSquad(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14.5f else size.height / 17.5f
    val totalW = 29f * u
    val startX = ((size.width - totalW) * 0.5f).coerceAtLeast(0.5f * u)
    val baseY = size.height - 13.5f * u
    val lectureStep = (phase * 4f).toInt()

    val cmdBob = if (lectureStep % 2 == 0) -0.4f * u else 0f
    drawCoreBix(startX, baseY + cmdBob, u, wingUp = lectureStep % 2 == 0, face = 1, commanderCap = true, accessory = acc)

    val batonTipY = if (lectureStep % 2 == 0) 4.2f else 6.0f
    px(11.8f, 6.8f, 1.8f, 0.8f, u, startX, baseY + cmdBob, BixCoral)
    px(13.2f, batonTipY, 2.2f, 0.8f, u, startX, baseY + cmdBob, BixGoldLight)
    if (lectureStep % 2 == 0) {
        px(13.8f, 2.2f, 1.4f, 0.7f, u, startX, baseY, BixCodeVolt)
        px(14.4f, 3.4f, 1.0f, 0.7f, u, startX, baseY, BixGold)
    }

    val miniColors = listOf(Color(0xFF74A6E6), BixCoral, BixCodeVolt)
    val mu = u * 0.56f
    for (i in 0..2) {
        val mx = startX + (16.4f + i * 4.3f) * u
        val hop = when {
            i == 2 && lectureStep == 3 -> -2.8f * u
            (lectureStep + i) % 2 == 0 -> -0.6f * u
            else -> 0f
        }
        val my = size.height - 8.2f * u + hop
        drawCoreBix(mx, my, mu, wingUp = (lectureStep + i) % 2 == 0, face = 4, accentGlow = miniColors[i])
        if (i == 2 && lectureStep == 3) {
            px(2.8f, -3.2f, 0.9f, 2.0f, mu, mx, my, BixCodeVolt)
            px(2.8f, -0.7f, 0.9f, 0.8f, mu, mx, my, BixCodeVolt)
        }
    }
}

/** Scene 4: Approval Guard Bix holding an 8-bit crimson STOP sign. */
private fun DrawScope.drawBixApprovalGuard(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 16.5f
    val sway = sin(phase * 2f * PI).toFloat() * 0.5f * u
    val bx = (size.width - 19f * u) * 0.5f
    val by = size.height - 13.5f * u

    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 4, accessory = acc)

    val sx = bx + 12.2f * u + sway * 0.5f
    val sy = by + 1.2f * u
    px(2.5f, 5.5f, 1.0f, 5.5f, u, sx, sy, BixLaptopEdge)
    px(1.0f, 0.5f, 4.0f, 5.0f, u, sx, sy, BixStopRed)
    px(0.3f, 1.3f, 5.4f, 3.4f, u, sx, sy, BixStopRed)
    px(2.5f, 1.4f, 1.0f, 2.2f, u, sx, sy, Color.White)
    px(2.5f, 4.0f, 1.0f, 0.8f, u, sx, sy, Color.White)
}

/** Scene 5: Dizzy Bix with orbiting pixel stars when a tool fails or gets blocked. */
private fun DrawScope.drawBixDizzy(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14.5f else size.height / 17f
    val bx = (size.width - 14f * u) * 0.5f
    val by = size.height - 13f * u
    drawCoreBix(bx, by, u, wingUp = false, face = 3, accessory = acc)
    val angle = phase * 2f * PI
    val cx = bx + 7f * u
    val cy = by - 0.8f * u
    for (k in 0..1) {
        val a = angle + k * PI
        val sx = cx + cos(a).toFloat() * 4.5f * u
        val sy = cy + sin(a).toFloat() * 1.2f * u
        drawRect(BixGoldLight, Offset(sx, sy), Size(1.3f * u, 1.3f * u))
    }
}

/** Scene 6: Happy completed Bix with subtle golden sparkle. */
private fun DrawScope.drawBixDone(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 16.5f
    val bob = sin(phase * 2f * PI).toFloat() * 0.45f * u
    val bx = (size.width - 15f * u) * 0.5f
    val by = size.height - 13.5f * u + bob
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 2, accessory = acc)
    val spark = 0.4f + 0.6f * sin(phase * 2f * PI).toFloat()
    px(13.2f, 2.0f, 1.2f, 1.2f, u, bx, by, BixGoldLight.copy(alpha = spark))
    px(12.6f, 2.6f, 2.4f, 0.4f, u, bx, by, Color.White.copy(alpha = spark))
}

/** Scene 7: Gentle idle Bix. */
private fun DrawScope.drawBixIdle(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 16.5f
    val bob = sin(phase * 2f * PI).toFloat() * 0.5f * u
    val bx = (size.width - 14f * u) * 0.5f
    val by = size.height - 13.5f * u + bob
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 0, accessory = acc)
}

// ============================================================
// 3. Physical Bounce-Up & Roof-Landing Stage (半弹出摘要框顶栖息)
// ============================================================

@Composable
fun BixRoofPerch(
    action: BixAction,
    modifier: Modifier = Modifier
) {
    val motion = GlassStyle.tier.value.motion
    var hopTrigger by remember { mutableIntStateOf(0) }
    val offsetY = remember { Animatable(if (motion) 34f else 0f) }
    val scaleX = remember { Animatable(1f) }
    val scaleY = remember { Animatable(1f) }
    val rotationZ = remember { Animatable(0f) }
    val dustProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (!motion) return@LaunchedEffect
        launch {
            offsetY.animateTo(-60f, tween(210, easing = FastOutSlowInEasing))
            offsetY.animateTo(0f, tween(190, easing = FastOutLinearInEasing))
            offsetY.animateTo(-7f, tween(75, easing = FastOutSlowInEasing))
            offsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        launch {
            scaleX.animateTo(0.86f, tween(160))
            scaleX.animateTo(1.02f, tween(180))
            delay(45)
            scaleX.animateTo(1.24f, tween(70))
            scaleX.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        launch {
            scaleY.animateTo(1.18f, tween(160))
            scaleY.animateTo(0.98f, tween(180))
            delay(45)
            scaleY.animateTo(0.76f, tween(70))
            scaleY.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        launch {
            rotationZ.animateTo(-13f, tween(150))
            rotationZ.animateTo(8f, tween(180))
            rotationZ.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        launch {
            delay(385)
            dustProgress.snapTo(0.01f)
            dustProgress.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
            dustProgress.snapTo(0f)
        }
    }

    LaunchedEffect(hopTrigger) {
        if (hopTrigger == 0 || !motion) return@LaunchedEffect
        launch {
            offsetY.animateTo(-26f, tween(130, easing = FastOutSlowInEasing))
            offsetY.animateTo(0f, tween(130, easing = FastOutLinearInEasing))
        }
        launch {
            scaleX.animateTo(0.90f, tween(110))
            delay(130)
            scaleX.animateTo(1.16f, tween(65))
            scaleX.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
        launch {
            scaleY.animateTo(1.12f, tween(110))
            delay(130)
            scaleY.animateTo(0.84f, tween(65))
            scaleY.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    Box(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { hopTrigger++ },
        contentAlignment = Alignment.BottomCenter
    ) {
        val dp = dustProgress.value
        if (dp > 0.01f && dp < 0.99f) {
            Canvas(Modifier.size(110.dp, 20.dp)) {
                val alpha = (1f - dp).coerceIn(0f, 1f)
                val spread = dp * size.width * 0.36f
                val rise = sin(dp * PI).toFloat() * 7.dp.toPx()
                val cy = size.height - 4.dp.toPx() - rise
                val cx = size.width * 0.5f
                val ps = 3.dp.toPx()
                drawRect(BixGoldLight.copy(alpha = alpha), Offset(cx - 10.dp.toPx() - spread, cy), Size(ps, ps))
                drawRect(Color.White.copy(alpha = alpha * 0.9f), Offset(cx + 8.dp.toPx() + spread, cy), Size(ps, ps))
                drawRect(ThemeAccent.copy(alpha = alpha * 0.8f), Offset(cx - 4.dp.toPx() - spread * 0.6f, cy - 4.dp.toPx()), Size(ps * 0.8f, ps * 0.8f))
                drawRect(ThemeAccent.copy(alpha = alpha * 0.8f), Offset(cx + 4.dp.toPx() + spread * 0.6f, cy - 4.dp.toPx()), Size(ps * 0.8f, ps * 0.8f))
            }
        }
        BixScene(
            action = action,
            compact = false,
            modifier = Modifier
                .offset(y = offsetY.value.dp)
                .graphicsLayer {
                    this.scaleX = scaleX.value
                    this.scaleY = scaleY.value
                    this.rotationZ = rotationZ.value
                }
        )
    }
}

// ============================================================
// 4. Half-Height Liquid Glass Summary Sheet (半弹出摘要框)
// ============================================================

@Composable
fun BixSummaryHalfSheet(
    title: String,
    subtitle: String,
    bixAction: BixAction,
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(visible) { onDismiss() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(GlassMotion.drawer())),
            exit = fadeOut(tween(GlassMotion.drawer()))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.36f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(GlassMotion.drawer(), easing = FastOutSlowInEasing)) { it } +
                fadeIn(tween(GlassMotion.screenFade())),
            exit = slideOutVertically(tween(GlassMotion.drawer(), easing = FastOutSlowInEasing)) { it } +
                fadeOut(tween(GlassMotion.screenFade()))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.64f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                BixRoofPerch(
                    action = bixAction,
                    modifier = Modifier.zIndex(2f).offset(y = 8.dp)
                )

                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { }
                        .harmonyGlass(
                            thickness = ImmersiveThickness.ULTRA_THICK,
                            radius = UiTokens.RadiusSheet,
                            tint = SheetGlassTint,
                            fallbackBg = Card
                        )
                ) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .width(38.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.34f))
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .pressBounce { onDismiss() }
                                .harmonyGlass(
                                    thickness = ImmersiveThickness.ULTRA_THIN,
                                    radius = 999.dp,
                                    fallbackBg = PILL
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Ic.Close, "关闭", tint = Fg, modifier = Modifier.size(18.dp))
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                title,
                                style = HiveSerif.SheetTitle(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(Modifier.width(54.dp), contentAlignment = Alignment.CenterEnd) {
                            if (subtitle.isNotBlank()) {
                                Text(subtitle, color = Sub, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }

                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        content = content
                    )
                }
            }
        }
    }
}

// ============================================================
// 5. Home Hero, Empty Chat Hero, Settings Badge & Theme Pollen
// ============================================================

/** Living HomeScreen Bix hero: reacts to live tasks/approvals, wears your accessory, hops when poked! */
@Composable
fun BixHomeHero(
    working: Int,
    hasPendingApproval: Boolean,
    modifier: Modifier = Modifier
) {
    var pokes by remember { mutableIntStateOf(0) }
    var quip by remember { mutableStateOf<String?>(null) }
    val hopY = remember { Animatable(0f) }

    LaunchedEffect(pokes) {
        if (pokes == 0) return@LaunchedEffect
        hopY.animateTo(-16f, tween(110, easing = FastOutSlowInEasing))
        hopY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        if (pokes >= 2) {
            quip = BIX_POKE_QUIPS[(pokes - 2) % BIX_POKE_QUIPS.size]
            delay(2600)
            quip = null
        }
    }

    val liveAction = when {
        hasPendingApproval -> BixAction.ApprovalGuard
        working >= 2 -> BixAction.DrillingSquad
        working == 1 -> BixAction.Coding
        else -> BixAction.Idle
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .offset(y = hopY.value.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { pokes++ },
            contentAlignment = Alignment.Center
        ) {
            BixScene(action = liveAction, compact = false)
        }
        if (quip != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                quip ?: "",
                color = ThemeAccentPale,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun InteractiveBixHero(
    isAgent: Boolean,
    modifier: Modifier = Modifier
) {
    var pokes by remember { mutableIntStateOf(0) }
    var quip by remember { mutableStateOf<String?>(null) }
    val hopY = remember { Animatable(0f) }

    LaunchedEffect(pokes) {
        if (pokes == 0) return@LaunchedEffect
        hopY.animateTo(-14f, tween(110, easing = FastOutSlowInEasing))
        hopY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        if (pokes >= 3) {
            quip = BIX_POKE_QUIPS[(pokes - 3) % BIX_POKE_QUIPS.size]
            delay(2800)
            quip = null
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .offset(y = hopY.value.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { pokes++ },
            contentAlignment = Alignment.Center
        ) {
            BixScene(
                action = if (pokes >= 5) BixAction.Thinking else BixAction.Idle,
                compact = false
            )
        }
        val line = quip ?: bixTimeGreeting(isAgent)
        Spacer(Modifier.height(6.dp))
        Text(
            line,
            color = if (quip != null) ThemeAccentPale else Sub,
            fontSize = 12.sp,
            fontWeight = if (quip != null) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Settings "Bix 工牌 & 8-bit 配件" Liquid Glass card:
 * lets the user dress Bix in pixel sunglasses, hard hat, crown, or headset — synced app-wide!
 */
@Composable
fun BixBadgeCard() {
    val ctx = LocalContext.current
    val current = BixPrefs.accessory
    var previewMode by remember { mutableIntStateOf(0) }
    val previewActions = listOf(BixAction.Idle, BixAction.Coding, BixAction.Searching, BixAction.DrillingSquad)

    Column(
        Modifier
            .fillMaxWidth()
            .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = UiTokens.RadiusCard, fallbackBg = Card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bix 档案工牌", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("ID #001 · 蜂巢首席架构师 · 点 Bix 切动作预览", color = Sub, fontSize = 12.sp)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(ThemeAccent.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("8-BIT", color = ThemeAccentPale, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(74.dp)
                .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 16.dp, fallbackBg = PILL)
                .clickable { previewMode = (previewMode + 1) % previewActions.size },
            contentAlignment = Alignment.Center
        ) {
            BixScene(action = previewActions[previewMode], compact = false)
        }

        Text("像素配件（全端同步生效）", color = Sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BixAccessory.entries.forEach { acc ->
                val sel = acc == current
                Box(
                    Modifier
                        .then(
                            if (sel) Modifier.clip(RoundedCornerShape(14.dp)).background(ThemeAccent)
                            else Modifier.harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 14.dp, fallbackBg = PILL)
                        )
                        .clickable { BixPrefs.setAccessory(ctx, acc) }
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                ) {
                    Text(
                        acc.label,
                        color = if (sel) Ink else Fg,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/** Tiny live Bix + theme-tinted 8-bit pollen sparkles for ThemeColorCard preview bar. */
@Composable
fun BixThemePollenBadge(modifier: Modifier = Modifier) {
    val accent = ThemeAccent
    val inf = rememberInfiniteTransition(label = "bix-pollen")
    val p by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "pollen-phase"
    )
    Box(modifier.size(38.dp, 30.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val ps = 2.6.dp.toPx()
            val y1 = size.height * (0.75f - p * 0.55f)
            val y2 = size.height * (0.25f + ((p + 0.5f) % 1f) * 0.55f)
            drawRect(accent.copy(alpha = (1f - p).coerceIn(0.2f, 0.95f)), Offset(2.dp.toPx(), y1), Size(ps, ps))
            drawRect(accent.copy(alpha = 0.85f), Offset(size.width - 5.dp.toPx(), y2), Size(ps, ps))
        }
        BixScene(action = BixAction.Idle, compact = true)
    }
}
