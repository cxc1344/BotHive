package app.opengrok

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import org.json.JSONObject

/**
* The device Agent's eyes and fingers: an AccessibilityService the user enables by hand in
* system settings (the same channel Operit uses for UI automation). The Agent "sees" the
* screen as a text dump of the UI node tree and acts through node clicks and gestures.
* Inert until enabled; every tool reports the exact setting to switch on when it is off.
*/
class OgAccessibilityService: AccessibilityService() {
override fun onServiceConnected() {
A11y.attach(this)
}

override fun onDestroy() {
A11y.detach(this)
super.onDestroy()
}

override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
override fun onInterrupt() {}

internal fun root(): AccessibilityNodeInfo? = try {
rootInActiveWindow
} catch (_: Exception) {
null
}

internal fun dispatch(path: Path, durationMs: Long, done: (Boolean) -> Unit) {
val gesture = GestureDescription.Builder()
.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 60_000)))
.build()
try {
dispatchGesture(gesture, object: GestureResultCallback() {
override fun onCompleted(g: GestureDescription?) = done(true)
override fun onCancelled(g: GestureDescription?) = done(false)
}, null)
} catch (_: Exception) {
done(false)
}
}
}

/** Screenshot via the accessibility API (API 30+); runs on the service's main thread. */
@RequiresApi(Build.VERSION_CODES.R)
internal fun OgAccessibilityService.takeShot(done: (Bitmap?) -> Unit) {
try {
takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
object : AccessibilityService.TakeScreenshotCallback {
override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
try {
val bmp = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
shot.hardwareBuffer.close()
done(bmp?.copy(Bitmap.Config.ARGB_8888, false))
} catch (_: Exception) {
done(null)
}
}
override fun onFailure(code: Int) = done(null)
})
} catch (_: Exception) {
done(null)
}
}

private const val SHOT_UNSUPPORTED = "系统版本低于 Android 11,不支持无障碍截屏"

/** True when the OS provides AccessibilityService.takeScreenshot (Android 11 / API 30+). */
internal fun canTakeShot(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

object A11y {
val enabled = mutableStateOf(false)

@Volatile
private var service: OgAccessibilityService? = null

/**
 * True when the user has switched this service on in system settings, checked through the
 * AccessibilityManager instead of our in-memory flag. The in-memory flag is lost whenever the
 * app process restarts while the service stays enabled system-wide, which used to make the Agent
 * report "无障碍未开启" even though it was on.
 */
fun enabledInSystem(context: android.content.Context): Boolean {
    val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as? android.view.accessibility.AccessibilityManager ?: return false
    return try {
        am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    } catch (_: Exception) {
        false
    }
}

/**
 * Reconnects the service when the OS still has it enabled but our process lost the instance
 * (e.g. the app was killed and restarted while the service stayed on system-wide).
 *
 * We cannot bind the service ourselves, so we hand the user the shortest reliable path and
 * also nudge the framework by re-setting the enabled service list.
 */
fun reconnect(context: android.content.Context): String {
    if (service != null) return "无障碍服务已连接,无需重连。"
    if (!enabledInSystem(context)) {
        return "系统里没有开启 BotHive 的无障碍服务,请先去系统设置打开它。"
    }
    return try {
        val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val mine = enabled.firstOrNull { it.resolveInfo.serviceInfo.packageName == context.packageName }
        if (mine != null) {
            // Re-applying the service info makes the framework rebind and call onServiceConnected
            // again. AccessibilityManager#setAccessibilityServiceInfo is a hidden (@hide) method:
            // it is absent from the public android.jar (compile error) and blocked at runtime
            // (NoSuchMethodError, which is an Error and would kill this call), so the same effect is
            // produced through the public secure setting: drop our component from the enabled list,
            // then put it back — the framework tears the service down and rebinds it.
            val serviceInfo = mine.resolveInfo.serviceInfo
            val component = "${serviceInfo.packageName}/${serviceInfo.name}"
            val resolver = context.contentResolver
            val key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val current = android.provider.Settings.Secure.getString(resolver, key).orEmpty()
            val parts = current.split(':').filter { it.isNotEmpty() }
            val without = parts.filter { it != component }
            // Writing secure settings needs WRITE_SECURE_SETTINGS (adb / Shizuku granted); when the
            // user's build does not hold it we still return the manual instruction instead of crashing.
            android.provider.Settings.Secure.putString(resolver, key, without.joinToString(":"))
            android.provider.Settings.Secure.putString(resolver, key, (without + component).joinToString(":"))
            "已请求系统重新绑定无障碍服务。若几秒后仍未生效,请在系统无障碍列表里把 BotHive 关掉再打开一次。"
        } else {
            "系统里查不到已启用的 BotHive 无障碍服务,请在系统无障碍列表里重新打开它。"
        }
    } catch (e: SecurityException) {
        "当前没有写入系统设置的权限,请在系统无障碍列表里把 BotHive 关掉再打开一次。"
    } catch (e: Throwable) {
        "重连请求失败:${e.message ?: e.javaClass.simpleName}。请在系统无障碍列表里把 BotHive 关掉再打开一次。"
    }
}

/** Diagnostic line for the settings screen: which state is actually blocking us. */
fun statusLine(context: android.content.Context): String {
    val inSystem = enabledInSystem(context)
    return when {
        service != null -> "状态:已连接,可截屏并操作屏幕。"
        inSystem -> "状态:系统里已开启,但服务还没连上本进程。点下面的「重新连接无障碍」即可恢复(通常是因为 App 被系统回收过)。"
        else -> "状态:未开启。请在系统无障碍设置里打开 BotHive。"
    }
}

internal fun attach(s: OgAccessibilityService) {
service = s
enabled.value = true
}

internal fun detach(s: OgAccessibilityService) {
if (service === s) {
service = null
enabled.value = false
}
}

private const val OFF_MSG =
"无障碍服务未连接。如果系统无障碍列表里 BotHive 已经是开启状态,请在 Agent 设置里点「重新连接无障碍」;否则先让用户在系统无障碍设置里打开 BotHive。"

private fun svc(): OgAccessibilityService? = service

/** Screen size in physical pixels, refreshed on every screenshot for coordinate mapping. */
var screenW: Int = 0
    private set
var screenH: Int = 0
    private set

// ---- screenshot (vision mode) ----

/**
 * Captures the screen as a scaled PNG under cacheDir/screenshots/. The model then points at a
 * normalized (0-1000 x 0-1000) position on that image, which we map back to physical pixels.
 */
suspend fun screenshot(cacheDir: File): JSONObject {
val s = svc()?: return JSONObject().put("error", OFF_MSG)
if (!canTakeShot()) {
return JSONObject().put("error", SHOT_UNSUPPORTED + "。请改用 screen_dump(文字读屏)+ tap_text 的文字模式。")
}
// AccessibilityService.takeScreenshot must be invoked on the service's main thread.
val bmp = withContext(Dispatchers.Main) {
withTimeoutOrNull(4_000) {
suspendCancellableCoroutine<Bitmap?> { cont ->
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
s.takeShot { b -> if (cont.isActive) cont.resume(b)}
} else {
cont.resume(null)
}
}
}
}?: return JSONObject().put("error", "截屏失败(系统拒绝或超时)。可能当前在锁屏/密码界面,或系统限制了截屏。")
screenW = bmp.width
screenH = bmp.height
val scale = minOf(1f, 720f / bmp.width.toFloat())
val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
val dir = File(cacheDir, "screenshots").apply { mkdirs() }
val f = File(dir, "shot_${System.currentTimeMillis()}.png")
FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 90, it) }
// keep at most the 20 newest shots
dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
return JSONObject()
.put("path", f.absolutePath)
.put("width", out.width)
.put("height", out.height)
.put("screen_w", screenW)
.put("screen_h", screenH)
}

