package app.opengrok

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import app.opengrok.ThemeAccent
import app.opengrok.ThemeAccentDeep

// BotHive vNext · Claude-style skeleton palette (sampled values in UiTokens.kt).
// The legacy names below are what ~230 call sites reference; re-pointing them here
// recolours every screen in one place. The bee's own colours live in BeeUi.kt and
// are untouched. Floating surfaces layer the glass material over these values.
// Accent colors (Honey/HoneyDeep/HoneyPale) are now driven by ThemeColor (user-selectable).
val Bg = Color(0xFF0B0C0E)            // Deep Titanium Obsidian canvas
val Card = Color(0xFF16181D)          // Smoked Crystal Glass fallback
val Line = Color(0x1FFFFFFF)          // Crisp 12% white optical hairline
val Fg = Color(0xFFF6F7F9)            // Pearl White primary text
val Sub = Color(0xFF969BA6)           // Cool Titanium secondary text
val Honey: Color get() = ThemeColor.currentColor
val HoneyDeep: Color get() = ThemeColor.currentDeep
val HoneyPale: Color get() = ThemeColor.currentPale
val Ink = Color(0xFF0B0C0E)           // Crisp obsidian ink on light/accent pills
val BeeGreen = Color(0xFF9DDC77)      // success/running dot
val BeeDanger = Color(0xFFE0705A)     // semantic danger coral
val BeePanel = Color(0xFF121419)      // sunken glass panel fallback
val BeePanelRaised = Color(0xFF191C23) // raised glass panel fallback

// Resolved at composition time via ThemeColor — use these in composables:
// (getters, not frozen vals: each read re-reads ThemeColor's snapshot state,
//  so a theme change in Settings recolours live — Muse landing repair.)
val ThemeAccent: Color get() = ThemeColor.currentColor
val ThemeAccentDeep: Color get() = ThemeColor.currentDeep
val ThemeAccentPale: Color get() = ThemeColor.currentPale

sealed interface Screen {
    data object Home : Screen
    /** Resolved by App to the most recently opened Bot/group chat. */
    data object BotMode : Screen
    data object BotHub : Screen
    data class Chat(val id: String) : Screen
    data class EditBot(val id: String?) : Screen
    data class EditProvider(val id: String?) : Screen
    data object AgentChat : Screen
    data object EditAgent : Screen
    data object AgentSettings : Screen
    data object Workflows : Screen
    data class EditWorkflow(val id: String?) : Screen
    data class EditMcp(val id: String?) : Screen
    data class EditSkill(val id: String?) : Screen
    data object NewGroup : Screen
    data object Settings : Screen
    data object Search : Screen
    // v0.7.0「蜂巢自治」
    data object Approvals : Screen
    data object Policy : Screen
    data object Routines : Screen
    data object Usage : Screen
    data object Activity : Screen
    data object HiveCenter : Screen
    data object SuperAgent : Screen
    // v0.7.2「蜂巢灵动觉醒 · Pure Android」
    data object PersonalHub : Screen
}

