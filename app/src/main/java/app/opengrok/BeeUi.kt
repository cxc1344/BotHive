package app.opengrok

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Pixel-hive palette. Names deliberately do not overlap the app's base theme tokens. */
val BeeHoneyGold = Color(0xFFF7B928)
val BeeHoneyGlow = Color(0xFFFFD866)
val BeeHoneyAmber = Color(0xFFD88517)
val BeeCharcoalInk = Color(0xFF27231D)
val BeeCharcoalSoft = Color(0xFF41392A)
val BeeCreamMilk = Color(0xFFFFF3CE)
val BeeWingMist = Color(0xFFBEE8EA)
val BeePollenCoral = Color(0xFFFF8F5C)

/** The small visual state used by the reusable bee pet. */
enum class BeeMood { Cheerful, Focused, Sleepy }

/**
 * A compact pixel-art desk pet. Its shadow stays grounded while the bee has a gentle idle bob;
 * wing motion is intentionally small so it remains calm beside text and controls.
 */
@Composable
fun PixelBeePet(
    size: Dp,
    mood: BeeMood = BeeMood.Cheerful,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "pixel-bee-pet")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (mood) {
                    BeeMood.Cheerful -> 1250
                    BeeMood.Focused -> 1450
                    BeeMood.Sleepy -> 1800
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bee-bob"
    )
    val wingBeat by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (mood) {
                    BeeMood.Cheerful -> 420
                    BeeMood.Focused -> 620
                    BeeMood.Sleepy -> 950
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bee-wing-beat"
    )
    val bobAmount = when (mood) {
        BeeMood.Cheerful -> 4f
        BeeMood.Focused -> 2.5f
        BeeMood.Sleepy -> 1.5f
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { drawBeePetShadow() }
        Canvas(
            Modifier.matchParentSize().offset(y = ((bob - 0.5f) * bobAmount).dp)
        ) {
            drawPixelBeePet(mood, wingBeat)
        }
    }
}

/**
 * Seeded, faceless identity mark for people, bots, and groups. It varies the bee's stripes and
 * accessory but always keeps the recognizable honeycomb / insect silhouette rather than a face.
 */
@Composable
fun PixelBeeAvatar(
    seed: String,
    size: Dp,
    group: Boolean = false,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        drawPixelBeeAvatar(seed, group)
    }
}

