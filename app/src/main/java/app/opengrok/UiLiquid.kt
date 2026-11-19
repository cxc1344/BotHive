package app.opengrok

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// =====================================================================================================
// Liquid selector family: ONE capsule + a water-drop indicator used by every single-choice control.
//   * the drop's leading edge springs fast, the trailing edge lags -> it stretches in flight and snaps back
//   * under-damped springs give the landing bounce; pressing makes the drop swell, releasing rebounds
//   * the label under the drop switches colour through a clip (no recomposition per frame)
//   * everything animated is read in the DRAW phase only, so it runs at the display's full refresh rate
//   * fixed segmented controls can also be dragged like a real iOS control
//   * respects the glass tier: Off = snap, Medium = soft spring, Highest = full liquid
// =====================================================================================================

/** The mode the capsule showed last, so a capsule created by the next screen can slide from there. */
object ModeMemory {
    val last = mutableStateOf("bot")
    @Volatile var index = 0
}

private class TabBounds(n: Int) {
    val lefts = FloatArray(n) { Float.NaN }
    val rights = FloatArray(n) { Float.NaN }
    var version by mutableIntStateOf(0)
    fun ready(i: Int) = i in lefts.indices && !lefts[i].isNaN()
    fun set(i: Int, l: Float, r: Float) {
        if (i !in lefts.indices) return
        if (lefts[i] != l || rights[i] != r) {
            lefts[i] = l; rights[i] = r; version++
        }
    }
}

@Composable
private fun RowScope.TabItems(
    options: List<String>, color: Color, scrollable: Boolean, fontSize: TextUnit, itemPadding: Dp, bounds: TabBounds?
) {
    options.forEachIndexed { i, label ->
        Box(
            Modifier
                .then(if (scrollable) Modifier else Modifier.weight(1f))
                .fillMaxHeight()
                .then(
                    if (bounds != null) Modifier.onPlaced { c ->
                        val x = c.positionInParent().x
                        bounds.set(i, x, x + c.size.width)
                    } else Modifier
                )
                .padding(horizontal = itemPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = color, fontSize = fontSize, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        }
    }
}

private fun DrawScope.drawLiquidDrop(
    l: Float, r: Float, alpha: Float, press: Float, baseW: Float, liquid: Boolean, layered: Boolean, accent: Color
) {
    if (alpha <= 0.01f || r <= l) return
    val stretch = if (baseW > 1f) ((r - l) / baseW - 1f).coerceIn(0f, 0.9f) else 0f
    val squash = if (liquid) 1f - 0.12f * stretch else 1f
    val swell = press * 2.4.dp.toPx()
    val h = size.height * squash + swell
    val top = (size.height - h) / 2f
    val x = l - swell * 0.85f
    val w = (r - l) + swell * 1.7f
    val cr = CornerRadius(h / 2f, h / 2f)
    if (layered) {
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.26f * alpha), topLeft = Offset(x + 0.5.dp.toPx(), top + 2.dp.toPx()),
            size = Size(w, h), cornerRadius = cr
        )
    }
    // HarmonyOS NEXT Immersive Light translucent crystal pill tinted subtly with active theme accent.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.20f), accent.copy(alpha = 0.22f), Color(0xFF282826).copy(alpha = 0.68f)),
            startY = top, endY = top + h
        ),
        topLeft = Offset(x, top), size = Size(w, h), cornerRadius = cr, alpha = alpha
    )
    if (layered) {
        // Inner Fresnel optical glow + interactive press light expansion.
        val glowBoost = 1f + press * 0.45f
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = (0.28f * glowBoost).coerceAtMost(0.45f)), Color.White.copy(alpha = 0.04f)),
                startY = top, endY = top + h
            ),
            topLeft = Offset(x, top), size = Size(w, h), cornerRadius = cr, alpha = alpha
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.42f * alpha), accent.copy(alpha = 0.30f * alpha), Color.White.copy(alpha = 0.12f * alpha)),
                startY = top, endY = top + h
            ),
            topLeft = Offset(x, top), size = Size(w, h),
            cornerRadius = cr, style = Stroke(1.1.dp.toPx())
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = (0.68f * alpha * glowBoost).coerceAtMost(0.9f)), Color.Transparent),
                startX = x, endX = x + w
            ),
            topLeft = Offset(x + h * 0.26f, top + 1.4.dp.toPx()), size = Size(max(1f, w - h * 0.52f), 1.3.dp.toPx()),
            cornerRadius = CornerRadius(1f, 1f)
        )
    }
    if (liquid) {
        drawOval(
            color = Color.White.copy(alpha = (0.20f + press * 0.12f) * alpha),
            topLeft = Offset(x + h * 0.38f, top + h * 0.14f), size = Size(h * 0.62f, h * 0.22f)
        )
    }
}

/**
 * Capsule selector with a liquid drop. [selected] = -1 hides the drop. [scrollable] lays the options out at their
 * natural width in a horizontally scrolling track (for long lists such as presets or models).
 * [allowReselect] reports taps on the already-selected option (the mode capsule uses it to navigate).
 */
