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
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
    Dizzy,
    // v0.7.0: Bix is the face of every new feature.
    /** Guard cap + magnifying glass sweeping across (sentinel review, vault). */
    Sentinel,
    /** Crossed arms + red X (sentinel rejected / blocked action). */
    Blocked,
    /** Holding out a key to the user (HANDOFF policy, browser user takeover). */
    Handoff,
    /** Reading a newspaper (hive morning brief / chief of staff). */
    Briefing,
    /** Camera + blinking REC dot (teach mode recording). */
    Teaching,
    /** Hugging a honey jar that drains with the budget (usage & budget); see BixScene(fill). */
    Budget,
    /** Zzz (paused routines, auto-pause). */
    Sleeping,
    /** Ringing a bell (event triggers, notifications, webhooks). */
    Alarm,
    // v0.7.1 Super-Agent poses:
    /** Beaming signal arcs from an 8-bit radio tower (SuperRouter / Hive Topology). */
    Router,
    /** Checking off green pixel ticks on a clipboard (Persistent Ticket engine). */
    Ticket,
    /** Pulling glowing cyan data packets out of a scroll drawer (Inline & Spill recall). */
    Spill,
    /** Sweeping emerald radar dish + ECG heartbeat pulse (External HiveWatchdog). */
    Watchdog,
    /** Scribbling glowing sticky notes with pixel chalk on a slate board (Hive Blackboard). */
    Blackboard,
    /** Pounding a pixel wooden gavel with sparks (Multi-Bot Debate Arena). */
    Debate,
    // v0.7.2 Pure-Android Hive Relay, Floating Island & Personal Agent poses:
    /** Sprinting with a glowing golden-volt relay baton (DAG Auto-Relay Pipeline). */
    Relay,
    /** Peeking cheerfully from a floating Liquid Glass island capsule (Bix Floating Island). */
    Island,
    /** Hugging a glowing pixel memory pod box with color-coded tags (Portable Memory Pod). */
    MemoryPod,
    /** Planting and waving a pixel goal flag on a milestone hill (Proactive Goals & Check-in). */
    GoalFlag,
    /** Standing guard before a glowing pixel privacy fence blocking private notifications. */
    PrivacyFence
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
    // v0.7.0 tools
    "team_status" -> BixAction.Briefing
    "browser_fill_secret", "secret_list" -> BixAction.Sentinel
    "use_skill", "list_skills" -> BixAction.Thinking
    "remember" -> BixAction.Thinking
    "notify", "post_notification" -> BixAction.Alarm
    // v0.7.1 Super-Agent tools
    "ticket_create", "ticket_list" -> BixAction.Router
    "ticket_update" -> BixAction.Ticket
    "recall_spill" -> BixAction.Spill
    "board_write", "board_read" -> BixAction.Blackboard
    "open_deeplink" -> BixAction.Handoff
    else -> BixAction.Coding
}

/**
 * Maps an approval's sentinel verdict ("" | "pending" | "ok" | "warn" | "block" | "error") to a Bix pose.
 */
fun bixForSentinelVerdict(verdict: String): BixAction = when (verdict.trim().lowercase()) {
    "ok" -> BixAction.Done
    "block", "error" -> BixAction.Blocked
    "" -> BixAction.ApprovalGuard
    else -> BixAction.Sentinel
}

/** Maps a PolicyLevel / ActionClass-ish name to a Bix pose (string based so it never depends on A's enums). */
fun bixForPolicyLevel(levelName: String): BixAction = when (levelName.trim().uppercase()) {
    "AUTO" -> BixAction.Coding
    "EXPLICIT" -> BixAction.Thinking
    "ASK" -> BixAction.ApprovalGuard
    "HANDOFF" -> BixAction.Handoff
    else -> BixAction.Idle
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
        "browser_screenshot" -> "截取独立浏览器画面"
        "browser_close" -> "关闭独立 AI 浏览器"
        "ticket_create" -> "创建持久化超级工单"
        "ticket_update" -> "更新工单进度检查点"
        "ticket_list" -> "查看全部超级工单"
        "recall_spill" -> "链式召回 Spill 上下文片段"
        "open_deeplink" -> "通过 Deep Link 直达目标页面"
        "board_write" -> "写入蜂巢共享黑板"
        "board_read" -> "查阅蜂巢共享黑板"
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
private val BixGuardNavy = Color(0xFF2E4A7A)
private val BixPaper = Color(0xFFF4EEDC)
private val BixPaperInk = Color(0xFF8A8577)
private val BixSleepBlue = Color(0xFFBFD4FF)
// Hoisted constant tables: the draw functions run every animation frame and must not allocate.
private val BixCodeLineWidths = floatArrayOf(4.2f, 5.6f, 3.1f, 4.9f)
private val BixMiniColors = arrayOf(Color(0xFF74A6E6), BixCoral, BixCodeVolt)

/**
 * Renders Bix performing the requested [action] in crisp 8-bit pixel style.
 * Automatically renders the user's chosen [BixPrefs.accessory] across the entire app!
 *
 * v0.7.0: [fill] (0..1) drives the honey level of [BixAction.Budget]; other poses ignore it.
 * Motion follows [GlassStyle.motionEnabled] (glass tier + system "remove animations").
 */
@Composable
fun BixScene(
    action: BixAction,
    compact: Boolean = false,
    accessoryOverride: BixAccessory? = null,
    modifier: Modifier = Modifier,
    fill: Float = 1f
) {
    val motion = GlassStyle.motionEnabled
    val acc = accessoryOverride ?: BixPrefs.accessory
    // No frame clock at all when motion is off (reduce motion / tier Off).
    val tick: State<Float> = if (motion) {
        val inf = rememberInfiniteTransition(label = "bix-scene")
        inf.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = bixCycleMs(action),
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "bix-tick"
        )
    } else {
        remember { mutableFloatStateOf(0.25f) }
    }
    val dims = bixSceneSize(action, compact)
    val f = fill.coerceIn(0f, 1f)

    // The phase is read in the draw phase only: no recomposition per animation frame.
    Canvas(modifier = modifier.size(dims.first, dims.second)) {
        val phase = if (motion) tick.value else 0.25f
        drawBixPose(action, phase, compact, acc, f)
    }
}

