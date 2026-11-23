package app.opengrok

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
 * Redesigned Titanium-Obsidian Liquid Glass floating preview window for [VirtualScreen].
 *
 * Improvements over the legacy yellow box:
 *  - Crisp dark frosted bezel with hairline rim and subtle status dot.
 *  - 4-step smooth zoom in / zoom out ("−" / "+") persisted across sessions.
 *  - Sleek compact glass pill when collapsed (tappable to restore, draggable anywhere).
 *  - Auto-idle teardown after a task finishes so no orphan bubble lingers forever.
 */
object VirtualDisplayOverlay {
    private const val FRAME_INTERVAL_MS = 110L
    private const val PREFS = "virtual_screen_overlay"
    private const val IDLE_AUTO_CLOSE_MS = 22_000L

    private val SCALE_STEPS = floatArrayOf(0.34f, 0.48f, 0.64f, 0.82f)

    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var preview: ImageView? = null
    private var statusText: TextView? = null
    private var statusDot: View? = null
    private var pauseBtn: TextView? = null
    private var scaleLabel: TextView? = null
    private var collapsedPillText: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsedView: View? = null
    private var expandedView: View? = null

    private var running = false
    private var collapsed = false
    private var scaleIdx = 1
    private var displayW = 720
    private var displayH = 1280
    private var appCtx: Context? = null
    private var shownFrame: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    private var idleCloseRunnable: Runnable? = null

    var title: String = "Agent"
        set(value) { field = value; refreshStatus() }

    var onStop: (() -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    fun hasPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else true

    fun isShowing(): Boolean = running

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).roundToInt()

