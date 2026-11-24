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
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp

/**
 * Floating Liquid Glass "Browser Task" pill displayed near the top of the app whenever the AI
 * has an active browser session. Tapping opens the live watch & human-takeover window.
 */
/**
 * Dynamic-Island-style Liquid Glass Browser Task Pill:
 *  - Expands for 3.5s on start or new browser action, then auto-collapses into a compact
 *    Bix-working orb docked below the sub-header on the right edge (never blocking Agent settings!).
 *  - Tapping the compact Bix orb re-expands the full bar for 4s before auto-collapsing again.
 *  - v0.7.0: tapping morphs the pill into the watch panel (shared bounds + corner radius), a status halo
 *    tracks AiBrowser (Working while the AI acts, Waiting/amber during user takeover), Bix rides the
 *    page-loading line, and Bix switches to the Handoff pose while the user is in control.
 */
@Composable
fun AiBrowserTaskPill(modifier: Modifier = Modifier) {
    val active = AiBrowser.active
    val sheetOpen = AiBrowser.sheetOpen
    val actionKey = AiBrowser.lastActionLabel
    val pulseKey = AiBrowser.cursorPulseMs
    var expanded by remember { mutableStateOf(true) }
    var expandTick by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }

    // Auto-expand briefly when a browser task starts or performs a new action, then collapse to compact Bix orb
    LaunchedEffect(active, sheetOpen, actionKey, expandTick) {
        if (!active || sheetOpen) return@LaunchedEffect
        expanded = true
        delay(if (expandTick > 0) 4000L else 3500L)
        expanded = false
    }
    // "Working" while the AI performed an action in the last ~2.5 s (synced with AiBrowser state).
    LaunchedEffect(active, actionKey, pulseKey) {
        if (!active) { working = false; return@LaunchedEffect }
        working = true
        delay(2500L)
        working = false
    }

    AnimatedVisibility(
        visible = active && !sheetOpen,
        modifier = modifier,
        enter = fadeIn(tween(GlassMotion.ms(200))) + slideInVertically(tween(GlassMotion.ms(220))) { -it },
        // Quick fade: the panel morphs out of this pill's bounds at the same time.
        exit = fadeOut(tween(GlassMotion.ms(140)))
    ) {
        val takeover = AiBrowser.userTakeover
        val halo = when {
            takeover -> HaloState.Waiting
            working -> HaloState.Working
            else -> HaloState.Thinking
        }
        val bixPose = when {
            takeover -> BixAction.Handoff
            working -> BixAction.Searching
            else -> BixAction.Coding
        }
        Box(
            Modifier
                .statusBarsPadding()
                .padding(top = 114.dp, start = 14.dp, end = 14.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (expanded) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { BrowserMorph.pillBounds = it.boundsInRoot() }
                        .statusHalo(halo, cornerRadius = 20.dp, gap = 2.dp, bixWings = false)
                        .bixLoadingRide(takeover)
                        .pressBounce { AiBrowser.sheetOpen = true }
                        .harmonyGlass(
                            thickness = ImmersiveThickness.THICK,
                            radius = 20.dp,
                            tint = if (takeover) ThemeAccent.copy(alpha = 0.22f) else SheetGlassTint,
                            fallbackBg = Card
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BixScene(action = bixPose, compact = true)
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
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(26.dp)
                            .pressBounce { AiBrowser.closeSession() }
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Ic.Close, "关闭浏览器", tint = Sub, modifier = Modifier.size(13.dp)) }
                }
            } else {
                // Compact floating Bix-working orb: zero obstruction to Agent settings & chat!
                Box(
                    Modifier
                        .onGloballyPositioned { BrowserMorph.pillBounds = it.boundsInRoot() }
                        .statusHalo(halo, cornerRadius = 999.dp, gap = 2.dp)
                        .pressBounce { expandTick++ }
                        .harmonyGlass(
                            thickness = ImmersiveThickness.THICK,
                            radius = 999.dp,
                            tint = if (takeover) ThemeAccent.copy(alpha = 0.26f) else SheetGlassTint,
                            fallbackBg = Card
                        )
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        BixScene(action = bixPose, compact = true)
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (takeover) ThemeAccent else BeeGreen)
                        )
                    }
                }
            }
        }
    }
}

/** Bounds shared by the pill and the panel for the morph (plain fields: only read in the draw phase). */
private object BrowserMorph {
    var pillBounds: Rect = Rect.Zero
    var panelBounds: Rect = Rect.Zero
}

/**
 * Draws the page-loading progress as a thin line along the bottom edge of the pill with a tiny Bix
 * riding its head (only while 1..99 % and not in takeover). Reads AiBrowser state in the draw phase.
 */
