package app.opengrok

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.7.1 Bix Cross-Screen Flight & Tab-Shuttle Transition Engine.
 *
 * Pure Compose Canvas overlays (zero touch interception, zero edits to BeeUi.kt, honours
 * GlassStyle.motionEnabled):
 *  1. [BixScreenFlightOverlay] — Whenever navigation moves between screens or the drawer opens/closes,
 *     Bix physically launches from the previous screen's perch along a quadratic Bezier flight arc,
 *     banking into the velocity vector with rapid wing flaps and an 8-bit golden-volt pixel pollen
 *     trail, morphing into the destination screen's signature [BixAction] pose, landing with a
 *     squash-and-stretch bounce + pixel starburst ring, then dissolving cleanly into the UI.
 *     During Android's predictive back gesture ([backProgress] > 0), Bix peeks from the swipe edge
 *     with a dotted return flight arc toward the parent screen.
 *  2. [BixTabFlightTrack] — Mini 8-bit Bix parabolic hop shuttle across Super-Agent Kanban tabs.
 */
data class BixLanding(
    val xFrac: Float,
    val yFrac: Float,
    val pose: BixAction,
    val accent: Color
)

/** Semantic perch coordinate and signature Bix pose for every screen in BotHive. */
fun bixLandingFor(screen: Screen): BixLanding = when (screen) {
    Screen.Home -> BixLanding(0.50f, 0.27f, BixAction.Idle, Color(0xFFFFD166))
    Screen.BotMode -> BixLanding(0.20f, 0.11f, BixAction.Thinking, Color(0xFFFFD166))
    Screen.BotHub -> BixLanding(0.80f, 0.14f, BixAction.DrillingSquad, Color(0xFF9DDC77))
    is Screen.Chat -> BixLanding(0.20f, 0.10f, BixAction.Thinking, Color(0xFFFFD166))
    Screen.AgentChat -> BixLanding(0.82f, 0.10f, BixAction.Coding, Color(0xFFC8FF00))
    Screen.SuperAgent -> BixLanding(0.50f, 0.23f, BixAction.Router, Color(0xFFC8FF00))
    Screen.HiveCenter -> BixLanding(0.84f, 0.09f, BixAction.Router, Color(0xFF9DDC77))
    Screen.Approvals -> BixLanding(0.84f, 0.09f, BixAction.ApprovalGuard, Color(0xFFFFB020))
    Screen.Policy -> BixLanding(0.84f, 0.09f, BixAction.Sentinel, Color(0xFF5CE1E6))
    Screen.Routines -> BixLanding(0.84f, 0.09f, BixAction.Alarm, Color(0xFFFFD166))
    Screen.Usage -> BixLanding(0.84f, 0.09f, BixAction.Budget, Color(0xFFFFB020))
    Screen.Activity -> BixLanding(0.84f, 0.09f, BixAction.Briefing, Color(0xFF9DDC77))
    Screen.Search -> BixLanding(0.82f, 0.10f, BixAction.Searching, Color(0xFF5CE1E6))
    Screen.Settings -> BixLanding(0.80f, 0.24f, BixAction.Done, Color(0xFFFFD166))
    Screen.Workflows -> BixLanding(0.84f, 0.10f, BixAction.DrillingSquad, Color(0xFF9DDC77))
    Screen.NewGroup -> BixLanding(0.82f, 0.11f, BixAction.DrillingSquad, Color(0xFF9DDC77))
    is Screen.EditBot,
    is Screen.EditProvider,
    Screen.EditAgent,
    Screen.AgentSettings,
    is Screen.EditWorkflow,
    is Screen.EditMcp,
    is Screen.EditSkill -> BixLanding(0.84f, 0.10f, BixAction.Coding, Color(0xFFC8FF00))
}

private data class BixFlightJob(
    val id: Int,
    val from: BixLanding,
    val to: BixLanding,
    val arcLiftFrac: Float,
    val durationMs: Int
)