/** A low-contrast honeycomb layer suitable for placing behind a page or card. */
@Composable
fun HoneycombBackdrop(
    modifier: Modifier = Modifier,
    alpha: Float = 1f
) {
    Canvas(modifier = modifier) {
        val opacity = alpha.coerceIn(0f, 1f)
        if (opacity == 0f || this.size.minDimension <= 0f) return@Canvas

        val radius = 18.dp.toPx()
        val columnStep = radius * 1.732f
        val rowStep = radius * 1.5f
        val columns = (this.size.width / columnStep).toInt() + 3
        val rows = (this.size.height / rowStep).toInt() + 3
        val stroke = Stroke(width = 1.dp.toPx())
        val lineColor = BeeHoneyGold.copy(alpha = opacity * 0.16f)
        val dotColor = BeeHoneyGlow.copy(alpha = opacity * 0.14f)

        for (row in -1..rows) {
            val y = row * rowStep
            val rowOffset = if (row % 2 == 0) 0f else columnStep / 2f
            for (column in -1..columns) {
                val center = Offset(column * columnStep + rowOffset, y)
                drawPath(pixelHexagon(center, radius), color = lineColor, style = stroke)
                if ((row + column) % 3 == 0) {
                    drawRect(
                        color = dotColor,
                        topLeft = Offset(center.x - 1.2.dp.toPx(), center.y - 1.2.dp.toPx()),
                        size = Size(2.4.dp.toPx(), 2.4.dp.toPx())
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawBeePetShadow() {
    val unit = min(size.width, size.height) / 22f
    val shadowWidth = unit * 9f
    val shadowHeight = unit * 1.2f
    val left = center.x - shadowWidth / 2f
    val top = size.height * 0.83f
    drawRect(
        color = BeeCharcoalInk.copy(alpha = 0.24f),
        topLeft = Offset(left, top),
        size = Size(shadowWidth, shadowHeight)
    )
    drawRect(
        color = BeeCharcoalInk.copy(alpha = 0.16f),
        topLeft = Offset(left + unit, top - unit * 0.45f),
        size = Size(shadowWidth - unit * 2f, unit * 0.45f)
    )
}

private fun DrawScope.drawPixelBeePet(mood: BeeMood, wingBeat: Float) {
    val unit = min(size.width, size.height) / 22f
    if (unit <= 0f) return
    val left = center.x - unit * 11f
    val top = center.y - unit * 11f
    fun cell(x: Float, y: Float, width: Float = 1f, height: Float = 1f, color: Color) {
        drawRect(
            color = color,
            topLeft = Offset(left + x * unit, top + y * unit),
            size = Size(width * unit, height * unit)
        )
    }

    val wingLift = (wingBeat - 0.5f) * when (mood) {
        BeeMood.Cheerful -> 2.2f
        BeeMood.Focused -> 1.4f
        BeeMood.Sleepy -> 0.7f
    }
    val wingColor = if (mood == BeeMood.Sleepy) BeeCreamMilk.copy(alpha = 0.72f) else BeeWingMist.copy(alpha = 0.82f)

    // Pixel wings behind the body, each stepped instead of rounded to preserve the sprite look.
    cell(2f, 8f + wingLift, 2f, 4f, BeeCharcoalSoft)
    cell(3f, 6f + wingLift, 4f, 1f, BeeCharcoalSoft)
    cell(3f, 7f + wingLift, 5f, 5f, wingColor)
    cell(2f, 9f + wingLift, 1f, 2f, wingColor)
    cell(4f, 6.7f + wingLift, 2f, 1f, BeeCreamMilk.copy(alpha = 0.72f))
    cell(18f, 8f - wingLift, 2f, 4f, BeeCharcoalSoft)
    cell(15f, 6f - wingLift, 4f, 1f, BeeCharcoalSoft)
    cell(14f, 7f - wingLift, 5f, 5f, wingColor)
    cell(19f, 9f - wingLift, 1f, 2f, wingColor)
    cell(16f, 6.7f - wingLift, 2f, 1f, BeeCreamMilk.copy(alpha = 0.72f))

    // Antennae and legs are intentionally blocky, making this read as a pet instead of a person.
    cell(8f, 4f, 1f, 2f, BeeCharcoalInk)
    cell(7f, 3f, 1f, 1f, BeeCharcoalInk)
    cell(7f, 2f, 1f, 1f, BeeHoneyGlow)
    cell(13f, 4f, 1f, 2f, BeeCharcoalInk)
    cell(14f, 3f, 1f, 1f, BeeCharcoalInk)
    cell(14f, 2f, 1f, 1f, BeeHoneyGlow)
    cell(8f, 17f, 1f, 3f, BeeCharcoalInk)
    cell(7f, 19f, 2f, 1f, BeeCharcoalInk)
    cell(13f, 17f, 1f, 3f, BeeCharcoalInk)
    cell(13f, 19f, 2f, 1f, BeeCharcoalInk)

    // Dark silhouette, then layered gold stripes.
    cell(8f, 5f, 6f, 1f, BeeCharcoalInk)
    cell(7f, 6f, 8f, 2f, BeeCharcoalInk)
    cell(6f, 8f, 10f, 8f, BeeCharcoalInk)
    cell(7f, 16f, 8f, 2f, BeeCharcoalInk)
    cell(8f, 18f, 6f, 1f, BeeCharcoalInk)
    cell(8f, 6f, 6f, 2f, BeeHoneyGlow)
    cell(7f, 8f, 8f, 2f, BeeHoneyGold)
    cell(7f, 10f, 8f, 2f, BeeCharcoalSoft)
    cell(7f, 12f, 8f, 2f, BeeHoneyGlow)
    cell(7f, 14f, 8f, 2f, BeeCharcoalSoft)
    cell(8f, 16f, 6f, 2f, BeeHoneyAmber)

    // Mood lives in tiny insect features, not a human head silhouette.
    when (mood) {
        BeeMood.Cheerful -> {
            cell(9f, 8.5f, 1f, 1f, BeeCharcoalInk)
            cell(12f, 8.5f, 1f, 1f, BeeCharcoalInk)
            cell(10f, 10f, 2f, 0.65f, BeePollenCoral)
            cell(9f, 11f, 1f, 0.7f, BeeCharcoalInk)
            cell(12f, 11f, 1f, 0.7f, BeeCharcoalInk)
        }
        BeeMood.Focused -> {
            cell(9f, 8f, 2f, 0.65f, BeeCharcoalInk)
            cell(12f, 8f, 2f, 0.65f, BeeCharcoalInk)
            cell(9f, 9.2f, 1f, 1.3f, BeeCharcoalInk)
            cell(13f, 9.2f, 1f, 1.3f, BeeCharcoalInk)
            cell(10f, 11.2f, 2f, 0.55f, BeeCreamMilk)
            cell(16f, 10f, 1f, 1f, BeePollenCoral)
            cell(17f, 9f, 1f, 3f, BeePollenCoral)
        }
        BeeMood.Sleepy -> {
            cell(9f, 9.5f, 2f, 0.6f, BeeCharcoalInk)
            cell(12f, 9.5f, 2f, 0.6f, BeeCharcoalInk)
            cell(10f, 11.1f, 2f, 0.45f, BeeCharcoalInk)
            cell(16f, 6f, 1f, 1f, BeeCreamMilk)
            cell(17f, 5f, 1f, 1f, BeeCreamMilk)
            cell(18f, 4f, 1f, 1f, BeeCreamMilk)
        }
    }
}

private fun DrawScope.drawPixelBeeAvatar(seed: String, group: Boolean) {
    val hash = seed.hashCode()
    val variant = Math.floorMod(hash, 4)
    val accent = when (Math.floorMod(hash / 7, 4)) {
        0 -> BeeHoneyGlow
        1 -> BeePollenCoral
        2 -> BeeWingMist
        else -> BeeCreamMilk
    }
    val radius = min(size.width, size.height) * 0.48f
    if (radius <= 0f) return
    val c = center

    drawPath(pixelHexagon(c, radius), color = BeeCharcoalInk)
    drawPath(pixelHexagon(c, radius * 0.9f), color = BeeHoneyAmber)
    drawPath(pixelHexagon(c, radius * 0.82f), color = BeeCharcoalSoft)

    if (group) {
        val miniRadius = radius * 0.26f
        val cells = listOf(
            Offset(c.x - miniRadius * 1.05f, c.y + miniRadius * 0.45f),
            Offset(c.x + miniRadius * 1.05f, c.y + miniRadius * 0.45f),
            Offset(c.x, c.y - miniRadius * 0.9f)
        )
        cells.forEachIndexed { index, at ->
            drawPath(pixelHexagon(at, miniRadius), color = BeeCreamMilk.copy(alpha = 0.9f))
            drawPath(pixelHexagon(at, miniRadius * 0.72f), color = if ((index + variant) % 2 == 0) BeeHoneyGold else accent)
            drawRect(
                color = BeeCharcoalSoft,
                topLeft = Offset(at.x - miniRadius * 0.38f, at.y - miniRadius * 0.12f),
                size = Size(miniRadius * 0.76f, miniRadius * 0.24f)
            )
        }
        return
    }

    val unit = radius / 7.2f
    val left = c.x - unit * 5.5f
    val top = c.y - unit * 5.8f
    fun cell(x: Float, y: Float, width: Float = 1f, height: Float = 1f, color: Color) {
        drawRect(color, Offset(left + x * unit, top + y * unit), Size(width * unit, height * unit))
    }

    // Small top-down bee: wings + striped body only, deliberately no facial features.
    cell(1f, 4f, 3f, 3f, BeeWingMist.copy(alpha = 0.85f))
    cell(7f, 4f, 3f, 3f, BeeWingMist.copy(alpha = 0.85f))
    cell(2f, 3f, 2f, 1f, BeeCreamMilk.copy(alpha = 0.75f))
    cell(7f, 3f, 2f, 1f, BeeCreamMilk.copy(alpha = 0.75f))
    cell(4f, 2f, 3f, 1f, BeeCharcoalInk)
    cell(3f, 3f, 5f, 6f, BeeCharcoalInk)
    cell(4f, 3f, 3f, 1.6f, BeeHoneyGold)
    cell(4f, 4.6f, 3f, 1.35f, BeeCharcoalSoft)
    cell(4f, 5.95f, 3f, 1.35f, BeeHoneyGlow)
    cell(4f, 7.3f, 3f, 1.1f, BeeCharcoalSoft)

    // Deterministic accessory: pollen clip, crown, leaf, or sparkle.
    when (variant) {
        0 -> {
            cell(4.55f, 0.8f, 0.9f, 1f, BeePollenCoral)
            cell(3.5f, 1.25f, 0.8f, 0.6f, BeePollenCoral)
            cell(5.7f, 1.25f, 0.8f, 0.6f, BeePollenCoral)
        }
        1 -> {
            cell(3.4f, 1f, 1f, 1.4f, accent)
            cell(5.8f, 1f, 1f, 1.4f, accent)
            cell(4.2f, 1.55f, 1.8f, 0.85f, accent)
        }
        2 -> {
            cell(7.9f, 1.9f, 0.85f, 1.9f, accent)
            cell(8.65f, 1.05f, 0.85f, 1.7f, accent)
        }
        else -> {
            cell(8.2f, 1.3f, 0.8f, 0.8f, accent)
            cell(7.45f, 2.1f, 2.3f, 0.75f, accent)
            cell(8.2f, 2.85f, 0.8f, 0.8f, accent)
        }
    }
}

private fun pixelHexagon(center: Offset, radius: Float): Path = Path().apply {
    moveTo(center.x, center.y - radius)
    lineTo(center.x + radius * 0.866f, center.y - radius * 0.5f)
    lineTo(center.x + radius * 0.866f, center.y + radius * 0.5f)
    lineTo(center.x, center.y + radius)
    lineTo(center.x - radius * 0.866f, center.y + radius * 0.5f)
    lineTo(center.x - radius * 0.866f, center.y - radius * 0.5f)
    close()
}
