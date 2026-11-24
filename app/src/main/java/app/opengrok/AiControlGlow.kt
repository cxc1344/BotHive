package app.opengrok

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import java.lang.ref.WeakReference
import kotlin.math.atan2

/**
 * Full-screen, non-touchable accessibility overlay with a slowly flowing accent glow along the screen
 * edge while the Agent drives the phone (Siri-style "AI is in control" cue). A small Bix rides along the
 * edge and turns Dizzy (glow amber) when [AgentProgress.stuck].
 *
 * - Window: TYPE_ACCESSIBILITY_OVERLAY created with the AccessibilityService context,
 *   FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN: it never intercepts input.
 * - Animation: Choreographer frame callbacks on the main thread; auto-fades ~2.5 s after the last
 *   [pulse]; on system "remove animations" the glow is static and simply disappears after the timeout.
 * - Every entry point is wrapped in runCatching: this object never throws.
 *
 * Call [pulse] after every main-screen gesture (any thread). [hide] removes it immediately.
 * Screenshots: the overlay may be visible in AccessibilityService.takeScreenshot; call
 * [pauseForCapture] right before capturing (wait one frame, ~50 ms) so the frame hash stays stable.
 */
object AiControlGlow {
    private const val FADE_AFTER_MS = 2_500L
    private const val FADE_IN_MS = 220f
    private const val FADE_OUT_MS = 480f
    private const val FLOW_MS = 4_200L
    private const val LAP_MS = 14_000L
    private const val STATIC_TICK_MS = 250L
    private const val AMBER = 0xFFFFB020.toInt()

    private val main = Handler(Looper.getMainLooper())
    private var serviceRef: WeakReference<AccessibilityService>? = null
    private var wm: WindowManager? = null
    private var view: EdgeGlowView? = null

    @Volatile
    private var lastPulseMs = 0L
    private var shownAtMs = 0L
    private var ticking = false
    private var captureHiddenUntil = 0L

    private val frameCallback = Choreographer.FrameCallback { tickSafely() }
    private val staticTick = Runnable { tickSafely() }

    /** Shows (or keeps alive) the glow. Safe from any thread; never throws. */
    fun pulse(service: AccessibilityService) {
        runCatching {
            lastPulseMs = SystemClock.uptimeMillis()
            if (Looper.myLooper() == Looper.getMainLooper()) pulseOnMain(service)
            else main.post { runCatching { pulseOnMain(service) } }
        }
    }

    /** Removes the overlay immediately. Safe from any thread; never throws. */
    fun hide() {
        runCatching {
            if (Looper.myLooper() == Looper.getMainLooper()) hideNow() else main.post { hideNow() }
        }
    }

    /** True while the overlay window is attached. */
    fun isShowing(): Boolean = view != null

    /** Temporarily makes the overlay invisible (e.g. while the Agent takes a screenshot). */
    fun pauseForCapture(ms: Long = 450L) {
        runCatching {
            val until = SystemClock.uptimeMillis() + ms.coerceIn(50L, 3_000L)
            main.post {
                runCatching {
                    captureHiddenUntil = until
                    view?.visibility = View.INVISIBLE
                    ensureTicking()
                }
            }
        }
    }

