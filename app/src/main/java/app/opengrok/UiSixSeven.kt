package app.opengrok

import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * BotHive 0.6.7.6.7 Meme Edition — Full-screen "67 / Six Seven!" chaos mode!
 *
 *  - Opens immediately on launch, flooding the screen with animated 67 motifs & Liquid Glass badges.
 *  - Bix roams & sways across the screen doing the iconic alternating palms-up "6-7" seesaw gesture.
 *  - Single tap on Bix: shouts "Six Seven!" aloud via Android TTS + comic speech bubble + screen shake.
 *  - Double tap on Bix: opens the exit confirmation dialog where you MUST manually type "67"
 *    to return to the normal BotHive interface.
 */
object SixSevenState {
    var active by mutableStateOf(true)
}

@Composable
fun SixSevenOverlay() {
    val visible = SixSevenState.active
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(260)),
        exit = fadeOut(tween(340))
    ) {
        val ctx = LocalContext.current
        var ttsReady by remember { mutableStateOf(false) }
        val tts = remember {
            var inst: TextToSpeech? = null
            inst = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    runCatching {
                        inst?.language = Locale.US
                        inst?.setPitch(1.22f)
                        inst?.setSpeechRate(1.06f)
                        ttsReady = true
                    }
                }
            }
            inst
        }
        DisposableEffect(Unit) {
            onDispose {
                runCatching {
                    tts.stop()
                    tts.shutdown()
                }
            }
        }

        var shoutCount by remember { mutableIntStateOf(0) }
        var showShoutBubble by remember { mutableStateOf(false) }
        var lastTapMs by remember { mutableLongStateOf(0L) }
        var showExitDialog by remember { mutableStateOf(false) }
        var exitInput by remember { mutableStateOf("") }
        var wrongHint by remember { mutableStateOf(false) }

        val shakeX = remember { Animatable(0f) }
        val shoutScale = remember { Animatable(1f) }

        fun shoutSixSeven() {
            shoutCount++
            showShoutBubble = true
            if (ttsReady) {
                runCatching {
                    tts.speak("Six Seven!", TextToSpeech.QUEUE_FLUSH, null, "six_seven_$shoutCount")
                }
            } else {
                // Instant 2-tone audio fallback if TTS engine is still warming up
                runCatching {
                    val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
                }
            }
        }

        LaunchedEffect(shoutCount) {
            if (shoutCount == 0) return@LaunchedEffect
            launch {
                shoutScale.snapTo(1.36f)
                shoutScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            }
            launch {
                val kicks = floatArrayOf(-14f, 14f, -10f, 10f, -5f, 0f)
                for (k in kicks) {
                    shakeX.animateTo(k, tween(36, easing = LinearEasing))
                }
            }
            delay(1350)
            showShoutBubble = false
        }

        val inf = rememberInfiniteTransition(label = "six-seven-world")
        val roam by inf.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(7200, easing = LinearEasing), RepeatMode.Restart),
            label = "roam"
        )
        val gesture by inf.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(760, easing = LinearEasing), RepeatMode.Restart),
            label = "gesture"
        )
        val pulse by inf.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse"
        )

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        0f to Color(0xFF241B05),
                        0.52f to Color(0xFF0E1015),
                        1f to Color(0xFF06070A)
                    )
                )
                .offset(x = shakeX.value.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* swallow touches beneath overlay */ }
        ) {
            val screenW = maxWidth
            val screenH = maxHeight

            // 1. Background giant tiled "67" watermark matrix
            Column(
                Modifier.fillMaxSize().padding(vertical = 24.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                for (row in 0..7) {
                    val shift = sin(roam + row * 0.9f) * 22f
                    Row(
                        Modifier.fillMaxWidth().offset(x = shift.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for (col in 0..3) {
                            val big = (row + col) % 2 == 0
                            Text(
                                if (big) "67" else "SIX SEVEN",
                                color = if (big) Color(0x26FFC83D) else Color(0x1EC8FF00),
                                fontSize = if (big) 44.sp else 16.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.graphicsLayer {
                                    rotationZ = if (big) -12f + row * 3f else 8f
                                }
                            )
                        }
                    }
                }
            }

            // 2. Top Edition Banner & Floating 67 Pills
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 18.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x2EFFC83D))
                        .border(1.dp, Color(0x88FFC83D), RoundedCornerShape(999.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        "BotHive v0.6.7.6.7 · SIX SEVEN EDITION",
                        color = Color(0xFFFFE88A),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    "67",
                    color = Color(0xFFFFC83D),
                    fontSize = 78.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    }
                )
                Text(
                    "点一下 Bix 大喊「Six Seven！」 · 连戳两下输入 67 回到原界面",
                    color = Color(0xFFB8C2D1),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            // 3. Two background Mini-Bix bees orbiting & doing the 6-7 gesture too!
            for (k in 0..1) {
                val angle = roam * (if (k == 0) 1f else -1.2f) + k * 3.14f
                val mx = (screenW.value * (0.5f + 0.34f * cos(angle))).coerceIn(24f, screenW.value - 90f)
                val my = (screenH.value * (0.48f + 0.24f * sin(angle * 1.5f))).coerceIn(150f, screenH.value - 150f)
                Canvas(
                    Modifier
                        .offset(x = mx.dp, y = my.dp)
                        .size(72.dp, 64.dp)
                        .graphicsLayer { alpha = 0.65f }
                ) {
                    drawBixSixSeven(gesture + k * 1.5f)
                }
            }

            // 4. HERO Roaming Bix doing the iconic "6-7" seesaw gesture!
            val heroX = (screenW.value * 0.5f - 82f + sin(roam) * (screenW.value * 0.26f))
                .coerceIn(12f, (screenW.value - 172f).coerceAtLeast(12f))
            val heroY = (screenH.value * 0.52f - 76f + sin(roam * 2f) * (screenH.value * 0.14f))
                .coerceIn(170f, (screenH.value - 220f).coerceAtLeast(170f))

            Column(
                modifier = Modifier
                    .offset(x = heroX.dp, y = heroY.dp)
                    .graphicsLayer {
                        scaleX = shoutScale.value
                        scaleY = shoutScale.value
                        rotationZ = sin(gesture) * 7f
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapMs <= 620L) {
                            // Double tap -> open mandatory "67" exit dialog!
                            lastTapMs = 0L
                            exitInput = ""
                            wrongHint = false
                            showExitDialog = true
                        } else {
                            // Single tap -> shout "Six Seven!"
                            lastTapMs = now
                            shoutSixSeven()
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Exploding comic speech bubble when tapped
                AnimatedVisibility(visible = showShoutBubble, enter = fadeIn(tween(80)), exit = fadeOut(tween(220))) {
                    Box(
                        Modifier
                            .padding(bottom = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFC8FF00))
                            .border(2.dp, Color(0xFF14161A), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "SIX SEVEN!! 🗣️",
                            color = Color(0xFF101216),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                // Floating "6" and "7" above Bix's alternating hands!
                val handLift = sin(gesture) * 10f
                Row(
                    horizontalArrangement = Arrangement.spacedBy(54.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "6",
                        color = Color(0xFFFFC83D),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.offset(y = (-handLift).dp)
                    )
                    Text(
                        "7",
                        color = Color(0xFFC8FF00),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.offset(y = handLift.dp)
                    )
                }

                Canvas(Modifier.size(164.dp, 132.dp)) {
                    drawBixSixSeven(gesture)
                }
            }

            // 5. Bottom hype counter badge
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 34.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC141820))
                    .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("67 喊话次数：$shoutCount", color = Color(0xFFFFE88A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("·", color = Sub)
                Text("连戳 Bix 两下退出", color = Sub, fontSize = 12.sp)
            }

            // 6. Mandatory "67" Exit Confirmation Dialog
            if (showExitDialog) {
                val unlocked = exitInput.trim() == "67"
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { Text("确定要回到一开始的界面吗？") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "想要离开 0.6.7.6.7 的 Six Seven 宇宙，必须先在下方手动输入「67」验证身份：",
                                color = Sub,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            Field(
                                label = "请手动输入 67",
                                value = exitInput,
                                onChange = { exitInput = it; wrongHint = false },
                                keyboard = KeyboardType.Number
                            )
                            if (wrongHint) {
                                Text("不对不对！必须老老实实输入「67」才能回去！", color = BeeDanger, fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (unlocked) {
                                    showExitDialog = false
                                    SixSevenState.active = false
                                } else {
                                    wrongHint = true
                                }
                            }
                        ) {
                            Text(
                                if (unlocked) "确认回到主界面 (67 ✓)" else "确认（需输入 67）",
                                color = if (unlocked) ThemeAccent else Sub,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitDialog = false }) {
                            Text("继续看 Bix 晃 67")
                        }
                    }
                )
            }
        }
    }
}

