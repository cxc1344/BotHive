package app.opengrok

import android.graphics.Matrix
import android.graphics.SweepGradient
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * v0.7.0 motion kit (Worker C). Every animated value is read in the DRAW phase only, every Brush /
 * Shader / Path / Stroke is built once in drawWithCache, and every helper honours
 * GlassStyle.motionEnabled (glass tier + system "remove animations").
 */

/** Semantic motion colours. */
val MotionAmber = Color(0xFFFFB020)
val MotionGreen = Color(0xFF4ADE80)
val MotionRed = Color(0xFFFF5A4E)

/** Status ring states for avatars / pills. */
enum class HaloState { Idle, Thinking, Working, Waiting }

/**
 * Animated status ring drawn around the content's bounds (outside it, [gap] away).
 *  - Idle: nothing.
 *  - Thinking: slow breathing ring.
 *  - Working: rotating comet sweep (+ an optional tiny Bix-wing flutter at the comet head).
 *  - Waiting: amber pulse (always amber, [color] ignored).
 * [cornerRadius] = Dp.Unspecified draws a circle (avatars); any other value draws a rounded rect ring.
 * The ring is drawn outside the bounds, so keep a few dp of room or an unclipped parent.
 */
fun Modifier.statusHalo(
    state: HaloState,
    color: Color = ThemeAccent,
    cornerRadius: Dp = Dp.Unspecified,
    gap: Dp = 3.dp,
    bixWings: Boolean = true
): Modifier =
    if (state == HaloState.Idle) this
    else this.then(Modifier.composed { haloModifier(state, color, cornerRadius, gap, bixWings) })

private fun haloCycleMs(state: HaloState): Int = when (state) {
    HaloState.Thinking -> 2400
    HaloState.Working -> 1500
    HaloState.Waiting -> 1300
    HaloState.Idle -> 1000
}

/** Sweep head sits at this fraction of the gradient (see the colour stops below). */
private const val SWEEP_HEAD = 0.30f

@Composable
private fun haloModifier(state: HaloState, color: Color, cornerRadius: Dp, gap: Dp, bixWings: Boolean): Modifier {
    val animate = GlassStyle.motionEnabled
    val ringColor = if (state == HaloState.Waiting) MotionAmber else color
    val phase: State<Float> = if (animate) {
        val inf = rememberInfiniteTransition(label = "halo")
        inf.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(haloCycleMs(state), easing = LinearEasing), RepeatMode.Restart),
            label = "halo-phase"
        )
    } else {
        remember { mutableFloatStateOf(0.25f) }
    }
    return Modifier.drawWithCache {
        val gapPx = gap.toPx()
        val strokeW = 2.dp.toPx()
        val circle = cornerRadius == Dp.Unspecified
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)
        val radius = minOf(size.width, size.height) / 2f + gapPx
        val ringTopLeft = Offset(-gapPx, -gapPx)
        val ringSize = Size(size.width + gapPx * 2f, size.height + gapPx * 2f)
        val cr = if (circle) radius else cornerRadius.toPx() + gapPx
        val corner = CornerRadius(cr, cr)
        val clear = ringColor.copy(alpha = 0f).toArgb()
        val sweep = SweepGradient(
            cx, cy,
            intArrayOf(clear, ringColor.copy(alpha = 0.35f).toArgb(), ringColor.toArgb(), clear, clear),
            floatArrayOf(0f, 0.18f, SWEEP_HEAD, 0.31f, 1f)
        )
        val sweepBrush = ShaderBrush(sweep)
        val matrix = Matrix()
        val thin = Stroke(strokeW)
        val wide = Stroke(strokeW * 3.2f)
        val wingW = 2.4.dp.toPx()
        val wingH = 1.6.dp.toPx()
        val halfW = ringSize.width / 2f
        val halfH = ringSize.height / 2f
        onDrawWithContent {
            drawContent()
            val p = phase.value
            when (state) {
                HaloState.Thinking -> {
                    val breath = 0.5f + 0.5f * sin(p * 2f * PI).toFloat()
                    haloRing(circle, center, radius, ringTopLeft, ringSize, corner, ringColor, 0.10f * breath, wide)
                    haloRing(circle, center, radius, ringTopLeft, ringSize, corner, ringColor, 0.25f + 0.55f * breath, thin)
                }
                HaloState.Working -> {
                    val deg = p * 360f
                    matrix.setRotate(deg, cx, cy)
                    sweep.setLocalMatrix(matrix)
                    haloRing(circle, center, radius, ringTopLeft, ringSize, corner, ringColor, 0.16f, thin)
                    haloRingBrush(circle, center, radius, ringTopLeft, ringSize, corner, sweepBrush, 0.35f, wide)
                    haloRingBrush(circle, center, radius, ringTopLeft, ringSize, corner, sweepBrush, 1f, thin)
                    if (bixWings && animate) {
                        // A tiny Bix-wing flutter riding the comet head (subtle: two 2 dp pixels).
                        val a = ((deg + SWEEP_HEAD * 360f) / 180f * PI).toFloat()
                        val dx = cos(a)
                        val dy = sin(a)
                        val reach = if (circle) radius else {
                            val tx = if (abs(dx) > 0.0001f) halfW / abs(dx) else Float.MAX_VALUE
                            val ty = if (abs(dy) > 0.0001f) halfH / abs(dy) else Float.MAX_VALUE
                            minOf(tx, ty)
                        }
                        val hx = cx + dx * reach
                        val hy = cy + dy * reach
                        val up = ((p * 24f).toInt() % 2) == 0
                        val lift = if (up) wingH * 1.4f else wingH * 0.4f
                        drawRect(BeeWingMist, Offset(hx - wingW * 1.1f, hy - lift - wingH), Size(wingW, wingH), alpha = 0.85f)
                        drawRect(BeeWingMist, Offset(hx + wingW * 0.1f, hy - lift - wingH), Size(wingW, wingH), alpha = 0.85f)
                    }
                }
                HaloState.Waiting -> {
                    haloRing(circle, center, radius, ringTopLeft, ringSize, corner, ringColor, 0.80f, thin)
                    if (animate) {
                        val grow = p * 6.dp.toPx()
                        val fade = (1f - p) * 0.55f
                        if (circle) {
                            drawCircle(color = ringColor, radius = radius + grow, center = center, alpha = fade, style = thin)
                        } else {
                            drawRoundRect(
                                color = ringColor,
                                topLeft = Offset(ringTopLeft.x - grow, ringTopLeft.y - grow),
                                size = Size(ringSize.width + grow * 2f, ringSize.height + grow * 2f),
                                cornerRadius = CornerRadius(cr + grow, cr + grow),
                                style = thin,
                                alpha = fade
                            )
                        }
                    }
                }
                HaloState.Idle -> Unit
            }
        }
    }
}

