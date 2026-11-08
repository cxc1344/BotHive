package app.opengrok

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 10_000)))
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

object A11y {
val enabled = mutableStateOf(false)

@Volatile
private var service: OgAccessibilityService? = null

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
"屏幕操作需要用户亲手开启无障碍服务。请让用户在 Agent 模式页面点「开启无障碍」,在系统无障碍列表里打开 OpenGrokBot 后再试。"

private fun svc(): OgAccessibilityService? = service

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

// ---- actions ----

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
val target = editable?: return "当前界面没有可输入的文本框(可能还没点开输入框)。可先 tap_text/tap_xy 点开输入框再输入。"
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