/**
 * Pure 8-bit pixel Canvas renderer for Bix doing the iconic alternating palms-up "6-7" seesaw gesture!
 */
private fun DrawScope.drawBixSixSeven(phase: Float) {
    val u = minOf(size.width / 24f, size.height / 18f)
    val ox = (size.width - 24f * u) * 0.5f
    val oy = (size.height - 17f * u) * 0.5f
    val seesaw = sin(phase) * 2.2f
    val wingUp = sin(phase * 2f) > 0f

    fun px(x: Float, y: Float, w: Float = 1f, h: Float = 1f, c: Color) {
        drawRect(c, Offset(ox + x * u, oy + y * u), Size(w * u, h * u))
    }

    val outline = Color(0xFF2B2118)
    val gold = Color(0xFFFFC83D)
    val goldLight = Color(0xFFFFE88A)
    val stripe = Color(0xFF4A3322)
    val coral = Color(0xFFFF8F5C)
    val wing = Color(0xFFEAF8FB)
    val wingEdge = Color(0xFF9AD3E3)
    val cheek = Color(0xFFFF9AA8)
    val shades = Color(0xFF121418)

    // Wings
    val wy = if (wingUp) 3.5f else 5.5f
    px(4f, wy, 3f, 3f, wingEdge)
    px(4.6f, wy + 0.5f, 2f, 2f, wing)
    px(17f, wy, 3f, 3f, wingEdge)
    px(17.4f, wy + 0.5f, 2f, 2f, wing)

    // Antennae
    px(9f, 1.5f, 1f, 2f, coral)
    px(14f, 1.5f, 1f, 2f, coral)
    px(9f, 3.5f, 1f, 1f, outline)
    px(14f, 3.5f, 1f, 1f, outline)

    // Body (centered at x = 7..17)
    px(8f, 4.5f, 8f, 1f, outline)
    px(7f, 5.5f, 10f, 6.5f, outline)
    px(8f, 12f, 8f, 2f, outline)
    px(10f, 14f, 4f, 1f, outline)

    px(8f, 5.5f, 8f, 2.2f, goldLight)
    px(8f, 7.7f, 8f, 3.0f, gold)
    px(8f, 10.7f, 8f, 1.4f, stripe)
    px(9f, 12.1f, 6f, 1.1f, gold)

    // Pixel sunglasses (swag 6-7 meme energy!)
    px(8.2f, 7.2f, 7.6f, 0.7f, shades)
    px(8.7f, 7.7f, 2.8f, 1.6f, shades)
    px(12.5f, 7.7f, 2.8f, 1.6f, shades)
    px(9.2f, 8.0f, 0.9f, 0.5f, Color.White)
    px(13.0f, 8.0f, 0.9f, 0.5f, Color.White)

    // Cheeks + hyped open mouth shouting!
    px(8.1f, 9.4f, 1.1f, 0.8f, cheek)
    px(14.8f, 9.4f, 1.1f, 0.8f, cheek)
    px(11.0f, 9.4f, 2.0f, 1.2f, coral)

    // Left arm + palm-up weighing hand (moves UP when right moves DOWN)
    val leftY = 9.0f - seesaw
    px(4.2f, leftY + 0.6f, 3.0f, 1.1f, outline)
    px(4.5f, leftY + 0.8f, 2.5f, 0.7f, gold)
    // Open palm-up pixel glove
    px(1.2f, leftY, 3.4f, 1.8f, outline)
    px(1.6f, leftY + 0.3f, 2.6f, 1.2f, Color(0xFFFFF3CE))
    // Upward motion tick
    px(2.4f, leftY - 1.3f, 1.0f, 0.6f, Color(0xFFFFC83D))

    // Right arm + palm-up weighing hand (moves DOWN when left moves UP)
    val rightY = 9.0f + seesaw
    px(16.8f, rightY + 0.6f, 3.0f, 1.1f, outline)
    px(17.0f, rightY + 0.8f, 2.5f, 0.7f, gold)
    // Open palm-up pixel glove
    px(19.4f, rightY, 3.4f, 1.8f, outline)
    px(19.8f, rightY + 0.3f, 2.6f, 1.2f, Color(0xFFFFF3CE))
    // Motion tick
    px(20.6f, rightY - 1.3f, 1.0f, 0.6f, Color(0xFFC8FF00))
}
