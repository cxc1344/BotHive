package app.opengrok

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating preview window for [VirtualScreen].
 *
 * Shows the virtual display's live frames in a draggable TYPE_APPLICATION_OVERLAY window with a
 * status bar (Bot name + state) and pause / stop controls. Collapsing turns it into a small dot.
 *
 * This is an *Agent automation* surface: it exists so the user can watch and interrupt the Agent
 * without losing their own screen. It intentionally offers no casting or recording.
 *
 * Requires the "display over other apps" permission; [hasPermission] reports the state so the UI
 * can guide the user instead of failing silently.
 */
object VirtualDisplayOverlay {
    private const val FRAME_INTERVAL_MS = 120L      // ~8 fps: enough to follow along, cheap on battery
    private const val PREFS = "virtual_screen_overlay"

    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var preview: ImageView? = null
    private var statusText: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsedView: View? = null
    private var expandedView: View? = null
    private var running = false
    private var collapsed = false
    /** Bitmap currently handed to the ImageView; recycled one tick after it is replaced. */
    private var shownFrame: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Bot label shown in the status bar. */
    var title: String = "Agent"
        set(value) { field = value; refreshStatus() }

    /** Called when the user taps stop: the owner should cancel the running Agent task. */
    var onStop: (() -> Unit)? = null
    /** Called after the window is dismissed for any reason. */
    var onDismissed: (() -> Unit)? = null

    fun hasPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else true

    /** Whether the floating window is currently on screen. */
    fun isShowing(): Boolean = running

    private fun savedX(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("x", 24)

    private fun savedY(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("y", 220)

    private fun savePos(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("x", x).putInt("y", y).apply()
    }

    /** Builds and shows the window. Returns an error string, or null on success. */
    fun show(context: Context, botName: String): String? {
        if (running) { title = botName; return null }
        if (!hasPermission(context)) return "缺少「显示在其他应用上层」权限"
        val w = VirtualScreen.width.takeIf { it > 0 } ?: return "虚拟屏还没有尺寸,请先创建虚拟屏"
        val h = VirtualScreen.height.takeIf { it > 0 } ?: return "虚拟屏还没有尺寸,请先创建虚拟屏"
        return try {
            title = botName
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager

            // Preview keeps the display's aspect ratio, capped to a comfortable phone-window size.
            val maxW = (context.resources.displayMetrics.widthPixels * 0.46f).roundToInt().coerceAtLeast(150)
            val scale = maxW.toFloat() / w
            val viewW = maxW
            val viewH = (h * scale).roundToInt().coerceAtLeast(120)

            val container = FrameLayout(context)
            val bg = GradientDrawable().apply {
                cornerRadius = 26f
                setColor(0xF21B1509.toInt())
                setStroke(3, 0xFFFFC53D.toInt())
            }
            container.background = bg

            // ---- expanded content: header + preview + controls ----
            val expanded = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 18, 16, 8)
            }
            statusText = TextView(context).apply {
                setTextColor(0xFFFFE6A1.toInt())
                textSize = 11f
                text = "BotHive"
            }
            header.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val collapseBtn = tinyButton(context, "▾") { setCollapsed(true) }
            header.addView(collapseBtn)
            expanded.addView(header)

            preview = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF000000.toInt())
            }
            expanded.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            val controls = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(16, 10, 16, 18)
            }
            controls.addView(actionButton(context, "暂停") { togglePause(context) })
            controls.addView(actionButton(context, "停止") { stopAndDismiss() })
            expanded.addView(controls)

            expandedView = expanded

            // ---- collapsed content: a single dot ----
            val dot = FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
            val dotInner = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFFC53D.toInt())
                }
            }
            val dotSize = 96
            dot.addView(dotInner, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER))
            collapsedView = dot
            dot.visibility = View.GONE

            container.addView(expanded, FrameLayout.LayoutParams(viewW, viewH))
            container.addView(dot, FrameLayout.LayoutParams(140, 140))
            root = container

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedX(context)
                y = savedY(context)
            }
            params = lp

            attachDrag(context, container)
            manager.addView(container, lp)
            running = true
            collapsed = false
            startFrameLoop()
            null
        } catch (e: Exception) {
            dismiss()
            "悬浮窗创建失败:${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun tinyButton(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(0xFFFFC53D.toInt())
            textSize = 14f
            setPadding(20, 4, 20, 4)
            setOnClickListener { onClick() }
        }

    private fun actionButton(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(0xFF1B1509.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(0xFFFFC53D.toInt())
            }
            setPadding(26, 10, 26, 10)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = 18
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun togglePause(context: Context) {
        VirtualScreen.paused.value = !VirtualScreen.paused.value
        val controls = (expandedView as? LinearLayout)?.getChildAt(2) as? LinearLayout
        val btn = controls?.getChildAt(0) as? TextView
        btn?.text = if (VirtualScreen.paused.value) "继续" else "暂停"
        refreshStatus()
    }

    private fun refreshStatus() {
        val state = when {
            !VirtualScreen.active.value -> "未连接"
            VirtualScreen.paused.value -> "已暂停"
            else -> "操控中"
        }
        statusText?.text = "$title · $state"
    }

    /** Collapses to a dot (keeps the display alive) or restores the full window. */
    fun setCollapsed(value: Boolean) {
        collapsed = value
        collapsedView?.visibility = if (value) View.VISIBLE else View.GONE
        expandedView?.visibility = if (value) View.GONE else View.VISIBLE
    }

    private fun attachDrag(context: Context, view: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).roundToInt()
                    val dy = (event.rawY - touchY).roundToInt()
                    // Only treat it as a drag once the finger actually travels, so taps still work.
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { wm?.updateViewLayout(view, lp) }
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    savePos(context, lp.x, lp.y)
                    false
                }
                else -> false
            }
        }
    }

    private fun startFrameLoop() {
        handler.removeCallbacksAndMessages(null)
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                if (!VirtualScreen.paused.value && !collapsed) {
                    val frame: Bitmap? = VirtualScreen.frameForOverlay()
                    if (frame != null && !frame.isRecycled) {
                        val previous = shownFrame
                        preview?.setImageBitmap(frame)
                        // Free the bitmap the ImageView no longer draws (kept one frame to be safe:
                        // the view may still be compositing it right after setImageBitmap).
                        if (previous != null && previous !== frame && !previous.isRecycled) {
                            runCatching { previous.recycle() }
                        }
                        shownFrame = frame
                    }
                    refreshStatus()
                }
                handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
        handler.post(tick)
    }

    /** User pressed stop: cancel the Agent run, then tear everything down. */
    private fun stopAndDismiss() {
        val cb = onStop
        dismiss()
        cb?.invoke()
    }

    /** Removes the window. Does not release the virtual display (the owner decides that). */
    fun dismiss() {
        running = false
        handler.removeCallbacksAndMessages(null)
        val view = root
        val manager = wm
        if (view != null && manager != null) runCatching { manager.removeView(view) }
        shownFrame?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
        shownFrame = null
        root = null
        preview = null
        statusText = null
        collapsedView = null
        expandedView = null
        params = null
        wm = null
        val cb = onDismissed
        onDismissed = null
        cb?.invoke()
    }
}