@Composable
fun App(store: Store) {
    val ctx = LocalContext.current
    remember { UiState.init(ctx) }
    remember { ThemeColor.init(ctx); BixPrefs.init(ctx); ModelPrefs.init(ctx); SuperAgent.init(ctx); HiveBlackboard.init(ctx); HivePipeline.init(ctx); MemoryPod.init(ctx); PersonalAgent.init(ctx); HiveWatchdog.start(store) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var drawer by remember { mutableStateOf(false) }
    val nav: (Screen) -> Unit = { s ->
        drawer = false
        screen = if (s == Screen.BotMode) resolveBotTarget(store) else s
    }
    // The floating window's stop button cancels the live Agent run.
    remember {
        AgentMode.stopHook = { store.stopChat(AGENT_ID) }
        true
    }
    BackHandler(drawer) { drawer = false }
    // v0.7.0: predictive back. While the system back gesture is in progress the current page
    // shrinks and slides toward the gesture edge; releasing goes back, cancelling restores it.
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backFromLeft by remember { mutableStateOf(true) }
    androidx.activity.compose.PredictiveBackHandler(enabled = !drawer && screen != Screen.Home) { progress ->
        try {
            progress.collect { e ->
                backFromLeft = e.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT
                backProgress = e.progress
            }
            backProgress = 0f
            screen = parentOf(screen)
        } catch (e: kotlinx.coroutines.CancellationException) {
            backProgress = 0f
            throw e
        }
    }
    // A notification asked to open a chat (Notify → MainActivity → store.openRequest).
    val openReq = store.openRequest.value
    LaunchedEffect(openReq) {
        val id = openReq ?: return@LaunchedEffect
        store.openRequest.value = null
        drawer = false
        screen = when {
            id == AGENT_ID -> Screen.AgentChat
            id == "approvals" -> Screen.Approvals
            else -> Screen.Chat(id)
        }
    }
    LaunchedEffect(screen) {
        when (val s = screen) {
            is Screen.Chat -> UiState.touch(s.id)
            Screen.AgentChat -> UiState.touch(AGENT_ID)
            else -> {}
        }
    }
    MaterialTheme(
        // vNext: body/UI typography is the system sans (Claude look); the bundled serif is
        // reserved for display moments via HiveSerif (greeting / titles / sheet titles).
        typography = hiveTypography(),
        colorScheme = darkColorScheme(
            primary = ThemeAccent, onPrimary = Ink,
            primaryContainer = ThemeAccentDeep, onPrimaryContainer = Fg,
            secondary = BeeGreen, onSecondary = Ink,
            background = Bg, surface = Bg, onSurface = Fg,
            surfaceVariant = Card, onSurfaceVariant = Sub, outline = Line,
            error = BeeDanger
        )
    ) {
        CompositionLocalProvider(
            LocalOpenDrawer provides { drawer = true },
            // vNext drops the global serif override: the ~230 Text call sites inherit the
            // sans base family; serif moments opt in explicitly with HiveSerif styles.
            androidx.compose.material3.LocalTextStyle provides
                androidx.compose.material3.LocalTextStyle.current
        ) {
            val glassTier = GlassStyle.tier.value
            // v0.7.0 (F21): the GPU readback only runs while the activity is resumed.
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            var resumed by remember { mutableStateOf(true) }
            DisposableEffect(lifecycleOwner) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
                    when (ev) {
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> resumed = true
                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> resumed = false
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
            }
            val enableLiveCapture = glassTier.shader && BackdropLens.supported() && resumed && !GlassStyle.capped
            val captureLayer = rememberGraphicsLayer()

            // Async GPU readback loop: completely idle when static (dirtySeq unchanged);
            // throttled to ~30fps during scrolling/transitions, never blocking UI draw on main thread.
            LaunchedEffect(enableLiveCapture) {
                if (!enableLiveCapture) {
                    AppBackdrop.clearLiveFrame()
                    return@LaunchedEffect
                }
                var lastSeq = -1L
                while (isActive) {
                    val seq = AppBackdrop.dirtySeq
                    val rw = AppBackdrop.width
                    val rh = AppBackdrop.height
                    if (seq != lastSeq && rw > 0 && rh > 0) {
                        lastSeq = seq
                        val bmp = runCatching { captureLayer.toImageBitmap().asAndroidBitmap() }.getOrNull()
                        if (bmp != null) {
                            AppBackdrop.publishLiveFrame(bmp, rw, rh)
                        }
                        // ~20 fps is plenty for a blurred lens and halves the readback cost.
                        delay(50L)
                    } else {
                        delay(48L)
                    }
                }
            }

            Box(Modifier.fillMaxSize().background(Bg)) {
                // Real-content capture container: wraps Bg + HoneycombBackdrop + ScreenHost.
                // AppDrawer sits outside as a sibling layer so overlays never enter the backdrop.
                // During captureLayer.record(), AppBackdrop.isCapturing = true skips all glass
                // surfaces and borders so glass only refracts real underlying UI content.
                // Dedicated optical backdrop layer captured for Liquid Glass refraction.
                // Kept strictly separate from ScreenHost so animated transitions, capsules,
                // and composers NEVER leak or ghost inside glass lenses.
                Box(
                    Modifier.fillMaxSize().drawWithContent {
                        val rootW = size.width.toInt()
                        val rootH = size.height.toInt()
                        if (enableLiveCapture && rootW > 0 && rootH > 0) {
                            // v0.7.0 (F21): the lens is blurred anyway; capture at a third of the size.
                            val s = (AppBackdrop.CAPTURE_SCALE * 0.66f).coerceAtLeast(0.25f)
                            val capW = (rootW * s).toInt().coerceAtLeast(1)
                            val capH = (rootH * s).toInt().coerceAtLeast(1)
                            AppBackdrop.isCapturing = true
                            try {
                                captureLayer.record(size = IntSize(capW, capH)) {
                                    scale(scaleX = s, scaleY = s, pivot = Offset.Zero) {
                                        this@drawWithContent.drawContent()
                                    }
                                }
                            } finally {
                                AppBackdrop.isCapturing = false
                            }
                            AppBackdrop.markContentDirty(rootW, rootH)
                        }
                        this@drawWithContent.drawContent()
                    }
                ) {
                    Box(Modifier.fillMaxSize().background(Bg))
                    StudioOpticalBackdrop(Modifier.fillMaxSize())
                    HoneycombBackdrop(Modifier.fillMaxSize(), alpha = .28f)
                }
                Box(
                    Modifier.fillMaxSize().systemBarsPadding().imePadding().graphicsLayer {
                        val p = if (GlassStyle.motionEnabled) backProgress else 0f
                        val sc = 1f - 0.08f * p
                        scaleX = sc
                        scaleY = sc
                        translationX = (if (backFromLeft) 1f else -1f) * size.width * 0.06f * p
                        alpha = 1f - 0.15f * p
                    }
                ) {
                    ScreenHost(screen, store, nav)
                }
                AiBrowserTaskPill(Modifier.align(Alignment.TopEnd))
                AppDrawer(store, drawer, screen, { drawer = false }, nav)
                BixScreenFlightOverlay(
                    screen = screen,
                    drawerOpen = drawer,
                    backProgress = backProgress,
                    backFromLeft = backFromLeft,
                    modifier = Modifier.systemBarsPadding()
                )
                AiBrowserSheetOverlay()
            }
        }
    }
}

/**
 * Renders the current screen and animates transitions between screens.
 *
 * Two directions are distinguished so navigation feels physical rather than uniform:
 *  - forward (drilling in: home → chat/editor) slides in from the right, the old screen drifts left
 *  - back (returning) slides in from the left
 * Home↔Home-adjacent screens additionally fade, and depth changes are kept subtle (a few percent of
 * the width) so repeated navigation stays calm instead of feeling bouncy.
 */
@Composable
private fun ScreenHost(screen: Screen, store: Store, nav: (Screen) -> Unit) {
    // Off tier (or system "remove animations") renders screens directly with no transition.
    if (!GlassStyle.motionEnabled) {
        when (screen) {
            Screen.Home -> HomeScreen(store, nav)
            Screen.BotMode -> {}
            Screen.BotHub -> BotHubScreen(store, nav)
            is Screen.Chat -> ChatScreen(store, screen.id, nav)
            is Screen.EditBot -> EditBotScreen(store, screen.id, nav)
            is Screen.EditProvider -> EditProviderScreen(store, screen.id, nav)
            Screen.AgentChat -> AgentChatScreen(store, nav)
            Screen.EditAgent -> EditAgentScreen(store, nav)
            Screen.AgentSettings -> AgentSettingsScreen(store, nav)
            Screen.Workflows -> WorkflowsScreen(store, nav)
            is Screen.EditWorkflow -> EditWorkflowScreen(store, screen.id, nav)
            is Screen.EditMcp -> EditMcpScreen(store, screen.id, nav)
            is Screen.EditSkill -> EditSkillScreen(store, screen.id, nav)
            Screen.NewGroup -> NewGroupScreen(store, nav)
            Screen.Settings -> SettingsScreen(store, nav)
            Screen.Search -> SearchScreen(store, nav)
            Screen.Approvals -> ApprovalInboxScreen(store, nav)
            Screen.Policy -> PolicyScreen(store, nav)
            Screen.Routines -> RoutinesScreen(store, nav)
            Screen.Usage -> UsageScreen(store, nav)
            Screen.Activity -> ActivityScreen(store, nav)
            Screen.HiveCenter -> HiveCenterScreen(store, nav)
            Screen.SuperAgent -> SuperAgentScreen(store, nav)
            Screen.PersonalHub -> PersonalHubScreen(store, nav)
        }
        return
    }
    // Depth decides direction: 0 = home, 1 = main destinations, 2 = editors/detail.
    val depth = remember(screen) { screenDepth(screen) }
    val previousDepth = remember { mutableStateOf(depth) }
    val forward = depth >= previousDepth.value
    LaunchedEffect(depth) { previousDepth.value = depth }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val modeSwitch = (initialState is Screen.Chat && targetState == Screen.AgentChat) ||
                (initialState == Screen.AgentChat && targetState is Screen.Chat)
            val isHubEnter = targetState == Screen.SuperAgent || targetState == Screen.HiveCenter || targetState == Screen.BotHub || targetState == Screen.PersonalHub
            val isHubExit = initialState == Screen.SuperAgent || initialState == Screen.HiveCenter || initialState == Screen.BotHub || initialState == Screen.PersonalHub
            when {
                targetState == Screen.Search -> {
                    val enter = slideInVertically(
                        animationSpec = spring(dampingRatio = 0.86f, stiffness = 460f),
                        initialOffsetY = { full -> -(full / 16) }
                    ) + fadeIn(tween(GlassMotion.screenFade()))
                    val exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.98f, animationSpec = tween(160))
                    enter togetherWith exit
                }
                isHubEnter && !isHubExit -> {
                    // Command hub dive: vertical lift + gentle zoom-in matching Bix's upward arc.
                    val enter = slideInVertically(
                        animationSpec = spring(dampingRatio = 0.86f, stiffness = 400f),
                        initialOffsetY = { full -> full / 14 }
                    ) + scaleIn(initialScale = 0.94f, animationSpec = spring(dampingRatio = 0.88f, stiffness = 400f)) +
                        fadeIn(tween(GlassMotion.screenFade()))
                    val exit = scaleOut(targetScale = 0.96f, animationSpec = tween(180)) + fadeOut(tween(150))
                    enter togetherWith exit
                }
                isHubExit && targetState == Screen.Home -> {
                    // Returning from command hub to Home: soft zoom-out settle.
                    val enter = scaleIn(initialScale = 0.96f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f)) +
                        fadeIn(tween(GlassMotion.screenFade()))
                    val exit = slideOutVertically(
                        animationSpec = tween(GlassMotion.screenSlide(), easing = FastOutSlowInEasing),
                        targetOffsetY = { full -> full / 16 }
                    ) + fadeOut(tween(150))
                    enter togetherWith exit
                }
                else -> {
                    // Bot <-> Agent or standard forward/back depth slide.
                    val dir = if (modeSwitch) (if (targetState == Screen.AgentChat) 1 else -1) else if (forward) 1 else -1
                    val dist = if (modeSwitch) 5 else 11
                    val enter = slideInHorizontally(
                        animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
                        initialOffsetX = { full -> dir * (full / dist) }
                    ) + fadeIn(tween(GlassMotion.screenFade())) +
                        scaleIn(initialScale = 0.97f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f))
                    val exit = slideOutHorizontally(
                        animationSpec = tween(GlassMotion.screenSlide(), easing = FastOutSlowInEasing),
                        targetOffsetX = { full -> -dir * (full / (dist + 4)) }
                    ) + scaleOut(targetScale = 0.985f, animationSpec = tween(160)) + fadeOut(tween(150))
                    enter togetherWith exit
                }
            }
        },
        label = "screen"
    ) { s ->
        when (s) {
            Screen.Home -> HomeScreen(store, nav)
            Screen.BotMode -> {}
            Screen.BotHub -> BotHubScreen(store, nav)
            is Screen.Chat -> ChatScreen(store, s.id, nav)
            is Screen.EditBot -> EditBotScreen(store, s.id, nav)
            is Screen.EditProvider -> EditProviderScreen(store, s.id, nav)
            Screen.AgentChat -> AgentChatScreen(store, nav)
            Screen.EditAgent -> EditAgentScreen(store, nav)
            Screen.AgentSettings -> AgentSettingsScreen(store, nav)
            Screen.Workflows -> WorkflowsScreen(store, nav)
            is Screen.EditWorkflow -> EditWorkflowScreen(store, s.id, nav)
            is Screen.EditMcp -> EditMcpScreen(store, s.id, nav)
            is Screen.EditSkill -> EditSkillScreen(store, s.id, nav)
            Screen.NewGroup -> NewGroupScreen(store, nav)
            Screen.Settings -> SettingsScreen(store, nav)
            Screen.Search -> SearchScreen(store, nav)
            Screen.Approvals -> ApprovalInboxScreen(store, nav)
            Screen.Policy -> PolicyScreen(store, nav)
            Screen.Routines -> RoutinesScreen(store, nav)
            Screen.Usage -> UsageScreen(store, nav)
            Screen.Activity -> ActivityScreen(store, nav)
            Screen.HiveCenter -> HiveCenterScreen(store, nav)
            Screen.SuperAgent -> SuperAgentScreen(store, nav)
            Screen.PersonalHub -> PersonalHubScreen(store, nav)
        }
    }
}

