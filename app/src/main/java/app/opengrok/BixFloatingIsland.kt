package app.opengrok

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v0.7.2 Pure-Android Bix Floating Dynamic Island ("灵动蜂巢岛").
 *
 * Floats a Titanium Liquid Glass pill with an animated 14x13 authentic pixel Bix over any Android
 * app. Supports:
 *  - Smooth dragging + magnetic snap to nearest screen edge.
 *  - Live status ticker (active Super-Agent tickets, active DAG relay, or standby heartbeat).
 *  - 1-tap expand into a mini Liquid Glass quick-command pod to dispatch tickets without leaving
 *    the current app.
 */
object BixFloatingIsland {
    val active = mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var collapsedPill: LinearLayout? = null
    private var expandedCard: LinearLayout? = null
    private var statusTv: TextView? = null
    private var bixView: MiniBixCanvasView? = null
    private var expanded = false
    private var storeRef: Store? = null
    private var appCtx: Context? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!active.value) return
            updateTicker()
            bixView?.invalidate()
            main.postDelayed(this, 180L)
        }
    }

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    fun requestPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
        }
    }

    fun toggle(ctx: Context, store: Store): Boolean {
        if (active.value) {
            hide()
            return false
        }
        return show(ctx, store)
    }

    fun show(ctx: Context, store: Store): Boolean {
        if (!hasPermission(ctx)) {
            Toast.makeText(ctx, "请先授予 BotHive 悬浮窗权限，以开启 Bix 灵动蜂巢岛", Toast.LENGTH_LONG).show()
            requestPermission(ctx)
            return false
        }
        val app = ctx.applicationContext
        appCtx = app
        storeRef = store
        main.post { attachWindow(app) }
        return true
    }

    fun hide() {
        main.post {
            main.removeCallbacks(ticker)
            runCatching { root?.let { wm?.removeView(it) } }
            root = null
            collapsedPill = null
            expandedCard = null
            statusTv = null
            bixView = null
            expanded = false
            active.value = false
        }
    }

    private fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun glassBg(ctx: Context, radiusDp: Float, bgColor: Int = 0xE6141720.toInt(), strokeColor: Int = 0x38FFFFFF): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(bgColor)
            setStroke(dp(ctx, 1f).coerceAtLeast(1), strokeColor)
        }

    private fun attachWindow(ctx: Context) {
        if (root != null) return
        val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = windowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(ctx, 14f)
            y = dp(ctx, 110f)
        }
        lp = params

        val container = FrameLayout(ctx)
        val pill = buildCollapsedPill(ctx, params, windowManager)
        val card = buildExpandedCard(ctx, params, windowManager)
        card.visibility = View.GONE

        container.addView(pill)
        container.addView(card)

        root = container
        collapsedPill = pill
        expandedCard = card
        expanded = false

        runCatching {
            windowManager.addView(container, params)
            active.value = true
            updateTicker()
            main.post(ticker)
        }.onFailure {
            root = null
            active.value = false
        }
    }

    private fun buildCollapsedPill(
        ctx: Context,
        params: WindowManager.LayoutParams,
        windowManager: WindowManager
    ): LinearLayout {
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBg(ctx, 22f)
            setPadding(dp(ctx, 10f), dp(ctx, 7f), dp(ctx, 13f), dp(ctx, 7f))
            elevation = dp(ctx, 10f).toFloat()
        }

        val bix = MiniBixCanvasView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 30f), dp(ctx, 26f))
        }
        bixView = bix

        val dot = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 7f), dp(ctx, 7f)).apply {
                marginStart = dp(ctx, 6f)
                marginEnd = dp(ctx, 6f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF9DDC77.toInt())
            }
        }

        val tv = TextView(ctx).apply {
            text = "Bix 蜂巢待命"
            setTextColor(0xFFF6F7F9.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(ctx, 150f)
        }
        statusTv = tv

        pill.addView(bix)
        pill.addView(dot)
        pill.addView(tv)

        // Draggable + magnetic snap to nearest screen edge + click to expand
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val touchSlop = dp(ctx, 6f)

        pill.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).roundToInt()
                    val dy = (ev.rawY - downRawY).roundToInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    params.x = startX + dx
                    params.y = (startY + dy).coerceAtLeast(dp(ctx, 32f))
                    runCatching { root?.let { windowManager.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        setExpanded(ctx, true)
                    } else {
                        val screenW = ctx.resources.displayMetrics.widthPixels
                        val pillW = pill.width.coerceAtLeast(dp(ctx, 130f))
                        val targetX = if (params.x + pillW / 2 < screenW / 2) dp(ctx, 10f)
                        else (screenW - pillW - dp(ctx, 10f)).coerceAtLeast(dp(ctx, 10f))
                        ValueAnimator.ofInt(params.x, targetX).apply {
                            duration = 260L
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { anim ->
                                params.x = anim.animatedValue as Int
                                runCatching { root?.let { windowManager.updateViewLayout(it, params) } }
                            }
                            start()
                        }
                    }
                    true
                }
                else -> false
            }
        }
        return pill
    }

    private fun buildExpandedCard(
        ctx: Context,
        params: WindowManager.LayoutParams,
        windowManager: WindowManager
    ): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBg(ctx, 20f, bgColor = 0xF212151E.toInt(), strokeColor = 0x48FFD166)
            setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
            layoutParams = FrameLayout.LayoutParams(dp(ctx, 290f), FrameLayout.LayoutParams.WRAP_CONTENT)
            elevation = dp(ctx, 14f).toFloat()
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = "Bix 灵动蜂巢岛 · 快速指令"
            setTextColor(0xFFFFD166.toInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val collapseBtn = makeChip(ctx, "收起") { setExpanded(ctx, false) }
        topRow.addView(title)
        topRow.addView(collapseBtn)

        val input = EditText(ctx).apply {
            hint = "一句话派发给 Super-Agent…"
            setHintTextColor(0xFF7E8490.toInt())
            setTextColor(0xFFF6F7F9.toInt())
            textSize = 13f
            background = glassBg(ctx, 12f, bgColor = 0xFF1A1E29.toInt(), strokeColor = 0x26FFFFFF)
            setPadding(dp(ctx, 10f), dp(ctx, 8f), dp(ctx, 10f), dp(ctx, 8f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 10f)
                bottomMargin = dp(ctx, 10f)
            }
        }

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sendBtn = makeChip(ctx, "派发工单", fill = true) {
            val txt = input.text?.toString()?.trim().orEmpty()
            val st = storeRef
            if (txt.isNotBlank() && st != null) {
                val owner = st.chiefId.value.takeIf { it.isNotBlank() } ?: AGENT_ID
                val chatId = if (owner == AGENT_ID) AGENT_ID else "bot:$owner"
                val tk = SuperAgent.createTicket(title = txt.take(28), goal = txt, ownerBotId = owner, priority = 1, source = "floating_island", threadChatId = chatId)
                SuperAgent.updateTicket(tk.id, checkpoint = "来自灵动蜂巢岛")
                if (owner == AGENT_ID) st.sendAgent(txt) else st.send(chatId, txt)
                input.setText("")
                Toast.makeText(ctx, "已通过灵动岛派发工单 ${tk.id}", Toast.LENGTH_SHORT).show()
                setExpanded(ctx, false)
            }
        }
        val openAppBtn = makeChip(ctx, "回 BotHive") {
            setExpanded(ctx, false)
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { ctx.startActivity(i) }
        }
        val closeBtn = makeChip(ctx, "关闭") { hide() }

        btnRow.addView(sendBtn)
        btnRow.addView(space(ctx, 6f))
        btnRow.addView(openAppBtn)
        btnRow.addView(space(ctx, 6f))
        btnRow.addView(closeBtn)

        card.addView(topRow)
        card.addView(input)
        card.addView(btnRow)
        return card
    }

    private fun space(ctx: Context, wDp: Float): View =
        View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(ctx, wDp), 1) }

    private fun makeChip(ctx: Context, label: String, fill: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (fill) 0xFF0B0C0E.toInt() else 0xFFF6F7F9.toInt())
            background = glassBg(
                ctx, 14f,
                bgColor = if (fill) 0xFFFFD166.toInt() else 0x22FFFFFF,
                strokeColor = if (fill) 0x00000000 else 0x33FFFFFF
            )
            setPadding(dp(ctx, 10f), dp(ctx, 6f), dp(ctx, 10f), dp(ctx, 6f))
            setOnClickListener { onClick() }
        }

    private fun setExpanded(ctx: Context, open: Boolean) {
        val params = lp ?: return
        val windowManager = wm ?: return
        expanded = open
        collapsedPill?.visibility = if (open) View.GONE else View.VISIBLE
        expandedCard?.visibility = if (open) View.VISIBLE else View.GONE
        params.flags = if (open) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        runCatching { root?.let { windowManager.updateViewLayout(it, params) } }
    }

    private fun updateTicker() {
        val activeTk = SuperAgent.tickets.count { it.status == TicketStatus.RUNNING || it.status == TicketStatus.QUEUED }
        val activePipe = HivePipeline.pipelines.count { p -> p.stages.any { it.status == StageStatus.RUNNING } }
        val label = when {
            LinuxDesktop.activeAiOwner.value.isNotBlank() -> "（${LinuxDesktop.activeAiOwner.value}）正在用桌面"
            activePipe > 0 -> "接力流水线 $activePipe 进行中"
            activeTk > 0 -> "蜂巢活跃工单 $activeTk"
            else -> "Bix 蜂巢待命"
        }
        statusTv?.text = label
    }

    /** Authentic 14x13 pixel Bix renderer for Android View Canvas inside the floating island. */
    private class MiniBixCanvasView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var tick = 0

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val u = minOf(width / 14f, height / 13f)
            val ox = (width - 14f * u) * 0.5f
            val oy = (height - 13f * u) * 0.5f
            val wingUp = (tick++ % 2) == 0

            fun p(x: Float, y: Float, w: Float, h: Float, col: Int) {
                paint.color = col
                canvas.drawRect(ox + x * u, oy + y * u, ox + (x + w) * u, oy + (y + h) * u, paint)
            }

            val outline = 0xFF2B2118.toInt()
            val gold = 0xFFFFC83D.toInt()
            val goldLight = 0xFFFFE88A.toInt()
            val stripe = 0xFF4A3322.toInt()
            val coral = 0xFFFF8F5C.toInt()
            val wing = 0xFFEAF8FB.toInt()
            val wingEdge = 0xFF9AD3E3.toInt()
            val cheek = 0xFFFF9AA8.toInt()

            val wy = if (wingUp) 2f else 4f
            p(0f, wy, 3f, 3f, wingEdge); p(0.6f, wy + 0.5f, 2f, 2f, wing)
            p(11f, wy, 3f, 3f, wingEdge); p(11.4f, wy + 0.5f, 2f, 2f, wing)

            // Antennae
            p(4f, 0f, 1f, 2f, coral); p(9f, 0f, 1f, 2f, coral)
            p(4f, 2f, 1f, 1f, outline); p(9f, 2f, 1f, 1f, outline)

            // Body
            p(3f, 3f, 8f, 1f, outline); p(2f, 4f, 10f, 6f, outline)
            p(3f, 10f, 8f, 2f, outline); p(5f, 12f, 4f, 1f, outline)
            p(3f, 4f, 8f, 2f, goldLight); p(3f, 6f, 8f, 3f, gold)
            p(3f, 9f, 8f, 1.3f, stripe); p(4f, 10.3f, 6f, 1f, gold)

            // Cheeks & sparkling eyes
            p(3.2f, 7.6f, 1.2f, 0.9f, cheek); p(9.6f, 7.6f, 1.2f, 0.9f, cheek)
            p(4.7f, 6.0f, 1.3f, 1.5f, outline); p(8.0f, 6.0f, 1.3f, 1.5f, outline)
            p(4.9f, 6.1f, 0.5f, 0.5f, Color.WHITE); p(8.2f, 6.1f, 0.5f, 0.5f, Color.WHITE)
            p(6.2f, 7.9f, 1.6f, 0.7f, outline)

            // Sync user's BixPrefs.accessory!
            if (BixPrefs.accessory == BixAccessory.Shades) {
                p(3.2f, 5.8f, 7.6f, 0.6f, 0xFF14161A.toInt())
                p(3.7f, 6.2f, 2.8f, 1.4f, 0xFF14161A.toInt())
                p(7.5f, 6.2f, 2.8f, 1.4f, 0xFF14161A.toInt())
            } else if (BixPrefs.accessory == BixAccessory.Crown) {
                p(3.5f, 0.4f, 1.4f, 2.2f, goldLight)
                p(6.3f, 0f, 1.4f, 2.6f, goldLight)
                p(9.1f, 0.4f, 1.4f, 2.2f, goldLight)
                p(3.5f, 1.8f, 7.0f, 1.1f, gold)
            }
        }
    }
}