/**
 * Full-screen non-blocking overlay that flies 8-bit Bix across screens whenever [screen] or
 * [drawerOpen] changes, and peeks dynamically during predictive back gestures ([backProgress]).
 */
@Composable
fun BixScreenFlightOverlay(
    screen: Screen,
    drawerOpen: Boolean,
    backProgress: Float,
    backFromLeft: Boolean,
    modifier: Modifier = Modifier
) {
    if (!GlassStyle.motionEnabled) return

    val initialLanding = remember { bixLandingFor(screen) }
    var lastScreen by remember { mutableStateOf(screen) }
    var lastDrawer by remember { mutableStateOf(drawerOpen) }
    var lastLanding by remember { mutableStateOf(initialLanding) }
    var jobSeq by remember { mutableIntStateOf(0) }
    var activeJob by remember { mutableStateOf<BixFlightJob?>(null) }

    val flightProgress = remember { Animatable(0f) }
    val settleAlpha = remember { Animatable(0f) }
    val landBounce = remember { Animatable(0f) }

    // Continuous wing flutter phase only active while flying or peeking on predictive back.
    val needFlutter = activeJob != null || backProgress > 0.015f
    val wingPhase: Float = if (needFlutter) {
        val inf = rememberInfiniteTransition(label = "bix-flight-wing")
        inf.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Restart),
            label = "bix-flight-phase"
        ).value
    } else 0f

    // Trigger flight on screen change.
    LaunchedEffect(screen) {
        if (screen == lastScreen) return@LaunchedEffect
        val from = lastLanding
        val to = bixLandingFor(screen)
        val dx = to.xFrac - from.xFrac
        val dy = to.yFrac - from.yFrac
        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val isModeFlip = (lastScreen is Screen.Chat && screen == Screen.AgentChat) ||
            (lastScreen == Screen.AgentChat && screen is Screen.Chat)
        val lift = when {
            isModeFlip -> -0.11f
            screen == Screen.Home -> 0.10f // gentle swoop down into Home greeting
            else -> (-0.08f - dist * 0.12f).coerceIn(-0.20f, -0.08f)
        }
        val dur = (480 + (dist * 260f).toInt()).coerceIn(500, 680)
        lastScreen = screen
        lastLanding = to
        jobSeq += 1
        activeJob = BixFlightJob(jobSeq, from, to, lift, dur)
    }

    // Trigger playful swoop when opening / closing the navigation drawer.
    LaunchedEffect(drawerOpen) {
        if (drawerOpen == lastDrawer) return@LaunchedEffect
        lastDrawer = drawerOpen
        val drawerPerch = BixLanding(0.74f, 0.12f, BixAction.Done, Color(0xFFFFD166))
        val screenPerch = bixLandingFor(screen)
        val from = if (drawerOpen) screenPerch else drawerPerch
        val to = if (drawerOpen) drawerPerch else screenPerch
        lastLanding = to
        jobSeq += 1
        activeJob = BixFlightJob(jobSeq, from, to, arcLiftFrac = -0.09f, durationMs = 460)
    }

    // Run flight animation timeline whenever a new job is posted.
    LaunchedEffect(activeJob?.id) {
        val job = activeJob ?: return@LaunchedEffect
        flightProgress.snapTo(0f)
        landBounce.snapTo(0f)
        settleAlpha.snapTo(1f)
        flightProgress.animateTo(1f, tween(job.durationMs, easing = FastOutSlowInEasing))
        // Touchdown squash-and-stretch + sparkle burst.
        landBounce.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        delay(170L)
        settleAlpha.animateTo(0f, tween(210, easing = FastOutSlowInEasing))
        activeJob = null
    }

    val job = activeJob
    if (job == null && backProgress <= 0.015f) return

    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = if (activeJob == null && backProgress > 0.015f) 1f else settleAlpha.value }) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // 1. Predictive Back Peek: Bix pokes out from the gesture edge pointing homeward.
        if (backProgress > 0.015f && job == null) {
            val p = backProgress.coerceIn(0f, 1f)
            val edgeX = if (backFromLeft) w * (0.04f + 0.14f * p) else w * (0.96f - 0.14f * p)
            val edgeY = h * (0.44f - 0.08f * sin(p * PI).toFloat())
            val dir = if (backFromLeft) 1f else -1f
            val spriteW = 42.dp.toPx()
            val spriteH = 30.dp.toPx()

            // Dotted pixel preview arc toward destination perch.
            val dotPx = 3.5.dp.toPx()
            for (i in 1..5) {
                val frac = i / 6f
                val px = edgeX + dir * (w * 0.18f * frac * p)
                val py = edgeY - (h * 0.05f * sin(frac * PI).toFloat())
                drawRect(
                    color = ThemeAccent.copy(alpha = (0.65f - frac * 0.45f) * p),
                    topLeft = Offset(px - dotPx / 2f, py - dotPx / 2f),
                    size = Size(dotPx, dotPx)
                )
            }
            withTransform({
                translate(left = edgeX - spriteW / 2f, top = edgeY - spriteH / 2f)
                rotate(degrees = dir * (16f * p), pivot = Offset(spriteW / 2f, spriteH / 2f))
                scale(
                    scaleX = (if (backFromLeft) 1f else -1f) * (0.85f + 0.25f * p),
                    scaleY = 0.85f + 0.25f * p,
                    pivot = Offset(spriteW / 2f, spriteH / 2f)
                )
            }) {
                drawBixSprite(BixAction.Idle, wingPhase, 0f, 0f, spriteW, spriteH)
            }
            return@Canvas
        }

        // 2. Active Cross-Screen Bezier Flight
        if (job != null) {
            val t = flightProgress.value.coerceIn(0f, 1f)
            val alpha = settleAlpha.value.coerceIn(0f, 1f)
            if (alpha <= 0.01f) return@Canvas

            val p0 = Offset(job.from.xFrac * w, job.from.yFrac * h)
            val p2 = Offset(job.to.xFrac * w, job.to.yFrac * h)
            val midX = (p0.x + p2.x) * 0.5f
            val midY = ((p0.y + p2.y) * 0.5f + job.arcLiftFrac * h).coerceIn(h * 0.06f, h * 0.85f)
            val p1 = Offset(midX, midY)

            val pos = quadBezier(p0, p1, p2, t)
            val tangent = quadBezierTangent(p0, p1, p2, t)
            val flyingLeft = tangent.x < -8f

            // Pixel pollen trail along Bezier curve behind Bix.
            val trailCount = 13
            val basePx = 4.dp.toPx()
            for (i in 1..trailCount) {
                val lag = i * 0.032f
                val sampleT = (t - lag).coerceIn(0f, 1f)
                if (t - lag <= 0f) continue
                val pt = quadBezier(p0, p1, p2, sampleT)
                val fade = (1f - i.toFloat() / (trailCount + 1)) * alpha * (1f - landBounce.value * 0.85f)
                if (fade <= 0.02f) continue
                val flutterY = sin((sampleT * 18f + i * 0.9f).toDouble()).toFloat() * 5.dp.toPx()
                val flutterX = cos((sampleT * 14f + i * 1.1f).toDouble()).toFloat() * 3.dp.toPx()
                val sz = basePx * (1.15f - i * 0.055f).coerceAtLeast(0.45f)
                val trailColor = when (i % 3) {
                    0 -> job.to.accent
                    1 -> BeeWingMist
                    else -> Color(0xFFFFD166)
                }
                drawRect(
                    color = trailColor.copy(alpha = fade * 0.85f),
                    topLeft = Offset(pt.x + flutterX - sz / 2f, pt.y + flutterY - sz / 2f),
                    size = Size(sz, sz)
                )
            }

            // Touchdown 8-pixel starburst ring on arrival.
            val b = landBounce.value
            if (b > 0.01f && b < 0.99f) {
                val burstR = (10.dp.toPx() + 26.dp.toPx() * b)
                val sparkPx = 3.5.dp.toPx() * (1f - b * 0.45f)
                val burstAlpha = (1f - b) * alpha * 0.9f
                for (k in 0 until 8) {
                    val ang = (k * 45.0) * PI / 180.0
                    val sx = p2.x + cos(ang).toFloat() * burstR
                    val sy = p2.y + sin(ang).toFloat() * burstR
                    val sparkColor = if (k % 2 == 0) job.to.accent else Color(0xFFFFD166)
                    drawRect(
                        color = sparkColor.copy(alpha = burstAlpha),
                        topLeft = Offset(sx - sparkPx / 2f, sy - sparkPx / 2f),
                        size = Size(sparkPx, sparkPx)
                    )
                }
            }

            // 3D depth arc zoom + touchdown squash-and-stretch.
            val depthBoost = 1f + 0.26f * sin(t * PI).toFloat()
            val squashWave = sin(b * PI).toFloat()
            val sx = depthBoost * (1f + 0.16f * squashWave) * (if (flyingLeft && t < 0.78f) -1f else 1f)
            val sy = depthBoost * (1f - 0.14f * squashWave)
            val rawTilt = (tangent.y / (abs(tangent.x) + 220f)).coerceIn(-0.45f, 0.45f) * 28f
            val bankDeg = rawTilt * (1f - b)

            // Morph into destination pose in final 28% of flight.
            val currentPose = if (t < 0.72f) BixAction.Idle else job.to.pose
            val spriteW = 48.dp.toPx()
            val spriteH = 34.dp.toPx()

            // Soft pixel drop-shadow underneath Bix during mid-flight apex.
            val shadowAlpha = (0.28f * sin(t * PI).toFloat() * alpha).coerceIn(0f, 0.3f)
            if (shadowAlpha > 0.02f) {
                drawRect(
                    color = Color.Black.copy(alpha = shadowAlpha),
                    topLeft = Offset(pos.x - spriteW * 0.26f, pos.y + spriteH * 0.52f),
                    size = Size(spriteW * 0.52f, 4.dp.toPx())
                )
            }

            withTransform({
                translate(left = pos.x - spriteW / 2f, top = pos.y - spriteH / 2f)
                rotate(degrees = bankDeg, pivot = Offset(spriteW / 2f, spriteH / 2f))
                scale(scaleX = sx, scaleY = sy, pivot = Offset(spriteW / 2f, spriteH / 2f))
            }) {
                drawBixSprite(currentPose, wingPhase, 0f, 0f, spriteW, spriteH)
            }
        }
    }
}

