package app.opengrok

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AutoGLM-style "look once, act once" phone operator.
 *
 * The contract, which is what makes this different from the normal tool loop: the model is given
 * one screenshot plus the task history, and must answer with exactly one action. We execute it,
 * take a new screenshot, and ask again. Models trained for this (AutoGLM-Phone and friends) are far
 * more reliable at screen work than a general chat model improvising tool calls, and each step is
 * one round trip instead of a free-form exploration.
 *
 * The endpoint is **not** hard-wired to Zhipu. Anything OpenAI-compatible works; if a model does not
 * emit the action syntax we simply fail that step with a clear message rather than looping.
 *
 * Action vocabulary mirrors the phone-agent convention:
 *   do(action="Tap", element=[x,y])          coordinates on a 0-1000 grid
 *   do(action="Long Press", element=[x,y])
 *   do(action="Swipe", start=[x,y], end=[x,y])
 *   do(action="Type", text="...")
 *   do(action="Launch", app="...")
 *   do(action="Back") / do(action="Home") / do(action="Wait")
 *   finish(message="...")
 */
object PhoneOperator {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val JSON_MT = "application/json".toMediaType()

    /** One parsed action from the model. */
    data class Action(
        val kind: String,
        val x: Int? = null,
        val y: Int? = null,
        val x2: Int? = null,
        val y2: Int? = null,
        val text: String? = null,
        val app: String? = null,
        val message: String? = null
    )

    private val SYSTEM = """
你是手机操作专家。你会收到一张当前屏幕的截图和任务,必须只输出**一个**下一步动作,不要解释,不要输出多个动作。

动作格式(坐标是 0-1000 的相对位置,左上角 (0,0),右下角 (1000,1000)):
- 点击:      do(action="Tap", element=[x,y])
- 长按:      do(action="Long Press", element=[x,y])
- 滑动:      do(action="Swipe", start=[x,y], end=[x,y])
- 输入文字:  do(action="Type", text="要输入的内容")
- 打开应用:  do(action="Launch", app="应用名")
- 返回:      do(action="Back")
- 回桌面:    do(action="Home")
- 等待加载:  do(action="Wait")
- 任务完成:  finish(message="给用户的简短结论")

规则:
1. 每次只输出一个动作。
2. 先看清截图再决定,不要猜坐标。
3. 如果当前界面已经满足任务目标,直接 finish。
4. 输入文字前必须先点击输入框。
5. 如果界面是黑屏,说明该页面禁止截屏,直接 finish 并说明需要用户切回主屏模式。
""".trimIndent()

    /** Parses the model's reply into an [Action]. Returns null when nothing usable was found. */
    fun parse(reply: String): Action? {
        val t = reply.trim()
        // finish(...)
        Regex("""finish\s*\(\s*message\s*=\s*"([^"]*)"\s*\)""").find(t)?.let {
            return Action("finish", message = it.groupValues[1])
        }
        Regex("""finish\s*\(\s*message\s*=\s*'([^']*)'\s*\)""").find(t)?.let {
            return Action("finish", message = it.groupValues[1])
        }
        // Note: built with escaped quotes rather than a raw string — a raw string cannot end with a
        // quote character, which is what the original `"""..."` form did.
        val kind = Regex("do\\s*\\(\\s*action\\s*=\\s*\"([^\"]+)\"").find(t)
            ?.groupValues?.get(1)?.trim()
            ?: return null

        fun pair(name: String): Pair<Int, Int>? {
            val m = Regex("""$name\s*=\s*\[\s*(-?\d+)\s*,\s*(-?\d+)\s*]""").find(t) ?: return null
            return m.groupValues[1].toIntOrNull()?.let { a ->
                m.groupValues[2].toIntOrNull()?.let { b -> a to b }
            }
        }
        fun quoted(name: String): String? =
            Regex("$name\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(t)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?: Regex("$name\\s*=\\s*'([^']*)'").find(t)?.groupValues?.get(1)