/** Loop length of each pose's animation cycle. */
fun bixCycleMs(action: BixAction): Int = when (action) {
    BixAction.Coding -> 520
    BixAction.DrillingSquad -> 900
    BixAction.Searching -> 1150
    BixAction.Thinking -> 1400
    BixAction.ApprovalGuard -> 760
    BixAction.Dizzy -> 900
    BixAction.Sentinel -> 1150
    BixAction.Blocked -> 700
    BixAction.Handoff -> 1200
    BixAction.Briefing -> 1600
    BixAction.Teaching -> 1000
    BixAction.Budget -> 1800
    BixAction.Sleeping -> 2400
    BixAction.Alarm -> 420
    BixAction.Router -> 900
    BixAction.Ticket -> 1200
    BixAction.Spill -> 980
    BixAction.Watchdog -> 1050
    BixAction.Blackboard -> 880
    BixAction.Debate -> 640
    BixAction.Relay -> 620
    BixAction.Island -> 840
    BixAction.MemoryPod -> 920
    BixAction.GoalFlag -> 700
    BixAction.PrivacyFence -> 860
    else -> 1300
}

/** Canvas size of a pose (width, height). */
fun bixSceneSize(action: BixAction, compact: Boolean): Pair<Dp, Dp> = when {
    action == BixAction.DrillingSquad && compact -> 48.dp to 28.dp
    action == BixAction.DrillingSquad -> 136.dp to 56.dp
    action == BixAction.Coding && compact -> 38.dp to 28.dp
    action == BixAction.Coding -> 92.dp to 54.dp
    action == BixAction.Searching && compact -> 36.dp to 28.dp
    action == BixAction.Searching -> 88.dp to 54.dp
    bixHasProp(action) && compact -> 40.dp to 28.dp
    bixHasProp(action) -> 92.dp to 54.dp
    compact -> 30.dp to 28.dp
    else -> 76.dp to 54.dp
}

private fun bixHasProp(action: BixAction): Boolean = when (action) {
    BixAction.Sentinel, BixAction.Blocked, BixAction.Handoff, BixAction.Briefing,
    BixAction.Teaching, BixAction.Budget, BixAction.Sleeping, BixAction.Alarm,
    BixAction.Router, BixAction.Ticket, BixAction.Spill, BixAction.Watchdog,
    BixAction.Blackboard, BixAction.Debate,
    BixAction.Relay, BixAction.Island, BixAction.MemoryPod, BixAction.GoalFlag, BixAction.PrivacyFence -> true
    else -> false
}

/**
 * Draws one frame of a pose into the current DrawScope (its size defines the pixel unit).
 * Shared by [BixScene], [BixSprite] (plain android Canvas) and the motion helpers in UiMotion.kt.
 */
fun DrawScope.drawBixPose(
    action: BixAction,
    phase: Float,
    compact: Boolean,
    acc: BixAccessory = BixPrefs.accessory,
    fill: Float = 1f
) {
    when (action) {
        BixAction.Coding -> drawBixCoding(phase, compact, acc)
        BixAction.Thinking -> drawBixThinking(phase, compact, acc)
        BixAction.Searching -> drawBixSearching(phase, compact, acc)
        BixAction.DrillingSquad -> drawBixDrillingSquad(phase, compact, acc)
        BixAction.ApprovalGuard -> drawBixApprovalGuard(phase, compact, acc)
        BixAction.Dizzy -> drawBixDizzy(phase, compact, acc)
        BixAction.Done -> drawBixDone(phase, compact, acc)
        BixAction.Idle -> drawBixIdle(phase, compact, acc)
        BixAction.Sentinel -> drawBixSentinel(phase, compact, acc)
        BixAction.Blocked -> drawBixBlocked(phase, compact, acc)
        BixAction.Handoff -> drawBixHandoff(phase, compact, acc)
        BixAction.Briefing -> drawBixBriefing(phase, compact, acc)
        BixAction.Teaching -> drawBixTeaching(phase, compact, acc)
        BixAction.Budget -> drawBixBudget(phase, compact, acc, fill)
        BixAction.Sleeping -> drawBixSleeping(phase, compact, acc)
        BixAction.Alarm -> drawBixAlarm(phase, compact, acc)
        BixAction.Router -> drawBixRouter(phase, compact, acc)
        BixAction.Ticket -> drawBixTicket(phase, compact, acc)
        BixAction.Spill -> drawBixSpill(phase, compact, acc)
        BixAction.Watchdog -> drawBixWatchdog(phase, compact, acc)
        BixAction.Blackboard -> drawBixBlackboard(phase, compact, acc)
        BixAction.Debate -> drawBixDebate(phase, compact, acc)
        BixAction.Relay -> drawBixRelay(phase, compact, acc)
        BixAction.Island -> drawBixIsland(phase, compact, acc)
        BixAction.MemoryPod -> drawBixMemoryPod(phase, compact, acc)
        BixAction.GoalFlag -> drawBixGoalFlag(phase, compact, acc)
        BixAction.PrivacyFence -> drawBixPrivacyFence(phase, compact, acc)
    }
}