/** Where system back goes from [s]: editors return to their list, everything else to Home. */
private fun parentOf(s: Screen): Screen = when (s) {
    is Screen.EditWorkflow -> Screen.Workflows
    Screen.Policy, Screen.Usage -> Screen.Settings
    is Screen.EditProvider, is Screen.EditMcp, is Screen.EditSkill -> Screen.Settings
    Screen.EditAgent, Screen.AgentSettings -> Screen.AgentChat
    else -> Screen.Home
}

/** Navigation depth used to pick the slide direction. */
private fun screenDepth(s: Screen): Int = when (s) {
    Screen.Home -> 0
    Screen.Settings, Screen.Workflows, Screen.Search, Screen.NewGroup -> 1
    Screen.AgentChat -> 1
    Screen.EditAgent, Screen.AgentSettings -> 2
    is Screen.Chat -> 1
    is Screen.EditBot, is Screen.EditProvider, is Screen.EditWorkflow,
    is Screen.EditMcp, is Screen.EditSkill -> 2
    Screen.BotMode -> 1
    Screen.BotHub -> 1
    Screen.Approvals, Screen.Routines, Screen.Activity, Screen.HiveCenter, Screen.SuperAgent, Screen.PersonalHub -> 1
    Screen.Policy, Screen.Usage -> 2
}

@Composable
fun Avatar(seed: String, size: Dp, group: Boolean = false) {
    Box(
        Modifier.size(size).harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card),
        contentAlignment = Alignment.Center
    ) {
        PixelBeeAvatar(seed = seed, size = size, group = group)
    }
}

