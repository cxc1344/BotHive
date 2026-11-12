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
/** Why the channel is unusable, for the settings card; null when healthy. */
val problem = mutableStateOf<String?>(null)

private var listenerAdded = false
private val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
    refresh()
    if (grantResult != PackageManager.PERMISSION_GRANTED) {
        problem.value = "Shizuku 权限被拒绝,请在 Shizuku 应用里重新授权 BotHive。"
    }
}

/**
 * Reads the live state from Shizuku. Called before every command, so it must stay cheap and must
 * never throw: a dead binder simply means "not running".
 */
fun refresh() {
    val ping = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        problem.value = "连不上 Shizuku 服务(${e.javaClass.simpleName})。请在 Shizuku 应用里确认服务仍在运行。"
        running.value = false
        granted.value = false
        return
    }
    running.value = ping
    if (!ping) {
        granted.value = false
        problem.value = "Shizuku 服务未运行。请打开 Shizuku 应用,用无线调试或电脑 ADB 启动服务后再试。"
        return
    }
    granted.value = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        problem.value = "读取 Shizuku 授权状态失败(${e.javaClass.simpleName})。"
        false
    }
    problem.value = if (granted.value) null
        else "Shizuku 已运行,但 BotHive 还没有被授权。点「请求 Shizuku 授权」并在弹窗里允许。"
}

fun requestPermission() {
    try {
        if (!listenerAdded) {
            Shizuku.addRequestPermissionResultListener(listener)
            listenerAdded = true
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            refresh()
            return
        }
        Shizuku.requestPermission(4242)
    } catch (e: Throwable) {
        problem.value = "请求 Shizuku 授权失败:${e.message ?: e.javaClass.simpleName}。请确认 Shizuku 服务正在运行。"
    }
}

/**
 * Shizuku 13.x keeps newProcess private (the UserService route is the blessed one); the method
 * still exists in the shipped library, so call it reflectively. The signature is
 * newProcess(String[] cmd, String[] env, String dir) — we pass null for env/dir, which the
 * implementation treats as "inherit".
 */
private fun newProcessReflect(cmd: Array<String>): Process {
    val cls = Shizuku::class.java
    val m = try {
        cls.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
    } catch (e: NoSuchMethodException) {
        // Some builds expose the two-argument overload instead.
        cls.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java)
    }
    m.isAccessible = true
    return if (m.parameterTypes.size == 3) {
        m.invoke(null, cmd, null, null) as Process
    } else {
        m.invoke(null, cmd, null) as Process
    }
}

suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
    exec(command, 30, 20_000)
}

/** Raw command execution with tunables, shared by the plain shell and the display-targeted helpers. */
private suspend fun exec(command: String, timeoutSec: Long, maxChars: Int): String = withContext(Dispatchers.IO) {
refresh()
if (!running.value) {
return@withContext problem.value
    ?: "Shizuku 服务未运行。请打开 Shizuku 应用,用无线调试或电脑 ADB 启动服务后再试。"
}
if (!granted.value) {
requestPermission()
return@withContext "已弹出 Shizuku 授权请求,请让用户在弹窗里允许 BotHive 后重试。" +
    (problem.value?.let { "\n当前状态:$it" } ?: "")
}
try {
// Wrap the command so the exit status is captured in the stream itself: Shizuku's Process is a
// remote stub whose waitFor()/exitValue() can report "process hasn't exited" even after the
// command finished, so we never rely on them for success/failure.
val marker = "__BOTHIVE_EXIT__"
val wrapped = arrayOf("sh", "-c", "( $command ) 2>&1; echo $marker\$?")
val process = newProcessReflect(wrapped)
val output = StringBuilder()
// One latch per drain thread: a shared latch let the *first* closed stream (stderr usually ends
// before stdout) release await() early, so the marker line could be missed and exit became
// [exit unknown]. Both streams must finish before we parse the exit code.
fun drain(stream: java.io.InputStream, done: java.util.concurrent.CountDownLatch) {
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
} finally {
done.countDown()
}
}
val doneOut = java.util.concurrent.CountDownLatch(1)
val doneErr = java.util.concurrent.CountDownLatch(1)
Thread { drain(process.inputStream, doneOut) }.apply { isDaemon = true; start() }
Thread { drain(process.errorStream, doneErr) }.apply { isDaemon = true; start() }

// The streams close when the shell finishes, which is the real completion signal.
if (!doneOut.await(timeoutSec, TimeUnit.SECONDS) || !doneErr.await(timeoutSec, TimeUnit.SECONDS)) {
runCatching { process.destroy() }
return@withContext "命令超时(${timeoutSec} 秒):$command\n已输出:${synchronized(output) { output.toString() }.take(2000)}"
}
val raw = synchronized(output) { output.toString() }
val exitIdx = raw.lastIndexOf(marker)
val exitCode = if (exitIdx >= 0) raw.substring(exitIdx + marker.length).trim().toIntOrNull() else null
val text = if (exitIdx >= 0) raw.substring(0, exitIdx).trimEnd() else raw.trimEnd()
val body = if (text.length > 4_000) text.take(4_000) + "\n…(已截断)" else text
when {
    exitCode == null -> body.ifBlank { "(无输出)" } + "\n[exit unknown]"
    exitCode == 0 -> body.ifBlank { "(无输出)" } + "\n[exit 0]"
    else -> body.ifBlank { "(无输出)" } + "\n[exit $exitCode]"
}
} catch (e: Exception) {
"Shizuku 执行失败:${e.message ?: e.javaClass.simpleName}"
}
}

/** True when both the Shizuku binder and our permission are ready (refreshes state first). */
fun ready(): Boolean {
    refresh()
    return running.value && granted.value
}

/** Human-readable reason the channel is not usable, or null when it is. */
fun reason(): String? {
    refresh()
    return if (running.value && granted.value) null else problem.value
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
    return exec("input --display $displayId text '${escapeInputText(text)}'", 15, 2_000)
}

/**
 * Escapes text for `sh -c "... input text '...'"` and `input text` itself:
 *  - `input text` takes no shell metacharacters, but it *does* treat `%s` as a space, so real
 *    spaces become %s (documented behaviour) and a literal `%` stays as-is.
 *  - For the surrounding single quotes we use the standard `'''` idiom; the old `\'` form
 *    left a stray backslash and broke quoting on apostrophes like "it's".
 */
fun escapeInputText(text: String): String = text
    .replace("'", "'\\''")
    .replace(" ", "%s")

/** Long press = a zero-distance swipe held for the duration. */
suspend fun longPressOnDisplay(displayId: Int, x: Int, y: Int, ms: Long): String =
    exec("input --display $displayId swipe $x $y $x $y ${ms.coerceIn(300, 5_000)}", 15, 2_000)

/** Foreground package on a display, used to report what the Agent is looking at. */
suspend fun foregroundOnDisplay(displayId: Int): String =
    exec("dumpsys display | grep -A2 'DisplayDeviceInfo{.*displayId $displayId' | head -5", 15, 2_000)
}