/**
 * Draws Bix poses into a plain [android.graphics.Canvas] (overlay windows, custom Views) by reusing the
 * Compose pixel renderer through one cached [CanvasDrawScope]. No bitmaps; the Compose canvas wrapper is
 * only re-created when the platform hands the View a different recording canvas instance.
 */
class BixSprite(density: Float) {
    private val scope = CanvasDrawScope()
    private val dens = Density(density)
    private var wrapped: android.graphics.Canvas? = null
    private var composeCanvas: androidx.compose.ui.graphics.Canvas? = null

    var action: BixAction = BixAction.Idle
    /** null = the user's chosen accessory. */
    var accessory: BixAccessory? = null
    var fill: Float = 1f
    /** Mirror horizontally (e.g. when riding the left screen edge). */
    var mirrored: Boolean = false

    fun draw(canvas: android.graphics.Canvas, left: Float, top: Float, width: Float, height: Float, phase: Float, compact: Boolean = true) {
        if (width <= 0f || height <= 0f) return
        val cached = composeCanvas
        val cc = if (cached != null && wrapped === canvas) cached else {
            androidx.compose.ui.graphics.Canvas(canvas).also { wrapped = canvas; composeCanvas = it }
        }
        val save = canvas.save()
        try {
            canvas.translate(left, top)
            if (mirrored) canvas.scale(-1f, 1f, width / 2f, height / 2f)
            val a = action
            val acc = accessory ?: BixPrefs.accessory
            val f = fill.coerceIn(0f, 1f)
            scope.draw(density = dens, layoutDirection = LayoutDirection.Ltr, canvas = cc, size = Size(width, height)) {
                drawBixPose(a, phase, compact, acc, f)
            }
        } catch (_: Throwable) {
        } finally {
            canvas.restoreToCount(save)
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
    accentGlow: Color? = null,
    guardCap: Boolean = false
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
        guardCap -> {
            // Sentinel guard cap: navy crown, gold badge, dark visor
            p(3f, 0.1f, 8f, 2.1f, BixGuardNavy)
            p(6.2f, 0.4f, 1.6f, 1.3f, BixGold)
            p(6.6f, 0.7f, 0.8f, 0.6f, BixGoldLight)
            p(2f, 2.2f, 11f, 0.9f, BixOutline)
        }
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
        5 -> {
            // asleep: closed eyes + small round mouth
            p(4.5f, 6.6f, 1.6f, 0.5f, BixOutline)
            p(7.9f, 6.6f, 1.6f, 0.5f, BixOutline)
            p(6.5f, 8.1f, 1.0f, 0.7f, BixOutline)
        }
        6 -> {
            // stern: inward brows + flat mouth
            p(4.3f, 5.2f, 1.3f, 0.6f, BixOutline)
            p(5.4f, 5.7f, 0.8f, 0.5f, BixOutline)
            p(8.4f, 5.2f, 1.3f, 0.6f, BixOutline)
            p(7.8f, 5.7f, 0.8f, 0.5f, BixOutline)
            p(4.8f, 6.4f, 1.2f, 1.1f, BixOutline)
            p(8.0f, 6.4f, 1.2f, 1.1f, BixOutline)
            p(5.8f, 8.2f, 2.4f, 0.6f, BixOutline)
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
    val lineWidths = BixCodeLineWidths
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

    val miniColors = BixMiniColors
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

/** 8-bit magnifying glass (6x7 logical px) at (mx, my). */
private fun DrawScope.drawPixelMagnifier(mx: Float, my: Float, u: Float, lensAlpha: Float = 0.55f) {
    px(1f, 0f, 4f, 1f, u, mx, my, BixLaptopEdge)
    px(1f, 5f, 4f, 1f, u, mx, my, BixLaptopEdge)
    px(0f, 1f, 1f, 4f, u, mx, my, BixLaptopEdge)
    px(5f, 1f, 1f, 4f, u, mx, my, BixLaptopEdge)
    px(1f, 1f, 4f, 4f, u, mx, my, BixGlassCyan.copy(alpha = lensAlpha))
    px(1.5f, 1.5f, 1.2f, 1.2f, u, mx, my, Color.White.copy(alpha = 0.90f))
    px(4.8f, 5.2f, 1.2f, 1.2f, u, mx, my, BixStripe)
    px(5.8f, 6.2f, 1.4f, 1.4f, u, mx, my, BixOutline)
}

/** Scene 8 (v0.7.0): Sentinel Bix in a guard cap sweeping a magnifying glass with a green scan beam. */
private fun DrawScope.drawBixSentinel(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val s = sin(phase * 2f * PI).toFloat()
    val sweep = s * 1.6f * u
    val bx = if (compact) 0.6f * u else (size.width - 21f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = (phase * 2f).toInt() % 2 == 0, face = 1, accessory = acc, guardCap = true)
    val mx = bx + 12.0f * u + sweep
    val my = by + 3.2f * u
    // Scan beam fanning down from the lens
    val beam = 0.28f + 0.22f * kotlin.math.abs(s)
    px(1.0f, 6.0f, 4.0f, 0.6f, u, mx, my, BixCodeGreen.copy(alpha = beam))
    px(0.4f, 6.8f, 5.2f, 0.5f, u, mx, my, BixCodeGreen.copy(alpha = beam * 0.6f))
    drawPixelMagnifier(mx, my, u)
}

/** Scene 9 (v0.7.0): Blocked Bix with crossed arms, stern face and a pulsing red X. */
private fun DrawScope.drawBixBlocked(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val shake = if (phase < 0.3f && ((phase * 20f).toInt() % 2 == 0)) 0.35f * u else 0f
    val bx = (if (compact) 0.5f * u else (size.width - 21f * u) * 0.5f) + shake
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = false, face = 6, accessory = acc)
    // Crossed arms over the belly
    px(3.4f, 8.1f, 7.0f, 1.0f, u, bx, by, BixGold)
    px(4.0f, 8.9f, 6.0f, 0.9f, u, bx, by, BixGoldLight)
    px(3.2f, 8.0f, 0.9f, 1.0f, u, bx, by, BixOutline)
    px(9.9f, 8.8f, 0.9f, 1.0f, u, bx, by, BixOutline)
    // Red X
    val a = (0.72f + 0.28f * sin(phase * 2f * PI).toFloat()).coerceIn(0f, 1f)
    val xx = bx + 14.4f * u
    val xy = by + 2.6f * u
    for (i in 0..4) {
        px(i.toFloat(), i.toFloat(), 1.25f, 1.25f, u, xx, xy, BixStopRed.copy(alpha = a))
        px(4f - i, i.toFloat(), 1.25f, 1.25f, u, xx, xy, BixStopRed.copy(alpha = a))
    }
}

/** Scene 10 (v0.7.0): Handoff Bix holding a golden key out to the user. */
private fun DrawScope.drawBixHandoff(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val s = sin(phase * 2f * PI).toFloat()
    val reach = (0.5f + 0.5f * s) * 1.1f
    val bx = if (compact) 0.4f * u else (size.width - 22f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 2, accessory = acc)
    val ax = bx + 11.4f * u
    val ay = by + 7.2f * u
    px(0f, 0f, 1.6f + reach, 1.0f, u, ax, ay, BixGold)
    val kx = ax + (1.6f + reach) * u
    // Key: bow (ring) + shaft + teeth
    px(0f, -1.0f, 2.6f, 2.6f, u, kx, ay, BixHatYellow)
    px(0.8f, -0.2f, 1.0f, 1.0f, u, kx, ay, BixOutline)
    px(2.6f, 0.0f, 3.4f, 0.8f, u, kx, ay, BixHatYellow)
    px(4.4f, 0.8f, 0.7f, 0.9f, u, kx, ay, BixHatYellow)
    px(5.4f, 0.8f, 0.6f, 0.7f, u, kx, ay, BixHatYellow)
    px(0.2f, -0.8f, 0.7f, 0.5f, u, kx, ay, Color.White.copy(alpha = 0.8f))
    if (s > 0.6f) {
        px(6.4f, -1.6f, 0.8f, 0.8f, u, kx, ay, BixGoldLight)
        px(6.0f, -1.3f, 1.6f, 0.3f, u, kx, ay, Color.White.copy(alpha = 0.85f))
    }
}

/** Scene 11 (v0.7.0): Briefing Bix reading the hive morning paper. */
private fun DrawScope.drawBixBriefing(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bob = sin(phase * 2f * PI).toFloat() * 0.3f * u
    val bx = if (compact) 0.4f * u else (size.width - 20f * u) * 0.5f
    val by = size.height - 13.5f * u + bob
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 1, accessory = acc)
    val nx = bx + 10.0f * u
    val ny = by + 3.8f * u
    val flip = (phase * 2f).toInt()
    px(0f, 0f, 7f, 6.2f, u, nx, ny, BixOutline)
    px(0.4f, 0.4f, 6.2f, 5.4f, u, nx, ny, BixPaper)
    px(0.9f, 0.9f, 4.2f, 0.9f, u, nx, ny, BixStopRed)
    for (i in 0..2) {
        val w = if ((i + flip) % 2 == 0) 5.0f else 3.6f
        px(0.9f, 2.3f + i * 1.1f, w, 0.5f, u, nx, ny, BixPaperInk)
    }
    px(3.4f, 0.4f, 0.25f, 5.4f, u, nx, ny, BixPaperInk.copy(alpha = 0.45f))
    px(-0.6f, 4.4f, 1.4f, 1.0f, u, nx, ny, BixGold)
}

/** Scene 12 (v0.7.0): Teaching Bix filming with a pixel camera and a blinking REC dot. */
private fun DrawScope.drawBixTeaching(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bx = if (compact) 0.4f * u else (size.width - 20f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = (phase * 2f).toInt() % 2 == 0, face = 4, accessory = acc)
    val cx = bx + 11.2f * u
    val cy = by + 4.4f * u
    px(1.0f, 0.2f, 2.0f, 1.0f, u, cx, cy, BixLaptopEdge)
    px(0f, 1.0f, 6.2f, 4.2f, u, cx, cy, BixLaptopBody)
    px(0.3f, 1.3f, 5.6f, 0.4f, u, cx, cy, BixLaptopEdge)
    px(1.9f, 1.9f, 2.4f, 2.4f, u, cx, cy, BixGlassCyan)
    px(2.4f, 2.3f, 0.8f, 0.8f, u, cx, cy, Color.White.copy(alpha = 0.9f))
    px(-0.7f, 3.4f, 1.2f, 1.0f, u, cx, cy, BixGold)
    if (phase < 0.55f) {
        px(5.0f, -1.4f, 1.3f, 1.3f, u, cx, cy, BixStopRed)
        px(6.6f, -1.3f, 1.6f, 0.4f, u, cx, cy, BixStopRed.copy(alpha = 0.85f))
        px(6.6f, -0.7f, 1.1f, 0.4f, u, cx, cy, BixStopRed.copy(alpha = 0.85f))
    }
}

/** Scene 13 (v0.7.0): Budget Bix hugging a honey jar whose level follows [fill] (0..1). */
private fun DrawScope.drawBixBudget(phase: Float, compact: Boolean, acc: BixAccessory, fill: Float) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val f = fill.coerceIn(0f, 1f)
    val bob = sin(phase * 2f * PI).toFloat() * 0.3f * u
    val bx = if (compact) 0.3f * u else (size.width - 18f * u) * 0.5f
    val by = size.height - 13.5f * u + bob
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = if (f < 0.2f) 4 else 2, accessory = acc)
    val jx = bx + 10.4f * u
    val jy = by + 3.2f * u
    px(0.7f, 0f, 5.0f, 1.2f, u, jx, jy, BixStripe)
    px(0.2f, 1.2f, 6.0f, 0.5f, u, jx, jy, BixOutline)
    px(0f, 1.7f, 6.4f, 7.2f, u, jx, jy, BixOutline)
    px(0.5f, 2.1f, 5.4f, 6.4f, u, jx, jy, BixWing.copy(alpha = 0.30f))
    val inner = 6.4f
    val h = inner * f
    if (h > 0.05f) {
        px(0.5f, 2.1f + inner - h, 5.4f, h, u, jx, jy, BixGold)
        px(0.5f, 2.1f + inner - h, 5.4f, minOf(0.5f, h), u, jx, jy, BixGoldLight)
    }
    px(1.0f, 2.6f, 0.6f, 2.2f, u, jx, jy, Color.White.copy(alpha = 0.45f))
    if (f in 0.02f..0.98f) {
        px(5.0f, 8.9f + phase * 2.2f, 0.6f, 0.8f, u, jx, jy, BixGold.copy(alpha = (1f - phase).coerceIn(0f, 1f)))
    }
    px(-0.7f, 4.4f, 1.4f, 1.2f, u, jx, jy, BixGold)
    px(-0.7f, 6.6f, 1.4f, 1.0f, u, jx, jy, BixGoldLight)
}