// ---- screen reading ----

fun dumpScreen(): String {
val s = svc()?: return OFF_MSG
val root = s.root()?: return "当前窗口没有可读取的界面节点(可能是桌面或系统界面)。"
val sb = StringBuilder()
var count = 0
fun walk(node: AccessibilityNodeInfo, depth: Int) {
if (count > 400) return
count++
val rect = Rect()
node.getBoundsInScreen(rect)
val text = node.text?.toString()?.take(60).orEmpty()
val desc = node.contentDescription?.toString()?.take(60).orEmpty()
val label = listOf(text, desc).filter { it.isNotBlank()}.joinToString(" / ")
if (label.isNotBlank() || node.isClickable || node.isEditable) {
sb.append(" ".repeat(depth.coerceAtMost(12)))
sb.append(node.className?.toString()?.substringAfterLast('.')?: "View")
if (label.isNotBlank()) sb.append(" 「").append(label).append("」")
sb.append(" [").append(rect.centerX()).append(',').append(rect.centerY()).append(']')
if (node.isClickable) sb.append(" 可点击")
if (node.isEditable) sb.append(" 可输入")
sb.append('\n')
}
for (i in 0 until node.childCount) {
val child = node.getChild(i)?: continue
walk(child, depth + 1)
}
}
walk(root, 0)
if (count > 400) sb.append("…(节点过多,已截断)\n")
return if (sb.isBlank()) "当前窗口没有带文字或可交互的节点。" else sb.toString().trimEnd()
}

// ---- normalized vision gestures (coordinates are 0-1000 grid of the screenshot) ----

/** Map a normalized coordinate (0-1000) to physical pixels using the latest screenshot size. */
private fun toPx(nx: Int, ny: Int): Pair<Int, Int>? {
val w = screenW; val h = screenH
if (w <= 0 || h <= 0) return null
return (nx.coerceIn(0, 1000) * w / 1000) to (ny.coerceIn(0, 1000) * h / 1000)
}

private const val NO_SHOT_MSG =
"还没有截屏尺寸信息。请先调用 screen_shot 截图,再根据图片上的位置用 0-1000 坐标操作。"