@Composable
fun LiquidSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    height: Dp = 40.dp,
    fontSize: TextUnit = 14.sp,
    itemPadding: Dp = 18.dp,
    initialIndex: Int = selected,
    allowReselect: Boolean = false
) {
    val n = options.size
    if (n == 0) return
    val tier = GlassStyle.tier.value
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val bounds = remember(n) { TabBounds(n) }
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }
    val shown = remember { Animatable(if (selected >= 0) 1f else 0f) }
    var placed by remember { mutableStateOf(false) }
    var dragCenter by remember { mutableFloatStateOf(Float.NaN) }
    var viewportW by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val selState by rememberUpdatedState(selected)
    val onSelectState by rememberUpdatedState(onSelect)

    // Leading edge stiff, trailing edge soft: the drop stretches while travelling, then the soft edge catches up.
    val stiff = spring<Float>(dampingRatio = if (tier.shader) 0.58f else 0.82f, stiffness = if (tier.shader) 700f else 520f)
    val soft = spring<Float>(dampingRatio = if (tier.shader) 0.74f else 0.82f, stiffness = if (tier.shader) 230f else 520f)

    suspend fun animateToIndex(i: Int) {
        if (!bounds.ready(i)) return
        val tl = bounds.lefts[i]
        val tr = bounds.rights[i]
        if (!tier.motion) {
            left.snapTo(tl); right.snapTo(tr)
            return
        }
        val goingRight = tl >= left.value
        coroutineScope {
            launch { left.animateTo(tl, if (goingRight) soft else stiff) }
            launch { right.animateTo(tr, if (goingRight) stiff else soft) }
        }
    }

    LaunchedEffect(selected, bounds.version, tier) {
        if (selected < 0) {
            if (tier.motion) shown.animateTo(0f, tween(160)) else shown.snapTo(0f)
            return@LaunchedEffect
        }
        if (!bounds.ready(selected)) return@LaunchedEffect
        if (!placed) {
            val ii = if (initialIndex in 0 until n && bounds.ready(initialIndex)) initialIndex else selected
            left.snapTo(bounds.lefts[ii]); right.snapTo(bounds.rights[ii]); shown.snapTo(1f)
            placed = true
            if (ii == selected) return@LaunchedEffect
        }
        if (shown.value < 0.05f) {
            left.snapTo(bounds.lefts[selected]); right.snapTo(bounds.rights[selected])
        }
        if (shown.value < 1f) {
            if (tier.motion) launch { shown.animateTo(1f, tween(160)) } else shown.snapTo(1f)
        }
        if (dragCenter.isNaN()) animateToIndex(selected)
    }

    // Keep the chosen chip in view inside a scrolling track.
    LaunchedEffect(selected, placed, viewportW) {
        if (!scrollable || selected < 0 || !placed || viewportW <= 0 || !bounds.ready(selected)) return@LaunchedEffect
        val c = (bounds.lefts[selected] + bounds.rights[selected]) / 2f
        val target = (c - viewportW / 2f).roundToInt().coerceAtLeast(0)
        if (tier.motion) scroll.animateScrollTo(target) else scroll.scrollTo(target)
    }

    fun indexAt(x: Float): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in 0 until n) {
            if (!bounds.ready(i)) continue
            if (x >= bounds.lefts[i] && x <= bounds.rights[i]) return i
            val d = minOf(abs(x - bounds.lefts[i]), abs(x - bounds.rights[i]))
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    fun release() {
        scope.launch {
            if (tier.motion) press.animateTo(0f, spring<Float>(dampingRatio = 0.35f, stiffness = 380f)) else press.snapTo(0f)
        }
    }

    val tapMod = Modifier.pointerInput(n) {
        detectTapGestures(
            onPress = {
                if (tier.motion) scope.launch { press.animateTo(1f, spring<Float>(dampingRatio = 0.55f, stiffness = 700f)) }
                tryAwaitRelease()
                release()
            },
            onTap = { off ->
                val i = indexAt(off.x)
                if (i >= 0 && (i != selState || allowReselect)) {
                    if (i != selState) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectState(i)
                }
            }
        )
    }
    val dragMod = if (scrollable) Modifier else Modifier.pointerInput(n) {
        detectHorizontalDragGestures(
            onDragStart = {
                val s = selState
                if (s in 0 until n && bounds.ready(s)) dragCenter = (bounds.lefts[s] + bounds.rights[s]) / 2f
                if (tier.motion) scope.launch { press.animateTo(1f, spring<Float>(dampingRatio = 0.55f, stiffness = 700f)) }
            },
            onHorizontalDrag = { change, dx ->
                change.consume()
                if (!dragCenter.isNaN() && bounds.ready(0) && bounds.ready(n - 1)) {
                    val lo = (bounds.lefts[0] + bounds.rights[0]) / 2f
                    val hi = (bounds.lefts[n - 1] + bounds.rights[n - 1]) / 2f
                    dragCenter = (dragCenter + dx).coerceIn(lo, hi)
                }
            },
            onDragEnd = {
                val dc = dragCenter
                if (!dc.isNaN()) {
                    var best = selState.coerceAtLeast(0)
                    var bd = Float.MAX_VALUE
                    for (i in 0 until n) {
                        if (!bounds.ready(i)) continue
                        val d = abs((bounds.lefts[i] + bounds.rights[i]) / 2f - dc)
                        if (d < bd) { bd = d; best = i }
                    }
                    val w = if (bounds.ready(best)) bounds.rights[best] - bounds.lefts[best] else 0f
                    scope.launch {
                        left.snapTo(dc - w / 2f); right.snapTo(dc + w / 2f)   // hand the live position to the springs
                        dragCenter = Float.NaN
                        if (best != selState) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectState(best)
                        }
                        animateToIndex(best)
                    }
                }
                release()
            },
            onDragCancel = {
                dragCenter = Float.NaN
                release()
            }
        )
    }

    val accent = ThemeAccent
    val trackMod = modifier.height(height)
        .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 999.dp, fallbackBg = Card)
    val innerH = height - 6.dp
    val sizeMod = if (scrollable) Modifier.height(innerH) else Modifier.fillMaxSize()

    val liquid = tier.shader
    val layered = tier.glassLayers
    val body: @Composable () -> Unit = {
        Box(
            Modifier
                .then(if (scrollable) Modifier.horizontalScroll(scroll) else Modifier.fillMaxSize())
                .padding(3.dp)
                .then(sizeMod)
                .then(tapMod)
                .then(dragMod)
                .drawBehind {
                    val dc = dragCenter
                    var l = left.value
                    var r = right.value
                    val baseW = if (selected in 0 until n && bounds.ready(selected)) bounds.rights[selected] - bounds.lefts[selected] else (r - l)
                    if (!dc.isNaN()) {
                        l = dc - baseW / 2f; r = dc + baseW / 2f
                    }
                    drawLiquidDrop(l, r, shown.value, press.value, baseW, liquid, layered, accent)
                }
        ) {
            Row(sizeMod) { TabItems(options, Sub, scrollable, fontSize, itemPadding, bounds) }
            // Same labels in the selected colour, visible only inside the drop: the colour flows with the water.
            Row(
                sizeMod.drawWithContent {
                    val dc = dragCenter
                    var l = left.value
                    var r = right.value
                    if (!dc.isNaN()) {
                        val w = r - l
                        l = dc - w / 2f; r = dc + w / 2f
                    }
                    if (shown.value > 0.01f) {
                        clipRect(left = l, top = 0f, right = r, bottom = size.height) {
                            this@drawWithContent.drawContent()
                        }
                    }
                }
            ) { TabItems(options, Fg, scrollable, fontSize, itemPadding, null) }
        }
    }
    if (scrollable) {
        Box(trackMod.androidxOnSize { viewportW = it }) { body() }
    } else {
        Box(trackMod) { body() }
    }
}