@Composable
fun ChatScreen(store: Store, id: String, go: (Screen) -> Unit) {
    val msgs = store.chat(id)
    val busy = id in store.busy
    val readOnly = id.startsWith("dm:") || id.startsWith("wf:")
    val isBot = id.startsWith("bot:")
    var input by remember { mutableStateOf("") }
    val staged = remember { mutableStateListOf<AttachItem>() }
    val pickFiles = AttachPickerLauncher { staged.addAll(it) }
    var confirm by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showFilesSheet by remember { mutableStateOf(false) }
    var showMemorySheet by remember { mutableStateOf(false) }
    var projectMode by remember { mutableStateOf(true) }
    val isManager = isBot && store.bot(id.removePrefix("bot:"))?.isManager == true
    val listState = rememberLazyListState()
    val last = msgs.lastOrNull()
    // Smart auto-scroll (v0.6.4 fix): track near-bottom continuously while scrolling & after settle.
    val scrollScope = rememberCoroutineScope()
    var atBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.isNearBottom() }
            .collect { (_, near) -> atBottom = near }
    }
    LaunchedEffect(msgs.size, last?.tools?.size, last?.text) {
        if (msgs.isNotEmpty() && atBottom) listState.animateScrollToItem(msgs.lastIndex)
    }
    // HarmonyOS Immersive Light STACK architecture: chat list scrolls cleanly underneath
    // the floating ULTRA_THIN top bar / mode capsule and THIN bottom composer so live text
    // and bubbles refract with true RGB edge dispersion as they pass behind the glass.
    val headerBlurFraction by remember(listState) {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> ((listState.firstVisibleItemScrollOffset - 8f) / 48f).coerceIn(0f, 1f)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // v0.7.0 (iOS 27.3-style scroll edge): text fades out softly under the floating top bar.
            modifier = Modifier.fillMaxSize().topScrollFade(104.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 118.dp,
                bottom = if (readOnly) 24.dp else 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(msgs, key = { it.id }) { m ->
                if (GlassStyle.motionEnabled) {
                    var appeared by remember(m.id) { mutableStateOf(false) }
                    LaunchedEffect(m.id) { appeared = true }
                    RowEnter(appeared) {
                        MsgView(store, m, showName = !isBot, thinking = busy && m.id == msgs.last().id, go = go, chatId = id)
                    }
                } else {
                    MsgView(store, m, showName = !isBot, thinking = busy && m.id == msgs.last().id, go = go, chatId = id)
                }
            }
        }
        if (msgs.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                InteractiveBixHero(isAgent = false)
                Spacer(Modifier.height(10.dp))
                Text("开始对话", style = HiveSerif.Title(22.sp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isManager) "总经理会先拆解分工再派给团队执行；也可以点右下角切回普通对话。"
                    else "在下方输入消息，Bot 会在自己的沙盒或独立浏览器里干活；点摘要可查看每一步。",
                    color = Sub, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp
                )
                if (!readOnly) {
                    Spacer(Modifier.height(18.dp))
                    val starters = if (isManager) listOf(
                        "帮我拆解一个新项目并分工",
                        "组织团队头脑风暴产品方案",
                        "先介绍一下你的团队成员"
                    ) else listOf(
                        "先介绍一下你最擅长做什么",
                        "用独立浏览器查下今天科技热点",
                        "在沙盒里跑一段 Python 小测试"
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        starters.forEach { q ->
                            Box(
                                Modifier.pressBounce {
                                    if (isManager && projectMode) store.startProject(id, q)
                                    else store.send(id, q, emptyList())
                                }
                                    .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card)
                                    .padding(horizontal = 13.dp, vertical = 8.dp)
                            ) { Text(q, color = Fg, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        // v0.6.4: Floating Liquid Glass scroll-to-bottom button when scrolled up in a long chat.
        androidx.compose.animation.AnimatedVisibility(
            visible = !atBottom && msgs.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (readOnly) 24.dp else 94.dp),
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(150))
        ) {
            Box(
                Modifier.size(40.dp)
                    .pressBounce {
                        atBottom = true
                        scrollScope.launch { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex) }
                    }
                    .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 999.dp, fallbackBg = Card),
                contentAlignment = Alignment.Center
            ) { Icon(Ic.Down, "回到底部", tint = Fg, modifier = Modifier.size(20.dp)) }
        }

        // Floating top bar: GRADUAL_BLUR-style scrim fades in smoothly as content scrolls under (8..56px).
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .drawWithContent {
                    if (!AppBackdrop.isCapturing && headerBlurFraction > 0.01f) {
                        drawRect(
                            Brush.verticalGradient(
                                0f to Bg.copy(alpha = 0.64f * headerBlurFraction),
                                0.72f to Bg.copy(alpha = 0.22f * headerBlurFraction),
                                1f to Color.Transparent
                            )
                        )
                    }
                    drawContent()
                }
        ) {
            AppTopBar(store, "bot", go)
            ChatSubHeader(id, !isBot, store.title(id), store.subtitle(id)) {
                ExportButton(store, id)
                if (isBot) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(36.dp).harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card)) {
                        IconButton(onClick = { showModelSheet = true }, modifier = Modifier.fillMaxSize()) { Icon(Ic.Tune, "切换模型", tint = ThemeAccentPale, modifier = Modifier.size(18.dp)) }
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(36.dp).harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card)) {
                        IconButton(onClick = { showFilesSheet = true }, modifier = Modifier.fillMaxSize()) { Icon(Ic.Artifacts, "沙盒文件柜", tint = Fg, modifier = Modifier.size(17.dp)) }
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(36.dp).harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card)) {
                        IconButton(onClick = { showMemorySheet = true }, modifier = Modifier.fillMaxSize()) { Icon(Ic.Info, "记忆大脑", tint = Fg, modifier = Modifier.size(17.dp)) }
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(36.dp).harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card)) {
                        IconButton(onClick = { go(Screen.EditBot(id.removePrefix("bot:"))) }, modifier = Modifier.fillMaxSize()) { Icon(Ic.Edit, "编辑 Bot", tint = Fg, modifier = Modifier.size(18.dp)) }
                    }
                } else if (id.startsWith("group:")) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(36.dp).harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card)) {
                        IconButton(onClick = { go(Screen.BotHub) }, modifier = Modifier.fillMaxSize()) { Icon(Ic.Group, "蜂巢指挥台", tint = Fg, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            ApprovalCards(store, id)
        }

        if (!readOnly) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .drawWithContent {
                        if (!AppBackdrop.isCapturing) {
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.35f to Bg.copy(alpha = 0.25f),
                                    1f to Bg.copy(alpha = 0.72f)
                                )
                            )
                        }
                        drawContent()
                    }
            ) {
                AttachChips(staged) { staged.remove(it) }
                ChatComposer(
                    value = input,
                    onValueChange = { input = it },
                    hint = if (isManager && projectMode) "下达任务,总经理先出分工方案…" else "发消息…",
                    busy = busy,
                    canSend = input.isNotBlank() || staged.isNotEmpty(),
                    onPickFiles = pickFiles,
                    extraChip = {
                        if (isManager) {
                            Box(
                                Modifier.clip(RoundedCornerShape(UiTokens.RadiusChip))
                                    .background(if (projectMode) ThemeAccent.copy(alpha = 0.18f) else PILL)
                                    .clickable { projectMode = !projectMode }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    if (projectMode) "立项模式" else "普通对话",
                                    color = if (projectMode) ThemeAccent else Sub, fontSize = 12.sp
                                )
                            }
                        }
                    },
                    onSend = {
                        if (isManager && projectMode && staged.isEmpty()) store.startProject(id, input.trim())
                        else store.send(id, input.trim(), staged.toList())
                        input = ""
                        staged.clear()
                    },
                    onStop = { store.stopChat(id) }
                )
            }
        }
    }
    if (isBot) {
        val curBot = store.bot(id.removePrefix("bot:"))
        ModelSheet(
            bot = curBot,
            store = store,
            visible = showModelSheet,
            onDismiss = { showModelSheet = false },
            onPick = { m ->
                curBot?.let { b ->
                    store.saveBot(b.copy(model = m))
                }
            }
        )
        SandboxFilesSheet(
            store = store,
            botId = id.removePrefix("bot:"),
            visible = showFilesSheet,
            onDismiss = { showFilesSheet = false }
        )
        MemoryBrainSheet(
            store = store,
            botId = id.removePrefix("bot:"),
            botName = curBot?.name ?: "Bot",
            visible = showMemorySheet,
            onDismiss = { showMemorySheet = false }
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("重置沙盒?") },
            text = { Text("会清空这个 Bot 沙盒里的所有文件,聊天记录保留。") },
            confirmButton = {
                TextButton(onClick = { confirm = false; store.resetSandbox(id.removePrefix("bot:")) }) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
}

/**
 * Eases one chat row in on first composition.
 *
 * The animation lives in its own composable because `ColumnScope.AnimatedVisibility` is a top-level
 * extension in the same package: inside a LazyColumn item, which sits under the chat's Column, that
 * overload wins name resolution and fails as "cannot be called with an implicit receiver". Here no
 * ColumnScope is in scope, so the plain composable is selected.
 */
@Composable
private fun RowEnter(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 6 }
    ) { content() }
}

/** True when the list is scrolled to (or very near) its last item — used by the smart auto-scroll. */
internal fun androidx.compose.foundation.lazy.LazyListState.isNearBottom(): Boolean {
    val info = layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
    return lastVisible.index >= info.totalItemsCount - 2
}