/** Tap the point at normalized (x,y) on the last screenshot. */
suspend fun tapNorm(x: Int, y: Int): String {
if (svc() == null) return OFF_MSG
val p = toPx(x, y)?: return NO_SHOT_MSG
val path = Path().apply { moveTo(p.first.toFloat(), p.second.toFloat())}
return if (gesture(path, 60)) "已点击 ($x,$y) → 屏幕 (${p.first},${p.second})" else "点击手势被系统取消"
}

/** Long-press at normalized (x,y) for [durationMs] milliseconds (default 600). */
suspend fun longPressNorm(x: Int, y: Int, durationMs: Long = 600): String {
if (svc() == null) return OFF_MSG
val p = toPx(x, y)?: return NO_SHOT_MSG
val path = Path().apply { moveTo(p.first.toFloat(), p.second.toFloat())}
return if (gesture(path, durationMs.coerceIn(300, 5_000))) "已长按 ($x,$y) ${durationMs}ms → 屏幕 (${p.first},${p.second})" else "长按手势被系统取消"
}

/** Drag from normalized (x1,y1) to (x2,y2); also used for swipes/scrolls. */
suspend fun dragNorm(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): String {
if (svc() == null) return OFF_MSG
val a = toPx(x1, y1)?: return NO_SHOT_MSG
val b = toPx(x2, y2)?: return NO_SHOT_MSG
val path = Path().apply {
moveTo(a.first.toFloat(), a.second.toFloat())
lineTo(b.first.toFloat(), b.second.toFloat())
}
return if (gesture(path, durationMs.coerceIn(50, 3_000)))
"已拖动 ($x1,$y1)→($x2,$y2) → 屏幕 (${a.first},${a.second})→(${b.first},${b.second})"
else "拖动手势被系统取消"
}

// ---- legacy actions (kept for text-based fallback) ----

private suspend fun gesture(path: Path, durationMs: Long): Boolean {
val s = svc()?: return false
val result = withTimeoutOrNull(3_000) {
suspendCancellableCoroutine<Boolean> { cont ->
s.dispatch(path, durationMs) { ok -> if (cont.isActive) cont.resume(ok)}
}
}
return result == true
}

suspend fun tap(x: Int, y: Int): String {
if (svc() == null) return OFF_MSG
val path = Path().apply { moveTo(x.toFloat(), y.toFloat())}
return if (gesture(path, 60)) "已点击 ($x,$y)" else "点击手势被系统取消"
}

suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): String {
if (svc() == null) return OFF_MSG
val path = Path().apply {
moveTo(x1.toFloat(), y1.toFloat())
lineTo(x2.toFloat(), y2.toFloat())
}
return if (gesture(path, durationMs)) "已滑动 ($x1,$y1)→($x2,$y2)" else "滑动手势被系统取消"
}

suspend fun tapText(query: String): String {
val s = svc()?: return OFF_MSG
val root = s.root()?: return "当前窗口没有可读取的界面节点。"
var best: AccessibilityNodeInfo? = null
var bestRect: Rect? = null
fun walk(node: AccessibilityNodeInfo) {
if (best!= null) return
val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
if (query.isNotBlank() && label.contains(query)) {
var target: AccessibilityNodeInfo? = node
while (target!= null &&!target.isClickable) target = target.parent
val rect = Rect()
(target?: node).getBoundsInScreen(rect)
best = target?: node
bestRect = rect
return
}
for (i in 0 until node.childCount) {
val child = node.getChild(i)?: continue
walk(child)
}
}
walk(root)
val node = best?: return "屏幕上找不到包含「$query」的元素。可先用 screen_dump 看看当前界面。"
if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
return "已点击「$query」"
}
val rect = bestRect?: return "找到「$query」但取不到位置"
val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())}
return if (gesture(path, 60)) "已点击「$query」(${rect.centerX()},${rect.centerY()})" else "点击手势被系统取消"
}

suspend fun inputText(text: String): String {
val s = svc()?: return OFF_MSG
val root = s.root()?: return "当前窗口没有可读取的界面节点。"
val focused = try {
root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
} catch (_: Exception) {
null
}
var editable: AccessibilityNodeInfo? = if (focused?.isEditable == true) focused else null
if (editable == null) {
fun walk(node: AccessibilityNodeInfo) {
if (editable!= null) return
if (node.isEditable) {
editable = node
return
}
for (i in 0 until node.childCount) {
val child = node.getChild(i)?: continue
walk(child)
}
}
walk(root)
}
val target = editable?: return "当前界面没有可输入的文本框(可能还没点开输入框)。可先 tap_at 点开输入框再输入。"
target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
delay(120)
val args = Bundle().apply {
putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
}
return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
"已输入文字(${text.length} 字符)"
} else {
"输入失败:该文本框拒绝了直接写入,可尝试点开后逐字输入(暂不支持)。"
}
}

fun pressKey(key: String): String {
val s = svc()?: return OFF_MSG
val action = when (key.trim().lowercase()) {
"back" -> AccessibilityService.GLOBAL_ACTION_BACK
"home" -> AccessibilityService.GLOBAL_ACTION_HOME
"recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
"notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
else -> return "不支持的按键:$key(可用 back / home / recents / notifications)"
}
return if (s.performGlobalAction(action)) "已按下 $key" else "按键执行失败"
}
}