/** Pixel "Z" glyph (3 x 2.3 logical px at unit [s]). */
private fun DrawScope.drawPixelZ(zx: Float, zy: Float, s: Float, color: Color) {
    px(0f, 0f, 3f, 0.7f, s, zx, zy, color)
    px(1.5f, 0.7f, 0.9f, 0.5f, s, zx, zy, color)
    px(0.7f, 1.1f, 0.9f, 0.5f, s, zx, zy, color)
    px(0f, 1.6f, 3f, 0.7f, s, zx, zy, color)
}

/** Scene 14 (v0.7.0): Sleeping Bix with rising Zzz (paused routines / auto-pause). */
private fun DrawScope.drawBixSleeping(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bob = sin(phase * 2f * PI).toFloat() * 0.35f * u
    val bx = if (compact) 0.4f * u else (size.width - 20f * u) * 0.5f
    val by = size.height - 13.5f * u + bob
    drawCoreBix(bx, by, u, wingUp = false, face = 5, accessory = acc)
    for (k in 0..2) {
        val t = (phase + k / 3f) % 1f
        val zx = bx + (11.6f + k * 1.5f + t * 0.8f) * u
        val zy = by + (4.0f - t * 4.2f) * u
        val s = u * (0.55f + 0.2f * k)
        val a = sin(t * PI).toFloat().coerceIn(0f, 1f)
        drawPixelZ(zx, zy, s, BixSleepBlue.copy(alpha = a * 0.9f))
    }
}