private fun Modifier.androidxOnSize(cb: (Int) -> Unit): Modifier =
    this.then(Modifier.onPlaced { cb(it.size.width) })

/** Scrollable chip-style single choice (presets, providers, models, …) on the liquid capsule. */
@Composable
fun LiquidChoice(
    options: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(), height: Dp = 40.dp, fontSize: TextUnit = 13.sp
) {
    LiquidSegmented(options, selected, onSelect, modifier, scrollable = true, height = height, fontSize = fontSize, itemPadding = 15.dp)
}

/** Top capsule that switches between the Bot side and the Agent side of the app. */
@Composable
fun ModeCapsule(current: String, go: (Screen) -> Unit) {
    val idx = if (current == "agent") 1 else 0
    val from = ModeMemory.index
    LiquidSegmented(
        options = listOf("Bot", "Agent"),
        selected = idx,
        onSelect = { i -> go(if (i == 1) Screen.AgentChat else Screen.BotMode) },
        modifier = Modifier.width(200.dp),
        height = 42.dp,
        fontSize = 14.sp,
        initialIndex = from,
        allowReselect = true
    )
    SideEffect {
        ModeMemory.index = idx
        ModeMemory.last.value = if (idx == 1) "agent" else "bot"
    }
}

/** Press feedback: the element squeezes in and springs back with a small overshoot. */
fun Modifier.pressBounce(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val tier = GlassStyle.tier.value
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && tier.motion) 0.955f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 520f),
        label = "pressBounce"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}


private class QuickAction(val label: String, val icon: ImageVector, val run: () -> Unit)

/** Home shortcuts under the greeting: small pills with press-bounce. */
@Composable
fun HomeQuickActions(store: Store, go: (Screen) -> Unit) {
    val actions = listOf(
        QuickAction("新建 Bot", Ic.Add) { go(Screen.EditBot(null)) },
        QuickAction("Agent 新对话", Ic.Phone) { store.newAgentSession(); go(Screen.AgentChat) },
        QuickAction("工作流", Ic.Tree) { go(Screen.Workflows) },
        QuickAction("搜索", Ic.Search) { go(Screen.Search) }
    )
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        actions.forEach { a ->
            Row(
                Modifier.pressBounce { a.run() }
                    .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 999.dp, fallbackBg = Card)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(a.icon, null, tint = Fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(a.label, color = Fg, fontSize = 13.sp)
            }
        }
    }
}
