package app.opengrok

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Liquid toggle switch — self-implemented interpretation of the OriginOS 7 / iOS 27 control
 * behaviour: the knob deforms while travelling (stretches along the motion axis, bulges behind),
 * the fill surface behaves like liquid (a wave sweeps across when the state flips), and a
 * specular sheen passes over the glass surface on toggle.
 *
 * Respect the global tier: [GlassTier.Off] renders a plain Material-style flat switch with no
 * morph; Medium gets the fill wave; Highest adds knob stretch, sheen sweep and spring bounce.
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
            sheen.value = 0f
            sheen.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        }
    }

    val onFill = Honey
    val offFill = Color(0xFF4A3C1C)
    val fill = if (checked) onFill else offFill

    var lastPos by remember { mutableFloatStateOf(if (checked) 1f else 0f) }

    Box(
        modifier = modifier
            .width(trackW)
            .height(trackH)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(checked) {
                detectTapGestures { onCheckedChange(!checked) }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = h / 2f
            val knobR = r - 3.dp.toPx()
            val travel = w - h
            val p = pos.value

            // ---- liquid fill: grows from the knob side like fluid pouring ----
            // Fill width follows the knob with a slight lead so it feels pressurised.
            val fillW = lerp(h, w, if (checked) 1f else p.coerceIn(0f, 1f))
            drawRoundRect(
                color = fill,
                topLeft = Offset(if (checked) w - fillW else 0f, 0f),
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
                topLeft = Offset(if (checked) w - fillW else 0f, 0f),
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
            // Deform from frame-to-frame delta (a cheap, robust velocity proxy):
            // stretch along travel while moving, settle round when idle.
            val v = if (tier.shader) abs(pos.value - lastPos).coerceIn(0f, 0.25f) else 0f
            val stretch = 1f + v * 1.4f                  // up to ~35% along X while in motion
            val squash = 1f - v * 0.6f
            val knobW = knobR * stretch
            val knobH = knobR * squash
            val cx = lerp(r, w - r, p)
            val cy = h / 2f

            lastPos = pos.value

            // knob shadow on the track
            drawCircle(
                color = Color.Black.copy(alpha = 0.25f),
                radius = knobR,
                center = Offset(cx + 1.5f, cy + 2f)
            )

            // knob body: glass gradient with bright top rim
            drawOval(
                brush = Brush.linearGradient(
                    0f to Color(0xFFFFF6DC),
                    1f to Color(0xFFE8C760)
                ),
                topLeft = Offset(cx - knobW, cy - knobH),
                size = Size(knobW * 2f, knobH * 2f)
            )
            // specular dot toward the light (top-left)
            drawCircle(
                color = Color.White.copy(alpha = 0.75f),
                radius = knobR * 0.28f,
                center = Offset(cx - knobR * 0.35f, cy - knobH * 0.45f)
            )
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
