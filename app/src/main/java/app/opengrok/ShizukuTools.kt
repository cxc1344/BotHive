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
    exec(command, 30, 20_000)
}

/** Raw command execution with tunables, shared by the plain shell and the display-targeted helpers. */
private suspend fun exec(command: String, timeoutSec: Long, maxChars: Int): String = withContext(Dispatchers.IO) {
refresh()
if (!running.value) {
return@withContext "请让用户先安装 Shizuku 应用,并通过无线调试或电脑 ADB 激活它,然后回到 Agent 模式页面确认状态。"
}
if (!granted.value) {
requestPermission()
return@withContext "已弹出授权请求,请让用户在 Shizuku 的弹窗里允许 BotHive 后再试。"
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
if (output.length < maxChars) output.append(buf, 0, n)
}
}
}
} catch (_: Exception) {
}
}
val reader = Thread { drain(process.inputStream) }.apply { isDaemon = true; start() }
Thread { drain(process.errorStream) }.apply { isDaemon = true; start() }
if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
process.destroyForcibly()
return@withContext "命令超时(${timeoutSec} 秒):$command\n已输出:${synchronized(output) { output.toString()}.take(2000)}"
}
reader.join(1_000)
val text = synchronized(output) { output.toString()}
val body = if (text.length > 4_000) text.take(4_000) + "\n…(已截断)" else text
body + "\n[exit ${process.exitValue()}]"
} catch (e: Exception) {
"Shizuku 执行失败:${e.message}"
}
}

/** True when both the Shizuku binder and our permission are ready (refreshes state first). */
fun ready(): Boolean {
    refresh()
    return running.value && granted.value
}

// ---- display-targeted helpers (virtual screen) ----

/**
 * Starts an app on a specific display. Uses the LAUNCHER intent form, which works for the vast
 * majority of apps without needing to know the exact activity name.
 */
suspend fun startAppOnDisplay(displayId: Int, pkg: String): String {
    val q = pkg.trim()
    if (q.isEmpty()) return "包名为空。"
    if (!Regex("^[A-Za-z0-9_.]+$").matches(q)) return "包名格式不合法:$q"
    val cmd = "am start --display $displayId -a android.intent.action.MAIN " +
        "-c android.intent.category.LAUNCHER -p $q"
    return exec(cmd, 20, 8_000)
}

/** Resolves the launchable activity of a package (used as a fallback when the intent form fails). */
suspend fun resolveLauncherActivity(pkg: String): String? {
    val out = exec("cmd package resolve-activity --brief -a android.intent.action.MAIN " +
        "-c android.intent.category.LAUNCHER $pkg", 15, 4_000)
    val line = out.lines().map { it.trim() }.lastOrNull { it.contains('/') } ?: return null
    return line.takeIf { !it.contains(' ') }
}

/** Starts an explicit component on a display: `am start --display <id> -n pkg/activity`. */
suspend fun startComponentOnDisplay(displayId: Int, component: String): String =
    exec("am start --display $displayId -n $component", 20, 8_000)

suspend fun tapOnDisplay(displayId: Int, x: Int, y: Int): String =
    exec("input --display $displayId tap $x $y", 12, 2_000)

suspend fun swipeOnDisplay(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): String =
    exec("input --display $displayId swipe $x1 $y1 $x2 $y2 ${ms.coerceIn(50, 5_000)}", 15, 2_000)

suspend fun keyOnDisplay(displayId: Int, keyCode: Int): String =
    exec("input --display $displayId keyevent $keyCode", 12, 2_000)

/** Text injection fallback for the virtual display (the accessibility tree is not available there). */
suspend fun textOnDisplay(displayId: Int, text: String): String {
    // `input text` treats spaces specially; %s is the documented escape.
    val escaped = text.replace(" ", "%s")
        .replace("\\", "\\\\")
        .replace("'", "\\'")
    return exec("input --display $displayId text '$escaped'", 15, 2_000)
}

/** Long press = a zero-distance swipe held for the duration. */
suspend fun longPressOnDisplay(displayId: Int, x: Int, y: Int, ms: Long): String =
    exec("input --display $displayId swipe $x $y $x $y ${ms.coerceIn(300, 5_000)}", 15, 2_000)

/** Foreground package on a display, used to report what the Agent is looking at. */
suspend fun foregroundOnDisplay(displayId: Int): String =
    exec("dumpsys display | grep -A2 'DisplayDeviceInfo{.*displayId $displayId' | head -5", 15, 2_000)
}