    private fun savedX(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("x", dp(context, 14))

    private fun savedY(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("y", dp(context, 120))

    private fun savedScaleIdx(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("scale_idx", 1)
            .coerceIn(0, SCALE_STEPS.lastIndex)

    private fun savePos(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("x", x).putInt("y", y).apply()
    }

    private fun saveScaleIdx(context: Context, idx: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("scale_idx", idx).apply()
    }

    fun show(context: Context, botName: String): String? {
        cancelIdleAutoClose()
        if (running) {
            title = botName
            return null
        }
        if (!hasPermission(context)) return "缺少「显示在其他应用上层」权限"
        val w = VirtualScreen.width.takeIf { it > 0 } ?: return "虚拟屏还没有尺寸,请先创建虚拟屏"
        val h = VirtualScreen.height.takeIf { it > 0 } ?: return "虚拟屏还没有尺寸,请先创建虚拟屏"
        return try {
            appCtx = context.applicationContext
            displayW = w
            displayH = h
            scaleIdx = savedScaleIdx(context)
            title = botName

            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager

            val container = FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }

            // ---- Expanded Titanium Liquid Glass Window ----
            val expanded = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 22).toFloat()
                    setColor(0xEB101318.toInt())
                    setStroke(dp(context, 1).coerceAtLeast(1), 0x42FFFFFF)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = dp(context, 12).toFloat()
                    clipToOutline = true
                }
            }

            // Header bar: status dot + title + zoom (-) / (+) + collapse + close
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 12), dp(context, 8), dp(context, 8), dp(context, 6))
            }
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF4ADE80.toInt())
                }
            }
            statusDot = dot
            header.addView(dot, LinearLayout.LayoutParams(dp(context, 7), dp(context, 7)).apply {
                marginEnd = dp(context, 7)
            })

            statusText = TextView(context).apply {
                setTextColor(0xFFF3F5F8.toInt())
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                text = "$title · 操控中"
            }
            header.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            header.addView(glassIconBtn(context, "−") { stepScale(context, -1) })
            scaleLabel = TextView(context).apply {
                setTextColor(0xFF9AA4B2.toInt())
                textSize = 10f
                gravity = Gravity.CENTER
                text = "${(SCALE_STEPS[scaleIdx] * 100).roundToInt()}%"
            }
            header.addView(scaleLabel, LinearLayout.LayoutParams(dp(context, 30), LinearLayout.LayoutParams.WRAP_CONTENT))
            header.addView(glassIconBtn(context, "+") { stepScale(context, +1) })
            header.addView(glassIconBtn(context, "—") { setCollapsed(true) })
            header.addView(glassIconBtn(context, "×", tint = 0xFFFF6B6B.toInt()) { stopAndDismiss() })
            expanded.addView(header)

            // Live virtual screen viewport with subtle rounded frame
            val previewWrap = FrameLayout(context).apply {
                setPadding(dp(context, 6), 0, dp(context, 6), 0)
            }
            preview = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 14).toFloat()
                    setColor(0xFF060709.toInt())
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    clipToOutline = true
                }
            }
            previewWrap.addView(
                preview,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            expanded.addView(previewWrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            // Footer controls: Pause/Resume + Close
            val controls = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 9))
            }
            val pBtn = glassActionBtn(context, "暂停", primary = false) { togglePause() }
            pauseBtn = pBtn
            controls.addView(pBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 8)
            })
            controls.addView(
                glassActionBtn(context, "关闭虚拟屏", primary = true) { stopAndDismiss() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            expanded.addView(controls)
            expandedView = expanded

            // ---- Collapsed Sleek Liquid Glass Mini Pill (tappable to expand!) ----
            val pill = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 999).toFloat()
                    setColor(0xEE12161E.toInt())
                    setStroke(dp(context, 1).coerceAtLeast(1), 0x55FFC83D)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = dp(context, 10).toFloat()
                }
                visibility = View.GONE
            }
            val pillDot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFFC83D.toInt())
                }
            }
            pill.addView(pillDot, LinearLayout.LayoutParams(dp(context, 8), dp(context, 8)).apply {
                marginEnd = dp(context, 7)
            })
            val pillTxt = TextView(context).apply {
                setTextColor(0xFFF3F5F8.toInt())
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                text = "虚拟屏 · 展开"
            }
            collapsedPillText = pillTxt
            pill.addView(pillTxt)
            collapsedView = pill

            val (viewW, viewH) = computeExpandedSize(context)
            container.addView(expanded, FrameLayout.LayoutParams(viewW, viewH))
            container.addView(
                pill,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            )
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

            attachDragAndTap(context, container, onTapCollapsed = {
                cancelIdleAutoClose()
                setCollapsed(false)
            })
            manager.addView(container, lp)
            running = true
            collapsed = false
            refreshStatus()
            startFrameLoop()
            null
        } catch (e: Exception) {
            dismiss()
            "悬浮窗创建失败:${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun computeExpandedSize(context: Context): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val frac = SCALE_STEPS[scaleIdx]
        val viewW = (dm.widthPixels * frac).roundToInt().coerceIn(dp(context, 145), (dm.widthPixels * 0.88f).roundToInt())
        val chromeH = dp(context, 78)
        val previewW = (viewW - dp(context, 12)).coerceAtLeast(100)
        val aspectH = (previewW.toFloat() * displayH / displayW.coerceAtLeast(1)).roundToInt()
        val maxTotalH = (dm.heightPixels * 0.78f).roundToInt()
        val viewH = (aspectH + chromeH).coerceIn(dp(context, 190), maxTotalH)
        return viewW to viewH
    }

    private fun stepScale(context: Context, delta: Int) {
        cancelIdleAutoClose()
        val next = (scaleIdx + delta).coerceIn(0, SCALE_STEPS.lastIndex)
        if (next == scaleIdx) return
        scaleIdx = next
        saveScaleIdx(context, scaleIdx)
        scaleLabel?.text = "${(SCALE_STEPS[scaleIdx] * 100).roundToInt()}%"
        val exp = expandedView ?: return
        val (w, h) = computeExpandedSize(context)
        exp.layoutParams = FrameLayout.LayoutParams(w, h)
        exp.requestLayout()
        val v = root ?: return
        val lp = params ?: return
        runCatching { wm?.updateViewLayout(v, lp) }
    }

    private fun glassIconBtn(context: Context, label: String, tint: Int = 0xFFE5EAF2.toInt(), onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(tint)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 999).toFloat()
                setColor(0x22FFFFFF)
            }
            val sz = dp(context, 24)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginStart = dp(context, 4)
            }
            setOnClickListener { onClick() }
        }

    private fun glassActionBtn(context: Context, label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(if (primary) 0xFF101318.toInt() else 0xFFF3F5F8.toInt())
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 14).toFloat()
                setColor(if (primary) 0xFFFFC83D.toInt() else 0x26FFFFFF)
            }
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
            setOnClickListener { onClick() }
        }

    private fun togglePause() {
        cancelIdleAutoClose()
        VirtualScreen.paused.value = !VirtualScreen.paused.value
        pauseBtn?.text = if (VirtualScreen.paused.value) "继续" else "暂停"
        refreshStatus()
    }

    private fun refreshStatus() {
        val paused = VirtualScreen.paused.value
        val active = VirtualScreen.active.value
        val state = when {
            !active -> "待机"
            paused -> "已暂停"
            else -> "操控中"
        }
        statusText?.text = "$title · $state"
        collapsedPillText?.text = "虚拟屏 · $state"
        (statusDot?.background as? GradientDrawable)?.setColor(
            when {
                !active -> 0xFF94A3B8.toInt()
                paused -> 0xFFFFC83D.toInt()
                else -> 0xFF4ADE80.toInt()
            }
        )
    }

    fun setCollapsed(value: Boolean) {
        collapsed = value
        collapsedView?.visibility = if (value) View.VISIBLE else View.GONE
        expandedView?.visibility = if (value) View.GONE else View.VISIBLE
        val v = root ?: return
        val lp = params ?: return
        runCatching { wm?.updateViewLayout(v, lp) }
    }

    /**
     * Called when an Agent turn finishes: folds into a sleek mini pill and schedules
     * automatic teardown if idle, so no orphan floating box stays stuck on screen.
     */
    fun scheduleIdleAutoClose(onAutoClose: () -> Unit) {
        if (!running) return
        handler.post {
            if (!running) return@post
            setCollapsed(true)
            collapsedPillText?.text = "虚拟屏 · 已完成"
            cancelIdleAutoClose()
            val r = Runnable {
                if (running) onAutoClose()
            }
            idleCloseRunnable = r
            handler.postDelayed(r, IDLE_AUTO_CLOSE_MS)
        }
    }

    fun cancelIdleAutoClose() {
        idleCloseRunnable?.let { handler.removeCallbacks(it) }
        idleCloseRunnable = null
    }

    private fun attachDragAndTap(context: Context, view: View, onTapCollapsed: () -> Unit) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    collapsed
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).roundToInt()
                    val dy = (event.rawY - touchY).roundToInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { wm?.updateViewLayout(view, lp) }
                        true
                    } else collapsed
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        savePos(context, lp.x, lp.y)
                        true
                    } else if (collapsed) {
                        onTapCollapsed()
                        true
                    } else false
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

    private fun stopAndDismiss() {
        cancelIdleAutoClose()
        val cb = onStop
        dismiss()
        VirtualScreen.release()
        cb?.invoke()
    }

    fun dismiss() {
        running = false
        cancelIdleAutoClose()
        handler.removeCallbacksAndMessages(null)
        val view = root
        val manager = wm
        if (view != null && manager != null) runCatching { manager.removeView(view) }
        shownFrame?.takeIf { !it.isRecycled }?.let { runCatching { it.recycle() } }
        shownFrame = null
        root = null
        preview = null
        statusText = null
        statusDot = null
        pauseBtn = null
        scaleLabel = null
        collapsedPillText = null
        collapsedView = null
        expandedView = null
        params = null
        wm = null
        val cb = onDismissed
        onDismissed = null
        cb?.invoke()
    }
}