private fun DrawScope.haloRing(
    circle: Boolean, center: Offset, radius: Float, topLeft: Offset, ringSize: Size,
    corner: CornerRadius, color: Color, alpha: Float, stroke: Stroke
) {
    if (circle) drawCircle(color = color, radius = radius, center = center, alpha = alpha, style = stroke)
    else drawRoundRect(color = color, topLeft = topLeft, size = ringSize, cornerRadius = corner, style = stroke, alpha = alpha)
}

private fun DrawScope.haloRingBrush(
    circle: Boolean, center: Offset, radius: Float, topLeft: Offset, ringSize: Size,
    corner: CornerRadius, brush: Brush, alpha: Float, stroke: Stroke
) {
    if (circle) drawCircle(brush = brush, radius = radius, center = center, alpha = alpha, style = stroke)
    else drawRoundRect(brush = brush, topLeft = topLeft, size = ringSize, cornerRadius = corner, alpha = alpha, style = stroke)
}

/** Edge colour for a sentinel verdict ("" = none). */
fun sentinelVerdictColor(verdict: String): Color = when (verdict.trim().lowercase()) {
    "ok" -> MotionGreen
    "warn", "pending" -> MotionAmber
    "block", "error" -> MotionRed
    else -> Color.Transparent
}

/**
 * Wraps a card (e.g. an approval) and sweeps a scan highlight across it once when shown or when
 * [verdict] changes ("pending" keeps scanning until a verdict arrives). The edge is tinted green (ok),
 * amber (warn / pending) or red (block / error). A small Sentinel Bix flies along the scan line, then
 * rests in the top-right corner: Blocked on block/error, a quick happy Done on ok, Sentinel on
 * warn/pending. [showBix] = false hides the mascot.
 */