        return when (kind.lowercase()) {
            "tap", "click" -> pair("element")?.let { Action("tap", it.first, it.second) }
            "long press", "longpress" -> pair("element")?.let { Action("long_press", it.first, it.second) }
            "swipe", "scroll" -> {
                val s = pair("start"); val e = pair("end")
                if (s != null && e != null) Action("swipe", s.first, s.second, e.first, e.second) else null
            }
            "type" -> quoted("text")?.let { Action("type", text = it) }
            "launch", "open" -> quoted("app")?.let { Action("launch", app = it) }
            "back" -> Action("back")
            "home" -> Action("home")
            "wait" -> Action("wait")
            else -> null
        }
    }

    /**
     * Asks the model for one action.
     *
     * @param pngBase64 screenshot of the current screen
     * @param history previous steps, oldest first, so the model does not repeat itself
     */
    suspend fun nextAction(
        context: Context,
        pngBase64: String,
        task: String,
        history: List<String>
    ): Action? = withContext(Dispatchers.IO) {
        if (!PhoneModel.ready()) return@withContext null
        val cfg = resolved()
        val url = cfg.first
        val key = cfg.second
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", SYSTEM))
        val userParts = JSONArray()
        val historyText = if (history.isEmpty()) "(还没有执行任何动作)"
        else history.takeLast(8).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        userParts.put(JSONObject().put("type", "text").put("text",
            "任务:$task\n\n已执行的动作:\n$historyText\n\n请看当前截图,给出下一个动作。"))
        userParts.put(JSONObject().put("type", "image_url").put("image_url",
            JSONObject().put("url", "data:image/png;base64,$pngBase64")))
        msgs.put(JSONObject().put("role", "user").put("content", userParts))

        val body = JSONObject()
            .put("model", PhoneModel.model.value)
            .put("messages", msgs)
            .put("temperature", 0.0)
            .put("max_tokens", 256)

        val req = Request.Builder()
            .url("$url/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()

        val reply: String = suspendCancellableCoroutine { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use { r ->
                            val s = r.body?.string().orEmpty()
                            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${s.take(300)}")
                            s
                        }
                        val msg = JSONObject(text).getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message")
                        val content = if (msg.isNull("content")) "" else msg.getString("content")
                        if (cont.isActive) cont.resume(content)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }
        parse(reply)
    }

    private fun resolved(): Pair<String, String> {
        val base = PhoneModel.baseUrl.value.trim().trimEnd('/')
        return base to PhoneModel.apiKey.value.trim()
    }

    /** Executes one action against the virtual display. Returns a short log line for the history. */
    suspend fun execute(context: Context, a: Action, task: String): String {
        // Read the display id per action: the display can be rebuilt between steps.
        val vid = VirtualScreen.displayId.value
        if (vid < 0) return "虚拟屏已失效,动作未执行(${a.kind})"
        fun px(x: Int?, y: Int?): Pair<Int, Int>? {
            if (x == null || y == null) return null
            return VirtualScreen.toPixels(x, y)
        }
        return when (a.kind) {
            "tap" -> px(a.x, a.y)?.let { p ->
                ShizukuTools.tapOnDisplay(vid, p.first, p.second).let { "点击 (${a.x},${a.y})" }
            } ?: "点击失败:坐标缺失"
            "long_press" -> px(a.x, a.y)?.let { p ->
                ShizukuTools.longPressOnDisplay(vid, p.first, p.second, 700).let { "长按 (${a.x},${a.y})" }
            } ?: "长按失败:坐标缺失"
            "swipe" -> {
                val s = px(a.x, a.y); val e = px(a.x2, a.y2)
                if (s != null && e != null) {
                    ShizukuTools.swipeOnDisplay(vid, s.first, s.second, e.first, e.second, 300)
                    "滑动 (${a.x},${a.y})→(${a.x2},${a.y2})"
                } else "滑动失败:坐标缺失"
            }
            "type" -> ShizukuTools.textOnDisplay(vid, a.text ?: "").let { "输入文字" }
            "launch" -> {
                val pkg = SystemTools.resolvePackage(context, a.app ?: "")
                if (pkg == null) "打开应用失败:找不到「${a.app}」"
                else {
                    val out = ShizukuTools.startAppOnDisplay(vid, pkg)
                    if (out.contains("[exit 0]")) "打开应用 $pkg" else "打开应用失败"
                }
            }
            "back" -> ShizukuTools.keyOnDisplay(vid, 4).let { "返回" }
            "home" -> ShizukuTools.keyOnDisplay(vid, 3).let { "回桌面" }
            "wait" -> { delay(1_200); "等待" }
            "finish" -> a.message ?: "完成"
            else -> "未识别动作:${a.kind}"
        }
    }
}
