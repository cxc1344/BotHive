package app.opengrok

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class PcDaemonInfo(
    val name: String,
    val command: String,
    val running: Boolean,
    val pid: Int,
    val uptimeSec: Long,
    val lastLine: String
)

data class PcAuditEntry(
    val time: String,
    val action: String,
    val detail: String,
    val ok: Boolean
)

/**
 * v0.7.2 Hive Computer — Remote PC Bridge to BotHive Desktop (desktop/bothive_pc.py).
 *
 * Gives Android BotHive Agents an always-on desktop computer they can observe (PNG screenshot),
 * operate (mouse/keyboard actions), run shell commands on, and manage 24x7 background daemons on,
 * while keeping all communication authenticated by a user-owned pairing token.
 */
object HiveComputer {
    private const val PREFS = "hive_computer"
    private const val KEY_URL = "pc_url"
    private const val KEY_TOKEN = "pc_token"
    private const val KEY_ALIAS = "pc_alias"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val baseUrl = mutableStateOf("")
    val token = mutableStateOf("")
    val alias = mutableStateOf("我的主力 PC")

    val connected = mutableStateOf(false)
    val hostName = mutableStateOf("")
    val osInfo = mutableStateOf("")
    val screenInfo = mutableStateOf("")
    val desktopCapable = mutableStateOf(false)
    val uptimeSec = mutableIntStateOf(0)
    val lastError = mutableStateOf("")
    val lastShotPath = mutableStateOf("")
    val shotVersion = mutableIntStateOf(0)

    val daemons = mutableStateListOf<PcDaemonInfo>()
    val auditLogs = mutableStateListOf<PcAuditEntry>()

