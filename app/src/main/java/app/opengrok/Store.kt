package app.opengrok

import android.content.Context
import android.system.Os
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class Store(private val ctx: Context) {
    val bots = mutableStateListOf<Bot>()
    val providers = mutableStateListOf<Provider>()
    /** Long-term memory entries per Bot (and the Agent), written via the remember tool. */
    val memories = HashMap<String, MutableList<String>>()
    val mcpServers = mutableStateListOf<McpServer>()
    val routines = mutableStateListOf<Routine>()
    val workflows = mutableStateListOf<Workflow>()
    val skills = mutableStateListOf<Skill>()
    val pendingApprovals = mutableStateListOf<Approval>()
    private var skillsSeeded = false

    fun saveSkill(s: Skill) {
        val i = skills.indexOfFirst { it.id == s.id }
        if (i >= 0) skills[i] = s else skills.add(s)
        save()
    }

    fun deleteSkill(id: String) {
        skills.removeAll { it.id == id }
        save()
    }

    /** Called by the agent loop when a Bot with shellApproval wants to run a command. */
    fun requestApproval(chatId: String, botId: String, command: String) {
        if (pendingApprovals.any { it.chatId == chatId && it.botId == botId && it.command == command }) return
        pendingApprovals.add(
            Approval(java.util.UUID.randomUUID().toString().take(8), chatId, botId, command, System.currentTimeMillis())
        )
        save()
    }

    /** Re-runs one Bot on an existing chat without adding a user message (approval follow-ups). */
    fun nudgeBot(chatId: String, botId: String) {
        val b = (if (botId == AGENT_ID) agent.value else bot(botId)) ?: return
        if (chatId in busy) return
        scope.launch {
            busy.add(chatId)
            try { runBotIn(chatId, b) } finally { busy.remove(chatId) }
        }
    }

    fun executeApproval(ap: Approval) {
        if (!pendingApprovals.removeAll { it.id == ap.id }) return
        save()
        scope.launch {
            val out = try {
                val sb = sandbox(ap.botId)
                val why = Guard.check(ap.command, sb.dir.absolutePath)
                if (why != null) {
                    logGuard(ap.botId, ap.command, why)
                    "【安全策略已拦截】$why"
                } else withContext(Dispatchers.IO) {
                    withSandboxLock(ap.botId) { sb.exec(ap.command) }
                }
            } catch (e: Exception) {
                "执行出错:${e.message}"
            }
            chat(ap.chatId).add(Msg(role = "bot", from = ap.botId, text = "【已批准执行的命令】${ap.command}\n$out"))
            save()
            nudgeBot(ap.chatId, ap.botId)
        }
    }

    fun rejectApproval(ap: Approval) {
        if (!pendingApprovals.removeAll { it.id == ap.id }) return
        chat(ap.chatId).add(Msg(role = "bot", from = ap.botId, text = "【用户拒绝了这条命令】${ap.command}"))
        save()
        nudgeBot(ap.chatId, ap.botId)
    }

    fun mcpServer(id: String?) = mcpServers.firstOrNull { it.id == id }

    fun saveMcpServer(s: McpServer) {
        val i = mcpServers.indexOfFirst { it.id == s.id }
        if (i >= 0) mcpServers[i] = s else mcpServers.add(s)
        Mcp.invalidate(s.id)
        save()
    }

    fun deleteMcpServer(id: String) {
        mcpServers.removeAll { it.id == id }
        Mcp.invalidate(id)
        save()
    }

    fun remember(botId: String, text: String) {
        val list = memories.getOrPut(botId) { mutableListOf() }
        list.add(text.take(500))
        while (list.size > 100) list.removeAt(0)
        save()
    }

    fun recall(botId: String): List<String> = memories[botId]?.toList() ?: emptyList()
    /** The on-device Agent (Agent mode), kept apart from the cloud Bot list. Null until configured. */
    val agent = mutableStateOf<Bot?>(null)
    /** SAF tree URI string of the folder the user granted the Agent access to. */
    val agentTreeUri = mutableStateOf<String?>(null)
    val groups = mutableStateListOf<Group>()
    val dmIds = mutableStateListOf<String>()
    val busy = mutableStateListOf<String>()
    val projects = mutableStateListOf<Project>()
    val guardLog = mutableStateListOf<GuardEntry>()
    private val lists = HashMap<String, SnapshotStateList<Msg>>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val file = File(ctx.filesDir, "state.json")
    val persistenceError = mutableStateOf<String?>(null)
    private val writeMutex = Mutex()
    private var saveRevision = 0L
    private var persistedRevision = 0L
    private val sandboxes = ConcurrentHashMap<String, Sandbox>()
    private val sandboxLocks = ConcurrentHashMap<String, Mutex>()

    init {
        load()
        if (!skillsSeeded) {
            // First run (or first run of this version): seed the built-in skill packs once.
            skills.addAll(PRESET_SKILLS.filter { p -> skills.none { it.id == p.id } })
            skillsSeeded = true
            save()
        }
        // In-process routine scheduler: fires due self-assigned tasks while the app process lives.
        scope.launch {
            while (true) {
                delay(45_000)
                runCatching { runDueRoutines() }
            }
        }
    }

    private fun ownerBot(ownerId: String): Bot? =
        if (ownerId == AGENT_ID) agent.value else bot(ownerId)

    private fun ownerChatId(ownerId: String): String =
        if (ownerId == AGENT_ID) AGENT_ID else "bot:$ownerId"

    private fun runDueRoutines() {
        val now = System.currentTimeMillis()
        val due = routines.filter { it.nextRunMs <= now }
        for (r in due) {
            val owner = ownerBot(r.ownerId) ?: continue
            val chatId = ownerChatId(r.ownerId)
            if (chatId in busy) continue
            scope.launch {
                busy.add(chatId)
                var reply = ""
                try {
                    chat(chatId).add(Msg(role = "user", text = "【例行任务】${r.task}"))
                    save()
                    reply = runBotIn(chatId, owner)
                } catch (e: Exception) {
                    reply = "执行出错:${e.message}"
                } finally {
                    busy.remove(chatId)
                }
                val i = routines.indexOfFirst { it.id == r.id }
                if (i >= 0) {
                    if (r.repeatDaily) {
                        var next = r.nextRunMs + 86_400_000L
                        while (next <= System.currentTimeMillis()) next += 86_400_000L
                        routines[i] = routines[i].copy(nextRunMs = next, lastRunMs = System.currentTimeMillis(), lastResult = reply.take(200))
                    } else {
                        routines.removeAt(i)
                    }
                    save()
                }
            }
        }
    }

    fun addRoutine(r: Routine) {
        routines.add(r)
        save()
    }

    fun deleteRoutine(id: String) {
        routines.removeAll { it.id == id }
        save()
    }

    fun saveWorkflow(w: Workflow) {
        val i = workflows.indexOfFirst { it.id == w.id }
        if (i >= 0) workflows[i] = w else workflows.add(w)
        save()
    }

    fun deleteWorkflow(id: String) {
        workflows.removeAll { it.id == id }
        lists.remove("wf:$id")
        save()
    }

    /** Runs a workflow's steps in order in its own chat ("wf:<id>"); each output feeds the next step's {prev}. */
    fun runWorkflow(wf: Workflow) {
        val chatId = "wf:${wf.id}"
        if (chatId in busy) return
        scope.launch {
            busy.add(chatId)
            try {
                var prev = ""
                val outputs = mutableListOf<String>()
                wf.steps.forEachIndexed { idx, step ->
                    val owner = ownerBot(step.botId)
                    val instruction = step.instruction.replace("{prev}", prev.take(4_000))
                    chat(chatId).add(
                        Msg(role = "user", text = "【步骤 ${idx + 1}/${wf.steps.size} · ${owner?.name ?: step.botId}】\n$instruction")
                    )
                    save()
                    val reply = if (owner == null) "(执行者不存在,跳过)" else runBotIn(chatId, owner)
                    outputs.add(reply)
                    prev = reply
                }
                val i = workflows.indexOfFirst { it.id == wf.id }
                if (i >= 0) workflows[i] = workflows[i].copy(lastOutputs = outputs, lastRunMs = System.currentTimeMillis())
                save()
            } finally {
                busy.remove(chatId)
            }
        }
    }

    fun chat(id: String): SnapshotStateList<Msg> = lists.getOrPut(id) { mutableStateListOf() }
    fun sandbox(botId: String): Sandbox = sandboxes.computeIfAbsent(botId) {
        Sandbox(ctx, it)
    }

    suspend fun <T> withSandboxLock(botId: String, block: suspend () -> T): T =
        sandboxLocks.computeIfAbsent(botId) { Mutex() }.withLock { block() }
    fun bot(id: String?) = bots.firstOrNull { it.id == id }
    fun provider(id: String?) = providers.firstOrNull { it.id == id }

    /** Effective connection for [b]: a linked provider preset wins; the Bot's own fields are the fallback. */
    fun resolved(b: Bot): Bot {
        val p = provider(b.providerId) ?: return b
        return b.copy(baseUrl = p.baseUrl, apiKey = p.apiKey, allowInsecureHttp = p.allowInsecureHttp)
    }

    fun contentResolver() = ctx.contentResolver

    fun appContext(): Context = ctx.applicationContext

    fun botName(id: String?): String =
        if (id == AGENT_ID) agent.value?.name ?: "Agent" else bot(id)?.name ?: "?"
    fun dmId(a: String, b: String) = "dm:" + listOf(a, b).sorted().joinToString(":")
    fun project(id: String) = projects.firstOrNull { it.id == id }

    fun chatList(): List<String> =
        bots.map { "bot:${it.id}" } + groups.map { "group:${it.id}" } + dmIds.toList()

    fun title(id: String): String = when {
        id == AGENT_ID -> agent.value?.name ?: "Agent"
        id.startsWith("wf:") -> workflows.firstOrNull { it.id == id.removePrefix("wf:") }?.name ?: "工作流"
        id.startsWith("bot:") -> botName(id.removePrefix("bot:"))
        id.startsWith("group:") -> groups.firstOrNull { it.id == id.removePrefix("group:") }?.name ?: "群聊"
        id.startsWith("dm:") -> id.removePrefix("dm:").split(":").joinToString(" ⇄ ") { botName(it) }
        else -> "?"
    }

    fun subtitle(id: String): String = when {
        id == AGENT_ID -> (agent.value?.model ?: "") + " · 设备 Agent"
        id.startsWith("wf:") -> "工作流运行记录"
        id.startsWith("bot:") -> {
            val b = bot(id.removePrefix("bot:"))
            (if (b?.isManager == true) "总经理 · " else "") + (b?.model ?: "")
        }
        id.startsWith("group:") -> "${groups.firstOrNull { it.id == id.removePrefix("group:") }?.memberIds?.size ?: 0} 位成员"
        else -> "Bot 私聊(只读)"
    }

    private fun ensureChat(id: String) {
        if (id.startsWith("dm:") && id !in dmIds) dmIds.add(id)
    }

    fun addDm(id: String, m: Msg) {
        ensureChat(id)
        chat(id).add(m)
        save()
    }

    fun logGuard(botId: String, cmd: String, reason: String) {
        guardLog.add(GuardEntry(System.currentTimeMillis(), botId, cmd.take(300), reason))
        while (guardLog.size > 200) guardLog.removeAt(0)
        save()
    }

    fun saveBot(b: Bot): Boolean {
        val i = bots.indexOfFirst { it.id == b.id }
        val previous = if (i >= 0) bots[i] else null
        if (i >= 0) bots[i] = b else bots.add(b)
        if (save()) return true
        // Roll back the in-memory Bot edit when encryption/serialization fails synchronously.
        if (previous == null) bots.removeAll { it.id == b.id }
        else {
            val current = bots.indexOfFirst { it.id == b.id }
            if (current >= 0) bots[current] = previous
        }
        return false
    }

    fun saveProvider(p: Provider): Boolean {
        val i = providers.indexOfFirst { it.id == p.id }
        val previous = if (i >= 0) providers[i] else null
        if (i >= 0) providers[i] = p else providers.add(p)
        if (save()) return true
        if (previous == null) providers.removeAll { it.id == p.id }
        else {
            val current = providers.indexOfFirst { it.id == p.id }
            if (current >= 0) providers[current] = previous
        }
        return false
    }

    fun deleteProvider(id: String) {
        providers.removeAll { it.id == id }
        save()
    }

    fun saveAgent(b: Bot): Boolean {
        val previous = agent.value
        agent.value = b
        if (save()) return true
        agent.value = previous
        return false
    }

    fun deleteBot(id: String) {
        bots.removeAll { it.id == id }
        for (i in groups.indices) groups[i] = groups[i].copy(memberIds = groups[i].memberIds - id)
        sandbox(id).deleteAll()
        sandboxes.remove(id)
        lists.remove("bot:$id")
        dmIds.removeAll { it.removePrefix("dm:").split(":").contains(id) }
        save()
    }

    fun addGroup(g: Group) { groups.add(g); save() }

    fun resetSandbox(botId: String) {
        scope.launch {
            withSandboxLock(botId) {
                withContext(Dispatchers.IO) { sandbox(botId).reset() }
            }
            chat("bot:$botId").add(Msg(role = "system", text = "沙盒已重置"))
            save()
        }
    }

    // ---- projects ----
    fun upd(pid: String, f: (Project) -> Project) {
        val i = projects.indexOfFirst { it.id == pid }
        if (i >= 0) { projects[i] = f(projects[i]); save() }
    }

    fun setA(pid: String, botId: String, f: (Assignment) -> Assignment) =
        upd(pid) { p -> p.copy(assignments = p.assignments.map { if (it.botId == botId) f(it) else it }) }

    fun startProject(chatId: String, text: String) {
        val m = bot(chatId.removePrefix("bot:")) ?: return
        chat(chatId).add(Msg(role = "user", text = text))
        save()
        Pipeline.start(this, m, text)
    }

    // ---- chatting ----
    /** Runs [b] in [chatId]: adds a live placeholder message (tool calls stream into it) and returns the reply. Call on Main. */
    suspend fun runBotIn(chatId: String, b: Bot, depth: Int = 0): String {
        ensureChat(chatId)
        val list = chat(chatId)
        val history = list.toList()
        list.add(Msg(role = "assistant", text = "", from = b.id))
        val idx = list.lastIndex
        val reply = try {
            Agent.run(this, b, history, depth, chatId) { tools ->
                withContext(Dispatchers.Main) { list[idx] = list[idx].copy(tools = tools) }
            }
        } catch (e: CancellationException) {
            if (list[idx].text.isBlank()) list[idx] = list[idx].copy(text = "(已停止)")
            save()
            throw e
        }
        list[idx] = list[idx].copy(text = reply)
        save()
        return reply
    }

    private val jobs = mutableMapOf<String, Job>()

    /** User pressed stop: cancel whatever this chat is currently running. */
    fun stopChat(chatId: String) {
        jobs[chatId]?.cancel()
    }

    private val textExts = listOf(".txt", ".md", ".json", ".csv", ".log", ".xml", ".html", ".kt", ".py", ".js", ".ts", ".yaml", ".yml", ".ini", ".properties")

    /**
     * Turns picked attachments into (extra message text, stored image names):
     * images are downscaled to JPEG and kept for vision models; small text files are
     * inlined into the message; anything else is copied into each target Bot's
     * sandbox uploads/ and referenced by path in the text.
     */
    private suspend fun processAttachments(targetIds: List<String>, items: List<AttachItem>): Pair<String, List<String>> =
        withContext(Dispatchers.IO) {
            val suffix = StringBuilder()
            val imgs = mutableListOf<String>()
            val attDir = File(appContext().filesDir, "attachments").apply { mkdirs() }
            for (item in items) {
                val mime = item.mime.lowercase()
                when {
                    mime.startsWith("image/") -> {
                        val jpg = downscaleToJpeg(item.bytes)
                        if (jpg != null) {
                            val name = java.util.UUID.randomUUID().toString().take(12) + ".jpg"
                            runCatching { File(attDir, name).writeBytes(jpg) }
                                .onSuccess { imgs.add(name) }
                                .onFailure { suffix.append("\n【附件图片 ${item.name} 保存失败,已忽略】") }
                        } else suffix.append("\n【附件图片 ${item.name} 解码失败,已忽略】")
                    }
                    (mime.startsWith("text/") || textExts.any { item.name.lowercase().endsWith(it) }) &&
                        item.bytes.size <= 256 * 1024 -> {
                        val content = runCatching { String(item.bytes, Charsets.UTF_8) }.getOrDefault("")
                        suffix.append("\n\n【附件:${item.name}】\n").append(content.take(20_000))
                    }
                    else -> {
                        var saved = false
                        for (tid in targetIds) {
                            val up = File(sandbox(tid).dir, "uploads").apply { mkdirs() }
                            runCatching { File(up, item.name).writeBytes(item.bytes) }.onSuccess { saved = true }
                        }
                        if (saved) suffix.append("\n【附件 ${item.name} 已存入你的工作区:/workspace/uploads/${item.name}】")
                        else suffix.append("\n【附件 ${item.name} 保存失败,已忽略】")
                    }
                }
            }
            suffix.toString() to imgs
        }

    private fun downscaleToJpeg(bytes: ByteArray): ByteArray? {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val maxDim = 1568
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(
            bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true
        ) else bmp
        val baos = java.io.ByteArrayOutputStream()
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, baos)
        return baos.toByteArray()
    }

    fun send(chatId: String, text: String, items: List<AttachItem> = emptyList()) {
        val job = scope.launch {
            busy.add(chatId)
            try {
                val targets = when {
                    chatId.startsWith("bot:") -> listOfNotNull(bot(chatId.removePrefix("bot:")))
                    chatId.startsWith("group:") ->
                        groups.firstOrNull { it.id == chatId.removePrefix("group:") }
                            ?.memberIds?.mapNotNull { bot(it) } ?: emptyList()
                    else -> emptyList()
                }
                val (suffix, imgs) = processAttachments(targets.map { it.id }, items)
                val list = chat(chatId)
                list.add(Msg(role = "user", text = text + suffix, images = imgs))
                save()
                for (b in targets) runBotIn(chatId, b)
            } finally {
                busy.remove(chatId)
                jobs.remove(chatId)
            }
        }
        jobs[chatId] = job
    }

    /** Sends a user message to the on-device Agent (chat id "agent"). */
    fun sendAgent(text: String, items: List<AttachItem> = emptyList()) {
        val a = agent.value ?: return
        val job = scope.launch {
            busy.add(AGENT_ID)
            try {
                val (suffix, imgs) = processAttachments(listOf(AGENT_ID), items)
                val list = chat(AGENT_ID)
                list.add(Msg(role = "user", text = text + suffix, images = imgs))
                save()
                runBotIn(AGENT_ID, a)
            } finally {
                busy.remove(AGENT_ID)
                jobs.remove(AGENT_ID)
            }
        }
        jobs[AGENT_ID] = job
    }

    // ---- persistence ----
    private fun msgJson(m: Msg) = JSONObject().apply {
        put("id", m.id); put("role", m.role); put("text", m.text)
        m.from?.let { put("from", it) }
        if (m.images.isNotEmpty()) put("images", JSONArray(m.images))
        put("tools", JSONArray(m.tools.map {
            JSONObject().put("id", it.id).put("name", it.name).put("args", it.args).put("output", it.output)
        }))
    }

    private fun botJson(b: Bot) = JSONObject().put("id", b.id).put("name", b.name).put("prompt", b.prompt)
        .put("baseUrl", b.baseUrl).put("apiKey", Secrets.enc(b.apiKey)).put("model", b.model)
        .put("manager", b.isManager).put("allowInsecureHttp", b.allowInsecureHttp)
        .put("providerId", b.providerId)
        .put("skillIds", JSONArray(b.skillIds))
        .put("shellApproval", b.shellApproval)

    private fun botFromJson(o: JSONObject): Bot {
        val sk = o.optJSONArray("skillIds")
        return Bot(
            o.getString("id"), o.getString("name"), o.optString("prompt"),
            o.optString("baseUrl"), Secrets.dec(o.optString("apiKey")), o.optString("model"),
            o.optBoolean("manager", false), o.optBoolean("allowInsecureHttp", false),
            o.optString("providerId"),
            if (sk == null) emptyList() else (0 until sk.length()).map { sk.getString(it) },
            o.optBoolean("shellApproval", false)
        )
    }

    fun save(): Boolean {
        val s = try {
        val o = JSONObject()
        o.put("v", 2)
        o.put("bots", JSONArray(bots.map { botJson(it) }))
        o.put("providers", JSONArray(providers.map {
            JSONObject().put("id", it.id).put("name", it.name)
                .put("baseUrl", it.baseUrl).put("apiKey", Secrets.enc(it.apiKey))
                .put("allowInsecureHttp", it.allowInsecureHttp)
        }))
        agent.value?.let { o.put("agent", botJson(it)) }
        agentTreeUri.value?.let { o.put("agentTreeUri", it) }
        o.put("memories", JSONObject().apply {
            for ((k, v) in memories) put(k, JSONArray(v))
        })
        o.put("mcpServers", JSONArray(mcpServers.map {
            JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)
                .put("headersEnc", Secrets.enc(it.headers)).put("enabled", it.enabled)
        }))
        o.put("routines", JSONArray(routines.map {
            JSONObject().put("id", it.id).put("owner", it.ownerId).put("task", it.task)
                .put("nextRunMs", it.nextRunMs).put("repeatDaily", it.repeatDaily)
                .put("createdBy", it.createdBy).put("lastRunMs", it.lastRunMs).put("lastResult", it.lastResult)
        }))
        o.put("workflows", JSONArray(workflows.map { w ->
            JSONObject().put("id", w.id).put("name", w.name)
                .put("steps", JSONArray(w.steps.map {
                    JSONObject().put("bot", it.botId).put("instruction", it.instruction)
                }))
                .put("lastOutputs", JSONArray(w.lastOutputs)).put("lastRunMs", w.lastRunMs)
        }))
        o.put("skills", JSONArray(skills.map {
            JSONObject().put("id", it.id).put("name", it.name).put("content", it.content)
        }))
        o.put("skillsSeeded", skillsSeeded)
        o.put("approvals", JSONArray(pendingApprovals.map {
            JSONObject().put("id", it.id).put("chatId", it.chatId).put("botId", it.botId)
                .put("command", it.command).put("createdMs", it.createdMs)
        }))
        o.put("groups", JSONArray(groups.map {
            JSONObject().put("id", it.id).put("name", it.name).put("members", JSONArray(it.memberIds))
        }))
        o.put("dms", JSONArray(dmIds.toList()))
        o.put("projects", JSONArray(projects.map { p ->
            JSONObject().put("id", p.id).put("manager", p.managerId).put("title", p.title)
                .put("request", p.request).put("stage", p.stage.name).put("report", p.report).put("note", p.note)
                .put("assignments", JSONArray(p.assignments.map { a ->
                    JSONObject().put("bot", a.botId).put("task", a.task).put("status", a.status)
                        .put("deliverable", a.deliverable).put("review", a.review).put("attempts", a.attempts)
                }))
        }))
        o.put("guard", JSONArray(guardLog.map {
            JSONObject().put("t", it.time).put("bot", it.botId).put("cmd", it.cmd).put("reason", it.reason)
        }))
        val chatsJson = JSONObject()
        for ((k, v) in lists) chatsJson.put(k, JSONArray(v.toList().map { msgJson(it) }))
        o.put("chats", chatsJson)
        o.toString()
        } catch (e: Exception) {
            persistenceError.value = "本地数据未保存：${e.message ?: e.javaClass.simpleName}"
            return false
        }
        val revision = ++saveRevision
        scope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                if (revision != saveRevision) return@withLock
                try {
                    val temp = File(file.parentFile, file.name + ".tmp")
                    FileOutputStream(temp).use { stream ->
                        stream.write(s.toByteArray(Charsets.UTF_8))
                        stream.fd.sync()
                    }
                    // Same-directory rename is atomic on Android/Linux filesystems.
                    Os.rename(temp.absolutePath, file.absolutePath)
                    persistedRevision = revision
                    withContext(Dispatchers.Main) { persistenceError.value = null }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        persistenceError.value = "本地数据保存失败：${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        }
        return true
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            o.optJSONArray("bots")?.let { a ->
                for (i in 0 until a.length()) bots.add(botFromJson(a.getJSONObject(i)))
            }
            o.optJSONArray("providers")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    providers.add(Provider(it.getString("id"), it.getString("name"),
                        it.optString("baseUrl"), Secrets.dec(it.optString("apiKey")),
                        it.optBoolean("allowInsecureHttp", false)))
                }
            }
            o.optJSONObject("agent")?.let { agent.value = botFromJson(it) }
            agentTreeUri.value = o.optString("agentTreeUri").ifBlank { null }
            o.optJSONObject("memories")?.let { m ->
                for (k in m.keys()) {
                    val arr = m.optJSONArray(k) ?: continue
                    memories[k] = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
                }
            }
            o.optJSONArray("mcpServers")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    mcpServers.add(McpServer(it.getString("id"), it.getString("name"), it.optString("url"),
                        Secrets.dec(it.optString("headersEnc")), it.optBoolean("enabled", true)))
                }
            }
            o.optJSONArray("routines")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    routines.add(Routine(it.getString("id"), it.optString("owner"), it.optString("task"),
                        it.optLong("nextRunMs"), it.optBoolean("repeatDaily", false),
                        it.optString("createdBy", "user"), it.optLong("lastRunMs", 0), it.optString("lastResult")))
                }
            }
            o.optJSONArray("workflows")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { w ->
                    val st = w.optJSONArray("steps")
                    val steps = if (st == null) emptyList() else (0 until st.length()).map {
                        val x = st.getJSONObject(it)
                        WStep(x.optString("bot"), x.optString("instruction"))
                    }
                    val lo = w.optJSONArray("lastOutputs")
                    workflows.add(Workflow(w.getString("id"), w.optString("name"), steps,
                        if (lo == null) emptyList() else (0 until lo.length()).map { lo.getString(it) },
                        w.optLong("lastRunMs", 0)))
                }
            }
            o.optJSONArray("skills")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    skills.add(Skill(it.getString("id"), it.optString("name"), it.optString("content")))
                }
            }
            skillsSeeded = o.optBoolean("skillsSeeded", false)
            o.optJSONArray("approvals")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    pendingApprovals.add(Approval(it.getString("id"), it.optString("chatId"), it.optString("botId"),
                        it.optString("command"), it.optLong("createdMs", 0)))
                }
            }
            o.optJSONArray("groups")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { g ->
                    val ms = g.getJSONArray("members")
                    groups.add(Group(g.getString("id"), g.getString("name"), (0 until ms.length()).map { ms.getString(it) }))
                }
            }
            o.optJSONArray("dms")?.let { a -> for (i in 0 until a.length()) dmIds.add(a.getString(i)) }
            o.optJSONArray("projects")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { p ->
                    val asg = p.optJSONArray("assignments")
                    val list = if (asg == null) emptyList() else (0 until asg.length()).map {
                        val x = asg.getJSONObject(it)
                        Assignment(x.getString("bot"), x.optString("task"), x.optString("status", "pending"),
                            x.optString("deliverable"), x.optString("review"), x.optInt("attempts", 0))
                    }
                    var stage = runCatching { Stage.valueOf(p.getString("stage")) }.getOrDefault(Stage.CANCELLED)
                    var note = p.optString("note")
                    if (stage in setOf(Stage.PLANNING, Stage.EXECUTING, Stage.REVIEWING, Stage.REPORTING)) {
                        stage = Stage.INTERRUPTED
                        note = "App 被关闭,项目中断,未自动恢复。"
                    }
                    projects.add(Project(p.getString("id"), p.getString("manager"), p.optString("title"),
                        p.optString("request"), stage, list, p.optString("report"), note))
                }
            }
            o.optJSONArray("guard")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    guardLog.add(GuardEntry(it.optLong("t"), it.optString("bot"), it.optString("cmd"), it.optString("reason")))
                }
            }
            o.optJSONObject("chats")?.let { c ->
                for (k in c.keys()) {
                    val arr = c.getJSONArray(k)
                    val l = chat(k)
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        val ts = m.optJSONArray("tools")
                        val tools = if (ts == null) emptyList() else (0 until ts.length()).map {
                            val t = ts.getJSONObject(it)
                            ToolCall(t.getString("id"), t.getString("name"), t.optString("args"), t.optString("output"))
                        }
                        val imgArr = m.optJSONArray("images")
                        val imgList = if (imgArr == null) emptyList() else (0 until imgArr.length()).map { imgArr.getString(it) }
                        l.add(Msg(m.getString("id"), m.getString("role"), m.optString("text"), tools,
                            if (m.has("from")) m.getString("from") else null, imgList))
                    }
                }
            }
        }
    }
}