private fun Modifier.bixLoadingRide(takeover: Boolean): Modifier = composed {
    val motion = GlassStyle.motionEnabled
    val phase: State<Float> = if (motion) {
        val inf = rememberInfiniteTransition(label = "bix-ride")
        inf.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "bix-ride-phase"
        )
    } else {
        remember { mutableFloatStateOf(0.25f) }
    }
    val accent = ThemeAccent
    Modifier.drawWithCache {
        val lineH = 2.dp.toPx()
        val inset = 14.dp.toPx()
        val spriteH = 14.dp.toPx()
        val spriteW = spriteH * (36f / 28f)
        val track = Size((size.width - inset * 2f).coerceAtLeast(0f), lineH)
        val trackTopLeft = Offset(inset, size.height - lineH - 1.dp.toPx())
        onDrawWithContent {
            drawContent()
            val pct = AiBrowser.loadingProgress
            if (takeover || pct !in 1..99 || track.width <= 0f) return@onDrawWithContent
            val f = pct / 100f
            drawRect(color = Color.White, topLeft = trackTopLeft, size = track, alpha = 0.10f)
            drawRect(color = accent, topLeft = trackTopLeft, size = Size(track.width * f, lineH), alpha = 0.95f)
            val hx = trackTopLeft.x + track.width * f - spriteW * 0.5f
            val hy = trackTopLeft.y - spriteH + lineH * 0.5f
            drawBixSprite(BixAction.Searching, phase.value, hx, hy, spriteW, spriteH)
        }
    }
}

/**
 * Reveal-morph: clips the content to a rounded rect interpolated from the pill's bounds (p = 0) to the
 * full bounds (p = 1), corner radius from the pill's capsule radius to [endRadius]. Zero allocations per
 * frame (one cached android Path, reset each frame).
 */
private fun Modifier.morphReveal(progress: () -> Float, endRadius: Dp): Modifier = this.drawWithCache {
    val path = android.graphics.Path()
    val endR = endRadius.toPx()
    val fallbackH = 48.dp.toPx()
    onDrawWithContent {
        val p = progress().coerceIn(0f, 1f)
        if (p >= 0.999f) {
            drawContent()
            return@onDrawWithContent
        }
        val panel = BrowserMorph.panelBounds
        val pill = BrowserMorph.pillBounds
        val w = size.width
        val h = size.height
        var fl: Float
        var ft: Float
        var fw: Float
        var fh: Float
        if (pill.width > 0f && panel.width > 0f) {
            fw = pill.width.coerceAtMost(w)
            fh = pill.height.coerceAtMost(h)
            fl = (pill.left - panel.left).coerceIn(0f, (w - fw).coerceAtLeast(0f))
            ft = (pill.top - panel.top).coerceIn(0f, (h - fh).coerceAtLeast(0f))
        } else {
            fw = w * 0.6f
            fh = fallbackH.coerceAtMost(h)
            fl = w * 0.2f
            ft = 0f
        }
        val l = fl + (0f - fl) * p
        val t = ft + (0f - ft) * p
        val r = (fl + fw) + (w - (fl + fw)) * p
        val b = (ft + fh) + (h - (ft + fh)) * p
        val rad = (fh / 2f) + (endR - fh / 2f) * p
        path.reset()
        path.addRoundRect(l, t, r, b, rad, rad, android.graphics.Path.Direction.CW)
        val c = drawContext.canvas.nativeCanvas
        val save = c.save()
        c.clipPath(path)
        drawContent()
        c.restoreToCount(save)
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

    // v0.7.0: the panel morphs out of the task pill (reveal from the pill's bounds) instead of sliding up.
    val morph = remember { Animatable(if (open) 1f else 0f) }
    LaunchedEffect(open) {
        val target = if (open) 1f else 0f
        if (!GlassStyle.motionEnabled) {
            morph.snapTo(target)
        } else if (open) {
            morph.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f))
        } else {
            morph.animateTo(0f, tween(GlassMotion.ms(240).coerceAtLeast(1), easing = FastOutSlowInEasing))
        }
    }
    val shown by remember { derivedStateOf { morph.value > 0.001f } }
    var sheetWorking by remember { mutableStateOf(false) }
    LaunchedEffect(open, AiBrowser.lastActionLabel, AiBrowser.cursorPulseMs) {
        if (!open) { sheetWorking = false; return@LaunchedEffect }
        sheetWorking = true
        delay(2500L)
        sheetWorking = false
    }

    if (open || shown) {
        val takeover = AiBrowser.userTakeover
        val panelHalo = when {
            takeover -> HaloState.Waiting
            sheetWorking -> HaloState.Working
            else -> HaloState.Idle
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black, alpha = 0.52f * morph.value.coerceIn(0f, 1f)) }
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
                    .onGloballyPositioned { BrowserMorph.panelBounds = it.boundsInRoot() }
                    .graphicsLayer { alpha = (0.30f + 0.70f * morph.value).coerceIn(0f, 1f) }
                    .morphReveal({ morph.value }, 24.dp)
                    .statusHalo(panelHalo, cornerRadius = 24.dp, gap = 0.dp, bixWings = false)
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
                    // Resolve the WebView at click time: closeSession() destroys the old instance,
                    // and a remembered reference would keep driving a destroyed WebView.
                    SmallChip("后退") { AiBrowser.ensureWebView(ctx).let { if (it.canGoBack()) it.goBack() } }
                    SmallChip("前进") { AiBrowser.ensureWebView(ctx).let { if (it.canGoForward()) it.goForward() } }
                    SmallChip("刷新") { AiBrowser.ensureWebView(ctx).reload() }
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
                        Modifier.size(7.dp)
                            .statusHalo(if (takeover) HaloState.Waiting else if (sheetWorking) HaloState.Working else HaloState.Idle, gap = 2.dp, bixWings = false)
                            .clip(CircleShape)
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