    private var appCtx: Context? = null

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        baseUrl.value = sp.getString(KEY_URL, "") ?: ""
        token.value = sp.getString(KEY_TOKEN, "") ?: ""
        alias.value = sp.getString(KEY_ALIAS, "我的主力 PC") ?: "我的主力 PC"
    }

    fun saveConfig(url: String, pairingToken: String, name: String) {
        val cleanUrl = url.trim().trimEnd('/')
        val normalized = if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            "http://$cleanUrl"
        } else cleanUrl
        baseUrl.value = normalized
        token.value = pairingToken.trim()
        alias.value = name.trim().ifBlank { "我的主力 PC" }
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY_URL, baseUrl.value)
            ?.putString(KEY_TOKEN, token.value)
            ?.putString(KEY_ALIAS, alias.value)
            ?.apply()
    }

    fun isConfigured(): Boolean = baseUrl.value.isNotBlank() && token.value.isNotBlank()

    private fun reqBuilder(path: String): Request.Builder {
        val u = baseUrl.value.trimEnd('/') + path
        return Request.Builder()
            .url(u)
            .header("Authorization", "Bearer ${token.value}")
            .header("X-BotHive-Client", "Android/0.7.2")
    }

    /** Connects to BotHive Desktop /api/status and refreshes host metadata, daemons & audit logs. */
    fun refreshStatus(): Boolean {
        if (!isConfigured()) {
            onMain {
                connected.value = false
                lastError.value = "请先填写 PC 端地址与配对 Token"
            }
            return false
        }
        return try {
            http.newCall(reqBuilder("/api/status").get().build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    onMain {
                        connected.value = false
                        lastError.value = "HTTP ${resp.code}: ${body.take(120)}"
                    }
                    return false
                }
                val j = JSONObject(body)
                val dList = parseDaemons(j.optJSONArray("daemons"))
                val aList = parseAudit(j.optJSONArray("audit"))
                onMain {
                    connected.value = true
                    hostName.value = j.optString("hostname", "PC")
                    osInfo.value = j.optString("os", "")
                    screenInfo.value = j.optString("screen", "")
                    desktopCapable.value = j.optBoolean("desktop_capable", false)
                    uptimeSec.intValue = j.optInt("uptime_sec", 0)
                    lastError.value = ""
                    daemons.clear(); daemons.addAll(dList)
                    auditLogs.clear(); auditLogs.addAll(aList)
                }
                true
            }
        } catch (e: Exception) {
            onMain {
                connected.value = false
                lastError.value = e.message ?: "无法连接到 BotHive Desktop"
            }
            false
        }
    }

    fun exec(command: String, timeoutSec: Int = 30): String {
        if (!isConfigured()) return "尚未绑定 PC 端，请先在「Bot 电脑」中填入 BotHive Desktop 地址与配对 Token。"
        val payload = JSONObject()
            .put("command", command)
            .put("timeout", timeoutSec.coerceIn(3, 120))
        return try {
            http.newCall(
                reqBuilder("/api/exec")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
            ).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "PC 执行失败 (HTTP ${resp.code}): ${txt.take(400)}"
                val j = JSONObject(txt)
                val code = j.optInt("exit_code", -1)
                val out = j.optString("output", "")
                val err = j.optString("error", "")
                buildString {
                    append("[PC exit=").append(code).append("]\n")
                    if (out.isNotBlank()) append(out)
                    if (err.isNotBlank()) {
                        if (out.isNotBlank()) append("\n--- stderr ---\n")
                        append(err)
                    }
                }.ifBlank { "[PC exit=$code] (无输出)" }
            }
        } catch (e: Exception) {
            "连接 PC 执行命令失败: ${e.message}"
        }
    }

    /** Captures a live PNG screenshot of the remote PC desktop and saves it to local cache. */
    fun captureScreenshot(ctx: Context? = appCtx): File? {
        if (!isConfigured() || ctx == null) return null
        return try {
            http.newCall(reqBuilder("/api/screenshot").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    onMain { lastError.value = "PC 截屏失败: ${err.take(120)}" }
                    return null
                }
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.size < 64) return null
                val out = File(ctx.cacheDir, "pc_desktop_latest.png")
                out.writeBytes(bytes)
                onMain {
                    lastShotPath.value = out.absolutePath
                    shotVersion.intValue += 1
                    lastError.value = ""
                }
                out
            }
        } catch (e: Exception) {
            onMain { lastError.value = "PC 截屏异常: ${e.message}" }
            null
        }
    }

    /** Sends a desktop mouse/keyboard action (normalized 0..1000 coordinates) to BotHive Desktop. */
    fun desktopAction(
        action: String,
        xNorm: Double? = null,
        yNorm: Double? = null,
        text: String = "",
        keys: String = "",
        delta: Int = 0
    ): String {
        if (!isConfigured()) return "尚未绑定 PC 端。"
        val payload = JSONObject().put("action", action.trim().lowercase())
        if (xNorm != null) payload.put("x", xNorm.coerceIn(0.0, 1000.0))
        if (yNorm != null) payload.put("y", yNorm.coerceIn(0.0, 1000.0))
        if (text.isNotBlank()) payload.put("text", text)
        if (keys.isNotBlank()) payload.put("keys", keys)
        if (delta != 0) payload.put("delta", delta)
        return try {
            http.newCall(
                reqBuilder("/api/desktop/action")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
            ).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) "PC 桌面操作失败 (HTTP ${resp.code}): ${txt.take(240)}"
                else JSONObject(txt).optString("message", "已执行 PC 桌面动作: $action")
            }
        } catch (e: Exception) {
            "PC 桌面操作异常: ${e.message}"
        }
    }

    fun manageDaemon(op: String, name: String = "", command: String = ""): String {
        if (!isConfigured()) return "尚未绑定 PC 端。"
        return try {
            when (op.trim().lowercase()) {
                "list", "" -> {
                    refreshStatus()
                    val list = daemons.toList()
                    if (list.isEmpty()) "PC 端当前没有运行中的 24×7 后台守护进程。"
                    else list.joinToString("\n") { d ->
                        val st = if (d.running) "运行中 PID=${d.pid}" else "已停止"
                        "• ${d.name} [$st] ${d.command.take(60)}"
                    }
                }
                "start" -> {
                    val payload = JSONObject().put("name", name).put("command", command)
                    http.newCall(
                        reqBuilder("/api/daemons/start")
                            .post(payload.toString().toRequestBody(jsonMedia))
                            .build()
                    ).execute().use { resp ->
                        val txt = resp.body?.string().orEmpty()
                        refreshStatus()
                        if (!resp.isSuccessful) "启动守护进程失败: ${txt.take(200)}"
                        else JSONObject(txt).optString("message", "已启动 PC 后台守护进程: $name")
                    }
                }
                "stop" -> {
                    val payload = JSONObject().put("name", name)
                    http.newCall(
                        reqBuilder("/api/daemons/stop")
                            .post(payload.toString().toRequestBody(jsonMedia))
                            .build()
                    ).execute().use { resp ->
                        val txt = resp.body?.string().orEmpty()
                        refreshStatus()
                        if (!resp.isSuccessful) "停止守护进程失败: ${txt.take(200)}"
                        else JSONObject(txt).optString("message", "已停止 PC 后台守护进程: $name")
                    }
                }
                else -> "不支持的 op：$op（请用 list / start / stop）"
            }
        } catch (e: Exception) {
            "PC 守护进程操作异常: ${e.message}"
        }
    }

    private fun parseDaemons(arr: JSONArray?): List<PcDaemonInfo> {
        if (arr == null) return emptyList()
        val out = mutableListOf<PcDaemonInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                PcDaemonInfo(
                    name = o.optString("name", "daemon"),
                    command = o.optString("command", ""),
                    running = o.optBoolean("running", false),
                    pid = o.optInt("pid", 0),
                    uptimeSec = o.optLong("uptime_sec", 0L),
                    lastLine = o.optString("last_line", "")
                )
            )
        }
        return out
    }

    private fun parseAudit(arr: JSONArray?): List<PcAuditEntry> {
        if (arr == null) return emptyList()
        val out = mutableListOf<PcAuditEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                PcAuditEntry(
                    time = o.optString("time", ""),
                    action = o.optString("action", ""),
                    detail = o.optString("detail", ""),
                    ok = o.optBoolean("ok", true)
                )
            )
        }
        return out
    }
}
