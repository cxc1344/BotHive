package app.opengrok

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.compose.runtime.mutableStateOf

/**
 * Configuration for an optional **dedicated phone-operation model** (the AutoGLM-style approach).
 *
 * Background: AutoGLM-Phone is a vision-language model trained specifically to drive a phone — you
 * send it a screenshot plus a task and it answers with a single action (`do(action="Tap", ...)`).
 * That is a different contract from a general chat model calling our tools, and it is noticeably
 * better at screen work.
 *
 * Operit wires this to Zhipu/GLM only. This project deliberately does not: [baseUrl], [apiKey] and
 * [model] are free-form, so any OpenAI-compatible endpoint works — Zhipu, OpenRouter, a local
 * vLLM server, or a self-hosted AutoGLM. If the endpoint speaks the AutoGLM action protocol we use
 * it directly; otherwise we fall back to the tool-calling loop, so a misconfigured model degrades
 * instead of failing.
 */
object PhoneModel {
    /** Whether the user turned the dedicated phone model on. */
    val enabled = mutableStateOf(false)
    val baseUrl = mutableStateOf("")
    val apiKey = mutableStateOf("")
    val model = mutableStateOf("")
    /** Allow plain HTTP (self-hosted / LAN endpoints). */
    val allowHttp = mutableStateOf(false)
    /** Last configuration error, surfaced in Settings. */
    val lastError = mutableStateOf<String?>(null)

    private const val PREFS = "phone_model"
    private const val K_ENABLED = "enabled"
    private const val K_BASE = "baseUrl"
    private const val K_KEY = "apiKey"
    private const val K_MODEL = "model"
    private const val K_HTTP = "allowHttp"

    /** Well-known endpoints so the user does not have to memorise base URLs. */
    val presets: List<Pair<String, String>> = listOf(
        "智谱 GLM(AutoGLM)" to "https://open.bigmodel.cn/api/paas/v4",
        "OpenRouter" to "https://openrouter.ai/api/v1",
        "OpenAI" to "https://api.openai.com/v1",
        "DeepSeek" to "https://api.deepseek.com/v1",
        "阿里云百炼" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "月之暗面 Kimi" to "https://api.moonshot.cn/v1",
        "硅基流动" to "https://api.siliconflow.cn/v1",
        "本地 vLLM / 自建" to "http://127.0.0.1:8000/v1"
    )

    /** Suggested model ids per preset, purely as a hint in the UI. */
    val modelHints: Map<String, String> = mapOf(
        "智谱 GLM(AutoGLM)" to "autoglm-phone-9b-multilingual",
        "OpenRouter" to "zhipu/glm-4v",
        "OpenAI" to "gpt-4o",
        "DeepSeek" to "deepseek-chat",
        "阿里云百炼" to "qwen-vl-max",
        "月之暗面 Kimi" to "moonshot-v1-8k-vision-preview",
        "硅基流动" to "Qwen/Qwen2-VL-72B-Instruct",
        "本地 vLLM / 自建" to "autoglm-phone-9b-multilingual"
    )

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled.value = p.getBoolean(K_ENABLED, false)
        baseUrl.value = p.getString(K_BASE, "") ?: ""
        apiKey.value = Secrets.dec(p.getString(K_KEY, "") ?: "")
        model.value = p.getString(K_MODEL, "") ?: ""
        allowHttp.value = p.getBoolean(K_HTTP, false)
    }

    fun save(
        context: Context,
        on: Boolean,
        url: String,
        key: String,
        mdl: String,
        http: Boolean
    ): Boolean {
        val urlErr = validate(url, http)
        if (on && urlErr != null) {
            lastError.value = urlErr
            return false
        }
        enabled.value = on
        baseUrl.value = url.trim().trimEnd('/')
        apiKey.value = key.trim()
        model.value = mdl.trim()
        allowHttp.value = http
        lastError.value = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ENABLED, on)
            .putString(K_BASE, baseUrl.value)
            .putString(K_KEY, Secrets.enc(apiKey.value))
            .putString(K_MODEL, model.value)
            .putBoolean(K_HTTP, http)
            .apply()
        return true
    }

    /** Returns null when the URL is acceptable, else a reason. Mirrors the Bot connection rules. */
    fun validate(url: String, http: Boolean): String? {
        val u = url.trim()
        if (u.isEmpty()) return "Base URL 不能为空"
        val parsed = u.toHttpUrlOrNull() ?: return "Base URL 格式无效"
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return "Base URL 不允许包含账号密码"
        }
        if (!parsed.isHttps && !(parsed.scheme == "http" && http)) {
            return "默认只允许 HTTPS;连接可信的本地或内网服务请勾选「允许 HTTP」"
        }
        return null
    }

    /** True when the model is on and usable. */
    fun ready(): Boolean = enabled.value && baseUrl.value.isNotBlank() && model.value.isNotBlank()

    /** One-line status for tool output. */
    fun describe(): String = when {
        !enabled.value -> "未启用专用手机模型(使用当前对话模型 + 工具调用)"
        baseUrl.value.isBlank() || model.value.isBlank() ->
            "已启用但未配置完整(需要 Base URL 与模型名)"
        else -> "已启用:${model.value} @ ${baseUrl.value}"
    }
}
