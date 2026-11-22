package app.opengrok

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Floating Liquid Glass "Browser Task" pill displayed near the top of the app whenever the AI
 * has an active browser session. Tapping opens the live watch & human-takeover window.
 */
@Composable
fun AiBrowserTaskPill(modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = AiBrowser.active && !AiBrowser.sheetOpen,
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { -it },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { -it }
    ) {
        val takeover = AiBrowser.userTakeover
        Row(
            Modifier
                .statusBarsPadding()
                .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
                .pressBounce { AiBrowser.sheetOpen = true }
                .harmonyGlass(
                    thickness = ImmersiveThickness.THICK,
                    radius = 18.dp,
                    tint = if (takeover) ThemeAccent.copy(alpha = 0.20f) else SheetGlassTint,
                    fallbackBg = Card
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BixScene(
                action = if (takeover) BixAction.ApprovalGuard else BixAction.Coding,
                compact = true
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (takeover) "浏览器任务 · 你正在接管" else "AI 浏览器任务 · ${AiBrowser.pageTitle}",
                    color = Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    AiBrowser.lastActionLabel,
                    color = if (takeover) ThemeAccentPale else Sub,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (takeover) ThemeAccent else PillWhite)
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(
                    if (takeover) "继续接管" else "观看 / 接管",
                    color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Live Watch & Human-Takeover Browser Sheet:
 *  - Shows the live WebView in real time.
 *  - When AI is driving: displays a glowing live AI pointer + action badge (左键/右键/中键/长按/拖动/滚轮).
 *  - Tap "接管操控": immediately pauses AI and unlocks full manual touch/keyboard control.
 *  - Tap "交还 AI": hands control back so the AI seamlessly resumes its workflow.
 */
@Composable
fun AiBrowserSheetOverlay() {
    val open = AiBrowser.active && AiBrowser.sheetOpen
    val ctx = LocalContext.current
    BackHandler(open) { AiBrowser.sheetOpen = false }

    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(180)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 3 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 3 }
    ) {
        val takeover = AiBrowser.userTakeover
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { AiBrowser.sheetOpen = false }
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .navigationBarsPadding()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    .harmonyGlass(
                        thickness = ImmersiveThickness.ULTRA_THICK,
                        radius = 24.dp,
                        tint = SheetGlassTint,
                        fallbackBg = Card
                    )
            ) {
                // Header: minimize, title/URL, Takeover / Hand-back primary button
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(34.dp).pressBounce { AiBrowser.sheetOpen = false }
                            .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL),
                        contentAlignment = Alignment.Center
                    ) { Icon(Ic.Down, "收起", tint = Fg, modifier = Modifier.size(18.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            AiBrowser.pageTitle.ifBlank { "AI 独立浏览器" },
                            color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            AiBrowser.currentUrl.ifBlank { AiBrowser.lastActionLabel },
                            color = Sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // Primary Takeover / Hand-back pill
                    Box(
                        Modifier
                            .pressBounce { AiBrowser.setTakeover(!takeover) }
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (takeover) ThemeAccent else PillWhite)
                            .padding(horizontal = 13.dp, vertical = 7.dp)
                    ) {
                        Text(
                            if (takeover) "交还 AI" else "接管操控",
                            color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Toolbar: Back / Refresh / Desktop-UA / Close Task + status badge
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val wv = remember { AiBrowser.ensureWebView(ctx) }
                    SmallChip("后退") { if (wv.canGoBack()) wv.goBack() }
                    SmallChip("刷新") { wv.reload() }
                    SmallChip(if (AiBrowser.desktopMode) "桌面版 ✓" else "手机版") {
                        AiBrowser.toggleDesktopMode(ctx)
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.pressBounce { AiBrowser.closeSession() }
                            .clip(RoundedCornerShape(12.dp))
                            .background(BeeDanger.copy(alpha = 0.22f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) { Text("结束任务", color = BeeDanger, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                }

                if (AiBrowser.loadingProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { AiBrowser.loadingProgress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 12.dp),
                        color = ThemeAccent,
                        trackColor = Color.Transparent
                    )
                }

                // Status strip
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (takeover) ThemeAccent.copy(alpha = 0.16f) else PILL)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .background(if (takeover) ThemeAccent else BeeGreen)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (takeover) "你已接管浏览器 · AI 已暂停等待，操作完成后点「交还 AI」继续"
                        else "AI 实况操控中：${AiBrowser.lastActionLabel}",
                        color = if (takeover) ThemeAccentPale else Fg,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }

                // Live WebView viewport + AI Cursor HUD overlay
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            width = if (takeover) 1.5.dp else 1.dp,
                            color = if (takeover) ThemeAccent.copy(alpha = 0.65f) else HAIRLINE,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .background(Color.White)
                ) {
                    val boxW = maxWidth
                    val boxH = maxHeight
                    val hostContainer = remember { FrameLayout(ctx) }

                    DisposableEffect(hostContainer) {
                        AiBrowser.attachTo(hostContainer, ctx)
                        onDispose { AiBrowser.detachFrom(hostContainer) }
                    }

                    AndroidView(
                        factory = { hostContainer },
                        modifier = Modifier.fillMaxSize()
                    )

                    // When AI is driving (!takeover), block accidental touches and show glowing AI pointer!
                    if (!takeover) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { }
                        )
                        if (AiBrowser.cursorVisible) {
                            val ax by animateFloatAsState(
                                targetValue = AiBrowser.cursorX / 1000f,
                                animationSpec = tween(110),
                                label = "ai-cursor-x"
                            )
                            val ay by animateFloatAsState(
                                targetValue = AiBrowser.cursorY / 1000f,
                                animationSpec = tween(110),
                                label = "ai-cursor-y"
                            )
                            val cx = boxW * ax
                            val cy = boxH * ay
                            val pressed = AiBrowser.cursorPressed
                            Box(
                                Modifier
                                    .offset(x = (cx - 12.dp).coerceIn(0.dp, boxW - 24.dp), y = (cy - 12.dp).coerceIn(0.dp, boxH - 24.dp))
                            ) {
                                Canvas(Modifier.size(28.dp)) {
                                    // Ripple ring when clicking/pressing
                                    if (pressed) {
                                        drawCircle(
                                            color = Color(0xFFFFC83D).copy(alpha = 0.35f),
                                            radius = size.minDimension * 0.48f,
                                            center = Offset(12.dp.toPx(), 12.dp.toPx())
                                        )
                                        drawCircle(
                                            color = Color(0xFFFFC83D),
                                            radius = size.minDimension * 0.48f,
                                            center = Offset(12.dp.toPx(), 12.dp.toPx()),
                                            style = Stroke(width = 2.dp.toPx())
                                        )
                                    }
                                    // Crisp pointer arrow
                                    val p = Path().apply {
                                        moveTo(10.dp.toPx(), 8.dp.toPx())
                                        lineTo(10.dp.toPx(), 23.dp.toPx())
                                        lineTo(14.5.dp.toPx(), 19.dp.toPx())
                                        lineTo(20.dp.toPx(), 19.dp.toPx())
                                        close()
                                    }
                                    drawPath(p, color = Color(0xFFFFC83D))
                                    drawPath(p, color = Color(0xFF14161A), style = Stroke(width = 1.5.dp.toPx()))
                                }
                                if (AiBrowser.cursorBadge.isNotBlank()) {
                                    Box(
                                        Modifier
                                            .offset(x = 18.dp, y = 18.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xE614161A))
                                            .border(1.dp, Color(0x99FFC83D), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(AiBrowser.cursorBadge, color = Color(0xFFFFE88A), fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