    private fun pulseOnMain(service: AccessibilityService) {
        val current = serviceRef?.get()
        if (view != null && current !== service) hideNow()
        if (view == null) {
            val manager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val v = EdgeGlowView(service, vignette = true, strokeScale = 1f, withBix = true)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.fitInsetsTypes = 0
            }
            lp.title = "BotHive AI control glow"
            v.isClickable = false
            v.isFocusable = false
            manager.addView(v, lp)
            wm = manager
            view = v
            serviceRef = WeakReference(service)
            shownAtMs = SystemClock.uptimeMillis()
        }
        ensureTicking()
    }

    private fun ensureTicking() {
        if (ticking || view == null) return
        ticking = true
        if (GlassStyle.reduceMotion.value) main.postDelayed(staticTick, STATIC_TICK_MS)
        else Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun tickSafely() {
        ticking = false
        runCatching { tick() }.onFailure { hideNow() }
    }

    private fun tick() {
        val v = view ?: return
        val now = SystemClock.uptimeMillis()
        val reduce = GlassStyle.reduceMotion.value
        val idle = now - lastPulseMs
        val alpha = if (reduce) {
            if (idle <= FADE_AFTER_MS) 1f else 0f
        } else {
            val fadeIn = ((now - shownAtMs) / FADE_IN_MS).coerceIn(0f, 1f)
            val fadeOut = if (idle <= FADE_AFTER_MS) 1f else (1f - (idle - FADE_AFTER_MS) / FADE_OUT_MS).coerceIn(0f, 1f)
            fadeIn * fadeOut
        }
        if (alpha <= 0f) {
            hideNow()
            return
        }
        val stuck = AgentProgress.stuck.value
        v.setColor(if (stuck) AMBER else ThemeColor.currentColor.toArgb())
        v.setBixAction(if (stuck) BixAction.Dizzy else BixAction.Idle)
        val hidden = now < captureHiddenUntil
        val wantVisibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (v.visibility != wantVisibility) v.visibility = wantVisibility
        val breath = if (reduce) 1f else 0.86f + 0.14f * kotlin.math.sin((now % 2_000L) / 2_000f * 2f * Math.PI).toFloat()
        v.glow = alpha * breath
        v.phase = if (reduce) 0.125f else (now % FLOW_MS) / FLOW_MS.toFloat()
        v.lap = if (reduce) 0.08f else (now % LAP_MS) / LAP_MS.toFloat()
        v.bixPhase = if (reduce) 0.25f else (now % 1_300L) / 1_300f
        v.invalidate()
        ticking = true
        if (reduce) main.postDelayed(staticTick, STATIC_TICK_MS)
        else Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun hideNow() {
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        main.removeCallbacks(staticTick)
        ticking = false
        val v = view
        val manager = wm
        view = null
        wm = null
        serviceRef = null
        captureHiddenUntil = 0L
        if (v != null && manager != null) runCatching { manager.removeView(v) }
    }
}

/**
 * Edge glow renderer shared by [AiControlGlow] (full screen) and [VirtualDisplayOverlay] (preview frame).
 * All shaders/paints are rebuilt only on size or colour changes; onDraw allocates nothing.
 * The owner drives [glow] (0..1 alpha), [phase] (0..1 flow), [lap] (0..1 Bix position) and invalidates.
 */
internal class EdgeGlowView(
    context: Context,
    private val vignette: Boolean,
    private val strokeScale: Float,
    private val withBix: Boolean
) : View(context) {
    private val density = context.resources.displayMetrics.density
    var cornerRadiusPx: Float = 30f * density
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgePaint = Paint()
    private val rect = RectF()
    private val matrix = Matrix()
    private var sweep: SweepGradient? = null
    private var gTop: LinearGradient? = null
    private var gBottom: LinearGradient? = null
    private var gLeft: LinearGradient? = null
    private var gRight: LinearGradient? = null
    private var colorArgb = 0
    private val edgePx = 26f * density
    private val sprite: BixSprite? = if (withBix) BixSprite(density) else null
    private var spriteW = 0f
    private var spriteH = 0f
    private val spriteMargin = 10f * density

    var glow = 0f
    var phase = 0f
    var lap = 0f
    var bixPhase = 0f

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
        setBixAction(BixAction.Idle)
    }

    fun setColor(argb: Int) {
        if (argb == colorArgb && sweep != null) return
        colorArgb = argb
        rebuild()
    }

    fun setBixAction(action: BixAction) {
        val s = sprite ?: return
        if (s.action == action && spriteH > 0f) return
        s.action = action
        // Compact sprite, 28 dp tall (width follows the pose's aspect ratio).
        val dims = bixSceneSize(action, true)
        spriteH = 28f * density
        spriteW = spriteH * (dims.first.value / dims.second.value)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val r = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
                if (r > 0) cornerRadiusPx = r.toFloat()
            }
        }
        return super.onApplyWindowInsets(insets)
    }

    private fun withAlpha(argb: Int, a: Float): Int =
        (argb and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    private fun rebuild() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val c = colorArgb
        val clear = withAlpha(c, 0f)
        sweep = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(clear, withAlpha(c, 0.30f), c, withAlpha(c, 0.65f), clear, withAlpha(c, 0.18f), clear),
            floatArrayOf(0f, 0.16f, HEAD, 0.34f, 0.55f, 0.78f, 1f)
        )
        if (vignette) {
            val edgeC = withAlpha(c, 0.30f)
            gTop = LinearGradient(0f, 0f, 0f, edgePx, edgeC, clear, Shader.TileMode.CLAMP)
            gBottom = LinearGradient(0f, h, 0f, h - edgePx, edgeC, clear, Shader.TileMode.CLAMP)
            gLeft = LinearGradient(0f, 0f, edgePx, 0f, edgeC, clear, Shader.TileMode.CLAMP)
            gRight = LinearGradient(w, 0f, w - edgePx, 0f, edgeC, clear, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = glow
        val sw = sweep ?: return
        if (g <= 0.001f) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (vignette) {
            edgePaint.alpha = (g * 255f).toInt().coerceIn(0, 255)
            gTop?.let { edgePaint.shader = it; canvas.drawRect(0f, 0f, w, edgePx, edgePaint) }
            gBottom?.let { edgePaint.shader = it; canvas.drawRect(0f, h - edgePx, w, h, edgePaint) }
            gLeft?.let { edgePaint.shader = it; canvas.drawRect(0f, 0f, edgePx, h, edgePaint) }
            gRight?.let { edgePaint.shader = it; canvas.drawRect(w - edgePx, 0f, w, h, edgePaint) }
        }

        // Bix position along the inner perimeter (clockwise from top-left); the glow's head follows Bix.
        var headDeg = phase * 360f
        var bx = 0f
        var by = 0f
        val s = sprite
        if (s != null && spriteW > 0f) {
            val left = spriteMargin
            val top = spriteMargin
            val rw = (w - spriteMargin * 2f - spriteW).coerceAtLeast(1f)
            val rh = (h - spriteMargin * 2f - spriteH).coerceAtLeast(1f)
            val per = 2f * (rw + rh)
            var d = lap.coerceIn(0f, 1f) * per
            s.mirrored = false
            if (d < rw) {
                bx = left + d; by = top
            } else {
                d -= rw
                if (d < rh) {
                    bx = left + rw; by = top + d
                } else {
                    d -= rh
                    if (d < rw) {
                        bx = left + rw - d; by = top + rh; s.mirrored = true
                    } else {
                        d -= rw
                        bx = left; by = top + rh - d
                    }
                }
            }
            val cx = bx + spriteW / 2f - w / 2f
            val cy = by + spriteH / 2f - h / 2f
            headDeg = Math.toDegrees(atan2(cy.toDouble(), cx.toDouble())).toFloat() - HEAD * 360f
        }
        matrix.setRotate(headDeg, w / 2f, h / 2f)
        sw.setLocalMatrix(matrix)
        strokePaint.shader = sw
        val r = cornerRadiusPx
        for (k in 0 until STROKE_WIDTHS_DP.size) {
            val sWidth = STROKE_WIDTHS_DP[k] * density * strokeScale
            val inset = sWidth / 2f
            rect.set(inset, inset, w - inset, h - inset)
            strokePaint.strokeWidth = sWidth
            strokePaint.alpha = (g * STROKE_ALPHAS[k] * 255f).toInt().coerceIn(0, 255)
            val rr = (r - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, rr, rr, strokePaint)
        }
        if (s != null && spriteW > 0f && g > 0.05f) {
            s.draw(canvas, bx, by, spriteW, spriteH, bixPhase, compact = true)
        }
    }

    companion object {
        /** Gradient position of the bright head. */
        const val HEAD = 0.30f
        private val STROKE_WIDTHS_DP = floatArrayOf(14f, 6f, 2f)
        private val STROKE_ALPHAS = floatArrayOf(0.16f, 0.42f, 1f)
    }
}