/** Scene 15 (v0.7.0): Alarm Bix ringing a swinging bell (event triggers / notifications). */
private fun DrawScope.drawBixAlarm(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val s = sin(phase * 2f * PI).toFloat()
    val bx = if (compact) 0.4f * u else (size.width - 20f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = (phase * 4f).toInt() % 2 == 0, face = 4, accessory = acc)
    val gx = bx + 12.0f * u
    val gy = by + 1.6f * u
    val sw = s * 0.6f
    px(1.6f + sw * 0.2f, 0f, 1.2f, 0.7f, u, gx, gy, BixOutline)
    px(1.0f + sw * 0.4f, 0.7f, 2.4f, 1.0f, u, gx, gy, BixHatYellow)
    px(0.5f + sw * 0.7f, 1.7f, 3.4f, 1.6f, u, gx, gy, BixHatYellow)
    px(0.0f + sw, 3.3f, 4.4f, 0.9f, u, gx, gy, BixGold)
    px(1.0f + sw * 0.4f, 0.9f, 0.6f, 1.6f, u, gx, gy, Color.White.copy(alpha = 0.7f))
    px(1.8f + sw * 1.6f, 4.2f, 0.9f, 0.9f, u, gx, gy, BixOutline)
    val ring = if (s > 0f) 0.85f else 0.35f
    px(-1.3f, 1.0f, 0.5f, 2.0f, u, gx, gy, Color.White.copy(alpha = ring))
    px(5.3f, 1.0f, 0.5f, 2.0f, u, gx, gy, Color.White.copy(alpha = 1.2f - ring))
}


