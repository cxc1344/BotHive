package app.opengrok

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import kotlin.math.absoluteValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import app.opengrok.ThemeAccent

/**
 * Liquid toggle switch — self-implemented interpretation of the OriginOS 7 / iOS 27 control
 * behaviour: the knob deforms while travelling (stretches along the motion axis, bulges behind),
 * the fill surface behaves like liquid (a wave sweeps across when the state flips), and a
 * specular sheen passes over the glass surface on toggle.
 *
 * Respect the global tier: [GlassTier.Off] renders a plain Material-style flat switch with no
 * morph; Medium gets the fill wave; Highest adds knob stretch, sheen sweep and spring bounce.
 *
 * Goo dynamics (this build):
 * - Knob stretches horizontally into a droplet (peak ~1.5x) while moving, with subtle tail taper.
 * - Fill color lags behind knob position (fluid "slosh" feel) — fill leads on press, trails on release.
 * - Press glow: knob brightens from inside on touch down.
 * - Resting state unchanged: OFF state keeps the theme-color disk under the knob (design intent).
 */
@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trackW: Dp = 56.dp,
    trackH: Dp = 32.dp
) {
    val tier = GlassStyle.tier.value
    val scope = rememberCoroutineScope()
    // 0..1 travel of the knob.
    val pos = remember { Animatable(if (checked) 1f else 0f) }

    LaunchedEffect(checked) {
        if (!tier.motion) {
            pos.snapTo(if (checked) 1f else 0f)
        } else if (tier.shader) {
            pos.animateTo(if (checked) 1f else 0f, spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ))
        } else {
            pos.animateTo(if (checked) 1f else 0f, tween(220, easing = FastOutSlowInEasing))
        }
    }

    // Sheen sweep runs once per state change (Highest tier only).
    val sheen = remember { Animatable(0f) }
    LaunchedEffect(checked) {
        if (tier.shader) {
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        }
    }

    var previewChecked by remember { mutableStateOf(checked) }
    val onFill = ThemeAccent
    val offFill = Color(0xFF4A3C1C)
    val fill = if (previewChecked) onFill else offFill

    var lastPos by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    // Drag state: knob lifts into a glass lens while touched, settles solid at rest.
    var dragging by remember { mutableStateOf(false) }
    var pressT by remember { mutableFloatStateOf(0f) }
    var dragStartPos by remember { mutableFloatStateOf(0f) }
    val lift = animateFloatAsState(
        targetValue = if (dragging && tier.shader) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "knob-lift"
    )
    // Press light: knob brightens from inside on touch down.
    val pressGlow = remember { Animatable(0f) }
    LaunchedEffect(dragging) {
        if (dragging && tier.shader) pressGlow.animateTo(1f, tween(140))
        else pressGlow.animateTo(0f, tween(200))
    }

    // Fill lag: the colored fill follows the knob with a slight delay (liquid slosh).
    // We animate a separate fillPos that trails pos.value with a spring.
    val fillPos = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(pos.value) {
        if (tier.shader) {
            fillPos.animateTo(pos.value, spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ))
        } else {
            fillPos.snapTo(pos.value)
        }
    }

    Box(
        modifier = modifier
            .width(trackW)
            .height(trackH)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) {
                // One gesture detector owns the pointer: drag with 1:1 scrubbing, and a tap
                // (down→up without meaningful move) flips the state. Two detectors on the same
                // node would fight over the event stream, so this is a single pipeline.
                var downX = 0f
                var moved = false
                kotlinx.coroutines.coroutineScope {
                    launch {
                        if (GlassStyle.tier.value.motion) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    downX = offset.x
                                    moved = false
                                    dragging = true
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    if (abs(change.position.x - downX) > 6f) moved = true
                                    val wPx = size.width.toFloat()
                                    val hPx = size.height.toFloat()
                                    val travel = (wPx - hPx).coerceAtLeast(1f)
                                    val raw = (change.position.x - hPx / 2f) / travel
                                    val clamped = raw.coerceIn(0f, 1f)
                                    val overshoot = (raw - clamped).absoluteValue
                                    val damped = clamped + (overshoot * 0.35f * (if (raw > 1f) 1f else -1f))
                                    scope.launch {
                                        pos.snapTo(damped.coerceIn(-0.18f, 1.18f))
                                        previewChecked = pos.value >= 0.5f
                                    }
                                },
                                onDragEnd = {
                                    dragging = false
                                    if (!moved) {
                                        // tap: flip
                                        val next = !checked
                                        previewChecked = next
                                        scope.launch {
                                            pos.animateTo(if (next) 1f else 0f, spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            ))
                                        }
                                        onCheckedChange(next)
                                    } else {
                                        val settled = pos.value >= 0.5f
                                        previewChecked = settled
                                        if (settled != checked) onCheckedChange(settled)
                                        scope.launch {
                                            pos.animateTo(if (settled) 1f else 0f, spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            ))
                                        }
                                    }
                                },
                                onDragCancel = {
                                    dragging = false
                                    previewChecked = checked
                                    scope.launch {
                                        pos.animateTo(if (checked) 1f else 0f, spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        ))
                                    }
                                }
                            )
                        } else {
                            // Off tier: plain tap only.
                            detectTapGestures {
                                onCheckedChange(!checked)
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = h / 2f
            val knobR = r - 3.dp.toPx()
            val travel = w - h
            val p = pos.value
            val fp = fillPos.value // lagged fill position

            // ---- liquid fill with lag: grows from the knob side like fluid pouring ----
            // Fill width follows fillPos (lagged) so it "sloshes" behind the knob.
            val fillW = lerp(h, w, if (previewChecked) 1f else fp.coerceIn(0f, 1f))
            drawRoundRect(
                color = fill,
                topLeft = Offset(if (previewChecked) w - fillW else 0f, 0f),
                size = Size(fillW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
            )

            // ---- inner glass shading on the fill ----
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.25f),
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.12f)
                ),
                topLeft = Offset(if (previewChecked) w - fillW else 0f, 0f),
                size = Size(fillW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
            )

            // ---- sheen sweep: a diagonal light band crossing the track on toggle ----
            val s = sheen.value
            if (s > 0f && s < 1f) {
                val bandX = lerp(-w * 0.4f, w * 1.2f, s)
                drawLine(
                    brush = Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = 0.45f),
                        1f to Color.Transparent
                    ),
                    start = Offset(bandX, -h * 0.2f),
                    end = Offset(bandX + h * 0.6f, h * 1.2f),
                    strokeWidth = h * 0.9f,
                    cap = StrokeCap.Butt
                )
            }

            // ---- knob: stretches along the motion axis while moving (liquid deformation) ----
            // Droplet stretch: horizontal stretch up to ~1.5x at peak velocity, with tail taper.
            val v = if (tier.shader) abs(pos.value - lastPos).coerceIn(0f, 0.3f) else 0f
            val grow = lerp(1f, 1.25f, lift.value)        // knob grows while lifted, capped at 1.25x
            val stretchX = (1f + v * 1.5f) * grow         // peak ~1.5x horizontal
            val stretchY = (1f - v * 0.5f) * grow         // slight vertical squash
            val knobW = knobR * stretchX
            val knobH = knobR * stretchY
            val cx = lerp(r, w - r, p)
            val cy = h / 2f

            lastPos = pos.value

            // knob shadow on the track
            drawCircle(
                color = Color.Black.copy(alpha = 0.25f),
                radius = knobR,
                center = Offset(cx + 1.5f, cy + 2f)
            )

            val liftT = lift.value // 0 rest -> 1 pressed
            if (liftT > 0.02f) {
                // ---- glass lens state while pressed/dragging ----
                // The knob becomes a magnifier over the track itself: track + fill are redrawn
                // into the knob circle scaled 1.4..1.65x, centered on the knob, with a per-channel
                // offset for RGB dispersion along the drag axis. Because it is redrawn from the
                // same frame's state, the lens content is always live — no static gradient.
                val magnify = lerp(1.4f, 1.65f, liftT)
                fun drawTrackTinted(rMul: Float, gMul: Float, bMul: Float, dx: Float, alpha: Float) {
                    val layerColor = Color(
                        red = (fill.red * rMul).coerceIn(0f, 1f),
                        green = (fill.green * gMul).coerceIn(0f, 1f),
                        blue = (fill.blue * bMul).coerceIn(0f, 1f),
                        alpha = alpha
                    )
                    // scaled track fill inside the knob circle (clip keeps it lens-shaped)
                    val scaledW = w * magnify
                    val scaledH = h * magnify
                    val cxOnTrack = lerp(0f + scaledH / 2f, w - scaledH / 2f, pos.value.coerceIn(0f, 1f))
                    val fillW2 = lerp(scaledH, scaledW, if (previewChecked) 1f else pos.value.coerceIn(0f, 1f))
                    val topLeftX = if (previewChecked) w - fillW2 else 0f
                    // map the track into knob-local space: translate so the knob center reads the
                    // magnified track around it, then draw the same shapes the track uses.
                    drawCircle(color = layerColor.copy(alpha = alpha * 0.55f), radius = knobR)
                    clipRect(
                        left = cx - knobW, top = cy - knobH,
                        right = cx + knobW, bottom = cy + knobH
                    ) {
                        translate(left = cx - cxOnTrack * magnify + dx, top = cy - cy * magnify) {
                            scale(magnify, magnify, pivot = Offset(cxOnTrack, cy)) {
                                drawRoundRect(
                                    color = layerColor,
                                    topLeft = Offset(topLeftX, 0f),
                                    size = Size(fillW2, h),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f)
                                )
                            }
                        }
                    }
                }
                // RGB separation grows with drag velocity
                val vDrag = abs(pos.value - lastPos).coerceIn(0f, 0.3f)
                val disp = lerp(1.2f, 2.6f, vDrag / 0.3f) * liftT
                drawTrackTinted(1.15f, 0.9f, 0.9f, -disp, 0.5f)
                drawTrackTinted(1.0f, 1.0f, 1.0f, 0f, 0.85f)
                drawTrackTinted(0.9f, 0.9f, 1.25f, disp, 0.5f)
                // rising glass shading + moving highlight (position follows the drag)
                drawOval(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.30f * liftT + 0.05f),
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.16f * liftT)
                    ),
                    topLeft = Offset(cx - knobW, cy - knobH),
                    size = Size(knobW * 2f, knobH * 2f)
                )
                val hlX = cx - knobW * 0.35f + pos.value * knobW * 0.4f
                drawCircle(
                    color = Color.White.copy(alpha = 0.28f + 0.5f * pressGlow.value),
                    radius = knobR * (0.30f + 0.1f * pressGlow.value),
                    center = Offset(hlX, cy - knobH * 0.45f)
                )
            } else {
                // ---- rest state: quiet solid knob (0.5.7 look, zero extra draw cost) ----
                // IMPORTANT: The theme-color disk under the knob in OFF state is an intentional
                // design element (既定設計) — do NOT remove it.
                drawOval(
                    brush = Brush.linearGradient(
                        0f to Color(0xFFFFF6DC),
                        1f to Color(0xFFE8C760)
                    ),
                    topLeft = Offset(cx - knobW, cy - knobH),
                    size = Size(knobW * 2f, knobH * 2f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.75f),
                    radius = knobR * 0.28f,
                    center = Offset(cx - knobR * 0.35f, cy - knobH * 0.45f)
                )
            }
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