@Composable
fun MsgView(store: Store, m: Msg, showName: Boolean, thinking: Boolean, go: (Screen) -> Unit, chatId: String = "") {
    // Resend only makes sense where a message can actually be sent again; DM/wf threads are read-only.
    val canResend = chatId.startsWith("bot:") || chatId.startsWith("group:") || chatId == AGENT_ID
    when {
        m.role == "project" -> ProjectCard(store, m.text, go)
        m.role == "system" -> Text(m.text, color = Sub, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        m.from == null -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            val ctx = LocalContext.current
            val chatBusy = chatId in store.busy
            var askResend by remember { mutableStateOf(false) }
            var showEdit by remember { mutableStateOf(false) }
            var editDraft by remember(m.id, m.text) { mutableStateOf(m.text) }
            Box(
                modifier = Modifier.widthIn(max = 300.dp)
                    .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 20.dp, fallbackBg = UserBubble)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (m.images.isNotEmpty()) {
                        m.images.forEachIndexed { i, name ->
                            val bmp = remember(name) {
                                runCatching {
                                    android.graphics.BitmapFactory.decodeFile(
                                        java.io.File(ctx.filesDir, "attachments/$name").absolutePath
                                    )
                                }.getOrNull()
                            }
                            if (bmp != null) {
                                Image(
                                    bmp.asImageBitmap(), null,
                                    Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp))
                                )
                                if (i < m.images.lastIndex || m.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    // User input stays literal: typing **text** should not become bold.
                    if (m.text.isNotBlank()) SelectionContainer { Text(m.text, color = Fg, fontSize = 16.sp, lineHeight = 23.sp) }
                }
            }
            // v0.6.4: Compact user message actions — Edit, Copy, Resend (gated on !chatBusy).
            if (!chatBusy && canResend) {
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (m.time > 0) {
                        Text(timeAgo(m.time), color = Sub.copy(alpha = 0.72f), fontSize = 10.sp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Box(
                        Modifier.size(28.dp).pressBounce { editDraft = m.text; showEdit = true }
                            .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL),
                        contentAlignment = Alignment.Center
                    ) { Icon(Ic.Edit, "修改消息", tint = Sub, modifier = Modifier.size(13.dp)) }
                    if (m.text.isNotBlank()) {
                        Box(
                            Modifier.size(28.dp).pressBounce {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("message", m.text))
                                android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                            }.harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL),
                            contentAlignment = Alignment.Center
                        ) { Icon(Ic.Copy, "复制", tint = Sub, modifier = Modifier.size(13.dp)) }
                    }
                    Box(
                        Modifier.size(28.dp).pressBounce { askResend = true }
                            .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL),
                        contentAlignment = Alignment.Center
                    ) { Icon(Ic.Refresh, "重发", tint = Sub, modifier = Modifier.size(13.dp)) }
                }
            }
            if (showEdit) {
                AlertDialog(
                    onDismissRequest = { showEdit = false },
                    title = { Text("修改消息") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field("消息内容", editDraft, { editDraft = it }, singleLine = false, minLines = 3)
                            Text(
                                "「保存并重发」会清除之后的回复并重新发送；也可仅原地修正文字或删除此条。",
                                color = Sub, fontSize = 11.sp, lineHeight = 16.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallChip("仅修改文字") {
                                    if (editDraft.isNotBlank()) {
                                        val list = store.chat(chatId)
                                        val idx = list.indexOfFirst { it.id == m.id }
                                        if (idx >= 0) {
                                            list[idx] = list[idx].copy(text = editDraft.trim())
                                            store.save()
                                        }
                                        showEdit = false
                                    }
                                }
                                Box(
                                    Modifier.pressBounce {
                                        val list = store.chat(chatId)
                                        val idx = list.indexOfFirst { it.id == m.id }
                                        if (idx >= 0) {
                                            list.removeAt(idx)
                                            store.save()
                                        }
                                        showEdit = false
                                    }.clip(RoundedCornerShape(14.dp)).background(BeeDanger.copy(alpha = 0.16f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) { Text("删除消息", color = BeeDanger, fontSize = 12.sp) }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = editDraft.isNotBlank(),
                            onClick = {
                                val list = store.chat(chatId)
                                val idx = list.indexOfFirst { it.id == m.id }
                                if (idx >= 0) {
                                    list[idx] = list[idx].copy(text = editDraft.trim())
                                    showEdit = false
                                    store.resendFrom(chatId, m.id)
                                } else {
                                    showEdit = false
                                }
                            }
                        ) { Text("保存并重发") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEdit = false }) { Text("取消") }
                    }
                )
            }
            if (askResend) {
                AlertDialog(
                    onDismissRequest = { askResend = false },
                    title = { Text("重发这条消息?") },
                    text = { Text("这条消息之后的内容会被清除,然后重新发送。") },
                    confirmButton = {
                        TextButton(onClick = {
                            askResend = false
                            store.resendFrom(chatId, m.id)
                        }) { Text("重发") }
                    },
                    dismissButton = { TextButton(onClick = { askResend = false }) { Text("取消") } }
                )
            }
        }
        else -> Column(Modifier.fillMaxWidth()) {
            val (thoughtText, answerText) = remember(m.text) { splitThinkingContent(m.text) }
            if (showName) Text(store.botName(m.from), color = Sub, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            if (m.tools.isNotEmpty() || thoughtText.isNotBlank() || (thinking && answerText.isBlank())) {
                ToolsPanel(
                    tools = m.tools,
                    scopeKey = m.id,
                    live = thinking,
                    store = store,
                    chatId = chatId,
                    botId = m.from,
                    thoughtText = thoughtText,
                    answerBlank = answerText.isBlank()
                )
            }
            if (answerText.isNotBlank()) {
                // Assistant replies are Markdown: headings, lists, code, tables all render as such.
                // vNext: no bubble — plain text laid straight onto the page colour, generous
                // paragraph spacing (the container padding below is the only chrome).
                SelectionContainer { MarkdownText(answerText, fontSize = 16.sp, lineHeight = 23.sp) }
                if (!thinking) ArtifactPreviewRow(answerText)
            }
            // Action row under the assistant message: only the capabilities that exist today —
            // copy and resend (read-out is a 0.5.10 feature and is NOT shown).
            if (!thinking && answerText.isNotBlank() && chatId.isNotBlank() && canResendChat(chatId)) {
                AssistantActions(store, chatId, m)
            }
        }
    }
}

/** Resend is offered exactly where Store.resendFrom can act (bot/group/agent threads). */
private fun canResendChat(chatId: String): Boolean =
    chatId.startsWith("bot:") || chatId.startsWith("group:") || chatId == AGENT_ID

/** Copy / regenerate / delete icons + timestamp under an assistant message (v0.6.4 bugfix). */
@Composable
private fun AssistantActions(store: Store, chatId: String, m: Msg) {
    val ctx = LocalContext.current
    val chatBusy = chatId in store.busy
    var askResend by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val cleanAnswer = remember(m.text) {
        val (_, ans) = splitThinkingContent(m.text)
        ans.ifBlank { m.text }
    }
    Row(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).pressBounce {
                // v0.7.0 (F16): replies often quote tool output (tokens, keys) — hide from clipboard preview.
                copySensitive(ctx, "message", cleanAnswer)
            }.harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL),
            contentAlignment = Alignment.Center
        ) { Icon(Ic.Copy, "复制", tint = Sub, modifier = Modifier.size(15.dp)) }
        if (!chatBusy) {
            Box(
                Modifier.size(32.dp).pressBounce { askResend = true }
                    .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL),
                contentAlignment = Alignment.Center
            ) { Icon(Ic.Refresh, "重新生成", tint = Sub, modifier = Modifier.size(15.dp)) }
            Box(
                Modifier.size(32.dp).pressBounce { askDelete = true }
                    .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL),
                contentAlignment = Alignment.Center
            ) { Icon(Ic.Close, "删除这条回复", tint = Sub, modifier = Modifier.size(14.dp)) }
        }
        // v0.7.0: feedback. Thumbs are stored on the message; "整理反馈" in the memory sheet turns
        // them into rule suggestions the user approves before they take effect.
        if (m.from != null && chatId.isNotEmpty()) {
            val up = m.feedback > 0
            val down = m.feedback < 0
            Box(
                Modifier.size(32.dp).pressBounce { store.setFeedback(chatId, m.id, if (up) 0 else 1) }
                    .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = if (up) ThemeAccent.copy(alpha = 0.28f) else PILL),
                contentAlignment = Alignment.Center
            ) { Icon(Ic.ThumbUp, "有帮助", tint = if (up) ThemeAccentPale else Sub, modifier = Modifier.size(14.dp)) }
            Box(
                Modifier.size(32.dp).pressBounce { store.setFeedback(chatId, m.id, if (down) 0 else -1) }
                    .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = if (down) BeeDanger.copy(alpha = 0.22f) else PILL),
                contentAlignment = Alignment.Center
            ) { Icon(Ic.ThumbDown, "没帮助", tint = if (down) BeeDanger else Sub, modifier = Modifier.size(14.dp)) }
        }
        if (m.time > 0) {
            Spacer(Modifier.width(4.dp))
            Text(timeAgo(m.time), color = Sub.copy(alpha = 0.72f), fontSize = 10.sp)
        }
    }
    if (askResend) {
        AlertDialog(
            onDismissRequest = { askResend = false },
            title = { Text("从此处重新生成?") },
            text = { Text("这条回复及之后的内容会被清除,然后让模型重新作答。") },
            confirmButton = {
                TextButton(onClick = {
                    askResend = false
                    // v0.6.4 fix: Store.resendFrom requires a user message id (m.from == null).
                    // Locate the closest preceding user message before this assistant reply.
                    val list = store.chat(chatId)
                    val idx = list.indexOfFirst { it.id == m.id }
                    val prevUser = if (idx > 0) list.subList(0, idx).lastOrNull { it.from == null } else null
                    if (prevUser != null) {
                        store.resendFrom(chatId, prevUser.id)
                    } else {
                        android.widget.Toast.makeText(ctx, "未找到对应的上一条提问", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("重新生成") }
            },
            dismissButton = { TextButton(onClick = { askResend = false }) { Text("取消") } }
        )
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("删除这条回复?") },
            text = { Text("仅删除这一条消息,不影响上下文其余对话。") },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    val list = store.chat(chatId)
                    val idx = list.indexOfFirst { it.id == m.id }
                    if (idx >= 0) {
                        list.removeAt(idx)
                        store.save()
                    }
                }) { Text("删除", color = BeeDanger) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("取消") } }
        )
    }
}