/** v0.7.1 Router: Bix next to an 8-bit signal tower beaming gold & acid-volt arcs. */
private fun DrawScope.drawBixRouter(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bx = if (compact) 0.4f * u else (size.width - 21f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = (phase * 4f).toInt() % 2 == 0, face = 1, accessory = acc)
    val tx = bx + 12.6f * u
    val ty = by + 1.0f * u
    // Signal tower mast + base
    px(2.6f, 3.0f, 1.0f, 7.2f, u, tx, ty, BixLaptopEdge)
    px(1.2f, 9.6f, 3.8f, 1.2f, u, tx, ty, BixLaptopBody)
    // Glowing antenna beacon
    val step = (phase * 3f).toInt()
    px(2.3f, 2.0f, 1.6f, 1.4f, u, tx, ty, if (step == 0) BixCodeVolt else BixGold)
    // Animated outbound signal arcs
    val a1 = if (step >= 0) 0.95f else 0.25f
    val a2 = if (step >= 1) 0.85f else 0.20f
    val a3 = if (step >= 2) 0.75f else 0.15f
    px(0.8f, 1.5f, 0.7f, 2.0f, u, tx, ty, BixCodeVolt.copy(alpha = a1))
    px(4.7f, 1.5f, 0.7f, 2.0f, u, tx, ty, BixCodeVolt.copy(alpha = a1))
    px(-0.2f, 0.8f, 0.7f, 3.2f, u, tx, ty, BixGold.copy(alpha = a2))
    px(5.7f, 0.8f, 0.7f, 3.2f, u, tx, ty, BixGold.copy(alpha = a2))
    px(6.7f, 0.2f, 0.6f, 4.2f, u, tx, ty, BixGlassCyan.copy(alpha = a3))
}

/** v0.7.1 Ticket: Bix holding an 8-bit clipboard with animated green checkmarks ticking off. */
private fun DrawScope.drawBixTicket(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bob = sin(phase * 2f * PI).toFloat() * 0.4f * u
    val bx = if (compact) 0.4f * u else (size.width - 21f * u) * 0.5f
    val by = size.height - 13.5f * u + bob
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 2, accessory = acc)
    val cx = bx + 12.2f * u
    val cy = by + 1.8f * u
    // Clipboard frame + clip
    px(0.4f, 0.8f, 6.2f, 8.6f, u, cx, cy, BixStripe)
    px(1.1f, 1.6f, 4.8f, 7.0f, u, cx, cy, Color(0xFFF6F1E3))
    px(2.2f, 0.2f, 2.6f, 1.1f, u, cx, cy, BixGold)
    // 3 task lines + ticking green checks
    val ticked = (phase * 4f).toInt()
    for (i in 0..2) {
        val ly = 2.6f + i * 2.0f
        px(1.6f, ly, 1.0f, 1.0f, u, cx, cy, if (i < ticked) BixCodeGreen else BixLaptopEdge)
        px(3.0f, ly + 0.2f, 2.4f, 0.6f, u, cx, cy, BixOutline.copy(alpha = 0.75f))
    }
}

/** v0.7.1 Spill: Bix pulling glowing cyan pixel data packets from an archived scroll drawer. */
private fun DrawScope.drawBixSpill(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bx = if (compact) 0.4f * u else (size.width - 21f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = (phase * 3f).toInt() % 2 == 0, face = 1, accessory = acc)
    val dx = bx + 12.2f * u
    val dy = by + 2.2f * u
    // Filing drawer / scroll vault
    px(0.5f, 4.4f, 6.6f, 4.8f, u, dx, dy, BixLaptopBody)
    px(0.5f, 4.4f, 6.6f, 0.8f, u, dx, dy, BixLaptopEdge)
    px(2.6f, 6.4f, 2.4f, 0.8f, u, dx, dy, BixGold)
    // Rising cyan & volt data packets
    val lift1 = (phase * 3.8f) % 3.8f
    val lift2 = ((phase + 0.5f) * 3.8f) % 3.8f
    px(1.6f, 3.6f - lift1, 1.4f, 1.4f, u, dx, dy, BixGlassCyan.copy(alpha = (1f - lift1 / 4.2f).coerceIn(0.2f, 1f)))
    px(4.2f, 3.8f - lift2, 1.3f, 1.3f, u, dx, dy, BixCodeVolt.copy(alpha = (1f - lift2 / 4.2f).coerceIn(0.2f, 1f)))
}

/** v0.7.1 Watchdog: Bix monitoring an emerald radar sweep & ECG heartbeat line. */
private fun DrawScope.drawBixWatchdog(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bx = if (compact) 0.4f * u else (size.width - 21f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 4, accessory = acc)
    val rx = bx + 12.2f * u
    val ry = by + 2.0f * u
    // Radar monitor screen
    px(0.2f, 1.2f, 7.2f, 6.2f, u, rx, ry, BixLaptopEdge)
    px(0.8f, 1.8f, 6.0f, 5.0f, u, rx, ry, BixScreenBg)
    // Animated ECG heartbeat spike sweeping across monitor
    val beatX = (phase * 5.2f) % 5.2f
    px(1.0f, 4.4f, 5.4f, 0.5f, u, rx, ry, BixCodeGreen.copy(alpha = 0.45f))
    px(1.0f + beatX, 2.6f, 0.8f, 2.3f, u, rx, ry, BixCodeGreen)
    px(1.6f + beatX.coerceAtMost(4.4f), 4.0f, 0.8f, 1.6f, u, rx, ry, BixCodeVolt)
}