@Composable
fun SentinelScanBox(
    verdict: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    showBix: Boolean = true,
    content: @Composable () -> Unit
) {
    val v = verdict.trim().lowercase()
    val motion = GlassStyle.motionEnabled
    val target = sentinelVerdictColor(v)
    val edge = animateColorAsState(target, tween(GlassMotion.ms(360)), label = "scan-edge")
    val sweep = remember { Animatable(0f) }
    // Resting Bix after the sweep (null = none).
    val restPose = remember { mutableStateOf<BixAction?>(null) }
    val bixPhase: State<Float> = if (motion && showBix && v.isNotEmpty()) {
        val inf = rememberInfiniteTransition(label = "scan-bix")
        inf.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing), RepeatMode.Restart),
            label = "scan-bix-phase"
        )
    } else {
        remember { mutableFloatStateOf(0.25f) }
    }

    LaunchedEffect(v, motion) {
        val rest: BixAction? = when (v) {
            "" -> null
            "ok" -> BixAction.Done
            "block", "error" -> BixAction.Blocked
            else -> BixAction.Sentinel
        }
        if (!motion || v.isEmpty()) {
            sweep.snapTo(0f)
            restPose.value = if (v == "ok") null else rest
            return@LaunchedEffect
        }
        restPose.value = null
        if (v == "pending") {
            while (true) {
                sweep.snapTo(0f)
                sweep.animateTo(1f, tween(1500, easing = LinearEasing))
                sweep.snapTo(0f)
                restPose.value = BixAction.Sentinel
                delay(420)
                restPose.value = null
            }
        } else {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            sweep.snapTo(0f)
            restPose.value = rest
            if (v == "ok") {
                // Quick happy face, then step aside.
                delay(1400)
                restPose.value = null
            }
        }
    }

    val bandColor = if (target.alpha > 0.01f) target else ThemeAccent
    Box(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val bandH = (h * 0.45f).coerceIn(24.dp.toPx(), 140.dp.toPx()).coerceAtMost(maxOf(h, 1f))
            val cr = cornerRadius.toPx()
            val stroke = Stroke(1.4.dp.toPx())
            val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, cr, cr)) }
            val band = Brush.verticalGradient(
                0f to Color.Transparent,
                0.72f to bandColor.copy(alpha = 0.16f),
                0.96f to bandColor.copy(alpha = 0.50f),
                1f to Color.Transparent,
                startY = 0f,
                endY = bandH
            )
            val bandSize = Size(w, bandH)
            val spriteH = 21.dp.toPx()
            val spriteW = 30.dp.toPx()
            val pad = 6.dp.toPx()
            onDrawWithContent {
                drawContent()
                val s = sweep.value
                val c = edge.value
                if (s > 0f && s < 1f) {
                    val y = -bandH + (h + bandH) * s
                    clipPath(clip) {
                        translate(top = y) { drawRect(brush = band, topLeft = Offset.Zero, size = bandSize) }
                    }
                    if (showBix) {
                        // Bix flies along the scan line, drifting from right to left.
                        val lineY = (y + bandH - spriteH * 0.75f).coerceIn(-spriteH * 0.3f, h - spriteH * 0.4f)
                        val x = (w - spriteW - pad) - (w * 0.35f) * s
                        drawBixSprite(BixAction.Sentinel, bixPhase.value, x, lineY, spriteW, spriteH)
                    }
                } else if (showBix) {
                    val pose = restPose.value
                    if (pose != null) drawBixSprite(pose, bixPhase.value, w - spriteW - pad, pad * 0.5f, spriteW, spriteH)
                }
                if (c.alpha > 0.01f) {
                    drawRoundRect(color = c, cornerRadius = CornerRadius(cr, cr), style = stroke)
                }
            }
        }
    ) { content() }
}

/** Draws a compact Bix pose into the rectangle (x, y, w, h) of the current DrawScope. */
fun DrawScope.drawBixSprite(action: BixAction, phase: Float, x: Float, y: Float, w: Float, h: Float, fill: Float = 1f) {
    if (w <= 0f || h <= 0f) return
    inset(left = x, top = y, right = size.width - x - w, bottom = size.height - y - h) {
        drawBixPose(action, phase, true, BixPrefs.accessory, fill)
    }
}

/**
 * Fades content to transparent under a floating top bar (iOS 27 soft scroll edge). Put it on the
 * scrolling container (LazyColumn / Column). Uses an offscreen layer + DstIn gradient mask.
 */
fun Modifier.topScrollFade(height: Dp = 96.dp): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val h = height.toPx().coerceIn(0f, size.height)
            val mask = Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to Color.Black.copy(alpha = 0.62f),
                1f to Color.Black,
                startY = 0f,
                endY = maxOf(h, 1f)
            )
            val maskSize = Size(size.width, h)
            onDrawWithContent {
                drawContent()
                if (h > 0f) drawRect(brush = mask, topLeft = Offset.Zero, size = maskSize, blendMode = BlendMode.DstIn)
            }
        }

/**
 * Tiny acknowledge bounce + haptic for a send button:
 * `val (ackMod, ack) = rememberAckBounce()` then `Modifier.then(ackMod)` on the button and call `ack()`
 * when the message is sent. Haptic still fires when motion is off.
 */
@Composable
fun rememberAckBounce(): Pair<Modifier, () -> Unit> {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    return remember(scale, scope, view) {
        val mod = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        val trigger: () -> Unit = {
            runCatching {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.KEYBOARD_TAP
                view.performHapticFeedback(type)
            }
            if (GlassStyle.motionEnabled) {
                scope.launch {
                    scale.animateTo(0.86f, tween(70, easing = FastOutSlowInEasing))
                    scale.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 520f))
                }
            }
        }
        mod to trigger
    }
}
