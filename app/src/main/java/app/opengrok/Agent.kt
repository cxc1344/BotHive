package app.opengrok

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Per-Bot HTTP policy. HTTP is available only after an explicit opt-in in that Bot's settings. */
object ApiEndpoint {
    fun validate(bot: Bot): String {
        val url = bot.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IOException("Base URL 格式无效")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw IOException("Base URL 不允许包含账号密码")
        }
        if (!url.isHttps && !(url.scheme == "http" && bot.allowInsecureHttp)) {
            throw IOException("默认只允许 HTTPS；如确实连接可信的本地 HTTP 服务，请在该 Bot 设置中明确开启")
        }
        return url.toString().trimEnd('/')
    }
}

/** OpenAI-compatible chat/completions agent loop with two tools: shell, ask_bot. */
object Agent {
    /** Hard cap on tool-calling rounds per user turn to prevent runaway loops and quota burn. */
    private const val MAX_TOOL_ROUNDS = 24

    private const val NO_APPROVAL_CONTEXT =
        "【未执行】这一步按权限策略需要用户批准,但当前调用没有可以发起批准的对话,这条操作已拒绝执行。请把需要执行的内容告诉用户,让用户在对话里发起。"

    /** Every N tool rounds the latest human request is restated to keep long runs on scope. */
    private const val SCOPE_REMINDER_EVERY = 8

    /** Tools that only the on-device Agent has (checked before the policy gate). */
    private val AGENT_ONLY = setOf(
        "fs_list", "fs_read", "fs_write", "fs_delete", "fs_scan", "device_status", "app_usage", "list_apps",
        "launch_app", "open_url", "recent_notifications", "shizuku_shell", "phone_task", "screen_mode",
        "screen_shot", "screen_dump", "tap_at", "long_press", "drag_at", "tap_text", "input_text", "press_key"
    )
    /** Classes whose string arguments are scanned by the leak guard before they leave the device. */
    private val LEAK_SCAN = setOf(
        ActionClass.SANDBOX, ActionClass.PRIVILEGED, ActionClass.WEB, ActionClass.EXTERNAL,
        ActionClass.SCREEN, ActionClass.FILE_WRITE
    )
    private val PAYMENT_SCREEN_TOOLS = setOf("tap_at", "long_press", "drag_at", "tap_text", "input_text", "launch_app")
    private val PAYMENT_WEB_TOOLS = setOf("browser_open", "browser_mouse", "browser_type", "browser_fill_secret", "open_url")
    private val SCREEN_ACTION_TOOLS = setOf("tap_at", "long_press", "drag_at", "tap_text", "input_text", "press_key")