/** v0.7.1 Blackboard: Bix scribbling glowing sticky notes on an 8-bit slate board. */
private fun DrawScope.drawBixBlackboard(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bx = if (compact) 0.4f * u else (size.width - 22f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = (phase * 4f).toInt() % 2 == 0, face = 1, accessory = acc)
    val sx = bx + 12.0f * u
    val sy = by + 1.2f * u
    // Slate blackboard frame
    px(0.6f, 0.6f, 8.0f, 6.8f, u, sx, sy, BixStripe)
    px(1.2f, 1.2f, 6.8f, 5.6f, u, sx, sy, Color(0xFF17332A))
    // Sticky note squares + animated chalk scribble
    px(1.8f, 1.8f, 2.2f, 2.0f, u, sx, sy, BixGold)
    px(4.8f, 1.8f, 2.2f, 2.0f, u, sx, sy, BixGlassCyan)
    val chalkW = 1.0f + ((phase * 4.6f) % 4.6f)
    px(1.8f, 4.8f, chalkW, 0.7f, u, sx, sy, Color.White)
    // Chalk piece tip
    px(1.8f + chalkW, 4.6f, 0.8f, 0.9f, u, sx, sy, BixCodeVolt)
}

/** v0.7.1 Debate: Judge Bix pounding a wooden pixel gavel with impact sparks! */
private fun DrawScope.drawBixDebate(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bx = if (compact) 0.4f * u else (size.width - 21f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 1, accessory = acc)
    val gx = bx + 12.0f * u
    val gy = by + 1.4f * u
    val strike = phase > 0.55f
    // Sound block at bottom
    px(1.0f, 7.6f, 5.6f, 1.4f, u, gx, gy, BixStripe)
    // Gavel head & handle (raised vs struck down)
    val gyOff = if (strike) 3.4f else 0.4f
    px(1.2f, 2.2f + gyOff, 3.6f, 1.8f, u, gx, gy, BixCoral)
    px(2.6f, 3.8f + gyOff, 1.0f, 2.8f, u, gx, gy, BixGold)
    // Impact sparks when gavel hits!
    if (strike) {
        px(0.2f, 6.2f, 0.9f, 0.9f, u, gx, gy, BixCodeVolt)
        px(5.8f, 6.2f, 0.9f, 0.9f, u, gx, gy, BixGoldLight)
        px(3.2f, 5.4f, 0.8f, 0.8f, u, gx, gy, Color.White)
    }
}

/** v0.7.2 Relay: Bix sprinting forward carrying a glowing golden-volt relay baton! */
private fun DrawScope.drawBixRelay(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val sprint = kotlin.math.sin(phase * 2.0 * kotlin.math.PI).toFloat() * 0.6f * u
    val bx = (if (compact) 0.4f * u else (size.width - 21f * u) * 0.5f) + sprint
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = (phase * 4f).toInt() % 2 == 0, face = 1, accessory = acc)
    val rx = bx + 11.6f * u
    val ry = by + 2.0f * u
    // Glowing relay baton + speed wind streaks
    px(1.0f, 2.4f, 4.8f, 1.3f, u, rx, ry, BixCodeVolt)
    px(1.6f, 2.6f, 3.2f, 0.6f, u, rx, ry, Color.White)
    px(0.2f, 4.8f, 2.4f, 0.6f, u, rx, ry, BixGold)
    px(1.8f, 6.0f, 3.4f, 0.6f, u, rx, ry, Color(0xFF5CE1E6))
}

/** v0.7.2 Island: Bix peeking out of a floating Titanium Liquid Glass Dynamic Island pill! */
private fun DrawScope.drawBixIsland(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bob = kotlin.math.sin(phase * 2.0 * kotlin.math.PI).toFloat() * 0.5f * u
    val bx = if (compact) 0.4f * u else (size.width - 21f * u) * 0.5f
    val by = size.height - 13.5f * u + bob
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 2, accessory = acc)
    val ix = bx + 11.4f * u
    val iy = by + 2.2f * u
    // Floating glass capsule + pulsing green beacon
    px(0.4f, 2.0f, 6.6f, 3.4f, u, ix, iy, BixLaptopBody)
    px(1.0f, 2.5f, 5.4f, 2.4f, u, ix, iy, BixScreenBg)
    px(1.6f, 3.2f, 1.1f, 1.1f, u, ix, iy, if (phase < 0.5f) BeeGreen else BixCodeVolt)
    px(3.3f, 3.4f, 2.4f, 0.7f, u, ix, iy, BixGoldLight)
}

/** v0.7.2 MemoryPod: Bix organizing glowing memory cards into a portable pixel pod! */
private fun DrawScope.drawBixMemoryPod(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bx = if (compact) 0.4f * u else (size.width - 21f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 1, accessory = acc)
    val px0 = bx + 11.8f * u
    val py0 = by + 1.2f * u
    // Portable archive pod box
    px(0.8f, 3.6f, 5.8f, 4.6f, u, px0, py0, BixStripe)
    px(1.4f, 4.2f, 4.6f, 3.4f, u, px0, py0, Color(0xFF182230))
    px(2.6f, 5.6f, 2.2f, 0.7f, u, px0, py0, BixGold)
    // Floating color-coded provenance tags rising into the pod
    val bob = kotlin.math.sin(phase * 2.0 * kotlin.math.PI).toFloat() * 0.8f
    px(1.4f, 1.4f + bob, 1.4f, 1.4f, u, px0, py0, BixCodeVolt)
    px(3.2f, 0.8f - bob, 1.4f, 1.4f, u, px0, py0, Color(0xFF5CE1E6))
    px(5.0f, 1.6f + bob * 0.6f, 1.4f, 1.4f, u, px0, py0, BixGoldLight)
}

