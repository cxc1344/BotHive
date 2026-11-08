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
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS)
        // Avoid HTTPS-to-HTTP redirect downgrades that could expose an Authorization header.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val JSON_MT = "application/json".toMediaType()

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
        put(fn("remember", "把一条重要事实存入你的长期记忆(跨会话保留,如用户偏好、项目约定、关键结论)。",
            JSONObject().put("text", str("要记住的内容")), "text"))
        put(fn("recall", "取回你的全部长期记忆条目。", JSONObject()))
        put(fn("schedule_task", "给自己派一个定时任务:到点后你会在自己的对话里收到这条任务并执行。delay_minutes 是多少分钟后执行;repeat_daily 为 true 时此后每天同一时间重复。",
            JSONObject().put("task", str("到点要执行的任务内容"))
                .put("delay_minutes", JSONObject().put("type", "integer").put("description", "多少分钟后首次执行"))
                .put("repeat_daily", JSONObject().put("type", "boolean").put("description", "是否每天重复")),
            "task", "delay_minutes"))
        put(fn("list_tasks", "列出你自己的全部待执行定时任务。", JSONObject()))
        put(fn("run_workflow", "按名称启动一个用户建好的工作流(步骤链),它会自己运行,你不用等结果。",
            JSONObject().put("name", str("工作流名称")), "name"))
        if (isAgent) {
            put(fn("list_dir", "列出用户授权文件夹内的目录内容。path 相对授权根目录,如 . 或 子目录名。",
                JSONObject().put("path", str("相对路径")), "path"))
            put(fn("read_file", "读取用户授权文件夹内的文本文件内容。path 相对授权根目录。",
                JSONObject().put("path", str("相对路径")), "path"))
            put(fn("write_file", "在用户授权文件夹内写入文本文件(不存在则创建,含必要的子目录)。path 相对授权根目录。",
                JSONObject().put("path", str("相对路径")).put("content", str("文件内容")), "path", "content"))
            put(fn("device_status", "查看手机状态:电量、充电、存储空间、内存、系统版本、已开机时长。", JSONObject()))
            put(fn("app_usage", "查看今天各应用的前台使用时长排行(需要用户已开启「使用情况访问」)。", JSONObject()))
            put(fn("list_apps", "列出手机上可启动的应用(名称与包名)。", JSONObject()))
            put(fn("launch_app", "打开/切换到某个应用(按应用名或包名)。",
                JSONObject().put("query", str("应用名或包名")), "query"))
            put(fn("open_url", "用浏览器打开一个网址链接。",
                JSONObject().put("url", str("完整网址,如 https://example.com")), "url"))
            put(fn("recent_notifications", "查看手机最近的通知(应用、标题、内容、时间)。需要用户已开启「通知使用权」。", JSONObject()))
            put(fn("shizuku_shell", "通过 Shizuku 以 shell 身份执行命令(需要 Shizuku 已激活并授权)。可做 input tap/swipe/keyevent(点击滑动按键)、screencap 截屏到文件、dumpsys 查看系统状态、pm/am 管理应用等系统级操作。",
                JSONObject().put("command", str("要执行的 shell 命令")), "command"))
            put(fn("screen_dump", "读取当前屏幕的界面结构(文字、位置、是否可点击/可输入),相当于看一眼屏幕。操作其他应用前先用它确认界面。", JSONObject()))
            put(fn("tap_text", "点击屏幕上包含指定文字的元素。",
                JSONObject().put("text", str("要点击的文字")), "text"))
            put(fn("tap_xy", "按坐标点击屏幕。坐标来自 screen_dump 中元素的位置。",
                JSONObject().put("x", JSONObject().put("type", "integer")).put("y", JSONObject().put("type", "integer")), "x", "y"))
            put(fn("swipe", "在屏幕上滑动(滚动页面、翻页等)。",
                JSONObject().put("x1", JSONObject().put("type", "integer")).put("y1", JSONObject().put("type", "integer"))
                    .put("x2", JSONObject().put("type", "integer")).put("y2", JSONObject().put("type", "integer")), "x1", "y1", "x2", "y2"))
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
        val skillsText = store.skills.filter { it.id in bot.skillIds }
            .joinToString("\n\n") { "## 技能:${it.name}\n${it.content}" }
        val skillSuffix = if (skillsText.isBlank()) "" else "\n\n你启用了以下技能,请遵循:\n$skillsText"
        val others = (store.bots.filter { it.id != bot.id }.map { it.name } +
            listOfNotNull(store.agent.value?.name).filter { !isAgent }).distinct().joinToString("、")
        val sys = if (isAgent) {
            bot.prompt + "\n\n你叫「${bot.name}」,是运行在用户安卓手机上的设备 Agent,有自己独立的工作目录(Ubuntu 环境),可用 shell 工具执行命令。" +
                (if (others.isNotEmpty()) "云端 Bot 同伴:$others,可用 ask_bot 私信求助或给他们派活。" else "") +
                "手机文件工具只能访问用户授权的文件夹。请使用用户的语言回复。" + skillSuffix
        } else {
            bot.prompt + "\n\n你叫「${bot.name}」,运行在一部安卓手机上,有自己独立的工作目录(Ubuntu 环境),可用 shell 工具执行命令。" +
                "工作目录不是操作系统级安全隔离,只允许读写自己的工作目录,不得尝试访问应用数据或其他 Bot 的文件。" +
                (if (others.isNotEmpty()) "同伴:$others(其中设备 Agent 在用户手机本地,能读写用户授权的手机文件夹),可用 ask_bot 私信求助。" else "") +
                "群聊里其他 Bot 的发言会以 [名字] 开头。请使用用户的语言回复。" + skillSuffix
        }
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", sys))
        for (m in history) {
            if (m.text.isBlank() || m.role == "system" || m.role == "project") continue
            when {
                m.from == bot.id -> msgs.put(JSONObject().put("role", "assistant").put("content", m.text))
                m.from == null -> msgs.put(JSONObject().put("role", "user").put("content", m.text))
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
        try {
            for (step in 0 until 8) {
                val message = withContext(Dispatchers.IO) { call(bot, msgs, allDefs) }
                val calls = message.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    val c = if (message.isNull("content")) "" else message.getString("content")
                    return c.ifBlank { "(空回复)" }
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
                    val out = runTool(store, bot, name, argStr, depth, chatId)
                    tools[idx] = tools[idx].copy(output = out)
                    onTools(tools.toList())
                    msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", out))
                }
            }
            return "(已达到最大步数 8,先停在这里)"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "出错:${e.message}"
        }
    }

    /** Single plain completion (no tools) — used by the manager pipeline and self-check. Throws on failure. */
    suspend fun once(store: Store, rawBot: Bot, system: String, user: String): String = withContext(Dispatchers.IO) {
        val bot = store.resolved(rawBot)
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", system))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val m = call(bot, msgs, null)
        if (m.isNull("content")) "" else m.getString("content")
    }

    private suspend fun runTool(store: Store, bot: Bot, name: String, args: String, depth: Int, chatId: String = ""): String {
        val a = try { JSONObject(args) } catch (_: Exception) { JSONObject() }
        if (name.startsWith("mcp_")) return Mcp.callTool(store, name, args)
        return when (name) {
            "shell" -> {
                val cmd = a.optString("command")
                val sb = store.sandbox(bot.id)
                val why = Guard.check(cmd, sb.dir.absolutePath)
                if (why != null) {
                    withContext(Dispatchers.Main) { store.logGuard(bot.id, cmd, why) }
                    "【安全策略已拦截】$why。该命令没有执行,请换一种更安全的做法。"
                } else if (bot.shellApproval && chatId.isNotEmpty()) {
                    withContext(Dispatchers.Main) { store.requestApproval(chatId, bot.id, cmd) }
                    "【等待用户批准】这条命令已提交到对话里等用户点「执行」。本轮不要重复提交同一条命令,请先用一句话告诉用户:你在等批准、这条命令打算做什么。用户批准并执行后,结果会回到对话里,你再继续。"
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
                val reply = run(store, target, hist, depth + 1) { }
                withContext(Dispatchers.Main) {
                    store.addDm(dm, Msg(role = "assistant", text = reply, from = target.id))
                }
                reply
            }
            "list_dir", "read_file", "write_file" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                when (name) {
                    "list_dir" -> DeviceFiles.listDir(store, a.optString("path"))
                    "read_file" -> DeviceFiles.readFile(store, a.optString("path"))
                    else -> DeviceFiles.writeFile(store, a.optString("path"), a.optString("content"))
                }
            }
            "device_status", "app_usage", "list_apps", "launch_app" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                val ctx = store.appContext()
                when (name) {
                    "device_status" -> SystemTools.deviceStatus(ctx)
                    "app_usage" -> SystemTools.appUsage(ctx)
                    "list_apps" -> SystemTools.listApps(ctx)
                    else -> SystemTools.launchApp(ctx, a.optString("query"))
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
                if (text.isBlank()) "内容为空,未保存。"
                else {
                    withContext(Dispatchers.Main) { store.remember(bot.id, text) }
                    "已存入长期记忆。"
                }
            }
            "recall" -> {
                val list = store.recall(bot.id)
                if (list.isEmpty()) "(长期记忆为空)" else list.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
            }
            "schedule_task" -> {
                val task = a.optString("task").trim()
                val delayMin = a.optInt("delay_minutes", 0)
                if (task.isBlank()) "任务内容为空,未创建。"
                else if (delayMin < 1) "delay_minutes 至少为 1。"
                else {
                    val repeat = a.optBoolean("repeat_daily", false)
                    val next = System.currentTimeMillis() + delayMin * 60_000L
                    val id = java.util.UUID.randomUUID().toString().take(8)
                    withContext(Dispatchers.Main) {
                        store.addRoutine(Routine(id, bot.id, task, next, repeat, createdBy = bot.name))
                    }
                    val when_ = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(next))
                    "已创建定时任务:$when_ 执行「$task」" + if (repeat) ",此后每天同一时间重复。" else "。"
                }
            }
            "list_tasks" -> {
                val mine = store.routines.filter { it.ownerId == bot.id }
                if (mine.isEmpty()) "(你没有待执行的定时任务)"
                else mine.joinToString("\n") { r ->
                    val when_ = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(r.nextRunMs))
                    "- $when_${if (r.repeatDaily) "(每天)" else ""}:${r.task}"
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
            "shizuku_shell" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                ShizukuTools.shell(a.optString("command"))
            }
            "screen_dump", "tap_text", "tap_xy", "swipe", "input_text", "press_key" -> {
                if (bot.id != AGENT_ID) return "该工具只有设备 Agent 可用。"
                when (name) {
                    "screen_dump" -> A11y.dumpScreen()
                    "tap_text" -> A11y.tapText(a.optString("text"))
                    "tap_xy" -> A11y.tap(a.optInt("x"), a.optInt("y"))
                    "swipe" -> A11y.swipe(a.optInt("x1"), a.optInt("y1"), a.optInt("x2"), a.optInt("y2"))
                    "input_text" -> A11y.inputText(a.optString("text"))
                    else -> A11y.pressKey(a.optString("key"))
                }
            }
            else -> "未知工具:$name"
        }
    }

    private suspend fun call(bot: Bot, msgs: JSONArray, tools: JSONArray?): JSONObject {
        val baseUrl = ApiEndpoint.validate(bot)
        val body = JSONObject().put("model", bot.model).put("messages", msgs)
        if (tools != null) body.put("tools", tools)
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer ${bot.apiKey}")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()

        // Cancelling the coroutine now cancels the actual OkHttp call rather than merely
        // abandoning a blocking execute() on an IO thread.
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
                            JSONObject(s).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
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