private val SUMMARY_KEYS = listOf("command", "path", "url", "bot_name", "query", "title", "name", "text", "package", "action")
private val EXIT_RE = Regex("""\[exit (-?\d+)\]""")

private fun parseArgs(args: String): org.json.JSONObject? =
    try { org.json.JSONObject(args) } catch (_: Exception) { null }

/** One-line summary of a tool call's arguments (command / path / target), at most ~60 chars. */
private fun toolSummary(args: String): String {
    val o = parseArgs(args)
    val raw: String = if (o == null) {
        args
    } else {
        var pick: String? = null
        for (k in SUMMARY_KEYS) {
            if (o.has(k) && !o.isNull(k)) { pick = o.opt(k).toString(); break }
        }
        if (pick == null) {
            val keys = o.keys()
            if (keys.hasNext()) pick = o.opt(keys.next()).toString()
        }
        pick ?: ""
    }
    val one = raw.replace(Regex("\\s+"), " ").trim()
    return if (one.length > 60) one.take(60) + "…" else one
}

private fun prettyArgs(args: String): String {
    val o = parseArgs(args) ?: return args
    val keys = o.keys()
    val sb = StringBuilder()
    while (keys.hasNext()) {
        val k = keys.next()
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(k).append(": ").append(o.opt(k).toString())
    }
    return sb.toString().ifBlank { args }
}

/** Coral dot shown when a call did not succeed (blocked by the safety policy, or non-zero exit). */
private fun toolBadge(t: ToolCall): Boolean {
    if (t.output.startsWith("【安全策略已拦截】")) return true
    val m = EXIT_RE.findAll(t.output).lastOrNull()
    return m != null && m.groupValues[1] != "0"
}

/**
 * Unified Bix Execution & Thinking Summary Box:
 *  - Collapsed: ONE single Liquid Glass capsule with animated Bix on the left (typing on pixel laptop,
 *    thinking, or drilling mini-Bix squad) and concise command summary or last-line "…thinking" on the right.
 *  - Expanded: Half-height Liquid Glass bottom sheet ("摘要") so the upper screen stays visible; Bix gets
 *    bounced up onto the top roof of the sheet with squash-and-stretch landing and continues its live action,
 *    while individual rows inside the timeline stay clean without repeating Bix.
 */
@Composable
fun ToolsPanel(
    tools: List<ToolCall>, scopeKey: String, live: Boolean,
    store: Store? = null, chatId: String = "", botId: String? = null,
    thoughtText: String = "", answerBlank: Boolean = false
) {
    var showSummary by remember { mutableStateOf(false) }
    val runningIdx = if (live) tools.indexOfFirst { it.output.isBlank() } else -1
    val done = tools.count { it.output.isNotBlank() }
    val isLiveThinking = live && runningIdx < 0 && (thoughtText.isNotBlank() || answerBlank)

    val currentBixAction = when {
        runningIdx >= 0 -> classifyToolBixAction(tools[runningIdx].name)
        isLiveThinking -> BixAction.Thinking
        tools.any { toolBadge(it) } -> BixAction.Dizzy
        tools.isNotEmpty() -> BixAction.Done
        else -> BixAction.Done
    }

    val collapsedText = when {
        runningIdx >= 0 -> humanToolSummary(tools[runningIdx])
        isLiveThinking -> formatLastThinkingLine(thoughtText)
        tools.size == 1 -> "${humanToolSummary(tools[0])} · 完成"
        tools.size > 1 -> "已完成 $done 步 · ${humanToolSummary(tools.last())}"
        thoughtText.isNotBlank() -> formatLastThinkingLine(thoughtText)
        else -> "已完成"
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .harmonyGlass(thickness = ImmersiveThickness.THICK, radius = 16.dp, fallbackBg = Card)
                .clickable { showSummary = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BixScene(action = currentBixAction, compact = true)
            Spacer(Modifier.width(8.dp))
            Text(
                collapsedText,
                color = if (runningIdx >= 0 || isLiveThinking) Fg else Sub,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (tools.any { toolBadge(it) }) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(BeeDanger))
                Spacer(Modifier.width(6.dp))
            }
            Icon(Ic.Chevron, null, tint = Sub, modifier = Modifier.size(16.dp))
        }
    }

    if (showSummary) {
        Dialog(
            onDismissRequest = { showSummary = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            val stepBadge = when {
                tools.isNotEmpty() -> "${tools.size} 步"
                thoughtText.isNotBlank() -> "思考"
                else -> ""
            }
            BixSummaryHalfSheet(
                title = "摘要",
                subtitle = stepBadge,
                bixAction = currentBixAction,
                visible = true,
                onDismiss = { showSummary = false }
            ) {
                var expandThought by remember { mutableStateOf(tools.isEmpty()) }
                val expandedTools = remember(scopeKey) {
                    mutableStateListOf<String>().apply {
                        if (tools.size == 1) add(tools[0].id)
                    }
                }

                // Thinking timeline entry (Claude-style subtle dot on rail, NO Bix beside it)
                if (thoughtText.isNotBlank() || isLiveThinking) {
                    val lastLine = formatLastThinkingLine(thoughtText)
                    val isLastRow = tools.isEmpty()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .clickable { expandThought = !expandThought }
                    ) {
                        Column(
                            Modifier.width(36.dp).fillMaxSize().padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier
                                    .size(if (isLiveThinking) 10.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(if (isLiveThinking) ThemeAccent else Sub.copy(alpha = 0.55f))
                            )
                            if (!isLastRow) {
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.width(1.5.dp).weight(1f).background(HAIRLINE))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
                            Text(
                                if (expandThought && thoughtText.isNotBlank()) thoughtText else lastLine,
                                style = HiveSerif.Thinking(),
                                maxLines = if (expandThought) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Tool execution timeline entries (clean terminal rail icons, NO Bix beside rows)
                tools.forEachIndexed { i, t ->
                    val running = live && i == runningIdx
                    val isOpen = t.id in expandedTools
                    val summaryTitle = humanToolSummary(t)
                    val argLine = toolSummary(t.args)
                    SummaryStepRow(
                        icon = Ic.Terminal,
                        name = summaryTitle,
                        detail = if (argLine.isNotBlank() && argLine != summaryTitle) "${t.name} · $argLine" else t.name,
                        running = running,
                        last = i == tools.lastIndex,
                        onClick = {
                            if (isOpen) expandedTools.remove(t.id) else expandedTools.add(t.id)
                        }
                    )
                    if (store != null && !running && isOpen) {
                        ToolDetail(t, ToolActions(store, chatId, botId))
                    }
                }
            }
        }
    }
}

/** What the expanded tool row can do: copy always; re-run only for shell calls in a writable chat. */
class ToolActions(val store: Store, val chatId: String, val botId: String?)

/** Text placed on the clipboard by "复制": the bare command for shell, otherwise the raw JSON arguments. */
private fun copyText(t: ToolCall): String {
    val o = parseArgs(t.args)
    return if (t.name == "shell" && o != null && o.has("command")) o.optString("command") else t.args
}

/** Detail block for one tool call inside the Summary sheet: args, output, copy / re-run actions. */
@Composable
private fun ToolDetail(t: ToolCall, actions: ToolActions) {
    Column(Modifier.fillMaxWidth().padding(start = 44.dp, bottom = 4.dp)) {
        // Tool screenshots (e.g. phone_task captures) — restored during landing verification;
        // the reskin's first pass dropped this block that the old inline rows had.
        if (t.image.isNotBlank()) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val bmp = remember(t.image) {
                runCatching { android.graphics.BitmapFactory.decodeFile(t.image) }.getOrNull()
            }
            if (bmp != null) {
                Text("截图", color = Sub, fontSize = 11.sp)
                Image(
                    bmp.asImageBitmap(), "屏幕截图",
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp))
                        .padding(top = 4.dp, bottom = 10.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                )
            }
        }
        if (t.output.isNotBlank() || t.args.isNotBlank()) {
            Text("参数", color = Sub, fontSize = 11.sp)
            Text(
                prettyArgs(t.args), color = Fg, fontSize = 12.sp, fontFamily = HiveFonts.Mono,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            Text("输出", color = Sub, fontSize = 11.sp)
            Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 2.dp)) {
                val empty = t.output.isBlank()
                Text(
                    if (empty) "(无输出)" else t.output,
                    color = if (empty) Sub else Fg, fontSize = 12.sp, fontFamily = HiveFonts.Mono,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
            ToolActionRow(t, actions)
        }
    }
}

@Composable
private fun ToolActionRow(t: ToolCall, a: ToolActions) {
    val ctx = LocalContext.current
    val canRerun = t.name == "shell" && a.botId != null &&
        (a.chatId.startsWith("bot:") || a.chatId.startsWith("group:") || a.chatId == AGENT_ID)
    var ask by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallChip("复制" + if (t.name == "shell") "命令" else "参数") { copySensitive(ctx, "tool", copyText(t)) }
        if (t.output.isNotBlank()) SmallChip("复制输出") { copySensitive(ctx, "tool output", t.output) }
        if (canRerun) SmallChip("重跑") { ask = true }
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("重跑这条命令?") },
            text = { Text(copyText(t), fontFamily = HiveFonts.Mono, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    val msg = a.store.rerunTool(a.chatId, a.botId ?: "", t)
                    if (msg != null) android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("重跑") }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("取消") } }
        )
    }
}

