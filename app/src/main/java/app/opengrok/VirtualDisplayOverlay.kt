package app.opengrok

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
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
 *  - v0.7.0: animated glowing border around the live preview while the Agent acts (fades ~2.5 s after
 *    the last action, amber when stuck), a step badge "n/15" with a progress ring and a mini Bix
 *    (Coding while working, Dizzy when stuck, Done when finished), and a Bix head in the collapsed pill.
 *    Driven by [AgentProgress]; the frame pipeline is unchanged.
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

    @Volatile
    private var running = false
    @Volatile
    private var collapsed = false
    @Volatile
    private var scaleIdx = 1
    private var displayW = 720
    private var displayH = 1280
    private var appCtx: Context? = null
    private var shownFrame: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    /** Background thread that pulls and downsizes frames; the main thread only swaps bitmaps. */
    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var idleCloseRunnable: Runnable? = null

    // v0.7.0 agent-progress visuals (main thread only)
    private var glowView: EdgeGlowView? = null
    private var stepBadge: View? = null
    private var stepRing: StepRingView? = null
    private var stepText: TextView? = null
    private var badgeBix: BixSpriteView? = null
    private var pillBix: BixSpriteView? = null
    private var glowAlpha = 0f
    private var lastGlowFrameMs = 0L
    private var glowTicking = false
    private var sawRun = false
    @Volatile
    private var finishedNote = false
    private const val GLOW_FADE_AFTER_MS = 2_500L
    private const val AMBER = 0xFFFFB020.toInt()
    private val glowFrame = Choreographer.FrameCallback { runCatching { glowTick() }.onFailure { glowTicking = false } }

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
            sawRun = false
            glowAlpha = 0f
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
            // Animated glowing border while the Agent acts (non-clickable overlay on the viewport).
            val glow = EdgeGlowView(context, vignette = false, strokeScale = 0.55f, withBix = false).apply {
                cornerRadiusPx = dp(context, 14).toFloat()
                setColor(ThemeColor.currentColor.toArgb())
            }
            glowView = glow
            previewWrap.addView(
                glow,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            // Step badge: mini Bix + progress ring + "n/15"
            val badge = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 6), dp(context, 3), dp(context, 8), dp(context, 3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 999).toFloat()
                    setColor(0xCC0B0C0E.toInt())
                    setStroke(dp(context, 1).coerceAtLeast(1), 0x33FFFFFF)
                }
                visibility = View.GONE
            }
            val bBix = BixSpriteView(context, BixAction.Coding)
            badgeBix = bBix
            badge.addView(bBix, LinearLayout.LayoutParams(dp(context, 27), dp(context, 20)).apply { marginEnd = dp(context, 4) })
            val ring = StepRingView(context)
            stepRing = ring
            badge.addView(ring, LinearLayout.LayoutParams(dp(context, 15), dp(context, 15)).apply { marginEnd = dp(context, 5) })
            val sText = TextView(context).apply {
                setTextColor(0xFFF3F5F8.toInt())
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            }
            stepText = sText
            badge.addView(sText)
            stepBadge = badge
            previewWrap.addView(
                badge,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(context, 6)
                    marginEnd = dp(context, 6)
                }
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
            // Bix head instead of the plain dot
            val pBix = BixSpriteView(context, BixAction.Idle)
            pillBix = pBix
            pill.addView(pBix, LinearLayout.LayoutParams(dp(context, 19), dp(context, 18)).apply {
                marginEnd = dp(context, 6)
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
                // Clamp: a position saved in another orientation could leave the window off-screen.
                val dm = context.resources.displayMetrics
                x = savedX(context).coerceIn(0, (dm.widthPixels - dp(context, 120)).coerceAtLeast(0))
                y = savedY(context).coerceIn(0, (dm.heightPixels - dp(context, 60)).coerceAtLeast(0))
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
        val agentActive = AgentProgress.active.value
        val stuck = agentActive && AgentProgress.stuck.value
        val step = AgentProgress.step.intValue
        val maxSteps = AgentProgress.maxSteps.intValue
        val label = AgentProgress.label.value
        val state = when {
            !active -> "待机"
            paused -> "已暂停"
            stuck -> "可能卡住了"
            agentActive && label.isNotBlank() -> label
            else -> "操控中"
        }
        setTextIfChanged(statusText, "$title · $state")
        val pillState = when {
            finishedNote && !agentActive -> "已完成"
            !active -> "待机"
            paused -> "已暂停"
            stuck -> "卡住了"
            agentActive && maxSteps > 0 -> "$step/$maxSteps"
            else -> "操控中"
        }
        setTextIfChanged(collapsedPillText, "虚拟屏 · $pillState")
        (statusDot?.background as? GradientDrawable)?.setColor(
            when {
                !active -> 0xFF94A3B8.toInt()
                paused -> 0xFFFFC83D.toInt()
                stuck -> AMBER
                else -> 0xFF4ADE80.toInt()
            }
        )
        updateProgressViews(agentActive, stuck, step, maxSteps)
    }

    private fun setTextIfChanged(view: TextView?, text: String) {
        val v = view ?: return
        if (v.text?.toString() != text) v.text = text
    }

    /** Step badge + mini Bix poses; kicks the glow ticker when the Agent is acting. */
    private fun updateProgressViews(agentActive: Boolean, stuck: Boolean, step: Int, maxSteps: Int) {
        if (agentActive) sawRun = true
        val done = !agentActive && sawRun
        val badge = stepBadge
        if (badge != null) {
            val show = agentActive || done
            val want = if (show) View.VISIBLE else View.GONE
            if (badge.visibility != want) badge.visibility = want
        }
        val accent = ThemeColor.currentColor.toArgb()
        val ringColor = if (stuck) AMBER else accent
        stepRing?.set(if (maxSteps > 0) step.toFloat() / maxSteps.toFloat() else -1f, ringColor)
        setTextIfChanged(
            stepText,
            when {
                done -> "完成"
                stuck -> "卡住"
                maxSteps > 0 -> "$step/$maxSteps"
                else -> "操控"
            }
        )
        badgeBix?.setAction(
            when {
                stuck -> BixAction.Dizzy
                done -> BixAction.Done
                else -> BixAction.Coding
            }
        )
        pillBix?.setAction(
            when {
                stuck -> BixAction.Dizzy
                agentActive -> BixAction.Coding
                done || finishedNote -> BixAction.Done
                else -> BixAction.Idle
            }
        )
        glowView?.setColor(ringColor)
        ensureGlowTicking()
    }

    private fun glowTarget(): Float {
        if (!running || collapsed || VirtualScreen.paused.value || !VirtualScreen.active.value) return 0f
        if (!AgentProgress.active.value) return 0f
        val since = System.currentTimeMillis() - AgentProgress.lastActionMs.longValue
        return if (since <= GLOW_FADE_AFTER_MS) 1f else 0f
    }

    private fun ensureGlowTicking() {
        if (!running) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { runCatching { ensureGlowTicking() } }
            return
        }
        if (GlassStyle.reduceMotion.value) {
            // Static: binary glow, no frame loop.
            glowAlpha = glowTarget()
            glowView?.let { it.glow = glowAlpha; it.phase = 0.125f; it.invalidate() }
            return
        }
        val animateBix = AgentProgress.active.value
        if (glowTicking) return
        if (glowTarget() <= 0f && glowAlpha <= 0f && !animateBix) return
        glowTicking = true
        lastGlowFrameMs = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(glowFrame)
    }

    private fun glowTick() {
        glowTicking = false
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastGlowFrameMs).coerceIn(0L, 100L).toFloat()
        lastGlowFrameMs = now
        val target = glowTarget()
        glowAlpha = if (target > glowAlpha) (glowAlpha + dt / 220f).coerceAtMost(target)
        else (glowAlpha - dt / 480f).coerceAtLeast(target)
        glowView?.let {
            val was = it.glow
            it.glow = glowAlpha
            it.phase = (now % 3_600L) / 3_600f
            if (glowAlpha > 0f || was > 0f) it.invalidate()
        }
        val bixPhase = (now % 1_000L) / 1_000f
        val animateBix = AgentProgress.active.value
        if (animateBix) {
            if (!collapsed) badgeBix?.setPhase(bixPhase) else pillBix?.setPhase(bixPhase)
        }
        if (glowAlpha > 0f || target > 0f || animateBix) {
            glowTicking = true
            Choreographer.getInstance().postFrameCallback(glowFrame)
        }
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
            cancelIdleAutoClose()
            finishedNote = true
            collapsedPillText?.text = "虚拟屏 · 已完成"
            pillBix?.setAction(BixAction.Done)
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
        finishedNote = false
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

    /** Preview width in pixels for the current zoom step (frames are downsized to this). */
    private fun previewMaxWidth(): Int {
        val screenW = appCtx?.resources?.displayMetrics?.widthPixels ?: 720
        return (screenW * SCALE_STEPS[scaleIdx.coerceIn(0, SCALE_STEPS.lastIndex)]).roundToInt().coerceAtLeast(160)
    }

    /**
     * v0.7.0: frames are pulled and scaled on a background thread to the preview size (a few
     * hundred pixels wide instead of a ~10 MB full-resolution copy on the main thread per tick), and
     * only when the display produced a new frame. Displayed bitmaps are never recycled here: the
     * RenderThread may still be drawing the previous one, so the GC reclaims them.
     */
    private fun startFrameLoop() {
        stopFrameLoop()
        val thread = HandlerThread("bothive-vd-preview").apply { start() }
        val fh = Handler(thread.looper)
        frameThread = thread
        frameHandler = fh
        var lastSeq = -1L
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                if (!VirtualScreen.paused.value && !collapsed) {
                    val next = VirtualScreen.previewFrame(previewMaxWidth(), lastSeq)
                    if (next != null) lastSeq = next.second
                    handler.post {
                        if (!running) return@post
                        if (next != null) {
                            preview?.setImageBitmap(next.first)
                            shownFrame = next.first
                        }
                        refreshStatus()
                    }
                } else {
                    // Paused or collapsed: no frame pull, but keep the pill / step badge in sync.
                    handler.post { if (running) refreshStatus() }
                }
                fh.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
        fh.post(tick)
    }

    private fun stopFrameLoop() {
        frameHandler?.removeCallbacksAndMessages(null)
        frameThread?.quitSafely()
        frameHandler = null
        frameThread = null
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
        stopFrameLoop()
        runCatching { Choreographer.getInstance().removeFrameCallback(glowFrame) }
        glowTicking = false
        glowAlpha = 0f
        glowView = null
        stepBadge = null
        stepRing = null
        stepText = null
        badgeBix = null
        pillBix = null
        val view = root
        val manager = wm
        if (view != null && manager != null) runCatching { manager.removeView(view) }
        // Detach instead of recycling: the RenderThread may still hold the last frame.
        runCatching { preview?.setImageDrawable(null) }
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

/** Progress ring for the step badge: track + arc of [progress] (0..1, < 0 = indeterminate dot). */
internal class StepRingView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x33FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var progress = -1f
    private var color = 0xFFFFC83D.toInt()

    fun set(p: Float, argb: Int) {
        val np = if (p < 0f) -1f else p.coerceIn(0f, 1f)
        if (np == progress && argb == color) return
        progress = np
        color = argb
        arc.color = argb
        dot.color = argb
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = arc.strokeWidth / 2f + 0.5f
        oval.set(inset, inset, w - inset, h - inset)
        canvas.drawOval(oval, track)
        if (progress < 0f) {
            canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) * 0.16f, dot)
        } else if (progress > 0f) {
            canvas.drawArc(oval, -90f, 360f * progress, false, arc)
        }
    }
}

/** Tiny View that draws a Bix pose via [BixSprite]; the owner advances [setPhase]. */
internal class BixSpriteView(context: Context, initial: BixAction) : View(context) {
    private val sprite = BixSprite(context.resources.displayMetrics.density).apply { action = initial }
    private var phase = 0.25f

    init {
        isClickable = false
        isFocusable = false
    }

    fun setAction(a: BixAction) {
        if (sprite.action == a) return
        sprite.action = a
        invalidate()
    }

    fun setPhase(p: Float) {
        phase = p
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        sprite.draw(canvas, 0f, 0f, width.toFloat(), height.toFloat(), phase, compact = true)
    }
}
