package app.opengrok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class McpTool(val exposed: String, val serverId: String, val realName: String, val description: String, val schema: JSONObject)

/**
 * Minimal MCP client (Model Context Protocol, Streamable HTTP transport): initialize →
 * tools/list → tools/call over JSON-RPC POSTs. Servers are configured in Settings; their
 * tools are merged into every Bot/Agent run with namespaced names (mcp_<server>_<tool>).
 * Responses may be plain JSON or SSE ("data:" lines) — both are handled.
 */
object Mcp {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val JSON_MT = "application/json".toMediaType()

    private class Session {
        var sessionId: String? = null
        var tools: List<McpTool> = emptyList()
        var ready: Boolean = false
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    @Volatile private var idSeq = 100

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)

    private fun parseHeaders(raw: String): List<Pair<String, String>> =
        raw.lineSequence().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.filter { it.first.isNotEmpty() }.toList()

    private fun rpc(server: McpServer, session: Session, method: String, params: JSONObject?, notification: Boolean = false): JSONObject? {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
        if (!notification) payload.put("id", ++idSeq)
        if (params != null) payload.put("params", params)
        val builder = Request.Builder()
            .url(server.url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MT))
        for ((k, v) in parseHeaders(server.headers)) builder.header(k, v)
        session.sessionId?.let { builder.header("Mcp-Session-Id", it) }
        http.newCall(builder.build()).execute().use { r ->
            r.header("Mcp-Session-Id")?.let { session.sessionId = it }
            val body = r.body?.string().orEmpty()
            if (notification) return null
            if (!r.isSuccessful) throw Exception("MCP HTTP ${r.code}:${body.take(200)}")
            return parseRpcBody(body)
        }
    }

    private fun parseRpcBody(body: String): JSONObject {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        // SSE: take the last data: payload that parses as JSON.
        var found: JSONObject? = null
        for (line in trimmed.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("data:")) {
                try { found = JSONObject(t.substring(5).trim()) } catch (_: Exception) {}
            }
        }
        return found ?: throw Exception("MCP 响应无法解析:${body.take(160)}")
    }

    private suspend fun ensureReady(server: McpServer): Session = withContext(Dispatchers.IO) {
        val session = sessions.getOrPut(server.id) { Session() }
        if (session.ready) return@withContext session
        locks.getOrPut(server.id) { Mutex() }.withLock {
            if (session.ready) return@withLock
            val initParams = JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "BotHive").put("version", "0.5.0"))
            val resp = rpc(server, session, "initialize", initParams)
            if (resp != null && resp.has("error")) {
                throw Exception("MCP initialize 被拒:${resp.getJSONObject("error").optString("message")}")
            }
            try { rpc(server, session, "notifications/initialized", null, notification = true) } catch (_: Exception) {}
            val listResp = rpc(server, session, "tools/list", JSONObject())
            val result = listResp?.optJSONObject("result") ?: throw Exception("MCP tools/list 无 result")
            val arr = result.optJSONArray("tools") ?: JSONArray()
            val tools = (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                val real = t.optString("name")
                McpTool(
                    exposed = "mcp_${sanitize(server.id)}_${sanitize(real)}",
                    serverId = server.id,
                    realName = real,
                    description = t.optString("description"),
                    schema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
                )
            }
            session.tools = tools
            session.ready = true
        }
        session
    }

    /** Drops a server's cached MCP session/tool list (after edits or failures). */
    fun invalidate(serverId: String) {
        sessions.remove(serverId)
    }

    /** Function definitions for all enabled servers; a failing server contributes nothing (and never blocks the run). */
    suspend fun toolDefs(store: Store): List<McpTool> {
        val out = mutableListOf<McpTool>()
        for (server in store.mcpServers.filter { it.enabled }) {
            try {
                out.addAll(ensureReady(server).tools)
            } catch (_: Exception) {
                sessions.remove(server.id)
            }
        }
        return out
    }

    fun defsJson(tools: List<McpTool>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            arr.put(
                JSONObject().put("type", "function").put(
                    "function", JSONObject().put("name", t.exposed)
                        .put("description", t.description.ifBlank { t.realName })
                        .put("parameters", t.schema)
                )
            )
        }
        return arr
    }

    /** Settings-page connectivity test: returns the server's tool names. Throws with a readable message. */
    suspend fun testList(server: McpServer): List<String> {
        sessions.remove(server.id)
        return ensureReady(server).tools.map { it.realName }
    }

    suspend fun callTool(store: Store, exposed: String, argsJson: String): String {
        val all = sessions.values.flatMap { it.tools }
        val tool = all.firstOrNull { it.exposed == exposed }
            ?: return "MCP 工具 $exposed 不可用(服务未连接或已改名)。"
        val server = store.mcpServer(tool.serverId) ?: return "MCP 服务不存在。"
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        return try {
            withContext(Dispatchers.IO) {
                val session = ensureReady(server)
                val params = JSONObject().put("name", tool.realName).put("arguments", args)
                val resp = rpc(server, session, "tools/call", params)
                    ?: throw Exception("无响应")
                if (resp.has("error")) {
                    "MCP 调用失败:" + resp.getJSONObject("error").optString("message")
                } else {
                    val result = resp.optJSONObject("result")
                    val content = result?.optJSONArray("content")
                    val text = if (content == null) "(无内容)" else (0 until content.length()).mapNotNull { i ->
                        val c = content.getJSONObject(i)
                        when (c.optString("type")) {
                            "text" -> c.optString("text")
                            else -> "〔${c.optString("type")} 内容已省略〕"
                        }
                    }.joinToString("\n")
                    val isErr = result?.optBoolean("isError", false) == true
                    (if (isErr) "MCP 工具报错:" else "") + text.ifBlank { "(空结果)" }
                }
            }
        } catch (e: Exception) {
            sessions.remove(server.id)
            "MCP 调用失败:${e.message}"
        }
    }
}
