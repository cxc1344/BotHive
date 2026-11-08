package app.opengrok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SelfCheck {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Each result: (passed, message). */
    suspend fun run(store: Store, bot: Bot): List<Pair<Boolean, String>> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Pair<Boolean, String>>()
        if (bot.apiKey.isBlank()) out.add(false to "未填写 API Key")

        try {
            val req = Request.Builder().url(ApiEndpoint.validate(bot) + "/models")
                .header("Authorization", "Bearer ${bot.apiKey}").build()
            val t0 = System.currentTimeMillis()
            http.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                val ms = System.currentTimeMillis() - t0
                if (!r.isSuccessful) {
                    out.add(false to "/models 返回 HTTP ${r.code}(${ms}ms):${s.take(120)}")
                } else {
                    val ids = try {
                        val d = JSONObject(s).getJSONArray("data")
                        (0 until d.length()).map { d.getJSONObject(it).optString("id") }
                    } catch (_: Exception) { emptyList() }
                    when {
                        ids.isEmpty() -> out.add(true to "/models 连通(${ms}ms),但没解析到模型列表")
                        bot.model in ids -> out.add(true to "/models 连通(${ms}ms),共 ${ids.size} 个模型,包含「${bot.model}」")
                        else -> out.add(false to "/models 连通(${ms}ms),共 ${ids.size} 个模型,但没有「${bot.model}」(部分服务不返回完整列表)")
                    }
                }
            }
        } catch (e: Exception) {
            out.add(false to "/models 请求失败:${e.message}")
        }

        try {
            val t0 = System.currentTimeMillis()
            val reply = Agent.once(bot, "你是连通性测试。", "只回复 ok")
            out.add(true to "对话接口可用(${System.currentTimeMillis() - t0}ms),回复:${reply.take(30)}")
        } catch (e: Exception) {
            out.add(false to "对话接口失败:${e.message?.take(160)}")
        }

        val r = store.sandbox(bot.id).exec("echo ok && pwd && uname -m && cat /etc/alpine-release")
        out.add((r.contains("ok") && r.contains("[exit 0]")) to "PRoot/Alpine:${if (r.contains("[exit 0]")) "可执行" else "异常"} — ${r.replace("\n", " ").take(100)}")

        out.add(Secrets.isKeystoreWorking() to if (Secrets.isKeystoreWorking()) "Key 存储:Keystore 加密正常" else "Key 存储:Keystore 不可用,不会明文保存 API Key")
        out
    }
}
