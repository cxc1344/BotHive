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

    private fun toolDefs() = JSONArray().apply {
        put(fn("shell", "在你的 Bot 工作目录中执行命令；该目录不是 OS 级权限隔离边界。",
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
        val sys = bot.prompt + "\n\n你叫「${bot.name}」,运行在一部安卓手机上,有自己独立的工作目录,可用 shell 工具执行命令。" +
            "工作目录不是操作系统级安全隔离,只允许读写自己的工作目录,不得尝试访问应用数据或其他 Bot 的文件。" +
            (if (others.isNotEmpty()) "同伴 Bot:$others,可用 ask_bot 私信求助。" else "") +
            "群聊里其他 Bot 的发言会以 [名字] 开头。请使用用户的语言回复。"
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
                } else store.withSandboxLock(bot.id) {
                    runInterruptible(Dispatchers.IO) { sb.exec(cmd) }
                }
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

    private suspend fun call(bot: Bot, msgs: JSONArray, withTools: Boolean = true): JSONObject {
        val baseUrl = ApiEndpoint.validate(bot)
        val body = JSONObject().put("model", bot.model).put("messages", msgs)
        if (withTools) body.put("tools", toolDefs())
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
