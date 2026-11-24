package app.opengrok

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Pure HTTP request parsing for the webhook server (unit-tested in WebhookParserTest). */
internal object WebhookParser {
    const val MAX_HEAD = 8 * 1024
    const val MAX_BODY = 32 * 1024
    private val ID_RE = Regex("[A-Za-z0-9_-]{1,64}")

    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    /** Parses the request line + headers (everything before the blank line). Header names are lower-cased. */
    fun parseHead(head: String): Request? {
        val lines = head.split("\r\n", "\n")
        val first = lines.firstOrNull()?.trim().orEmpty()
        val parts = first.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        if (!method.all { it in 'A'..'Z' }) return null
        val path = parts[1]
        if (!path.startsWith("/")) return null
        val headers = LinkedHashMap<String, String>()
        for (l in lines.drop(1)) {
            if (l.isBlank()) continue
            val i = l.indexOf(':')
            if (i <= 0) continue
            headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        return Request(method, path, headers, "")
    }

    /** Parses a complete raw request (head, blank line, body). The body is cut to Content-Length and [MAX_BODY]. */
    fun parse(raw: String): Request? {
        val sep = raw.indexOf("\r\n\r\n").let { if (it >= 0) it to 4 else raw.indexOf("\n\n") to 2 }
        val head = if (sep.first >= 0) raw.substring(0, sep.first) else raw
        val req = parseHead(head) ?: return null
        var body = if (sep.first >= 0) raw.substring(sep.first + sep.second) else ""
        val len = contentLength(req.headers)
        if (len != null && len >= 0 && body.length > len) body = body.take(len)
        if (body.length > MAX_BODY) body = body.take(MAX_BODY)
        return req.copy(body = body)
    }

    fun contentLength(headers: Map<String, String>): Int? = headers["content-length"]?.trim()?.toIntOrNull()

    /** "/hook/<id>" (optionally with a query string or trailing slash) -> id; anything else -> null. */
    fun routineId(path: String): String? {
        val p = path.substringBefore('?').substringBefore('#').trimEnd('/')
        if (!p.startsWith("/hook/")) return null
        val id = p.removePrefix("/hook/")
        return if (ID_RE.matches(id)) id else null
    }

    /** Token from "Authorization: Bearer <t>" or the "X-BotHive-Token" header. */
    fun tokenFrom(headers: Map<String, String>): String? {
        val auth = headers["authorization"]?.trim()
        if (auth != null && auth.length > 7 && auth.substring(0, 7).equals("bearer ", ignoreCase = true)) {
            return auth.substring(7).trim()
        }
        return headers["x-bothive-token"]?.trim()
    }

    /** Constant-time comparison; an empty expected token never matches. */
    fun tokenMatches(given: String?, expected: String): Boolean {
        if (given == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }
}

/**
 * v0.7.0 local webhook trigger for routines. Listens on 127.0.0.1:[port] (optionally all
 * interfaces when the user enables LAN access) and accepts
 *   POST /hook/<routineId>   Authorization: Bearer <token>   body = event text
 * Only routines whose trigger is "webhook" fire; per-routine interval and the global hourly
 * cap are enforced by [Store.triggerWebhook]. The server lives as long as the app process.
 */
object WebhookServer {
    val port: Int = 17321
    val running = mutableStateOf(false)

    private const val PREFS = "webhook"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var storeRef: Store? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var lanCached = false
    @Volatile private var authFailures = 0
    @Volatile private var authWindowStart = 0L
    private var pool: ExecutorService? = null
    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("on", false)

    fun allowLan(ctx: Context): Boolean = prefs(ctx).getBoolean("lan", false)

    fun setEnabled(ctx: Context, on: Boolean, store: Store) {
        prefs(ctx).edit().putBoolean("on", on).apply()
        if (on) start(ctx, store) else stop()
    }

    fun setAllowLan(ctx: Context, on: Boolean, store: Store) {
        prefs(ctx).edit().putBoolean("lan", on).apply()
        lanCached = on
        if (isEnabled(ctx)) {
            stop()
            start(ctx, store)
        }
    }

    fun token(ctx: Context): String {
        val p = prefs(ctx)
        val existing = p.getString("token", null)
        if (!existing.isNullOrBlank()) return existing
        return regenerateToken(ctx)
    }

    /** Creates a new random token (the old one stops working immediately) and returns it. */
    fun regenerateToken(ctx: Context): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val t = bytes.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString("token", t).commit()
        return t
    }

    /** URL a caller should POST to for [routineId]; the LAN address when LAN access is on. */
    fun urlFor(routineId: String): String {
        val host = if (lanCached) (lanAddress() ?: "127.0.0.1") else "127.0.0.1"
        return "http://$host:$port/hook/$routineId"
    }

    private fun lanAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    /** Starts the listener if it is not running. Returns true when listening. Call on Main. */
    fun start(ctx: Context, store: Store): Boolean = synchronized(lock) {
        appCtx = ctx.applicationContext
        storeRef = store
        lanCached = allowLan(ctx)
        if (server?.isClosed == false) return@synchronized true
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                val addr = if (lanCached) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")
                bind(InetSocketAddress(addr, port), 8)
            }
        } catch (e: Exception) {
            running.value = false
            return@synchronized false
        }
        server = s
        val workers = pool ?: Executors.newFixedThreadPool(2).also { pool = it }
        running.value = true
        Thread({
            try {
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: Exception) { break }
                    try {
                        workers.execute { handle(c) }
                    } catch (_: Exception) {
                        runCatching { c.close() }
                    }
                }
            } finally {
                if (server === s) {
                    server = null
                    running.value = false
                }
            }
        }, "bothive-webhook").apply { isDaemon = true; start() }
        true
    }

    fun stop() {
        synchronized(lock) {
            val s = server
            server = null
            runCatching { s?.close() }
            running.value = false
        }
    }

    private fun readRequest(input: InputStream): WebhookParser.Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        // Read the head byte by byte until CRLFCRLF (bounded).
        while (head.size() < WebhookParser.MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        if (matched != 4) return null
        val req = WebhookParser.parseHead(String(head.toByteArray(), Charsets.UTF_8)) ?: return null
        val len = (WebhookParser.contentLength(req.headers) ?: 0).coerceIn(0, WebhookParser.MAX_BODY)
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n < 0) break
            off += n
        }
        return req.copy(body = String(body, 0, off, Charsets.UTF_8))
    }

    private fun respond(c: Socket, code: Int, reason: String, json: String) {
        try {
            val bytes = json.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            val out = c.getOutputStream()
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
        }
    }

    private fun jsonMsg(ok: Boolean, msg: String): String =
        org.json.JSONObject().put("ok", ok).put("message", msg).toString()

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 5_000
            val req = readRequest(c.getInputStream())
            if (req == null) {
                respond(c, 400, "Bad Request", jsonMsg(false, "bad request"))
                return
            }
            val now = System.currentTimeMillis()
            if (now - authWindowStart > 60_000) {
                authWindowStart = now
                authFailures = 0
            }
            if (authFailures >= 20) {
                respond(c, 429, "Too Many Requests", jsonMsg(false, "too many failed attempts"))
                return
            }
            if (req.method != "POST") {
                respond(c, 405, "Method Not Allowed", jsonMsg(false, "use POST /hook/<routineId>"))
                return
            }
            val ctx = appCtx
            val store = storeRef
            if (ctx == null || store == null) {
                respond(c, 503, "Service Unavailable", jsonMsg(false, "not ready"))
                return
            }
            if (!WebhookParser.tokenMatches(WebhookParser.tokenFrom(req.headers), token(ctx))) {
                authFailures++
                respond(c, 401, "Unauthorized", jsonMsg(false, "invalid token"))
                return
            }
            val id = WebhookParser.routineId(req.path)
            if (id == null) {
                respond(c, 404, "Not Found", jsonMsg(false, "unknown path"))
                return
            }
            val result = runBlocking {
                withTimeoutOrNull(5_000) {
                    withContext(Dispatchers.Main) { store.triggerWebhook(id, req.body) }
                }
            } ?: (503 to "busy")
            val reason = when (result.first) {
                202 -> "Accepted"
                404 -> "Not Found"
                409 -> "Conflict"
                429 -> "Too Many Requests"
                else -> "Error"
            }
            respond(c, result.first, reason, jsonMsg(result.first == 202, result.second))
        } catch (_: Exception) {
        } finally {
            runCatching { c.close() }
        }
    }
}