    /** Bot ids already notified about today's budget stop (one notification per Bot per day). */
    private val budgetNotified = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Screenshot captured by the latest screen_shot call, keyed by bot id so concurrent chats never mix up shots. */
    private val pendingShots = java.util.concurrent.ConcurrentHashMap<String, File>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS)
        // Avoid HTTPS-to-HTTP redirect downgrades that could expose an Authorization header.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val JSON_MT = "application/json".toMediaType()

    /** Key-pool cursors: a Bot/Provider key field may hold several keys, one per line;
     *  on HTTP 429 the next key is tried and the cursor stays on the working key. */
    private val keyCursors = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private fun keyPool(bot: Bot): List<String> = RetryPolicy.parseKeyPool(bot.apiKey)

    private fun fn(name: String, desc: String, props: JSONObject, vararg req: String) =
        JSONObject().put("type", "function").put(
            "function", JSONObject().put("name", name).put("description", desc).put(
                "parameters", JSONObject().put("type", "object").put("properties", props)
                    .put("required", JSONArray(req.toList()))
            )
        )

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    private fun toolDefs(isAgent: Boolean) = JSONArray().apply {
        put(fn("shell", "在你的工作目录中执行命令(Ubuntu Linux 环境);该目录不是 OS 级权限隔离边界。",
            JSONObject().put("command", str("要执行的命令")), "command"))
        put(fn("ask_bot", "向另一个 Bot 或设备 Agent 发私信求助/派活,返回对方的回复。",
            JSONObject().put("bot_name", str("对方的名字")).put("message", str("要说的话")),
            "bot_name", "message"))
        put(fn("remember", "把一条重要事实存入长期记忆(跨会话保留,如用户偏好、项目约定、关键结论)。可附可选分类标签 category。scope=user 表示这是关于用户本人的基础事实(称呼、时区、长期偏好),会共享给所有 Bot;默认 scope=self 只存在你自己的记忆里。",
            JSONObject().put("text", str("要记住的内容")).put("category", str("可选分类标签,如 偏好/项目/设备"))
                .put("scope", str("self(默认,只给自己) / user(关于用户的共享事实)")), "text"))
        put(fn("recall", "取回你的长期记忆条目;传入可选 query 可按关键词或分类筛选。",
            JSONObject().put("query", str("可选筛选关键词或分类标签"))))
        put(fn("schedule_task", "给自己派一个例行任务。trigger=time(默认):delay_minutes 分钟后在你自己的对话里收到这条任务并执行,repeat_daily=true 时此后每天同一时间重复。trigger=notification:手机收到匹配的通知时执行(match_app 为应用名或包名,match_text 为通知里的关键词,至少填一个)。trigger=webhook:外部程序调用本机 Webhook 地址时执行。事件内容会附在任务后面一起交给你。",
            JSONObject().put("task", str("到点或事件发生时要执行的任务内容"))
                .put("trigger", str("time(默认) / notification / webhook"))
                .put("delay_minutes", JSONObject().put("type", "integer").put("description", "trigger=time 时:多少分钟后首次执行"))
                .put("repeat_daily", JSONObject().put("type", "boolean").put("description", "trigger=time 时:是否每天重复"))
                .put("match_app", str("trigger=notification 时:应用名或包名(可选)"))
                .put("match_text", str("trigger=notification 时:通知标题或内容里的关键词(可选)")),
            "task"))
        put(fn("list_tasks", "列出你自己的全部例行任务(定时、通知触发、Webhook 触发)。", JSONObject()))
        put(fn("run_workflow", "按名称启动一个用户建好的工作流(步骤链),它会自己运行,你不用等结果。",
            JSONObject().put("name", str("工作流名称")), "name"))
        put(fn("list_skills", "列出全部技能包及其用途说明,以及哪些已经挂在你身上。", JSONObject()))
        put(fn("use_skill", "按名称加载一个技能包的完整内容(没挂在你身上的技能也可以按需加载,本轮生效)。",
            JSONObject().put("name", str("技能名")), "name"))
        put(fn("create_skill", "把一套可复用的方法、规范或领域知识写成新技能包,并立刻挂到你自己身上(之后每次对话都生效)。内容要具体、可执行,建议按六段写:何时使用 / 需要的输入与权限 / 步骤 / 如何验证结果 / 输出什么 / 哪些操作需要批准。",
            JSONObject().put("name", str("技能名")).put("content", str("技能内容"))
                .put("description", str("一句话说明什么时候该用这个技能")), "name", "content"))
        put(fn("team_status", "查看整个蜂巢的现状:正在运行的对话、待批准的操作、各 Bot 最近 24 小时的输出、项目进度、接下来的例行任务与失败记录。写早报或回答「大家在忙什么」时先调用它。", JSONObject()))
        put(fn("secret_list", "列出用户保险箱里已保存的凭据名称(只有名称,看不到内容)。", JSONObject()))
        put(fn("browser_fill_secret", "把用户保险箱里名为 name 的凭据填入 AI 浏览器当前聚焦的输入框(先用 browser_mouse 点中输入框)。你看不到凭据内容,只会得到「已填入」;每次填写都需要用户批准。",
            JSONObject().put("name", str("凭据名称,用 secret_list 查看")), "name"))
        put(fn("browser_open", "在内置独立 AI 浏览器中打开网址(会实时展示浏览器任务条,用户可随时观看实况或一键接管操控)。返回页面摘要并附带实时截图。",
            JSONObject().put("url", str("完整网址,如 https://example.com"))
                .put("desktop_mode", JSONObject().put("type", "boolean").put("description", "是否使用桌面版 UA,默认 false")), "url"))
        put(fn("browser_mouse", "在独立 AI 浏览器中执行桌面级鼠标/指针/滚轮操控(坐标 0-1000 归一化网格)。支持左键(left)/右键(right)/中键(middle)的 click/double_click/long_press/drag/wheel/move。",
            JSONObject()
                .put("action", str("click / double_click / long_press / drag / wheel / move"))
                .put("button", str("left(默认) / right / middle"))
                .put("x", JSONObject().put("type", "integer").put("description", "0-1000 横向坐标"))
                .put("y", JSONObject().put("type", "integer").put("description", "0-1000 纵向坐标"))
                .put("x2", JSONObject().put("type", "integer").put("description", "drag 终点 x(0-1000)"))
                .put("y2", JSONObject().put("type", "integer").put("description", "drag 终点 y(0-1000)"))
                .put("delta_x", JSONObject().put("type", "integer").put("description", "wheel 横向滚动像素"))
                .put("delta_y", JSONObject().put("type", "integer").put("description", "wheel 纵向滚动像素(正向下,负向上)"))
                .put("duration_ms", JSONObject().put("type", "integer").put("description", "long_press 或 drag 持续毫秒数,默认 500")),
            "action", "x", "y"))
        put(fn("browser_type", "向独立 AI 浏览器当前聚焦的输入框写入文字,可选 submit=true 回车提交。返回最新截图。",
            JSONObject().put("text", str("要输入的文字"))
                .put("submit", JSONObject().put("type", "boolean").put("description", "是否回车提交,默认 false")), "text"))
        put(fn("browser_nav", "控制独立 AI 浏览器导航或查看当前最新画面:back(后退)/ forward(前进)/ reload(刷新)/ snapshot(仅截图查看)。",
            JSONObject().put("action", str("back / forward / reload / snapshot")), "action"))
        put(fn("browser_screenshot", "截取当前独立 AI 浏览器的最新画面并返回给视觉模型(用于查看页面状态、定位 0-1000 坐标后执行点击/拖动等操作)。", JSONObject()))
        put(fn("browser_close", "关闭独立 AI 浏览器并结束浏览器任务。", JSONObject()))
        put(fn("ticket_create", "创建一张持久化 Super-Agent 任务工单(与聊天窗口解耦,支持断点续跑和 Fast Path 事件直通)。",
            JSONObject()
                .put("title", str("简短工单标题"))
                .put("goal", str("详细目标与验收标准"))
                .put("owner_bot_id", str("责任 Worker 的 botId 或 agent,可选,默认自己"))
                .put("source_key", str("可选 Fast Path 订阅键,如 App 包名或关键词")),
            "title", "goal"))
        put(fn("ticket_update", "更新持久化 Super-Agent 工单状态或记录检查点(checkpoint),防止长任务中断丢进度。",
            JSONObject()
                .put("id", str("工单 ID,如 tk_xxxxxx"))
                .put("status", str("可选: queued / running / blocked / paused / done / failed"))
                .put("checkpoint", str("可选: 刚刚完成的关键里程碑说明")),
            "id"))
        put(fn("ticket_list", "列出当前所有持久化 Super-Agent 工单及检查点进度。", JSONObject()))
        put(fn("board_write", "往跨 Bot 共享黑板贴一条结构化键值对结论(全部 Bot 和设备 Agent 立即可读,适合协作共享调研结果、参数或辩论论点)。",
            JSONObject().put("key", str("简短词条名")).put("value", str("内容")).put("tag", str("可选标签,如 调研/决策/辩论赛")), "key", "value"))
        put(fn("board_read", "读取跨 Bot 共享黑板上的词条(不传参数列出全部,可按 key 或 tag 筛选)。",
            JSONObject().put("key", str("可选关键词")).put("tag", str("可选标签"))))
        put(fn("recall_spill", "链式召回(Chain of Recall):按 Spill ID 读取被 Inline & Spill 折叠的超长工具输出,支持关键词检索或按行分页。",
            JSONObject()
                .put("id", str("Spill ID,如 sp_xxxxxxxx"))
                .put("query", str("可选: 按关键词精准检索匹配行"))
                .put("start_line", JSONObject().put("type", "integer").put("description", "可选: 起始行号(从1开始)"))
                .put("max_lines", JSONObject().put("type", "integer").put("description", "可选: 最多返回行数(10-200,默认80)")),
            "id"))
        // v0.7.2 Pure-Android DAG Auto-Relay Pipeline + Portable Memory Pod tools:
        put(fn("pipeline_advance", "推进蜂巢 DAG 自动接力流水线：将当前棒次标为完成、写入共享黑板并自动唤醒下一棒 Worker。",
            JSONObject()
                .put("id", str("流水线 ID,如 pl_xxxxxx"))
                .put("summary", str("本棒精炼产出摘要(将自动交接给下一棒)")),
            "id", "summary"))
        put(fn("memory_pod_save", "向用户的可溯源记忆档案舱写入一条结构化记忆(带分类与置信度,情绪吐槽请标为 mood 避免误判为目标)。",
            JSONObject()
                .put("content", str("记忆内容"))
                .put("category", str("fact / pref / goal / mood"))
                .put("confidence", str("stated(用户明说) / inferred(模型推测)")),
            "content"))
        put(fn("memory_pod_query", "按当前 Bot 权限检索用户的可溯源记忆档案舱(自动遵守按 Bot 授权分区)。",
            JSONObject().put("query", str("可选关键词"))))
        if (isAgent) {
            put(fn("fs_list", "列出任意目录的内容(绝对路径,如 /sdcard/Download)。channel 通道:auto(默认,普通优先、自动回退 Shizuku)/ standard(普通文件权限,需用户给了所有文件访问)/ shizuku(shell 身份,能到 /data/local/tmp 等)/ root(root 身份,能进其他应用的 /data 私有目录,需 Shizuku 以 root 运行或有 su)。",
                JSONObject().put("path", str("绝对路径")).put("channel", str("通道,可选,默认 auto")), "path"))
            put(fn("fs_read", "读取任意文本文件(绝对路径)。channel 含义同 fs_list。",
                JSONObject().put("path", str("绝对路径")).put("channel", str("通道,可选,默认 auto")), "path"))
            put(fn("fs_write", "写入任意文本文件(绝对路径,自动创建父目录)。channel 含义同 fs_list。",
                JSONObject().put("path", str("绝对路径")).put("content", str("文件内容")).put("channel", str("通道,可选,默认 auto")), "path", "content"))
            put(fn("fs_delete", "删除任意文件或目录(绝对路径,递归删除,务必谨慎)。channel 含义同 fs_list。",
                JSONObject().put("path", str("绝对路径")).put("channel", str("通道,可选,默认 auto")), "path"))
            put(fn("fs_scan", "统计空间占用(普通通道,需所有文件访问授权):返回某目录下各子项的总大小并按大小降序排列。清理存储、找大文件时先用它摸底,一次顶几十次 fs_list。depth=统计层级(1 只看一级子项、2 看到二级,默认 2,最大 3)。",
                JSONObject().put("path", str("绝对路径")).put("depth", JSONObject().put("type", "integer").put("description", "统计层级 1-3,默认 2")), "path"))
            put(fn("device_status", "查看手机状态:电量、充电、存储空间、内存、系统版本、已开机时长。", JSONObject()))
            put(fn("app_usage", "查看今天各应用的前台使用时长排行(需要用户已开启「使用情况访问」)。", JSONObject()))
            put(fn("list_apps", "列出手机上可启动的应用(名称与包名)。", JSONObject()))
            put(fn("launch_app", "打开/切换到某个应用(按应用名或包名)。虚拟屏模式下会在虚拟屏上启动该应用。",
                JSONObject().put("query", str("应用名或包名")), "query"))
            put(fn("open_url", "用浏览器打开一个网址链接。",
                JSONObject().put("url", str("完整网址,如 https://example.com")), "url"))
            put(fn("open_deeplink", "优先通过系统 Deep Link / URI Scheme 直达目标 App 页面(如 weixin://、taobao://、bilibili://、baidumap://、mailto: 等),比看屏点击更快更稳。",
                JSONObject().put("uri", str("完整 Deep Link URI")), "uri"))
            put(fn("recent_notifications", "查看手机最近的通知(应用、标题、内容、时间)。需要用户已开启「通知使用权」。", JSONObject()))
            put(fn("shizuku_shell", "通过 Shizuku 以 shell 身份执行命令(需要 Shizuku 已激活并授权)。可做 input tap/swipe/keyevent(点击滑动按键)、screencap 截屏到文件、dumpsys 查看系统状态、pm/am 管理应用等系统级操作。",
                JSONObject().put("command", str("要执行的 shell 命令")), "command"))
            put(fn("phone_task", "把一条手机操作任务交给专用的手机操作模型(AutoGLM 风格)自动执行:它会自己截屏、判断、点击/滑动/输入,直到完成。适合「打开微信给某人发消息」这类需要连续看屏操作的任务。需要先在设置里启用专用手机模型。",
                JSONObject().put("task", str("要完成的手机操作任务,写清楚目标和判断完成的条件")), "task"))
            put(fn("screen_mode", "查看、切换或关闭 Agent 的操控模式。mode=status 查看当前模式;mode=virtual 切到虚拟屏悬浮窗;mode=main 切回主屏模式;mode=close 任务完成后关闭虚拟屏悬浮窗释放资源。", JSONObject().put("mode", str("status / virtual / main / close")), "mode"))
            put(fn("screen_shot", "截取当前屏幕截图并直接看到画面(视觉看屏,推荐首选)。之后用 tap_at / long_press / drag_at 按画面上的相对位置操作:坐标是 0-1000 的归一化网格,左上角是 (0,0),右下角是 (1000,1000)。虚拟屏模式下返回虚拟屏的画面。", JSONObject()))
            put(fn("screen_dump", "以文字形式读取当前屏幕的界面结构(备用手段;优先用 screen_shot 看图)。", JSONObject()))
            put(fn("tap_at", "点击画面上的一点。x、y 为 0-1000 归一化坐标:先看 screen_shot 的截图,判断目标在画面中的相对位置,再给出坐标,例如画面正中是 (500,500)。",
                JSONObject().put("x", JSONObject().put("type", "integer").put("description", "0-1000,横向相对位置")).put("y", JSONObject().put("type", "integer").put("description", "0-1000,纵向相对位置")), "x", "y"))
            put(fn("long_press", "长按画面上的一点(弹出菜单、选中、进入编辑模式等)。坐标同 tap_at,duration_ms 为按压毫秒数(默认 600)。",
                JSONObject().put("x", JSONObject().put("type", "integer")).put("y", JSONObject().put("type", "integer"))
                    .put("duration_ms", JSONObject().put("type", "integer").put("description", "按压毫秒数,默认 600")), "x", "y"))
            put(fn("drag_at", "从画面上一点拖动到另一点(滑动翻页、滚动列表、拖放)。坐标同 tap_at,为 0-1000 归一化坐标;duration_ms 为拖动毫秒数(默认 300,滚动用 300-600,精细拖放用 800-1500)。",
                JSONObject().put("x1", JSONObject().put("type", "integer")).put("y1", JSONObject().put("type", "integer"))
                    .put("x2", JSONObject().put("type", "integer")).put("y2", JSONObject().put("type", "integer"))
                    .put("duration_ms", JSONObject().put("type", "integer").put("description", "拖动毫秒数,默认 300")), "x1", "y1", "x2", "y2"))
            put(fn("tap_text", "点击屏幕上包含指定文字的元素(文字模式备用;视觉模式下优先 tap_at)。",
                JSONObject().put("text", str("要点击的文字")), "text"))
            put(fn("input_text", "向当前聚焦(或界面中第一个)输入框写入文字。写之前通常要先点开输入框。",
                JSONObject().put("text", str("要输入的文字")), "text"))
            put(fn("press_key", "按系统键:back(返回)/ home(桌面)/ recents(最近任务)/ notifications(通知栏)。",
                JSONObject().put("key", str("back / home / recents / notifications")), "key"))
        }
    }

    suspend fun run(
        store: Store,
        rawBot: Bot,
        history: List<Msg>,
        depth: Int = 0,
        chatId: String = "",
        onTools: suspend (List<ToolCall>) -> Unit
    ): String {
        val bot = store.resolved(rawBot)
        val isAgent = bot.id == AGENT_ID
        // v0.7.0: daily token budget, checked before any model call.
        budgetStop(store, bot)?.let { return it }
        val skillsText = store.skills.filter { it.id in bot.skillIds }
            .joinToString("\n\n") { "## 技能:${it.name}\n${it.content}" }
        // v0.7.0 Skills 2.0: unattached skills are listed by description and loaded on demand (use_skill).
        val catalog = store.skills.filter { it.id !in bot.skillIds }.take(20)
            .joinToString("\n") { "- ${it.name}" + if (it.description.isNotBlank()) ":${it.description.take(80)}" else "" }
        val skillSuffix = (if (skillsText.isBlank()) "" else "\n\n你启用了以下技能,请遵循:\n$skillsText") +
            (if (catalog.isBlank()) "" else "\n\n技能库里还有这些技能,需要时用 use_skill 按名称加载:\n$catalog")
        // v0.7.0 Memory 2.0: shared facts about the user (all Bots), unless this Bot's memory is off.
        val memoryOn = ModelPrefs.isMemoryEnabled(bot.id, store.appContext())
        val facts = if (memoryOn) store.userFacts().takeLast(30) else emptyList()
        val factsSuffix = if (facts.isEmpty()) "" else
            "\n\n关于用户的共享事实(所有 Bot 共用,请遵循):\n" + facts.joinToString("\n") { "- $it" }.take(2_000)
        // v0.7.0: staff assigned to a project see the shared exchange folder.
        val exchangeSuffix = if (!isAgent && store.exchangeDirFor(bot.id) != null)
            "\n\n你正在参与一个项目,项目共享交换目录挂载在 /exchange:把要交付给总经理或同事的文件复制进去(例如 cp 报告.md /exchange/),不要 cd 进去;总经理审查时会看到这里的文件列表和内容预览。"
        else ""
        val others = (store.bots.filter { it.id != bot.id }.map { it.name } +
            listOfNotNull(store.agent.value?.name).filter { !isAgent }).distinct().joinToString("、")
        // Current date/time grounding: models have no clock, and "今天/明天" style requests
        // were silently resolved against stale training-date assumptions.
        val now = java.text.SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date())
        val clockSuffix = "\n\n当前时间:$now(用户时区,24 小时制)。涉及「今天/明天/本周」等相对时间时以此为准。" + "\nSuper-Agent 协作纪律：①复杂/长程任务可用 ticket_create 立单、推进关键步骤用 ticket_update 写检查点防中断；②若工具输出被自动折叠并附带 [Spill ID: sp_xxx]，必须用 recall_spill 按关键词或行号精准查阅，绝不凭空猜测；③手机端打开特定 App 页面时优先用 open_deeplink 直达，走不通再兜底 screen_shot + tap_at。"
        val sys = if (isAgent) {
            bot.prompt + clockSuffix + "\n\n你叫「${bot.name}」,是运行在用户安卓手机上的设备 Agent,有一台自己的 Ubuntu 沙盒可用 shell 干活。" +
                (if (others.isNotEmpty()) "云端 Bot 同伴:$others,可用 ask_bot 私信求助或给他们派活。" else "") +
                "你能管理手机文件:fs_* 工具用绝对路径,分普通(需所有文件访问授权,管共享存储)/ Shizuku(shell 身份)/ Root(能进其他应用 /data)三档通道,默认 auto 自动选。" +
                "屏幕操作走视觉模式:先 screen_shot 截屏(截图会随结果附给你看),判断目标在画面中的相对位置,再用 0-1000 归一化坐标调用 tap_at 点击 / long_press 长按 / drag_at 拖动;左上角是 (0,0),右下角是 (1000,1000),例如正中央是 (500,500)。每操作几步重新截屏核对,别凭记忆连点。无障碍通道不增加文件权限。" +
                "如果用户希望不被打断,可用 screen_mode 切到虚拟屏模式:Agent 在独立虚拟显示屏里操作 App,画面显示在可拖动的悬浮窗里。虚拟屏模式下 screen_dump / tap_text 不可用(节点树绑主屏),一切以 screen_shot 看图为准;遇到 FLAG_SECURE 页面(银行/支付)会黑屏,应提示用户切回主屏模式。请使用用户的语言回复。" + factsSuffix + skillSuffix
        } else {
            bot.prompt + clockSuffix + "\n\n你叫「${bot.name}」,运行在一部安卓手机上,有自己独立的工作目录(Ubuntu 环境),可用 shell 工具执行命令。" +
                "工作目录不是操作系统级安全隔离,只允许读写自己的工作目录,不得尝试访问应用数据或其他 Bot 的文件。" +
                (if (others.isNotEmpty()) "同伴:$others(其中设备 Agent 在用户手机本地,能读写用户授权的手机文件夹),可用 ask_bot 私信求助。" else "") +
                "群聊里其他 Bot 的发言会以 [名字] 开头。请使用用户的语言回复。" + factsSuffix + exchangeSuffix + skillSuffix
        }
        // Context window: very long chats were sent wholesale, burning tokens (and money) on
        // hundreds of stale messages before the model even saw the new one.
        val window = history.takeLast(40)
        // Attached images are inlined as base64 vision parts on user messages inside the window.
        // Encoding is scoped to the window on purpose: encoding every image ever attached (then
        // dropping most of them at send time) wasted both CPU and memory on long chats.
        val imgB64 = withContext(Dispatchers.IO) {
            val dir = File(store.appContext().filesDir, "attachments")
            window.flatMap { it.images }.distinct().mapNotNull { name ->
                val f = File(dir, name)
                if (f.exists()) runCatching {
                    name to java.util.Base64.getEncoder().encodeToString(f.readBytes())
                }.getOrNull() else null
            }.toMap()
        }
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", sys))
        for (m in window) {
            if (m.text.isBlank() && m.images.isEmpty() || m.role == "system" || m.role == "project") continue
            when {
                m.from == bot.id -> msgs.put(JSONObject().put("role", "assistant").put("content", m.text))
                m.from == null -> {
                    val allowMm = ModelPrefs.isMultimodal(bot.id, store.appContext())
                    val usable = if (allowMm) m.images.mapNotNull { imgB64[it] } else emptyList()
                    if (usable.isEmpty()) {
                        msgs.put(JSONObject().put("role", "user").put("content", m.text))
                    } else {
                        val parts = JSONArray()
                        if (m.text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", m.text))
                        for (b64 in usable) parts.put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
                        )
                        msgs.put(JSONObject().put("role", "user").put("content", parts))
                    }
                }
                else -> msgs.put(JSONObject().put("role", "user")
                    .put("content", "[${store.botName(m.from)}] ${m.text}"))
            }
        }
        val tools = mutableListOf<ToolCall>()
        val mcpTools = Mcp.toolDefs(store)
        val allDefs = JSONArray().apply {
            val base = toolDefs(isAgent)
            for (i in 0 until base.length()) put(base.get(i))
            val extra = Mcp.defsJson(mcpTools)
            for (i in 0 until extra.length()) put(extra.get(i))
        }
        val latestHuman = history.lastOrNull { it.from == null && it.role == "user" && it.text.isNotBlank() }?.text.orEmpty()
        try {
            var rounds = 0
            while (rounds < MAX_TOOL_ROUNDS) {
                // v0.7.0: scope reminder. Long tool chains drift; restate the latest human request.
                if (rounds > 0 && rounds % SCOPE_REMINDER_EVERY == 0 && latestHuman.isNotBlank()) {
                    msgs.put(JSONObject().put("role", "user").put("content",
                        "【范围提醒】继续之前,请对照用户最近的请求检查进度,只做请求范围内的事,完成就停下汇报:「" +
                            LeakGuard.mask(latestHuman).take(500) + "」"))
                }
                if (rounds > 0) budgetStop(store, bot)?.let { return it }
                rounds++
                val message = withContext(Dispatchers.IO) { call(bot, msgs, allDefs) }
                val calls = message.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    val c = if (message.isNull("content")) "" else message.optString("content", "")
                    val r = message.optString("reasoning_content", "").ifBlank { message.optString("reasoning", "") }.trim()
                    val combined = if (r.isNotBlank() && !c.contains("<think>", true)) "<think>\n$r\n</think>\n$c" else c
                    return combined.ifBlank { "(空回复)" }
                }
                msgs.put(message)
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i)
                    val f = c.getJSONObject("function")
                    val name = f.getString("name")
                    val argStr = f.optString("arguments", "{}")
                    val idx = tools.size
                    tools.add(ToolCall(c.getString("id"), name, argStr, ""))
                    onTools(tools.toList())
                    // v0.7.0: defence in depth: a Vault value never travels back to the model or the UI.
                    val rawOut = Vault.redact(runTool(store, bot, name, argStr, depth, chatId))
                    // v0.7.1: Inline & Spill oversized outputs to disk + record Watchdog heartbeat.
                    val (out, spillId) = if (name == "recall_spill") rawOut to null else ContextSpill.inlineOrSpill(name, rawOut)
                    if (chatId.isNotBlank()) SuperAgent.heartbeatByChat(chatId, spillId)
                    // screen_shot AND browser_* calls carry a live screenshot for the model AND a thumbnail for the UI.
                    val shot = pendingShots.remove(bot.id)
                    val carriesShot = shot != null && (name == "screen_shot" || name.startsWith("browser_"))
                    tools[idx] = tools[idx].copy(output = out, image = if (carriesShot) shot!!.absolutePath else "")
                    onTools(tools.toList())
                    msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", out))
                    if (carriesShot && shot != null && ModelPrefs.isMultimodal(bot.id, store.appContext())) {
                        // Attach the screenshot as a plain user vision message: tool messages with
                        // array content are rejected by several OpenAI-compatible providers.
                        val b64 = runCatching {
                            java.util.Base64.getEncoder().encodeToString(shot.readBytes())
                        }.getOrNull()
                        if (b64 != null) {
                            val mime = if (shot.name.endsWith(".jpg", true) || shot.name.endsWith(".jpeg", true)) "image/jpeg" else "image/png"
                            val caption = if (name.startsWith("browser_")) {
                                "这是刚刚 $name 截取的独立浏览器实时画面，请结合画面中的元素位置（0-1000 归一化坐标）继续执行点击、长按、拖动、滚轮或输入操作。"
                            } else {
                                "这是刚刚 screen_shot 截到的屏幕(操作后请重新截屏核对)。"
                            }
                            val parts = JSONArray()
                            parts.put(JSONObject().put("type", "text").put("text", caption))
                            parts.put(JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:$mime;base64,$b64")))
                            msgs.put(JSONObject().put("role", "user").put("content", parts))
                        }
                    }
                }
            }
            return "已达到单轮工具调用上限($MAX_TOOL_ROUNDS 步),为避免死循环消耗额度已自动中止。你可以继续回复让我从当前进度接着做。"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "出错:${e.message}"
        }
    }

    /** Single plain completion (no tools) — used by the manager pipeline and self-check. Throws on failure. */
    suspend fun once(store: Store, rawBot: Bot, system: String, user: String): String = withContext(Dispatchers.IO) {
        val bot = store.resolved(rawBot)
        budgetStop(store, bot)?.let { throw IOException(it) }
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", system))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val m = call(bot, msgs, null)
        if (m.isNull("content")) "" else m.getString("content")
    }

    /** Budget stop text (neutral, also shown in the chat) or null when the Bot may call its model. */
    private fun budgetStop(store: Store, bot: Bot): String? {
        if (!UsageLedger.overBudget(bot.id)) return null
        val budget = UsageLedger.budget(bot.id)
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (budgetNotified.put(bot.id, day) != day) {
            Notify.post(store.appContext(), Notify.CH_ROUTINES, ("budget:" + bot.id).hashCode(),
                BixVoice.TITLE, BixVoice.budgetHit(bot.name), null)
        }
        return "今日用量已达到「${bot.name}」的每日预算($budget tokens),本次没有调用模型。可以在「设置 → 用量与预算」调高预算,明天 0 点自动恢复。"
    }

    /** Takes (and clears) the screenshot the last screen/browser tool produced for [botId]. */
    internal fun takePendingShot(botId: String): File? = pendingShots.remove(botId)

    private suspend fun runTool(store: Store, bot: Bot, name: String, args: String, depth: Int, chatId: String = ""): String {
        val a = try { JSONObject(args) } catch (_: Exception) { JSONObject() }
        if (name in AGENT_ONLY && bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
        gate(store, bot, name, a, args, chatId, approved = false)?.let { return it }
        return executeTool(store, bot, name, a, args, depth, chatId)
    }

    /**
     * v0.7.0: replays a tool call the user approved. Skips the approval gate once, but the
     * payment floor (HANDOFF) and the Guard checks inside each tool still apply.
     */
    suspend fun executeApproved(store: Store, bot: Bot, chatId: String, tool: String, argsJson: String): String {
        val b = store.resolved(bot)
        val a = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        if (tool in AGENT_ONLY && b.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
        gate(store, b, tool, a, argsJson, chatId, approved = true)?.let { return it }
        return Vault.redact(executeTool(store, b, tool, a, argsJson, 0, chatId))
    }

    /** One-line, masked, human description of a tool call (approval cards, logs, sentinel). */
    internal fun summarize(name: String, a: JSONObject): String {
        fun s(k: String) = a.optString(k).replace(Regex("\\s+"), " ").trim()
        val text = when (name) {
            "shell" -> "在沙盒执行:${s("command")}"
            "shizuku_shell" -> "以 Shizuku 执行:${s("command")}"
            "fs_write" -> "写入文件 ${s("path")}(${a.optString("content").length} 字符)"
            "fs_delete" -> "删除 ${s("path")}"
            "browser_open", "open_url" -> "打开网页 ${s("url")}"
            "browser_type" -> "在网页输入「${s("text").take(60)}」" + if (a.optBoolean("submit", false)) "并提交" else ""
            "browser_mouse" -> "网页鼠标 ${s("action").ifBlank { "click" }} (${a.optInt("x", 500)},${a.optInt("y", 500)})"
            "browser_nav" -> "网页${s("action")}"
            "browser_fill_secret" -> "把保险箱凭据「${s("name")}」填入 ${PolicyRules.hostOf(AiBrowser.currentUrl).ifBlank { "当前网页" }} 的输入框"
            "tap_at" -> "点击屏幕 (${a.optInt("x")},${a.optInt("y")})"
            "long_press" -> "长按屏幕 (${a.optInt("x")},${a.optInt("y")})"
            "drag_at" -> "拖动 (${a.optInt("x1")},${a.optInt("y1")})→(${a.optInt("x2")},${a.optInt("y2")})"
            "tap_text" -> "点击文字「${s("text")}」"
            "input_text" -> "在手机输入「${s("text").take(60)}」"
            "press_key" -> "按系统键 ${s("key")}"
            "launch_app" -> "打开应用 ${s("query")}"
            "phone_task" -> "手机任务:${s("task")}"
            "remember" -> "写入记忆:${s("text")}"
            "schedule_task" -> "创建例行任务:${s("task")}"
            "create_skill" -> "创建技能「${s("name")}」"
            "run_workflow" -> "启动工作流「${s("name")}」"
            "ask_bot" -> "给「${s("bot_name")}」发消息:${s("message")}"
            else -> if (name.startsWith("mcp_")) "调用外部工具 $name" else "$name ${a.toString().take(80)}"
        }
        return LeakGuard.mask(Vault.redact(text)).take(160)
    }

    /** Payment / account protection (PAYMENT floor = HANDOFF). Returns the reason, or null. */
    private suspend fun paymentReason(store: Store, bot: Bot, name: String, a: JSONObject): String? {
        if (name in PAYMENT_WEB_TOOLS) {
            if (name == "browser_open" || name == "open_url") {
                val url = a.optString("url").trim()
                if (url.isNotEmpty() && Policy.isSensitivePage(url, "")) return "目标网址是支付或账户安全页面(${PolicyRules.hostOf(url)})"
                if (name == "browser_open") return null
            }
            if (name != "open_url" && withContext(Dispatchers.Main) { AiBrowser.onSensitivePage() }) {
                return "AI 浏览器当前停在支付或账户安全页面(${PolicyRules.hostOf(AiBrowser.currentUrl)})"
            }
            return null
        }
        if (bot.id != AGENT_ID || name !in PAYMENT_SCREEN_TOOLS) return null
        if (name == "launch_app") {
            val q = a.optString("query").trim()
            val pkg = SystemTools.resolvePackage(store.appContext(), q) ?: q
            return if (Policy.isProtectedApp(pkg)) "「$q」在受保护应用名单里(支付或银行类应用)" else null
        }
        if (VirtualScreen.active.value) {
            val pkg = ShizukuTools.foregroundOnDisplay(VirtualScreen.displayId.value)
            return if (pkg.isNotEmpty() && Policy.isProtectedApp(pkg)) "虚拟屏前台是受保护的应用 $pkg" else null
        }
        val pkg = A11y.foregroundPackage()
        if (pkg.isNotEmpty() && Policy.isProtectedApp(pkg)) return "屏幕前台是受保护的应用 $pkg"
        if (Policy.isPaymentText(A11y.visibleText())) return "屏幕上出现了支付或密码确认字样"
        return null
    }

    private suspend fun handoff(store: Store, bot: Bot, chatId: String, name: String, a: JSONObject, reason: String): String {
        withContext(Dispatchers.Main) {
            if (name in PAYMENT_WEB_TOOLS && name != "open_url") {
                AiBrowser.setTakeover(true)
                AiBrowser.sheetOpen = true
            }
            if (chatId.isNotEmpty()) store.postNotice(chatId, BixVoice.handoff(reason))
            store.logGuard(bot.id, summarize(name, a), "交给用户:$reason")
        }
        return "【已交给用户,未执行】$reason。按安全策略,支付、转账和账户安全相关的操作必须由用户亲自完成。" +
            "请停止操作这个页面,用一句话告诉用户需要他亲自完成哪一步,然后等待用户回复。"
    }

    private fun isQuickReviewTool(name: String) = name == "browser_type" || name == "input_text" || name.startsWith("mcp_")

    /** The text form of a legacy approval (UI cards of older builds read `command`). */
    private fun legacyCommand(name: String, a: JSONObject, summary: String): String = when (name) {
        "shell", "shizuku_shell" -> a.optString("command")
        "fs_delete" -> a.optString("channel", "auto").ifBlank { "auto" } + "|" + a.optString("path")
        else -> summary
    }

    /**
     * v0.7.0 policy gate, run before EVERY tool: classify -> level -> leak guard -> payment check ->
     * optional sentinel quick review -> run / approval card / hand off. Fails closed: an ASK without
     * a chat to post the card into is refused. [approved] = user-approved replay (payment floor only).
     * Returns null when the tool may run now, else the (neutral) text for the model.
     */
    private suspend fun gate(
        store: Store, bot: Bot, name: String, a: JSONObject, args: String, chatId: String, approved: Boolean
    ): String? {
        var cls = Policy.classify(name, a)
        if (cls == ActionClass.READ) return null
        paymentReason(store, bot, name, a)?.let { return handoff(store, bot, chatId, name, a, it) }
        if (approved) return null
        // Commands the Guard blocks never become approval cards.
        if (name == "shell" || name == "shizuku_shell") {
            val cmd = a.optString("command")
            val allow = if (name == "shell") store.sandbox(bot.id).dir.absolutePath else "/"
            val why = Guard.check(cmd, allow)
            if (why != null) {
                withContext(Dispatchers.Main) { store.logGuard(bot.id, cmd, why) }
                return "【安全策略已拦截】$why。该命令没有执行,请换一种更安全的做法。"
            }
        }
        var level = PolicyRules.effectiveLevel(cls, Policy.configured(cls), bot.shellApproval)
        var leakNote = ""
        if (cls in LEAK_SCAN) {
            val text = a.toString()
            val hits = LeakGuard.scan(text)
            val vaultHit = Vault.leaksAny(text)
            if (hits.isNotEmpty() || vaultHit) {
                cls = ActionClass.SENSITIVE_DATA
                level = PolicyRules.stricter(level, PolicyLevel.ASK)
                leakNote = (hits + if (vaultHit) listOf("保险箱凭据") else emptyList()).joinToString("、")
            }
        }
        if (level == PolicyLevel.EXPLICIT) {
            val human = withContext(Dispatchers.Main) { store.latestHumanText(chatId) }
            val key = PolicyRules.keyArg(name) { k -> a.optString(k, "") }
            level = if (PolicyRules.explicitlyRequested(name, key, human)) PolicyLevel.AUTO else PolicyLevel.ASK
        }
        val summary = summarize(name, a)
        var verdict = ""
        var verdictNote = ""
        if (level == PolicyLevel.AUTO && Policy.sentinelEnabled && Policy.sentinelReviewAuto && isQuickReviewTool(name)) {
            val v = Sentinel.quickCheck(store, bot, chatId, name, args, summary)
            if (v.verdict != "ok") {
                level = PolicyLevel.ASK
                verdict = v.verdict
                verdictNote = v.note
            }
        }
        return when (level) {
            PolicyLevel.AUTO -> null
            PolicyLevel.HANDOFF -> handoff(store, bot, chatId, name, a, "「${cls.label}」类操作按你的设置交给你亲自完成")
            else -> {
                if (chatId.isEmpty()) return NO_APPROVAL_CONTEXT
                val unattended = withContext(Dispatchers.Main) {
                    store.requestApproval(
                        chatId, bot.id, legacyCommand(name, a, summary), name, args, summary, cls.name,
                        sentinelVerdict = verdict, sentinelNote = verdictNote
                    )
                    store.isUnattended(chatId)
                }
                val why = when {
                    leakNote.isNotEmpty() -> "参数里含有敏感数据($leakNote)"
                    verdict.isNotEmpty() -> "哨兵复核未通过($verdict:${verdictNote.take(60)})"
                    else -> "属于「${cls.label}」类操作"
                }
                "【等待用户批准】这一步($summary)$why,已提交给用户批准。本轮不要重复提交同一操作;" +
                    "请先用一句话告诉用户你在等批准、这一步打算做什么。用户批准后结果会回到对话里,你再继续。" +
                    if (unattended) "这条审批 30 分钟内无人处理会自动过期。" else ""
            }
        }
    }

    private suspend fun executeTool(
        store: Store, bot: Bot, name: String, a: JSONObject, args: String, depth: Int, chatId: String
    ): String {
        if (name.startsWith("mcp_")) return Mcp.callTool(store, name, args)
        // v0.7.0: one Bot drives the AI browser at a time; others get a clear message.
        if (name.startsWith("browser_") && name != "browser_fill_secret") {
            val busyMsg = withContext(Dispatchers.Main) {
                if (name == "browser_close") {
                    if (AiBrowser.isOwnedByOther(bot.id)) "AI 浏览器正在被「${AiBrowser.ownerName}」使用,你不能关闭它。" else null
                } else AiBrowser.claim(bot.id, bot.name)
            }
            if (busyMsg != null) return busyMsg
        }
        return when (name) {
            "shell" -> {
                val cmd = a.optString("command")
                val sb = store.sandbox(bot.id)
                val why = Guard.check(cmd, sb.dir.absolutePath)
                if (why != null) {
                    withContext(Dispatchers.Main) { store.logGuard(bot.id, cmd, why) }
                    "【安全策略已拦截】$why。该命令没有执行,请换一种更安全的做法。"
                } else store.withSandboxLock(bot.id) {
                    runInterruptible(Dispatchers.IO) { sb.exec(cmd) }
                }
            }
            "ask_bot" -> {
                if (depth >= 1) return "为避免循环,被求助的一方不能再求助其他 Bot。"
                val targetName = a.optString("bot_name")
                val target: Bot? = store.bots.firstOrNull { it.name == targetName && it.id != bot.id }
                    ?: store.agent.value?.takeIf { it.name == targetName && bot.id != AGENT_ID }
                if (target == null) return "找不到名为「$targetName」的 Bot 或 Agent。"
                val dm = store.dmId(bot.id, target.id)
                withContext(Dispatchers.Main) {
                    store.addDm(dm, Msg(role = "assistant", text = a.optString("message"), from = bot.id))
                }
                val hist = withContext(Dispatchers.Main) { store.chat(dm).toList() }
                // v0.7.0: run the target inside the DM thread so its own approval settings apply
                // (approval cards land in that DM and on the home banner). Previously no chat id
                // was passed and「命令先批准」was silently skipped for every Bot reached via ask_bot.
                val reply = run(store, target, hist, depth + 1, chatId = dm) { }
                withContext(Dispatchers.Main) {
                    store.addDm(dm, Msg(role = "assistant", text = reply, from = target.id))
                }
                reply
            }
            "fs_list", "fs_read", "fs_write", "fs_delete", "fs_scan" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                val ch = a.optString("channel", "auto").ifBlank { "auto" }
                val p = a.optString("path")
                // Disk-heavy tools must never run on the main thread (ANR risk on slow storage).
                withContext(Dispatchers.IO) {
                    when (name) {
                        "fs_list" -> FsTools.list(store.appContext(), p, ch)
                        "fs_read" -> FsTools.read(store.appContext(), p, ch)
                        "fs_write" -> FsTools.write(store.appContext(), p, a.optString("content"), ch)
                        "fs_scan" -> FsTools.scan(p, a.optInt("depth", 2))
                        else -> FsTools.delete(store.appContext(), p, ch)
                    }
                }
            }
            "device_status", "app_usage", "list_apps", "launch_app" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                val ctx = store.appContext()
                when (name) {
                    "device_status" -> SystemTools.deviceStatus(ctx)
                    "app_usage" -> SystemTools.appUsage(ctx)
                    "list_apps" -> SystemTools.listApps(ctx)
                    else -> {
                        val q = a.optString("query")
                        if (VirtualScreen.active.value) {
                            val pkg = SystemTools.resolvePackage(ctx, q)
                            if (pkg == null) "找不到应用「$q」。"
                            else {
                                VirtualScreen.ownerName.value = bot.name
                                val out = ShizukuTools.startAppOnDisplay(VirtualScreen.displayId.value, pkg)
                                if (out.contains("[exit 0]")) {
                                    "已在虚拟屏 #${VirtualScreen.displayId.value} 上启动 $pkg。用 screen_shot 看画面。\n$out"
                                } else {
                                    // Fall back to the explicit component form before giving up.
                                    val comp = ShizukuTools.resolveLauncherActivity(pkg)
                                    if (comp != null) {
                                        val second = ShizukuTools.startComponentOnDisplay(VirtualScreen.displayId.value, comp)
                                        if (second.contains("[exit 0]"))
                                            "已在虚拟屏 #${VirtualScreen.displayId.value} 上启动 $comp。用 screen_shot 看画面。\n$second"
                                        else "虚拟屏启动失败(两种方式都试过)。\n$out\n$second"
                                    } else "虚拟屏启动失败:$out"
                                }
                            }
                        } else SystemTools.launchApp(ctx, q)
                    }
                }
            }
            "open_url" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                val url = a.optString("url").trim()
                if (url.isBlank()) "网址为空。"
                else try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    store.appContext().startActivity(intent)
                    "已用浏览器打开:$url"
                } catch (e: Exception) {
                    "打开失败:${e.message}"
                }
            }
            "recent_notifications" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                Notifications.recent(store.appContext())
            }
            "remember" -> {
                val text = a.optString("text").trim()
                val cat = a.optString("category").trim()
                val scope = a.optString("scope").trim().lowercase()
                when {
                    !ModelPrefs.isMemoryEnabled(bot.id, store.appContext()) -> "用户关闭了你的长期记忆,本条没有保存。"
                    text.isBlank() -> "内容为空,未保存。"
                    LeakGuard.scan(text).isNotEmpty() || Vault.leaksAny(text) -> "内容里含有敏感数据(卡号、证件号、验证码或密钥),长期记忆不保存这类信息。"
                    scope == "user" -> {
                        withContext(Dispatchers.Main) { store.addUserFact(text) }
                        "已存为关于用户的共享事实,所有 Bot 都会看到。"
                    }
                    else -> {
                        withContext(Dispatchers.Main) { store.remember(bot.id, text, cat) }
                        if (cat.isNotEmpty()) "已存入长期记忆(分类:$cat)。" else "已存入长期记忆。"
                    }
                }
            }
            "recall" -> {
                val q = a.optString("query").trim()
                if (!ModelPrefs.isMemoryEnabled(bot.id, store.appContext())) "(用户关闭了你的长期记忆)"
                else {
                    val list = withContext(Dispatchers.Main) { store.recall(bot.id, q) }
                    if (list.isEmpty()) (if (q.isEmpty()) "(长期记忆为空)" else "(未找到包含「$q」的记忆)")
                    else list.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
                }
            }
            "schedule_task" -> {
                val task = a.optString("task").trim()
                val trigger = a.optString("trigger", "time").trim().lowercase().ifBlank { "time" }
                val id = java.util.UUID.randomUUID().toString().take(8)
                val boundary = "执行中遇到需要批准的操作会生成审批卡;无人值守时审批 30 分钟内未处理会过期,该步骤不执行。"
                val missing = "触发事件缺少信息时,任务照常运行,并在结果里说明缺了什么。"
                when {
                    task.isBlank() -> "任务内容为空,未创建。"
                    trigger == "time" -> {
                        val delayMin = a.optInt("delay_minutes", 0)
                        if (delayMin < 1) "delay_minutes 至少为 1。"
                        else {
                            val repeat = a.optBoolean("repeat_daily", false)
                            val next = System.currentTimeMillis() + delayMin * 60_000L
                            withContext(Dispatchers.Main) {
                                store.addRoutine(Routine(id, bot.id, task, next, repeat, createdBy = bot.name))
                            }
                            val when_ = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(next))
                            "已创建定时任务(id $id):\n- 执行者:${bot.name}\n- 何时运行:$when_" +
                                (if (repeat) ",此后每天同一时间" else ",只运行一次") +
                                "\n- 任务:$task\n- 审批边界:$boundary"
                        }
                    }
                    trigger == "notification" -> {
                        val matchApp = a.optString("match_app").trim().take(60)
                        val matchText = a.optString("match_text").trim().take(60)
                        if (matchApp.isEmpty() && matchText.isEmpty()) {
                            "通知触发至少要给 match_app 或 match_text 之一,否则每条通知都会触发。未创建。"
                        } else {
                            withContext(Dispatchers.Main) {
                                store.addRoutine(Routine(id, bot.id, task, 0L, false, createdBy = bot.name,
                                    trigger = "notification", matchApp = matchApp, matchText = matchText))
                            }
                            val listening = Notifications.isEnabled(store.appContext())
                            "已创建通知触发任务(id $id):\n- 执行者:${bot.name}\n- 何时运行:收到" +
                                (if (matchApp.isNotEmpty()) "「$matchApp」的" else "任意应用的") + "通知" +
                                (if (matchText.isNotEmpty()) "且标题或内容包含「$matchText」" else "") +
                                "时(两次之间至少间隔 5 分钟)\n- 任务:$task\n- 输入:通知的应用、标题和内容(验证码等敏感内容会被打码)\n- 审批边界:$boundary\n- 缺数据时:$missing" +
                                if (listening) "" else "\n注意:用户还没开启「通知使用权」,开启前不会触发。请提醒用户在 Agent 面板开启。"
                        }
                    }
                    trigger == "webhook" -> {
                        withContext(Dispatchers.Main) {
                            store.addRoutine(Routine(id, bot.id, task, 0L, false, createdBy = bot.name, trigger = "webhook"))
                        }
                        val on = WebhookServer.isEnabled(store.appContext())
                        "已创建 Webhook 触发任务(id $id):\n- 执行者:${bot.name}\n- 何时运行:有程序向 ${WebhookServer.urlFor(id)} 发送 POST 请求时" +
                            "(请求头需带设置页里的 Bearer 令牌,请求体就是事件内容)\n- 任务:$task\n- 审批边界:$boundary\n- 缺数据时:$missing" +
                            if (on) "" else "\n注意:Webhook 服务还没开启,请提醒用户在「设置 → Webhook」里打开。"
                    }
                    else -> "trigger 只能是 time / notification / webhook。未创建。"
                }
            }
            "list_tasks" -> {
                val mine = store.routines.filter { it.ownerId == bot.id }
                if (mine.isEmpty()) "(你没有例行任务)"
                else mine.joinToString("\n") { r ->
                    val whenText = when (r.trigger) {
                        "notification" -> "通知触发(" + listOf(r.matchApp, r.matchText).filter { it.isNotBlank() }.joinToString(" / ") + ")"
                        "webhook" -> "Webhook 触发"
                        else -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(r.nextRunMs)) +
                            if (r.repeatDaily) "(每天)" else ""
                    }
                    "- [${r.id}] $whenText${if (r.paused) "【已暂停】" else ""}:${r.task}"
                }
            }
            "run_workflow" -> {
                val wfName = a.optString("name").trim()
                val wf = store.workflows.firstOrNull { it.name == wfName }
                when {
                    store.workflows.isEmpty() -> "还没有任何工作流,让用户先在「工作流」页面建一个。"
                    wf == null -> "没有叫「$wfName」的工作流。现有工作流:${store.workflows.joinToString("、") { it.name }}"
                    "wf:${wf.id}" in store.busy -> "工作流「$wfName」正在运行中。"
                    else -> {
                        withContext(Dispatchers.Main) { store.runWorkflow(wf) }
                        "已启动工作流「$wfName」,它会自己跑完,记录在工作流的会话里。"
                    }
                }
            }
            "list_skills" -> {
                if (store.skills.isEmpty()) "(还没有技能包,用 create_skill 可以自己写一个)"
                else store.skills.joinToString("\n") {
                    "- ${it.name}${if (it.id in bot.skillIds) "(已挂在你身上)" else ""}" +
                        if (it.description.isNotBlank()) ":${it.description.take(100)}" else ""
                } + "\n没挂在你身上的技能可用 use_skill 按名称加载。"
            }
            "use_skill" -> {
                val q = a.optString("name").trim()
                val sk = store.skills.firstOrNull { it.name == q }
                    ?: store.skills.firstOrNull { it.name.equals(q, ignoreCase = true) }
                    ?: store.skills.firstOrNull { q.isNotEmpty() && it.name.contains(q, ignoreCase = true) }
                if (sk == null) "没有叫「$q」的技能。可用 list_skills 查看全部技能。"
                else "## 技能:${sk.name}\n" + (if (sk.description.isNotBlank()) "用途:${sk.description}\n\n" else "") +
                    sk.content + "\n\n(本技能本轮生效,请按其中的步骤执行。)"
            }
            "team_status" -> withContext(Dispatchers.Main) { store.teamStatus() }
            "secret_list" -> {
                val names = Vault.names()
                if (names.isEmpty()) "(保险箱里还没有凭据。请用户在「设置 → 凭据保险箱」里添加。)"
                else "保险箱里的凭据(只有名称):\n" + names.joinToString("\n") { "- $it" } +
                    "\n用 browser_fill_secret(name) 把凭据填入 AI 浏览器当前聚焦的输入框。"
            }
            "browser_fill_secret" -> {
                val sName = a.optString("name").trim()
                withContext(Dispatchers.Main) { AiBrowser.claim(bot.id, bot.name) }?.let { return it }
                val value = Vault.valueOf(sName)
                if (value == null) "保险箱里没有叫「$sName」的凭据(或暂时无法解密)。可用 secret_list 查看。"
                else AiBrowser.fillSecret(store.appContext(), value)
            }
            "create_skill" -> {
                val sName = a.optString("name").trim()
                val sContent = a.optString("content").trim()
                val sDesc = a.optString("description").trim().take(200)
                when {
                    sName.isBlank() || sContent.isBlank() -> "技能名与内容都要填。"
                    store.skills.any { it.name == sName } -> "已存在同名技能「$sName」,换个名字,或让用户在设置里改现成的。"
                    else -> {
                        val id = java.util.UUID.randomUUID().toString().take(8)
                        withContext(Dispatchers.Main) {
                            store.saveSkill(Skill(id, sName, sContent.take(4_000), sDesc))
                            if (bot.id == AGENT_ID) {
                                store.agent.value?.let { store.saveAgent(it.copy(skillIds = it.skillIds + id)) }
                            } else {
                                store.bot(bot.id)?.let { store.saveBot(it.copy(skillIds = it.skillIds + id)) }
                            }
                        }
                        "已创建技能「$sName」并挂到你身上,从下一轮对话起生效。"
                    }
                }
            }
            "browser_open" -> {
                val url = a.optString("url").trim()
                if (url.isBlank()) "请提供要打开的 url。"
                else {
                    val (info, shot) = AiBrowser.openUrl(store.appContext(), store.appContext().cacheDir, url, a.optBoolean("desktop_mode", false))
                    if (shot != null) pendingShots[bot.id] = shot
                    "$info\n已附带当前浏览器画面截图,请结合 0-1000 坐标继续用 browser_mouse / browser_type 操作。"
                    }
            }
            "browser_mouse" -> {
                val (info, shot) = AiBrowser.mouseAction(
                    context = store.appContext(),
                    cacheDir = store.appContext().cacheDir,
                    action = a.optString("action", "click"),
                    x = a.optInt("x", 500),
                    y = a.optInt("y", 500),
                    button = a.optString("button", "left"),
                    x2 = a.optInt("x2", a.optInt("x", 500)),
                    y2 = a.optInt("y2", a.optInt("y", 500)),
                    deltaX = a.optInt("delta_x", 0),
                    deltaY = a.optInt("delta_y", 0),
                    durationMs = a.optLong("duration_ms", 500L)
                )
                if (shot != null) pendingShots[bot.id] = shot
                info
            }
            "browser_type" -> {
                val (info, shot) = AiBrowser.typeText(
                    store.appContext(),
                    store.appContext().cacheDir,
                    a.optString("text"),
                    a.optBoolean("submit", false)
                )
                if (shot != null) pendingShots[bot.id] = shot
                info
            }
            "browser_nav" -> {
                val (info, shot) = AiBrowser.navigate(
                    store.appContext(),
                    store.appContext().cacheDir,
                    a.optString("action", "snapshot")
                )
                if (shot != null) pendingShots[bot.id] = shot
                info
            }
            "browser_screenshot" -> {
                val (info, shot) = AiBrowser.navigate(store.appContext(), store.appContext().cacheDir, "snapshot")
                if (shot != null) pendingShots[bot.id] = shot
                "$info\n已截取最新浏览器画面并附带截图,请结合 0-1000 坐标继续操作。"
                }
            "ticket_create" -> {
                val title = a.optString("title").trim()
                val goal = a.optString("goal").trim()
                if (goal.isBlank()) "请提供工单 goal。"
                else {
                    val owner = a.optString("owner_bot_id").trim().ifBlank { bot.id }
                    val t = SuperAgent.createTicket(title.ifBlank { goal.take(24) }, goal, owner, source = "router", sourceKey = a.optString("source_key"))
                    "已创建持久化工单 ${t.id}（「${t.title}」，责任 Worker：$owner）。"
                }
            }
            "ticket_update" -> {
                val tid = a.optString("id").trim()
                val st = a.optString("status").trim().uppercase().takeIf { it.isNotBlank() }?.let {
                    runCatching { TicketStatus.valueOf(it) }.getOrNull()
                }
                val cp = a.optString("checkpoint").trim()
                val updated = SuperAgent.updateTicket(tid, status = st, checkpoint = cp)
                if (updated == null) "未找到工单 $tid。"
                else "工单 ${updated.id} 已更新（状态：${updated.status.label}，检查点数：${updated.checkpoints.size}）。"
            }
            "ticket_list" -> {
                val list = SuperAgent.tickets.take(20)
                if (list.isEmpty()) "当前没有持久化 Super-Agent 工单。"
                else list.joinToString("\n") { t ->
                    "${t.id} [${t.status.label}] ${t.title} (Worker:${t.ownerBotId}, 检查点:${t.checkpoints.size})"
                }
            }
            "board_write" -> {
                val k = a.optString("key").trim()
                val v = a.optString("value").trim()
                if (k.isBlank() || v.isBlank()) "请提供 key 和 value。"
                else {
                    val n = HiveBlackboard.write(k, v, a.optString("tag", "通用"), bot.id)
                    "已写入蜂巢共享黑板：[${n.tag}] ${n.key}"
                }
            }
            "board_read" -> {
                val list = HiveBlackboard.read(a.optString("key"), a.optString("tag"))
                if (list.isEmpty()) "蜂巢共享黑板上暂无匹配词条。"
                else list.take(25).joinToString("\n\n") { n -> "[${n.tag}] ${n.key} (by ${n.authorBotId}):\n${n.value}" }
            }
            "recall_spill" -> {
                ContextSpill.recall(
                    id = a.optString("id"),
                    query = a.optString("query"),
                    startLine = a.optInt("start_line", 1),
                    maxLines = a.optInt("max_lines", 80)
                )
            }
            "pipeline_advance" -> {
                val pid = a.optString("id").trim()
                if (pid.isBlank()) "请提供流水线 id。"
                else HivePipeline.advanceStage(store, pid, a.optString("summary"))
            }
            "memory_pod_save" -> {
                val content = a.optString("content").trim()
                if (content.isBlank()) "请提供 content。"
                else {
                    val cat = when (a.optString("category").trim().lowercase()) {
                        "pref" -> MemoryCategory.PREF
                        "goal" -> MemoryCategory.GOAL
                        "mood" -> MemoryCategory.MOOD
                        else -> MemoryCategory.FACT
                    }
                    val conf = if (a.optString("confidence").trim().lowercase() == "inferred")
                        MemoryConfidence.INFERRED else MemoryConfidence.STATED
                    val rec = MemoryPod.add(content, cat, source = "Bot:${bot.name}", confidence = conf)
                    "已写入个人记忆档案舱 ${rec.id}（[${rec.category.label}/${rec.confidence.label}]）。"
                }
            }
            "memory_pod_query" -> {
                val list = MemoryPod.queryForBot(bot.id, a.optString("query")).take(20)
                if (list.isEmpty()) "记忆档案舱中暂无对当前 Bot 授权的匹配记录。"
                else list.joinToString("\n") { r ->
                    "• [${r.category.label}/${r.confidence.label}] ${r.content} (来源:${r.source})"
                }
            }
            "open_deeplink" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                val uriStr = a.optString("uri").trim()
                if (uriStr.isBlank() || !uriStr.contains("://") && !uriStr.startsWith("mailto:") && !uriStr.startsWith("tel:")) {
                    "请提供合法的 Deep Link URI（如 weixin://、bilibili://、baidumap://）。"
                } else {
                    val ok = runCatching {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uriStr)).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        store.appContext().startActivity(intent)
                        true
                    }.getOrDefault(false)
                    if (ok) "已通过系统 Deep Link 直达：$uriStr" else "没有可处理该 Deep Link 的已安装应用：$uriStr（可改用 screen_shot + tap_at 兜底）"
                }
            }
            "browser_close" -> {
                withContext(Dispatchers.Main) { AiBrowser.closeSession() }
                "独立 AI 浏览器已关闭。"
            }
            "shizuku_shell" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                val cmd = a.optString("command")
                val why = Guard.check(cmd, "/")
                if (why != null) {
                    withContext(Dispatchers.Main) { store.logGuard(bot.id, cmd, why) }
                    "【安全策略已拦截】$why"
                } else ShizukuTools.shell(cmd)
            }
            "phone_task" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                if (!PhoneModel.ready()) {
                    return "专用手机模型未启用或配置不完整(${PhoneModel.describe()})。" +
                        "请让用户在「Agent 设置 → 专用手机模型」里填好 Base URL、API Key 与模型名;" +
                        "或者直接改用 screen_shot + tap_at 自己操作。"
                }
                // Needs a live virtual display to see and drive.
                val prep = AgentMode.ensureVirtual(store.appContext(), bot.name)
                if (prep != null) return "虚拟屏未就绪,无法执行手机任务:$prep"
                runPhoneTask(store, bot, a.optString("task").trim())
            }
            "screen_mode" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                val mode = a.optString("mode", "status").trim().lowercase()
                when (mode) {
                    "status", "" -> AgentMode.describe()
                    "virtual" -> AgentMode.switchTo(store.appContext(), bot.name, virtual = true)
                    "main" -> AgentMode.switchTo(store.appContext(), bot.name, virtual = false)
                    "close", "off" -> {
                        AgentMode.teardown()
                        "已关闭虚拟屏悬浮窗并释放虚拟屏资源。"
                    }
                    else -> "mode 只能是 status / virtual / main / close。"
                }
            }
            "screen_shot", "screen_dump", "tap_at", "long_press", "drag_at", "tap_text", "input_text", "press_key" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                // v0.7.0: live progress for the overlay step ring / control glow (free-form: no step budget).
                if (name in SCREEN_ACTION_TOOLS) {
                    val label = summarize(name, a)
                    withContext(Dispatchers.Main) { AgentProgress.action(AgentProgress.step.intValue, label) }
                }
                // In virtual mode the display is expected to outlive a single turn. If the OS dropped
                // it (process reclaim, OEM killing the display), rebuild it here instead of returning
                // "not running" — that used to send the model into a retry loop that burned tokens.
                if (AgentMode.mode.value == AgentMode.Mode.Virtual) {
                    val rebuilt = AgentMode.ensureVirtual(store.appContext(), bot.name)
                    if (rebuilt != null) {
                        return "虚拟屏模式需要先恢复虚拟屏,但恢复失败:$rebuilt\n" +
                            "请让用户检查 Shizuku 与悬浮窗权限,或用 screen_mode main 切回主屏模式。"
                    }
                }
                val vid = VirtualScreen.displayId.value
                if (VirtualScreen.active.value) {
                    // Virtual-display mode: vision from ImageReader frames, injection through Shizuku.
                    return when (name) {
                        "screen_shot" -> {
                            // captureToFile blocks briefly waiting for a frame; keep it off the main thread.
                            val cap = withContext(Dispatchers.IO) {
                                VirtualScreen.captureToFile(store.appContext().cacheDir)
                            }
                            if (cap == null) "虚拟屏还没有可用画面。可能刚创建、App 未启动,或当前页面是 FLAG_SECURE(银行/支付等会黑屏)。"
                            else {
                                val (file, size) = cap
                                pendingShots[bot.id] = file
                                val black = runCatching {
                                    val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                    if (bmp == null) false else {
                                        val isBlack = VirtualScreen.looksBlack(bmp)
                                        bmp.recycle()
                                        isBlack
                                    }
                                }.getOrDefault(false)
                                val warn = if (black) "\n注意:这一帧几乎全黑,当前页面可能启用了 FLAG_SECURE(银行/支付/部分密码页)。这类页面在虚拟屏里无法看清,请让用户切回主屏模式(screen_mode main)处理。" else ""
                                "虚拟屏截屏完成(${size.first}x${size.second},虚拟屏 #$vid)。请查看随本条结果附带的截图,把目标在画面中的相对位置换算成 0-1000 坐标再操作。$warn"
                            }
                        }
                        "screen_dump" -> "虚拟屏模式下无障碍节点树不可用(它绑定主屏的活动窗口),请以视觉为准:用 screen_shot 看图 + tap_at / long_press / drag_at 操作。"
                        "tap_at" -> {
                            val p = VirtualScreen.toPixels(a.optInt("x"), a.optInt("y"))
                            if (p == null) "虚拟屏尺寸未知,请先 screen_shot。" else ShizukuTools.tapOnDisplay(vid, p.first, p.second)
                        }
                        "long_press" -> {
                            val p = VirtualScreen.toPixels(a.optInt("x"), a.optInt("y"))
                            if (p == null) "虚拟屏尺寸未知,请先 screen_shot。" else ShizukuTools.longPressOnDisplay(vid, p.first, p.second, a.optLong("duration_ms", 600))
                        }
                        "drag_at" -> {
                            val p1 = VirtualScreen.toPixels(a.optInt("x1"), a.optInt("y1"))
                            val p2 = VirtualScreen.toPixels(a.optInt("x2"), a.optInt("y2"))
                            if (p1 == null || p2 == null) "虚拟屏尺寸未知,请先 screen_shot。"
                            else ShizukuTools.swipeOnDisplay(vid, p1.first, p1.second, p2.first, p2.second, a.optLong("duration_ms", 300))
                        }
                        "tap_text" -> "虚拟屏模式下 tap_text 不可用(节点树绑主屏)。请用 screen_shot 看图后 tap_at。"
                        "input_text" -> ShizukuTools.textOnDisplay(vid, a.optString("text"))
                        else -> {
                            val keyCode = when (a.optString("key").trim().lowercase()) {
                                "back" -> 4
                                "home" -> 3
                                "recents" -> 187
                                "notifications" -> 83
                                else -> -1
                            }
                            if (keyCode < 0) "不支持的按键:${a.optString("key")}(可用 back / home / recents / notifications)"
                            else ShizukuTools.keyOnDisplay(vid, keyCode)
                        }
                    }
                }
                when (name) {
                    "screen_shot" -> {
                        val r = A11y.screenshot(store.appContext().cacheDir)
                        if (r.has("error")) r.getString("error")
                        else {
                            // Mark this tool call as carrying an image; run() will attach the
                            // screenshot as a vision part on the tool-result message.
                            pendingShots[bot.id] = File(r.getString("path"))
                            "截屏完成(${r.getInt("width")}x${r.getInt("height")})。请直接查看随本条结果附带的截图;" +
                                "操作时把目标在画面中的相对位置换算成 0-1000 坐标(左上 0,0;右下 1000,1000),再调用 tap_at / long_press / drag_at。"
                        }
                    }
                    "screen_dump" -> A11y.dumpScreen()
                    "tap_at" -> A11y.tapNorm(a.optInt("x"), a.optInt("y"))
                    "long_press" -> A11y.longPressNorm(a.optInt("x"), a.optInt("y"), a.optLong("duration_ms", 600))
                    "drag_at" -> A11y.dragNorm(a.optInt("x1"), a.optInt("y1"), a.optInt("x2"), a.optInt("y2"), a.optLong("duration_ms", 300))
                    "tap_text" -> A11y.tapText(a.optString("text"))
                    "input_text" -> A11y.inputText(a.optString("text"))
                    else -> A11y.pressKey(a.optString("key"))
                }
            }
            else -> "未知工具:$name"
        }
    }

    /**
     * Drives the virtual display with the dedicated phone model: screenshot -> one action -> repeat.
     *
     * Bounded on purpose. A phone task that has not finished in [maxSteps] steps is almost always
     * stuck (wrong app, login wall, unexpected dialog), and continuing just burns the user's quota —
     * which is exactly what happened when the model was left to retry on its own.
     */
    private suspend fun runPhoneTask(store: Store, bot: Bot, task: String): String {
        if (task.isBlank()) return "任务内容为空。"
        val maxSteps = 15
        withContext(Dispatchers.Main) { AgentProgress.begin(maxSteps) }
        try {
            return phoneTaskLoop(store, bot, task, maxSteps)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { AgentProgress.end() }
        }
    }

    /**
     * Decodes a screenshot once: (looks black, 64-bit difference hash or null).
     * v0.7.0: only the central region is hashed (see [ScreenHash.centerCrop]) so edge overlays
     * (control glow, status bar clock, notification dots) cannot defeat the stuck detection.
     */
    private fun inspectFrame(file: File): Pair<Boolean, Long?> {
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return false to null
        var crop: android.graphics.Bitmap? = null
        return try {
            val black = VirtualScreen.looksBlack(bmp)
            val r = ScreenHash.centerCrop(bmp.width, bmp.height)
            val region = if (r[2] >= 9 && r[3] >= 8) {
                android.graphics.Bitmap.createBitmap(bmp, r[0], r[1], r[2], r[3]).also { crop = it }
            } else bmp
            val small = android.graphics.Bitmap.createScaledBitmap(region, 9, 8, true)
            val gray = IntArray(72)
            for (y in 0 until 8) for (x in 0 until 9) {
                val c = small.getPixel(x, y)
                gray[y * 9 + x] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            }
            if (small !== region) small.recycle()
            black to ScreenHash.dHash(gray)
        } catch (_: Exception) {
            false to null
        } finally {
            crop?.takeIf { it !== bmp && !it.isRecycled }?.recycle()
            bmp.recycle()
        }
    }

    private suspend fun phoneTaskLoop(store: Store, bot: Bot, task: String, maxSteps: Int): String {
        val ctx = store.appContext()
        val log = mutableListOf<String>()
        var lastAction = ""
        var repeats = 0
        var prevHash: Long? = null
        var unchanged = 0
        var lastWasAction = false
        fun done() = log.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n").ifBlank { "无" }
        for (step in 1..maxSteps) {
            // v0.7.0: payment protection. A protected (payment / bank) app in front stops the task.
            val fg = ShizukuTools.foregroundOnDisplay(VirtualScreen.displayId.value)
            if (fg.isNotEmpty() && Policy.isProtectedApp(fg)) {
                val reason = "虚拟屏前台是受保护的应用 $fg"
                withContext(Dispatchers.Main) {
                    store.logGuard(bot.id, "手机任务:" + LeakGuard.mask(task).take(80), "交给用户:$reason")
                }
                return "【已交给用户,未继续】$reason(支付或银行类应用必须由用户亲自操作),手机任务已停止。\n已执行:\n${done()}"
            }
            val cap = withContext(Dispatchers.IO) { VirtualScreen.captureToFile(ctx.cacheDir) }
                ?: return "第 $step 步截不到虚拟屏画面,任务中止。\n已执行:\n${done()}"
            val b64 = withContext(Dispatchers.IO) {
                runCatching { java.util.Base64.getEncoder().encodeToString(cap.first.readBytes()) }.getOrNull()
            } ?: return "截图读取失败,任务中止。"
            val (black, hash) = withContext(Dispatchers.IO) { inspectFrame(cap.first) }
            // Black frame = FLAG_SECURE page; the model cannot see anything, so stop early.
            if (black) {
                return "当前页面在虚拟屏里是黑屏(可能启用了 FLAG_SECURE,如银行/支付/密码页)。" +
                    "任务中止,请让用户切回主屏模式(screen_mode main)处理。\n已执行:\n${done()}"
            }
            // v0.7.0: screenshot-hash stuck detection: 3 actions in a row that changed nothing -> stop.
            val prev = prevHash
            if (lastWasAction && prev != null && hash != null && ScreenHash.distance(prev, hash) <= ScreenHash.SAME_THRESHOLD) {
                unchanged++
            } else if (lastWasAction) {
                unchanged = 0
            }
            if (hash != null) prevHash = hash
            if (unchanged >= 3) {
                withContext(Dispatchers.Main) { AgentProgress.markStuck() }
                return "连续 3 次操作后画面都没有变化,判定为卡住,任务中止以免继续消耗额度。\n已执行:\n${done()}"
            }
            val action = try {
                PhoneOperator.nextAction(ctx, b64, task, log)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return "调用手机模型失败:${e.message ?: e.javaClass.simpleName}\n已执行:\n${done()}"
            } ?: return "手机模型没有返回可识别的动作(回复格式不符)。\n已执行:\n${done()}"

            if (action.kind == "finish") {
                return "任务完成:${action.message ?: "(无说明)"}\n执行了 ${log.size} 步:\n${done()}"
            }
            val signature = "${action.kind}:${action.x},${action.y}:${action.text ?: ""}"
            repeats = if (signature == lastAction) repeats + 1 else 0
            lastAction = signature
            if (repeats >= 2) {
                withContext(Dispatchers.Main) { AgentProgress.markStuck() }
                return "手机模型连续 3 次给出同一个动作,判定为卡住,任务中止以免继续消耗额度。\n已执行:\n${done()}"
            }
            if (action.kind == "launch") {
                val pkg = SystemTools.resolvePackage(ctx, action.app ?: "")
                if (pkg != null && Policy.isProtectedApp(pkg)) {
                    return "【已交给用户,未继续】要打开的「${action.app}」在受保护应用名单里(支付或银行类应用必须由用户亲自操作),手机任务已停止。\n已执行:\n${done()}"
                }
            }
            val label = when (action.kind) {
                "tap" -> "点击 (${action.x}, ${action.y})"
                "long_press" -> "长按 (${action.x}, ${action.y})"
                "swipe" -> "滑动"
                "type" -> "输入文字"
                "launch" -> "打开 ${action.app ?: "应用"}"
                "back" -> "返回"
                "home" -> "回桌面"
                "wait" -> "等待"
                else -> action.kind
            }
            withContext(Dispatchers.Main) { AgentProgress.action(step, label) }
            val line = PhoneOperator.execute(ctx, action, task)
            log.add(line)
            lastWasAction = action.kind != "wait"
        }
        return "已执行 $maxSteps 步仍未完成,任务中止(避免继续消耗额度)。\n执行记录:\n${done()}"
    }

    private suspend fun call(bot: Bot, msgs: JSONArray, tools: JSONArray?): JSONObject {
        // Configuration errors (bad URL, HTTP not allowed) fail fast instead of being retried.
        ApiEndpoint.validate(bot)
        val keys = keyPool(bot)
        val cursor = keyCursors.getOrPut(bot.id + "|" + bot.baseUrl) { java.util.concurrent.atomic.AtomicInteger(0) }
        var payload = msgs
        var lastError: Exception? = null
        for (k in keys.indices) {
            val idx = Math.floorMod(cursor.get() + k, keys.size)
            var retries = 0
            keyLoop@ while (true) {
                try {
                    val out = callOnce(bot, payload, tools, keys[idx])
                    cursor.set(idx)
                    return out
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    val msg = e.message.orEmpty()
                    val action = RetryPolicy.decide(
                        message = msg,
                        isIo = e is IOException,
                        hasMoreKeys = k < keys.lastIndex,
                        retriesSoFar = retries,
                        hasImages = hasImageParts(payload)
                    )
                    when (action) {
                        RetryPolicy.Action.ROTATE_KEY -> break@keyLoop
                        RetryPolicy.Action.BACKOFF -> {
                            retries++
                            kotlinx.coroutines.delay(RetryPolicy.backoffMs(retries, RetryPolicy.httpCode(msg) == 429))
                        }
                        // The endpoint rejects image input (e.g. OpenRouter 404 "image input"): retry as text-only.
                        RetryPolicy.Action.STRIP_IMAGES -> payload = stripImageParts(payload)
                        RetryPolicy.Action.FAIL -> throw e
                    }
                }
            }
        }
        throw lastError ?: IllegalStateException("key pool exhausted")
    }

    private fun hasImageParts(msgs: JSONArray): Boolean {
        for (i in 0 until msgs.length()) {
            val content = msgs.optJSONObject(i)?.opt("content") as? JSONArray ?: continue
            for (j in 0 until content.length()) {
                if (content.optJSONObject(j)?.optString("type") == "image_url") return true
            }
        }
        return false
    }

    private fun stripImageParts(msgs: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until msgs.length()) {
            val obj = msgs.optJSONObject(i) ?: continue
            val copy = JSONObject(obj.toString())
            val content = copy.opt("content")
            if (content is JSONArray) {
                val sb = StringBuilder()
                var hadImg = false
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    when (part.optString("type")) {
                        "text" -> {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(part.optString("text"))
                        }
                        "image_url" -> hadImg = true
                    }
                }
                if (hadImg) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append("[当前上游节点不支持图像输入，已自动降级为纯文本模式；请结合文字摘要继续或更换支持视觉的节点]")
                }
                copy.put("content", sb.toString())
            }
            out.put(copy)
        }
        return out
    }

    private suspend fun callOnce(bot: Bot, msgs: JSONArray, tools: JSONArray?, apiKey: String): JSONObject {
        val baseUrl = ApiEndpoint.validate(bot)
        val body = JSONObject().put("model", bot.model).put("messages", msgs)
        if (tools != null) body.put("tools", tools)
        val effort = ModelPrefs.reasoningEffort(bot.id)
        if (effort != "auto") {
            if (baseUrl.contains("openrouter.ai", ignoreCase = true)) {
                val rObj = JSONObject().put("effort", effort)
                if (effort == "none") rObj.put("exclude", true)
                body.put("reasoning", rObj)
            } else {
                body.put("reasoning_effort", effort)
            }
        }
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()

        // Cancelling the coroutine now cancels the actual OkHttp call rather than merely
        // abandoning a blocking execute() on an IO thread.
        val t0 = System.currentTimeMillis()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val message = response.use { r ->
                            val s = r.body?.string().orEmpty()
                            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${s.take(300)}")
                            val root = JSONObject(s)
                            // v0.7.0 usage ledger: OpenAI-compatible `usage` (absent on some providers -> 0 tokens, 1 call).
                            val usage = root.optJSONObject("usage")
                            runCatching {
                                UsageLedger.record(
                                    bot.id, bot.model,
                                    usage?.optLong("prompt_tokens", 0L) ?: 0L,
                                    usage?.optLong("completion_tokens", 0L) ?: 0L,
                                    System.currentTimeMillis() - t0
                                )
                            }
                            root.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                        }
                        if (continuation.isActive) continuation.resume(message)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}

/** v0.7.0: tiny perceptual hash for "did the screen change?" checks (pure; unit-tested). */
internal object ScreenHash {
    /** Hamming distance at or below which two frames count as unchanged (tolerates a blinking cursor or clock). */
    const val SAME_THRESHOLD = 3

    /** Difference hash of a 9x8 grayscale grid (row-major, 72 values): bit = left pixel brighter than right. */
    fun dHash(gray: IntArray): Long {
        require(gray.size >= 72) { "need 9x8 values" }
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (gray[y * 9 + x] > gray[y * 9 + x + 1]) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * Central region used for change detection: the middle 80 % of the width and 70 % of the
     * height. Returns [x, y, w, h] in pixels, always inside the frame.
     */
    fun centerCrop(width: Int, height: Int, fracW: Float = 0.8f, fracH: Float = 0.7f): IntArray {
        if (width <= 0 || height <= 0) return intArrayOf(0, 0, 0, 0)
        val w = (width * fracW).toInt().coerceIn(1, width)
        val h = (height * fracH).toInt().coerceIn(1, height)
        return intArrayOf((width - w) / 2, (height - h) / 2, w, h)
    }
}