@Composable
fun SmallChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.pressBounce(onClick = onClick)
            .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 14.dp, fallbackBg = Line)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(text, color = Fg, fontSize = 12.sp) }
}

/** Three-dot loading indicator shown while a Bot is preparing its reply — coral, sequentially lit. */
@Composable
fun ThinkingDots(compact: Boolean = false) {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "phase"
    )
    Row(
        Modifier.padding(vertical = if (compact) 0.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0..2) {
            Box(
                Modifier.size(if (compact) 5.dp else 7.dp).clip(CircleShape)
                    .background(if (phase.toInt() == i) ThemeAccent else ThemeAccent.copy(alpha = 0.28f))
            )
        }
    }
}

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit,
    singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text,
    visual: VisualTransformation = VisualTransformation.None, minLines: Int = 1,
    trailing: @Composable (() -> Unit)? = null
) {
    Box(
        Modifier.fillMaxWidth()
            .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 18.dp, fallbackBg = Card)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) },
            singleLine = singleLine, minLines = minLines, visualTransformation = visual,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            trailingIcon = trailing,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Fg, unfocusedTextColor = Fg,
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedBorderColor = ThemeAccent.copy(alpha = 0.78f), unfocusedBorderColor = Color.Transparent,
                focusedLabelColor = ThemeAccent, unfocusedLabelColor = Sub, cursorColor = ThemeAccent
            )
        )
    }
}

