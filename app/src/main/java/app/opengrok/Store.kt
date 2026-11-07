package app.opengrok

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Store(private val ctx: Context) {
    val bots = mutableStateListOf<Bot>()
    val groups = mutableStateListOf<Group>()
    val dmIds = mutableStateListOf<String>()
    val busy = mutableStateListOf<String>()
    val projects = mutableStateListOf<Project>()
    val guardLog = mutableStateListOf<GuardEntry>()
    private val lists = HashMap<String, SnapshotStateList<Msg>>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val file = File(ctx.filesDir, "state.json")

    init { load() }

    fun chat(id: String): SnapshotStateList<Msg> = lists.getOrPut(id) { mutableStateListOf() }
    fun sandbox(botId: String) = Sandbox(File(ctx.filesDir, "sandbox/$botId"))
    fun bot(id: String?) = bots.firstOrNull { it.id == id }
    fun botName(id: String?) = bot(id)?.name ?: "?"
    fun dmId(a: String, b: String) = "dm:" + listOf(a, b).sorted().joinToString(":")
    fun project(id: String) = projects.firstOrNull { it.id == id }

    fun chatList(): List<String> =
        bots.map { "bot:${it.id}" } + groups.map { "group:${it.id}" } + dmIds.toList()

    fun title(id: String): String = when {
        id.startsWith("bot:") -> botName(id.removePrefix("bot:"))
        id.startsWith("group:") -> groups.firstOrNull { it.id == id.removePrefix("group:") }?.name ?: "群聊"
        id.startsWith("dm:") -> id.removePrefix("dm:").split(":").joinToString(" ⇄ ") { botName(it) }
        else -> "?"
    }

    fun subtitle(id: String): String = when {
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

    fun saveBot(b: Bot) {
        val i = bots.indexOfFirst { it.id == b.id }
        if (i >= 0) bots[i] = b else bots.add(b)
        save()
    }

    fun deleteBot(id: String) {
        bots.removeAll { it.id == id }
        for (i in groups.indices) groups[i] = groups[i].copy(memberIds = groups[i].memberIds - id)
        sandbox(id).dir.deleteRecursively()
        lists.remove("bot:$id")
        dmIds.removeAll { it.removePrefix("dm:").split(":").contains(id) }
        save()
    }

    fun addGroup(g: Group) { groups.add(g); save() }

    fun resetSandbox(botId: String) {
        sandbox(botId).reset()
        chat("bot:$botId").add(Msg(role = "system", text = "沙盒已重置"))
        save()
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
        val reply = Agent.run(this, b, history, depth) { tools ->
            withContext(Dispatchers.Main) { list[idx] = list[idx].copy(tools = tools) }
        }
        list[idx] = list[idx].copy(text = reply)
        save()
        return reply
    }

    fun send(chatId: String, text: String) {
        val list = chat(chatId)
        list.add(Msg(role = "user", text = text))
        save()
        scope.launch {
            busy.add(chatId)
            try {
                val targets = when {
                    chatId.startsWith("bot:") -> listOfNotNull(bot(chatId.removePrefix("bot:")))
                    chatId.startsWith("group:") ->
                        groups.firstOrNull { it.id == chatId.removePrefix("group:") }
                            ?.memberIds?.mapNotNull { bot(it) } ?: emptyList()
                    else -> emptyList()
                }
                for (b in targets) runBotIn(chatId, b)
            } finally {
                busy.remove(chatId)
            }
        }
    }

    // ---- persistence ----
    private fun msgJson(m: Msg) = JSONObject().apply {
        put("id", m.id); put("role", m.role); put("text", m.text)
        m.from?.let { put("from", it) }
        put("tools", JSONArray(m.tools.map {
            JSONObject().put("id", it.id).put("name", it.name).put("args", it.args).put("output", it.output)
        }))
    }

    fun save() {
        val o = JSONObject()
        o.put("v", 2)
        o.put("bots", JSONArray(bots.map {
            JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt)
                .put("baseUrl", it.baseUrl).put("apiKey", Secrets.enc(it.apiKey)).put("model", it.model)
                .put("manager", it.isManager)
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
        val s = o.toString()
        scope.launch(Dispatchers.IO) { runCatching { file.writeText(s) } }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            o.optJSONArray("bots")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    bots.add(Bot(it.getString("id"), it.getString("name"), it.optString("prompt"),
                        it.optString("baseUrl"), Secrets.dec(it.optString("apiKey")), it.optString("model"),
                        it.optBoolean("manager", false)))
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
                        l.add(Msg(m.getString("id"), m.getString("role"), m.optString("text"), tools,
                            if (m.has("from")) m.getString("from") else null))
                    }
                }
            }
        }
    }
}
