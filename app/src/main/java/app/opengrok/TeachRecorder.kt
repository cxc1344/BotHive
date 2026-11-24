package app.opengrok

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * v0.7.0 teach mode (示教录制): records what the user does on screen through the accessibility
 * service (window changes, clicks, text edits with password masking, scrolls) as plain-language
 * steps, for at most 10 minutes / 300 steps. Store.draftSkillFromTeach turns them into a skill.
 *
 * Coordinates are normalized to the 0-1000 grid the screen tools use. Text typed into password
 * fields is never recorded; other text passes through [LeakGuard.mask]. Nothing is recorded from
 * BotHive itself. All calls happen on the main thread (accessibility events are delivered there).
 */
object TeachRecorder {
    const val MAX_MS = 10 * 60_000L
    const val MAX_STEPS = 300

    val recording = mutableStateOf(false)
    val steps: SnapshotStateList<String> = mutableStateListOf()

    private var startedMs = 0L
    private var lastKey = ""
    private var overlay: View? = null
    private var overlayWm: WindowManager? = null
    private val labels = HashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { if (recording.value) stop() }

    /** Starts recording. Returns null when started, or a readable error (accessibility off). */
    fun start(): String? {
        val svc = A11y.serviceOrNull()
            ?: return "无障碍服务未连接:请先在系统无障碍设置里打开 BotHive(已开启的话在 Agent 设置里点「重新连接无障碍」),再开始示教。"
        if (recording.value) return null
        steps.clear()
        lastKey = ""
        startedMs = System.currentTimeMillis()
        recording.value = true
        showOverlay(svc)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, MAX_MS)
        return null
    }

    /** Stops recording and returns the recorded steps (they also stay in [steps] for the UI). */
    fun stop(): List<String> {
        handler.removeCallbacks(autoStop)
        recording.value = false
        hideOverlay()
        return steps.toList()
    }

    /** Called when the accessibility service goes away mid-recording. */
    internal fun onServiceGone() {
        if (recording.value) stop()
        overlay = null
        overlayWm = null
    }

    private fun appLabel(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private fun add(key: String, step: String, coalesce: Boolean) {
        if (coalesce && key == lastKey && steps.isNotEmpty()) {
            steps[steps.lastIndex] = step
        } else {
            if (!coalesce && key == lastKey && steps.lastOrNull() == step) return
            steps.add(step)
        }
        lastKey = key
        if (steps.size >= MAX_STEPS) stop()
    }

    private fun eventText(e: AccessibilityEvent, node: AccessibilityNodeInfo?): String {
        val fromEvent = e.text?.joinToString(" ") { it?.toString().orEmpty() }?.trim().orEmpty()
        val fromNode = listOfNotNull(node?.text?.toString(), node?.contentDescription?.toString())
            .joinToString(" ").trim()
        val raw = fromEvent.ifBlank { fromNode }.ifBlank { e.contentDescription?.toString().orEmpty() }
        return LeakGuard.mask(raw.replace(Regex("\\s+"), " ")).take(40)
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo?) {
        if (node != null && Build.VERSION.SDK_INT < 33) runCatching { node.recycle() }
    }

    internal fun onEvent(svc: AccessibilityService, e: AccessibilityEvent) {
        if (!recording.value) return
        if (System.currentTimeMillis() - startedMs > MAX_MS) {
            stop()
            return
        }
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == svc.packageName) return
        val app = appLabel(svc, pkg)
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = e.className?.toString()?.substringAfterLast('.').orEmpty()
                val title = e.text?.firstOrNull()?.toString()?.trim().orEmpty().take(24)
                val what = when {
                    title.isNotEmpty() -> "「$title」"
                    cls.isNotEmpty() -> "($cls)"
                    else -> ""
                }
                add("win:$pkg:$cls:$title", "打开「$app」的界面$what", coalesce = false)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = try { e.source } catch (_: Exception) { null }
                try {
                    val label = eventText(e, node)
                    val pos = node?.let { normalizedCenter(svc, it) }
                    val where = if (pos != null) " @ (${pos.first},${pos.second})" else ""
                    val what = if (label.isNotEmpty()) "「$label」" else "一个控件"
                    add("click:${System.nanoTime()}", "在「$app」点击$what$where", coalesce = false)
                } finally {
                    recycle(node)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val node = try { e.source } catch (_: Exception) { null }
                try {
                    val field = node?.viewIdResourceName ?: e.className?.toString().orEmpty()
                    val secret = e.isPassword || node?.isPassword == true
                    val step = if (secret) {
                        "在「$app」的密码框里输入(内容已隐藏)"
                    } else {
                        val t = eventText(e, null)
                        if (t.isEmpty()) "在「$app」清空了输入框" else "在「$app」的输入框里输入「$t」"
                    }
                    add("text:$pkg:$field", step, coalesce = true)
                } finally {
                    recycle(node)
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                add("scroll:$pkg", "在「$app」里滚动页面", coalesce = true)
            }
            else -> Unit
        }
    }

    private fun normalizedCenter(svc: AccessibilityService, node: AccessibilityNodeInfo): Pair<Int, Int>? {
        val r = Rect()
        node.getBoundsInScreen(r)
        val dm = svc.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        if (w <= 0 || h <= 0 || r.isEmpty()) return null
        val x = (r.centerX().toLong() * 1000 / w).toInt().coerceIn(0, 1000)
        val y = (r.centerY().toLong() * 1000 / h).toInt().coerceIn(0, 1000)
        return x to y
    }

    private fun showOverlay(svc: AccessibilityService) {
        hideOverlay()
        try {
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = svc.resources.displayMetrics.density
            val tv = TextView(svc).apply {
                text = BixVoice.teachRecording()
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                val padH = (14 * density).toInt()
                val padV = (8 * density).toInt()
                setPadding(padH, padV, padH, padV)
                background = GradientDrawable().apply {
                    cornerRadius = 18 * density
                    setColor(Color.argb(220, 28, 28, 34))
                    setStroke((1 * density).toInt().coerceAtLeast(1), Color.argb(200, 255, 196, 64))
                }
                setOnClickListener { stop() }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (56 * density).toInt()
            }
            wm.addView(tv, lp)
            overlay = tv
            overlayWm = wm
        } catch (_: Exception) {
            // Recording still works without the pill; the app UI offers a stop button too.
            overlay = null
            overlayWm = null
        }
    }

    private fun hideOverlay() {
        val v = overlay
        val wm = overlayWm
        overlay = null
        overlayWm = null
        if (v != null && wm != null) runCatching { wm.removeView(v) }
    }
}