@Composable
fun PillButton(text: String, filled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.pressBounce(onClick = onClick)
            .then(
                if (filled) Modifier.clip(RoundedCornerShape(26.dp)).background(PillWhite)
                else Modifier.harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 26.dp, fallbackBg = Card)
            )
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (filled) Ink else Fg, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun EditBotScreen(store: Store, botId: String?, go: (Screen) -> Unit) {
    val old = store.bot(botId)
    var name by remember { mutableStateOf(old?.name ?: "") }
    var prompt by remember { mutableStateOf(old?.prompt ?: PRESETS[1].prompt) }
    var preset by remember { mutableStateOf(if (old == null) PRESETS[1].name else "自定义") }
    var isManager by remember { mutableStateOf(old?.isManager ?: PRESETS[1].manager) }
    val skillSel = remember { mutableStateListOf<String>().apply { addAll(old?.skillIds ?: emptyList()) } }
    val ctxBot = LocalContext.current
    var shellAppr by remember { mutableStateOf(old?.shellApproval ?: false) }
    var multimodal by remember { mutableStateOf(old?.let { ModelPrefs.isMultimodal(it.id, ctxBot) } ?: true) }
    var reasoningEffort by remember { mutableStateOf(old?.let { ModelPrefs.reasoningEffort(it.id, ctxBot) } ?: "auto") }
    var baseUrl by remember { mutableStateOf(old?.baseUrl ?: "https://api.openai.com/v1") }
    var model by remember { mutableStateOf(old?.model ?: "gpt-4o-mini") }
    var key by remember { mutableStateOf(old?.apiKey ?: "") }
    var allowInsecureHttp by remember { mutableStateOf(old?.allowInsecureHttp ?: false) }
    var showKey by remember { mutableStateOf(false) }
    var providerId by remember { mutableStateOf(old?.providerId ?: "") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val back = { go(if (botId != null) Screen.Chat("bot:$botId") else Screen.Home) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).pressBounce { back() }.harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card), contentAlignment = Alignment.Center) { Icon(Ic.Back, null, tint = Fg, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Text(if (old == null) "新建 Bot" else "编辑 Bot", style = HiveSerif.SheetTitle())
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Field("名字", name, { name = it })
            Text("预设", color = Sub, fontSize = 13.sp)
            LiquidChoice(
                options = PRESETS.map { it.name },
                selected = PRESETS.indexOfFirst { it.name == preset },
                onSelect = { i ->
                    val p = PRESETS[i]
                    preset = p.name; prompt = p.prompt; isManager = p.manager
                    if (name.isBlank() && p.prompt.isNotEmpty()) name = p.name
                }
            )
            Field("System Prompt", prompt, { prompt = it; preset = "自定义" }, singleLine = false, minLines = 5)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("总经理", color = Fg, fontSize = 15.sp)
                    Text("可立项、派工、审查员工成果", color = Sub, fontSize = 12.sp)
                }
                LiquidSwitch(checked = isManager, onCheckedChange = { isManager = it })
            }
            HorizontalDivider(color = Line)
            Text("连接(Provider 预设一次配置、处处复用;或为这个 Bot 单独填写)", color = Sub, fontSize = 13.sp)
            LiquidChoice(
                options = listOf("自定义") + store.providers.map { it.name },
                selected = if (providerId.isEmpty()) 0 else store.providers.indexOfFirst { it.id == providerId }.let { if (it < 0) -1 else it + 1 },
                onSelect = { i ->
                    if (i == 0) providerId = "" else { providerId = store.providers[i - 1].id; models = emptyList(); modelMsg = "" }
                }
            )
            val prov = store.provider(providerId)
            if (prov != null) {
                Text("使用预设「${prov.name}」:${prov.baseUrl} · Key 已保存在预设中(在设置里管理)", color = Sub, fontSize = 12.sp)
            } else {
                if (store.providers.isEmpty()) Text("还没有预设:可在「设置 → Provider 预设」里添加,之后所有 Bot 直接选用", color = Sub, fontSize = 12.sp)
                Field("Base URL", baseUrl, { baseUrl = it }, keyboard = KeyboardType.Uri)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("允许 HTTP 明文连接", color = Fg, fontSize = 14.sp)
                        Text("仅对可信的本地或内网 API 开启,HTTP 可能泄露 API Key", color = Sub, fontSize = 11.sp)
                    }
                    LiquidSwitch(checked = allowInsecureHttp, onCheckedChange = { allowInsecureHttp = it })
                }
                // Plain text keyboard + visual masking: never triggers the system secure keyboard,
                // so paste/copy always works.
                Field(
                    "API Key", key, { key = it }, keyboard = KeyboardType.Uri,
                    visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示", color = Sub) }
                    }
                )
            }
            Field("模型名", model, { model = it }, keyboard = KeyboardType.Uri)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val eu = prov?.baseUrl ?: baseUrl; val ek = prov?.apiKey ?: key; val eh = prov?.allowInsecureHttp ?: allowInsecureHttp
                    modelMsg = "拉取中…"
                    scope.launch {
                        try {
                            models = ModelsApi.list(eu, ek, eh)
                            modelMsg = "共 ${models.size} 个模型,点选即可填入"
                        } catch (e: Exception) {
                            modelMsg = "拉取失败:${e.message}"
                        }
                    }
                }) { Text("从服务商拉取模型列表", color = Fg, fontSize = 13.sp) }
                if (modelMsg.isNotBlank()) Text(modelMsg, color = Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            if (models.isNotEmpty()) {
                SearchableModelList(
                    models = models,
                    selectedModel = model,
                    onPick = { model = it },
                    inlineMaxHeight = 240.dp
                )
            }
            ModelCapabilitiesSection(
                botId = old?.id ?: "",
                multimodal = multimodal,
                onMultimodalChange = { multimodal = it },
                reasoningEffort = reasoningEffort,
                onReasoningChange = { reasoningEffort = it }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Shell 命令先经我批准", color = Fg, fontSize = 14.sp)
                    Text("开启后,它每次想执行命令都会先在对话里发一张批准卡,你点执行才会真跑(安全策略拦截仍然先生效)", color = Sub, fontSize = 11.sp)
                }
                LiquidSwitch(checked = shellAppr, onCheckedChange = { shellAppr = it })
            }
            Text("技能包", color = Fg, fontSize = 14.sp)
            SkillsPicker(store, skillSel)
            store.persistenceError.value?.let {
                Text(it, color = BeeDanger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("保存", modifier = Modifier.fillMaxWidth()) {
                if (name.isNotBlank()) {
                    val id = old?.id ?: UUID.randomUUID().toString().take(8)
                    val provToSave = store.provider(providerId)
                    val saved = if (provToSave != null) {
                        store.saveBot(Bot(id, name.trim(), prompt, provToSave.baseUrl, "", model.trim(), isManager, provToSave.allowInsecureHttp, provToSave.id, skillSel.toList(), shellAppr))
                    } else {
                        store.saveBot(Bot(id, name.trim(), prompt, baseUrl.trim(), key.trim(), model.trim(), isManager, allowInsecureHttp, "", skillSel.toList(), shellAppr))
                    }
                    if (saved) {
                        ModelPrefs.setMultimodal(ctxBot, id, multimodal)
                        ModelPrefs.setReasoningEffort(ctxBot, id, reasoningEffort)
                        go(Screen.Chat("bot:$id"))
                    }
                }
            }
            if (old != null) PillButton("复制这个 Bot(不含对话)", filled = false, modifier = Modifier.fillMaxWidth()) {
                val copy = store.duplicateBot(old.id)
                if (copy != null) go(Screen.EditBot(copy.id))
            }
            if (old != null) PillButton("删除这个 Bot", filled = false, modifier = Modifier.fillMaxWidth()) {
                store.deleteBot(old.id); go(Screen.Home)
            }
        }
    }
}

@Composable
fun NewGroupScreen(store: Store, go: (Screen) -> Unit) {
    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).pressBounce { go(Screen.Home) }.harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card), contentAlignment = Alignment.Center) { Icon(Ic.Back, null, tint = Fg, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Text("新建群聊", style = HiveSerif.SheetTitle())
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Field("群名", name, { name = it })
            Spacer(Modifier.height(12.dp))
            Text("选择成员", color = Sub, fontSize = 13.sp)
            if (store.bots.isEmpty()) Text("还没有 Bot,先去新建几个", color = Sub, modifier = Modifier.padding(top = 12.dp))
            store.bots.forEach { b ->
                Row(
                    Modifier.fillMaxWidth().clickable { if (b.id in picked) picked.remove(b.id) else picked.add(b.id) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar("bot:${b.id}", 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(b.name, color = Fg, modifier = Modifier.weight(1f))
                    Checkbox(checked = b.id in picked, onCheckedChange = { if (it) picked.add(b.id) else picked.remove(b.id) })
                }
            }
        }
        PillButton("创建", modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            if (name.isNotBlank() && picked.size >= 2) {
                val g = Group(UUID.randomUUID().toString().take(8), name.trim(), picked.toList())
                store.addGroup(g); go(Screen.Chat("group:${g.id}"))
            }
        }
    }
}


/**
 * Architectural studio light canvas drawn inside AppBackdrop's captureLayer.
 * Provides crisp neutral titanium softbox pools + subtle ThemeAccent rim glow +
 * fine geometric light arcs so every floating Liquid Glass lens has real optical
 * luminance contrast to bend, magnify, and prism-split (zero cliché purple/pink AI gradient).
 */
@Composable
fun StudioOpticalBackdrop(modifier: Modifier = Modifier) {
    val accent = ThemeAccent
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Top-left cool titanium studio softbox pool
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFFE8EEF8).copy(alpha = 0.14f),
                0.55f to Color(0xFFB8C4D8).copy(alpha = 0.045f),
                1f to Color.Transparent,
                center = Offset(w * 0.16f, h * 0.10f),
                radius = w * 0.78f
            ),
            radius = w * 0.78f,
            center = Offset(w * 0.16f, h * 0.10f)
        )
        // Bottom-right signature ThemeAccent ambient studio bounce
        drawCircle(
            brush = Brush.radialGradient(
                0f to accent.copy(alpha = 0.15f),
                0.55f to accent.copy(alpha = 0.045f),
                1f to Color.Transparent,
                center = Offset(w * 0.86f, h * 0.86f),
                radius = w * 0.72f
            ),
            radius = w * 0.72f,
            center = Offset(w * 0.86f, h * 0.86f)
        )
        // Crisp architectural orbital rings for subtle optical lensing reference lines
        val ringCol = Color.White.copy(alpha = 0.045f)
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        drawCircle(color = ringCol, radius = w * 0.42f, center = Offset(w * 0.5f, h * 0.44f), style = stroke)
        drawCircle(color = ringCol, radius = w * 0.66f, center = Offset(w * 0.5f, h * 0.44f), style = stroke)
    }
}