/**
 * Mini horizontal parabolic Bix shuttle track for multi-tab boards (e.g. Super-Agent 5-tab Kanban).
 * Whenever [selectedIndex] changes, an 8-bit Bix hops across the strip leaving golden-volt sparkles
 * and settles into the active tab's [BixAction] pose.
 */
@Composable
fun BixTabFlightTrack(
    selectedIndex: Int,
    tabCount: Int,
    poses: List<BixAction>,
    modifier: Modifier = Modifier
) {
    if (tabCount <= 0) return
    val clamped = selectedIndex.coerceIn(0, tabCount - 1)
    var prevIndex by remember { mutableIntStateOf(clamped) }
    val xAnim = remember { Animatable(clamped.toFloat()) }

    LaunchedEffect(clamped) {
        if (clamped == prevIndex) return@LaunchedEffect
        val motion = GlassStyle.motionEnabled
        prevIndex = clamped
        if (!motion) {
            xAnim.snapTo(clamped.toFloat())
            return@LaunchedEffect
        }
        xAnim.animateTo(
            targetValue = clamped.toFloat(),
            animationSpec = tween(360, easing = FastOutSlowInEasing)
        )
    }

    // Track normalized hop progress separately so parabolic arc peaks mid-flight.
    var hopFrom by remember { mutableFloatStateOf(clamped.toFloat()) }
    LaunchedEffect(clamped) {
        if (!GlassStyle.motionEnabled) {
            hopFrom = clamped.toFloat()
            return@LaunchedEffect
        }
        delay(370L)
        hopFrom = clamped.toFloat()
    }

    val wingPhase: Float = if (GlassStyle.motionEnabled) {
        val inf = rememberInfiniteTransition(label = "bix-tab-shuttle")
        inf.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "bix-tab-phase"
        ).value
    } else 0.25f

    Canvas(modifier.fillMaxWidth().height(28.dp)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val slotW = w / tabCount.toFloat()
        val curSlot = xAnim.value
        val curX = slotW * (curSlot + 0.5f)
        val totalSpan = abs(clamped.toFloat() - hopFrom)
        val localFrac = if (totalSpan > 0.01f) {
            (abs(curSlot - hopFrom) / totalSpan).coerceIn(0f, 1f)
        } else 1f
        val hopArc = if (localFrac in 0.01f..0.99f) sin(localFrac * PI).toFloat() else 0f

        // Subtle pixel rail along bottom.
        val railY = h - 3.dp.toPx()
        drawRect(
            color = Color(0x1FFFFFFF),
            topLeft = Offset(8.dp.toPx(), railY),
            size = Size(w - 16.dp.toPx(), 1.5.dp.toPx())
        )
        // Active slot glowing pixel pill under Bix.
        drawRect(
            color = ThemeAccent.copy(alpha = 0.75f),
            topLeft = Offset(curX - slotW * 0.24f, railY - 0.5.dp.toPx()),
            size = Size(slotW * 0.48f, 2.5.dp.toPx())
        )

        // Sparkle trail while hopping between tabs.
        if (hopArc > 0.05f) {
            val dir = if (clamped >= hopFrom) 1f else -1f
            val dotPx = 3.dp.toPx()
            for (i in 1..5) {
                val tx = curX - dir * i * 7.dp.toPx()
                val ty = (h * 0.56f) - (hopArc * 10.dp.toPx()) + i * 1.4.dp.toPx()
                drawRect(
                    color = (if (i % 2 == 0) Color(0xFFC8FF00) else Color(0xFFFFD166))
                        .copy(alpha = (0.85f - i * 0.14f) * hopArc),
                    topLeft = Offset(tx - dotPx / 2f, ty - dotPx / 2f),
                    size = Size(dotPx, dotPx)
                )
            }
        }

        val spriteW = 28.dp.toPx()
        val spriteH = 20.dp.toPx()
        val yPos = (h - spriteH - 4.dp.toPx()) - hopArc * 8.dp.toPx()
        val pose = poses.getOrElse(clamped) { BixAction.Router }
        val flip = if (hopArc > 0.05f && clamped < hopFrom) -1f else 1f

        withTransform({
            translate(left = curX - spriteW / 2f, top = yPos)
            scale(scaleX = flip, scaleY = 1f, pivot = Offset(spriteW / 2f, spriteH / 2f))
        }) {
            drawBixSprite(pose, wingPhase, 0f, 0f, spriteW, spriteH)
        }
    }
}

private fun quadBezier(p0: Offset, p1: Offset, p2: Offset, t: Float): Offset {
    val u = 1f - t
    val x = u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x
    val y = u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y
    return Offset(x, y)
}

private fun quadBezierTangent(p0: Offset, p1: Offset, p2: Offset, t: Float): Offset {
    val u = 1f - t
    val dx = 2f * u * (p1.x - p0.x) + 2f * t * (p2.x - p1.x)
    val dy = 2f * u * (p1.y - p0.y) + 2f * t * (p2.y - p1.y)
    return Offset(dx, dy)
}
