package app.opengrok

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** OpenAI-compatible chat/completions agent loop with two tools: shell, ask_bot. */
object Agent {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
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

    private fun toolDefs() = JSONArray().apply {
        put(fn("shell", "在你自己的沙盒目录里执行 shell 命令,返回合并后的输出。",
            JSONObject().put("command", str("要执行的命令")), "command"))
        put(fn("ask_bot", "向另一个 Bot 发私信求助,返回对方的回复。",
            JSONObject().put("bot_name", str("对方的名字")).put("message", str("要说的话")),
            "bot_name", "message"))
    }

    suspend fun run(
        store: Store,
        bot: Bot,
        history: List<Msg>,
        depth: Int = 0,
        onTools: suspend (List<ToolCall>) -> Unit
    ): String {
        val others = store.bots.filter { it.id != bot.id }.joinToString("、") { it.name }
        val sys = bot.prompt + "\n\n你叫「${bot.name}」,运行在一部安卓手机上,有自己独立的沙盒目录,可用 shell 工具执行命令。" +
            (if (others.isNotEmpty()) "同伴 Bot:$others,可用 ask_bot 私信求助。" else "") +
            "群聊里其他 Bot 的发言会以 [名字] 开头。请使用用户的语言回复。"
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", sys))
        for (m in history) {
            if (m.text.isBlank() || m.role == "system") continue
            when {
                m.from == bot.id -> msgs.put(JSONObject().put("role", "assistant").put("content", m.text))
                m.from == null -> msgs.put(JSONObject().put("role", "user").put("content", m.text))
                else -> msgs.put(JSONObject().put("role", "user")
                    .put("content", "[${store.botName(m.from)}] ${m.text}"))
            }
        }
        val tools = mutableListOf<ToolCall>()
        try {
            for (step in 0 until 8) {
                val message = withContext(Dispatchers.IO) { call(bot, msgs) }
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
                    val out = runTool(store, bot, name, argStr, depth)
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
    suspend fun once(bot: Bot, system: String, user: String): String = withContext(Dispatchers.IO) {
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", system))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val m = call(bot, msgs, false)
        if (m.isNull("content")) "" else m.getString("content")
    }

    private suspend fun runTool(store: Store, bot: Bot, name: String, args: String, depth: Int): String {
        val a = try { JSONObject(args) } catch (_: Exception) { JSONObject() }
        return when (name) {
            "shell" -> {
                val cmd = a.optString("command")
                val sb = store.sandbox(bot.id)
                val why = Guard.check(cmd, sb.dir.absolutePath)
                if (why != null) {
                    withContext(Dispatchers.Main) { store.logGuard(bot.id, cmd, why) }
                    "【安全策略已拦截】$why。该命令没有执行,请换一种更安全的做法。"
                } else withContext(Dispatchers.IO) { sb.exec(cmd) }
            }
            "ask_bot" -> {
                if (depth >= 1) return "为避免循环,被求助的 Bot 不能再求助其他 Bot。"
                val target = store.bots.firstOrNull { it.name == a.optString("bot_name") && it.id != bot.id }
                    ?: return "找不到名为「${a.optString("bot_name")}」的 Bot。"
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
            else -> "未知工具:$name"
        }
    }

    private fun call(bot: Bot, msgs: JSONArray, withTools: Boolean = true): JSONObject {
        val body = JSONObject().put("model", bot.model).put("messages", msgs)
        if (withTools) body.put("tools", toolDefs())
        val req = Request.Builder()
            .url(bot.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${bot.apiKey}")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${s.take(300)}")
            return JSONObject(s).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        }
    }
}
