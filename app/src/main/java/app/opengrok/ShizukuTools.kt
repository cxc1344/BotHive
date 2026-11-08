package app.opengrok

import android.content.pm.PackageManager
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
* Optional Shizuku privileged channel (Apache-2.0 SDK, github.com/RikkaApps/Shizuku) —
* the same ADB-level channel Operit uses for its deeper system tools. Inert unless the
* user installs the Shizuku app, activates it (wireless debugging or PC ADB), and grants
* this app permission inside Shizuku. Commands then run as the shell user, which unlocks
* input injection (tap/swipe), screencap, dumpsys, pm/am and similar shell tooling.
*/
object ShizukuTools {
val running = mutableStateOf(false)
val granted = mutableStateOf(false)

private val listener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh()}

fun refresh() {
running.value = try { Shizuku.pingBinder()} catch (_: Exception) { false}
granted.value = running.value && try {
Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
} catch (_: Exception) {
false
}
}

fun requestPermission() {
try {
Shizuku.addRequestPermissionResultListener(listener)
Shizuku.requestPermission(4242)
} catch (_: Exception) {
}
}

/**
 * Shizuku 13.x keeps newProcess private (the UserService route is the blessed one);
 * the method still exists in the shipped library, so call it reflectively. Any failure
 * surfaces as a readable error to the caller.
 */
private fun newProcessReflect(cmd: Array<String>): Process {
val m = Shizuku::class.java.getDeclaredMethod(
"newProcess",
Array<String>::class.java,
Array<String>::class.java,
String::class.java
)
m.isAccessible = true
return m.invoke(null, cmd, null, null) as Process
}

suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
refresh()
if (!running.value) {
return@withContext "请让用户先安装 Shizuku 应用,并通过无线调试或电脑 ADB 激活它,然后回到 Agent 模式页面确认状态。"
}
if (!granted.value) {
requestPermission()
return@withContext "已弹出授权请求,请让用户在 Shizuku 的弹窗里允许 OpenGrokBot 后再试。"
}
try {
val process = newProcessReflect(arrayOf("sh", "-c", command))
val output = StringBuilder()
fun drain(stream: java.io.InputStream) {
try {
stream.bufferedReader().use { r ->
val buf = CharArray(1024)
while (true) {
val n = r.read(buf)
if (n < 0) break
synchronized(output) {
if (output.length < 20_000) output.append(buf, 0, n)
}
}
}
} catch (_: Exception) {
}
}
val reader = Thread { drain(process.inputStream) }.apply { isDaemon = true; start() }
Thread { drain(process.errorStream) }.apply { isDaemon = true; start() }
if (!process.waitFor(30, TimeUnit.SECONDS)) {
process.destroyForcibly()
return@withContext "命令超时(30 秒):$command\n已输出:${synchronized(output) { output.toString()}.take(2000)}"
}
reader.join(1_000)
val text = synchronized(output) { output.toString()}
val body = if (text.length > 4_000) text.take(4_000) + "\n…(已截断)" else text
body + "\n[exit ${process.exitValue()}]"
} catch (e: Exception) {
"Shizuku 执行失败:${e.message}"
}
}
}