/** v0.7.2 GoalFlag: Bix planting and waving a pixel milestone flag! */
private fun DrawScope.drawBixGoalFlag(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bx = if (compact) 0.4f * u else (size.width - 21f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 1, accessory = acc)
    val fx = bx + 12.2f * u
    val fy = by + 0.6f * u
    // Flagpole + base
    px(1.4f, 1.0f, 0.8f, 7.6f, u, fx, fy, BixGoldLight)
    px(0.4f, 8.2f, 3.0f, 0.9f, u, fx, fy, BixStripe)
    // Fluttering pixel pennant
    val wave = if (phase < 0.5f) 0f else 0.6f
    px(2.2f, 1.2f, 2.2f, 2.4f, u, fx, fy, BixCoral)
    px(4.4f, 1.2f + wave, 1.8f, 2.0f, u, fx, fy, BixGold)
    if (phase > 0.5f) {
        px(5.8f, 0.4f, 0.8f, 0.8f, u, fx, fy, BixCodeVolt)
    }
}

/** v0.7.2 PrivacyFence: Bix standing guard in front of a glowing pixel privacy barrier! */
private fun DrawScope.drawBixPrivacyFence(phase: Float, compact: Boolean, acc: BixAccessory) {
    val u = if (compact) size.height / 14f else size.height / 17f
    val bx = if (compact) 0.4f * u else (size.width - 21f * u) * 0.5f
    val by = size.height - 13.5f * u
    drawCoreBix(bx, by, u, wingUp = phase < 0.5f, face = 1, accessory = acc)
    val sx = bx + 11.8f * u
    val sy = by + 1.0f * u
    // Three pixel barrier posts + glowing security rail
    px(0.6f, 2.4f, 1.2f, 6.0f, u, sx, sy, BixStripe)
    px(2.8f, 2.4f, 1.2f, 6.0f, u, sx, sy, BixStripe)
    px(5.0f, 2.4f, 1.2f, 6.0f, u, sx, sy, BixStripe)
    val pulseColor = if (phase < 0.5f) Color(0xFF5CE1E6) else BixCodeVolt
    px(0.4f, 3.6f, 6.0f, 0.9f, u, sx, sy, pulseColor)
    px(0.4f, 6.0f, 6.0f, 0.9f, u, sx, sy, BixGold)
    // Shield emblem at center
    px(2.6f, 1.0f, 1.6f, 1.6f, u, sx, sy, BeeGreen)
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
    val previewActions = remember {
        listOf(
            BixAction.Idle, BixAction.Coding, BixAction.Searching, BixAction.DrillingSquad,
            BixAction.Sentinel, BixAction.Handoff, BixAction.Briefing, BixAction.Teaching,
            BixAction.Budget, BixAction.Alarm, BixAction.Sleeping, BixAction.Blocked,
            BixAction.Router, BixAction.Ticket, BixAction.Spill, BixAction.Watchdog,
            BixAction.Blackboard, BixAction.Debate,
            BixAction.Relay, BixAction.Island, BixAction.MemoryPod, BixAction.GoalFlag, BixAction.PrivacyFence
        )
    }

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

// ============================================================
// v0.7.0: Bix as the face of every new feature
// ============================================================

/**
 * Friendly empty state for lists: Bix pose on a small glass plinth + title + hint.
 * e.g. no approvals -> BixEmptyState(BixAction.Done, "都处理完啦", "有需要你拍板的操作会出现在这里");
 * no routines -> Sleeping; empty activity -> Idle.
 */
@Composable
fun BixEmptyState(action: BixAction, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 22.dp, fallbackBg = PILL)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            BixScene(action = action, compact = false)
        }
        Spacer(Modifier.height(2.dp))
        Text(title, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (hint.isNotBlank()) {
            Text(hint, color = Sub, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Inline speech-bubble tip: compact Bix + a glass bubble with a pixel tail.
 * [onDismiss] != null shows a small close button.
 */
@Composable
fun BixTip(
    text: String,
    action: BixAction = BixAction.Idle,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BixScene(action = action, compact = true)
        Spacer(Modifier.width(4.dp))
        // Stepped 8-bit bubble tail pointing at Bix
        Canvas(Modifier.size(6.dp, 10.dp)) {
            val q = size.width / 3f
            val h = size.height
            drawRect(BixTipFill, Offset(q * 2f, 0f), Size(q, h))
            drawRect(BixTipFill, Offset(q, h * 0.2f), Size(q, h * 0.6f))
            drawRect(BixTipFill, Offset(0f, h * 0.4f), Size(q, h * 0.2f))
        }
        Row(
            Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(14.dp))
                .background(BixTipFill)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, color = Fg, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f, fill = false))
            if (onDismiss != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Icon(Ic.Close, "关闭提示", tint = Sub, modifier = Modifier.size(11.dp)) }
            }
        }
    }
}

private val BixTipFill = Color(0xFF23262E)

/**
 * Transient Bix toast (slides up + fades). The caller owns [visible] and hides it after a delay, e.g.
 * `LaunchedEffect(msg) { delay(2200); msg = null }`.
 */
@Composable
fun BixToast(
    visible: Boolean,
    text: String,
    action: BixAction = BixAction.Done,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(GlassMotion.ms(200))) + slideInVertically(tween(GlassMotion.ms(240), easing = FastOutSlowInEasing)) { it / 2 },
        exit = fadeOut(tween(GlassMotion.ms(160))) + slideOutVertically(tween(GlassMotion.ms(200))) { it / 2 }
    ) {
        Row(
            Modifier
                .harmonyGlass(thickness = ImmersiveThickness.THICK, radius = 999.dp, tint = SheetGlassTint, fallbackBg = Card)
                .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BixScene(action = action, compact = true)
            Spacer(Modifier.width(6.dp))
            Text(text, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
