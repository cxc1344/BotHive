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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.util.UUID

// BotHive 0.5 · Pixel Bee visual system
val Bg = Color(0xFF171207)
val Card = Color(0xFF28200E)
val Line = Color(0xFF4A3C1C)
val Fg = Color(0xFFFFF4D2)
val Sub = Color(0xFFD5BE83)
val Honey = Color(0xFFFFC53D)
val HoneyDeep = Color(0xFFFFA62B)
val HoneyPale = Color(0xFFFFE6A1)
val Ink = Color(0xFF211806)
val BeeGreen = Color(0xFF9DDC77)
val BeeDanger = Color(0xFFFF8376)
val BeePanel = Color(0xFF211A0B)
val BeePanelRaised = Color(0xFF322711)

sealed interface Screen {
    data object Home : Screen
    /** Resolved by App to the most recently opened Bot/group chat. */
    data object BotMode : Screen
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
}

@Composable
fun App(store: Store) {
    val ctx = LocalContext.current
    remember { UiState.init(ctx) }
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
    BackHandler(!drawer && screen != Screen.Home) { screen = Screen.Home }
    LaunchedEffect(screen) {
        when (val s = screen) {
            is Screen.Chat -> UiState.touch(s.id)
            Screen.AgentChat -> UiState.touch(AGENT_ID)
            else -> {}
        }
    }
    MaterialTheme(
        // LaTeX-family typography (Latin Modern) with the system family as CJK fallback.
        typography = hiveTypography(),
        colorScheme = darkColorScheme(
            primary = Honey, onPrimary = Ink,
            primaryContainer = HoneyDeep, onPrimaryContainer = Ink,
            secondary = BeeGreen, onSecondary = Ink,
            background = Bg, surface = Bg, onSurface = Fg,
            surfaceVariant = Card, onSurfaceVariant = Sub, outline = Line,
            error = BeeDanger
        )
    ) {
        CompositionLocalProvider(
            LocalOpenDrawer provides { drawer = true },
            // Every Text that does not set its own fontFamily inherits the LaTeX family here, so the
            // ~230 call sites do not each need editing (and cannot drift apart later).
            androidx.compose.material3.LocalTextStyle provides
                androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = HiveFonts.Roman)
        ) {
            Box(Modifier.fillMaxSize().background(Bg)) {
                // One shared honeycomb wash behind every screen keeps the theme continuous while
                // individual screens stay free to use their own surfaces on top of it.
                HoneycombBackdrop(Modifier.fillMaxSize(), alpha = .35f)
                Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    ScreenHost(screen, store, nav)
                }
                AppDrawer(store, drawer, screen, { drawer = false }, nav)
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
    // Depth decides direction: 0 = home, 1 = main destinations, 2 = editors/detail.
    val depth = remember(screen) { screenDepth(screen) }
    val previousDepth = remember { mutableStateOf(depth) }
    val forward = depth >= previousDepth.value
    LaunchedEffect(depth) { previousDepth.value = depth }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val dir = if (forward) 1 else -1
            val enter = slideInHorizontally(
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                initialOffsetX = { full -> dir * (full / 12) }
            ) + fadeIn(tween(220))
            val exit = slideOutHorizontally(
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                targetOffsetX = { full -> -dir * (full / 16) }
            ) + fadeOut(tween(180))
            enter togetherWith exit
        },
        label = "screen"
    ) { s ->
        when (s) {
            Screen.Home -> HomeScreen(store, nav)
            Screen.BotMode -> {}
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
        }
    }
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
}

@Composable
fun Avatar(seed: String, size: Dp, group: Boolean = false) {
    // v0.5 replaces the generic face with a distinct hive companion for every Bot.
    PixelBeeAvatar(seed = seed, size = size, group = group)
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
    var projectMode by remember { mutableStateOf(true) }
    val isManager = isBot && store.bot(id.removePrefix("bot:"))?.isManager == true
    val listState = rememberLazyListState()
    val last = msgs.lastOrNull()
    // Smart auto-scroll (same policy as the Agent chat): only when already near the bottom.
    var atBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) atBottom = listState.isNearBottom()
            }
    }
    LaunchedEffect(msgs.size, last?.tools?.size, last?.text) {
        if (msgs.isNotEmpty() && atBottom) listState.animateScrollToItem(msgs.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(store, "bot", go)
        ChatSubHeader(id, !isBot, store.title(id), store.subtitle(id)) {
            ExportButton(store, id)
            if (isBot) {
                IconButton(onClick = { confirm = true }) { Icon(Ic.Refresh, null, tint = Fg) }
                IconButton(onClick = { go(Screen.EditBot(id.removePrefix("bot:"))) }) { Icon(Ic.Edit, null, tint = Fg) }
            }
        }
        HorizontalDivider(color = Line)
        ApprovalCards(store, id)
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(msgs, key = { it.id }) { m ->
                    // Each message eases in once; existing rows are untouched when new ones arrive.
                    var appeared by remember(m.id) { mutableStateOf(false) }
                    LaunchedEffect(m.id) { appeared = true }
                    RowEnter(appeared) {
                        MsgView(store, m, showName = !isBot, thinking = busy && m.id == msgs.last().id, go = go, chatId = id)
                    }
                }
            }
            if (msgs.isEmpty()) EmptyChatHint("开始对话", "在下方输入消息，Bot 会在自己的沙盒里干活；点工具调用可查看每一步。")
        }
        if (!readOnly && isManager) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (projectMode) Honey else Card)
                        .clickable { projectMode = !projectMode }.padding(horizontal = 14.dp, vertical = 6.dp)
                ) { Text(if (projectMode) "立项模式:开" else "立项模式:关(普通对话)", color = if (projectMode) Ink else Sub, fontSize = 13.sp) }
            }
        }
        if (!readOnly) {
            AttachChips(staged) { staged.remove(it) }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { pickFiles() }, modifier = Modifier.size(48.dp)) {
                    Icon(Ic.Attach, null, tint = Fg)
                }
                TextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f), placeholder = { Text(if (isManager && projectMode) "下达任务,总经理先出分工方案…" else "发消息…") },
                    shape = RoundedCornerShape(26.dp), maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Card, unfocusedContainerColor = Card,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Fg, unfocusedTextColor = Fg
                    )
                )
                Spacer(Modifier.width(8.dp))
                if (busy) {
                    StopButton { store.stopChat(id) }
                } else {
                    val can = input.isNotBlank() || staged.isNotEmpty()
                    SendButton(can) {
                        if (isManager && projectMode && staged.isEmpty()) store.startProject(id, input.trim())
                        else store.send(id, input.trim(), staged.toList())
                        input = ""
                        staged.clear()
                    }
                }
            }
        }
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
    when {
        m.role == "project" -> ProjectCard(store, m.text, go)
        m.role == "system" -> Text(m.text, color = Sub, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        m.from == null -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            var askResend by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!thinking) {
                    Text(
                        "重发", color = Sub, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { askResend = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Surface(
                    color = HoneyDeep, shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                    modifier = Modifier.widthIn(max = 300.dp)
                ) {
                    Column(Modifier.padding(14.dp, 10.dp)) {
                        if (m.images.isNotEmpty()) {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
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
                        if (m.text.isNotBlank()) SelectionContainer { Text(m.text, color = Fg, fontSize = 16.sp) }
                    }
                }
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
            if (showName) Text(store.botName(m.from), color = Sub, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            if (m.tools.isNotEmpty()) ToolsPanel(m.tools, m.id, thinking, store, chatId, m.from)
            if (m.text.isNotBlank()) {
                // Assistant replies are Markdown: headings, lists, code, tables all render as such.
                SelectionContainer { MarkdownText(m.text) }
            } else if (thinking) ThinkingDots()
        }
    }
}

/** Expanded/collapsed state of each tool row, keyed by message id + position + tool id so it survives list recycling. */
private val toolOpen = mutableStateMapOf<String, Boolean>()

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

/** Short badge for calls that did not succeed (blocked by the safety policy, or non-zero exit). */
private fun toolBadge(t: ToolCall): String? {
    if (t.output.startsWith("【安全策略已拦截】")) return "已拦截"
    val m = EXIT_RE.findAll(t.output).lastOrNull()
    if (m != null && m.groupValues[1] != "0") return "exit " + m.groupValues[1]
    return null
}

/** Every tool call is its own collapsible row: one-line summary, tap to expand full args and output. */
@Composable
fun ToolsPanel(
    tools: List<ToolCall>, scopeKey: String, live: Boolean,
    store: Store? = null, chatId: String = "", botId: String? = null
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(Card)) {
        tools.forEachIndexed { i, t ->
            if (i > 0) HorizontalDivider(color = Line)
            val running = live && i == tools.lastIndex && t.output.isBlank()
            ToolRow(t, "$scopeKey#$i:${t.id}", running, if (store != null && !running) ToolActions(store, chatId, botId) else null)
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

@Composable
private fun ToolRow(t: ToolCall, key: String, running: Boolean, actions: ToolActions? = null) {
    val open = toolOpen[key] ?: running
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "rot")
    val badge = toolBadge(t)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { toolOpen[key] = !open }.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (running) CircularProgressIndicator(Modifier.size(14.dp), color = Sub, strokeWidth = 2.dp)
            else Icon(Ic.Terminal, null, tint = Sub, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(t.name, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(
                toolSummary(t.args), color = Sub, fontSize = 12.sp, fontFamily = HiveFonts.Mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                Text(badge, color = BeeDanger, fontSize = 11.sp)
            }
            Spacer(Modifier.width(4.dp))
            Icon(Ic.Down, null, tint = Sub, modifier = Modifier.size(18.dp).rotate(rot))
        }
        AnimatedVisibility(open) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
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
                    Text("参数", color = Sub, fontSize = 11.sp)
                    Text(
                        prettyArgs(t.args), color = Fg, fontSize = 12.sp, fontFamily = HiveFonts.Mono,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )
                    Text("输出", color = Sub, fontSize = 11.sp)
                    Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(top = 2.dp)) {
                        val empty = t.output.isBlank()
                        Text(
                            if (empty) (if (running) "运行中…" else "(无输出)") else t.output,
                            color = if (empty) Sub else Fg, fontSize = 12.sp, fontFamily = HiveFonts.Mono,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                    if (actions != null) ToolActionRow(t, actions)
                }
            }
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
        SmallChip("复制" + if (t.name == "shell") "命令" else "参数") {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("tool", copyText(t)))
            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
        }
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
        Modifier.clip(RoundedCornerShape(14.dp)).background(Line).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(text, color = Fg, fontSize = 12.sp) }
}

/** Three-dot loading indicator shown while a Bot is preparing its reply. */
@Composable
fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "phase"
    )
    Row(
        Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0..2) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (phase.toInt() == i) Fg else Line))
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
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = singleLine, minLines = minLines, visualTransformation = visual,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        trailingIcon = trailing,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Fg, unfocusedTextColor = Fg,
            focusedBorderColor = Honey, unfocusedBorderColor = Line,
            focusedLabelColor = Honey, unfocusedLabelColor = Sub, cursorColor = Honey
        )
    )
}

@Composable
fun PillButton(text: String, filled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(26.dp))
            .background(if (filled) Honey else Card)
            .clickable(onClick = onClick).padding(vertical = 14.dp),
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
    var shellAppr by remember { mutableStateOf(old?.shellApproval ?: false) }
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
            IconButton(onClick = { back() }) { Icon(Ic.Back, null, tint = Fg) }
            Text(if (old == null) "新建 Bot" else "编辑 Bot", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Field("名字", name, { name = it })
            Text("预设", color = Sub, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { p ->
                    val sel = preset == p.name
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) Honey else Card)
                            .clickable {
                                preset = p.name; prompt = p.prompt; isManager = p.manager
                                if (name.isBlank() && p.prompt.isNotEmpty()) name = p.name
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text(p.name, color = if (sel) Ink else Fg, fontSize = 14.sp) }
                }
            }
            Field("System Prompt", prompt, { prompt = it; preset = "自定义" }, singleLine = false, minLines = 5)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("总经理", color = Fg, fontSize = 15.sp)
                    Text("可立项、派工、审查员工成果", color = Sub, fontSize = 12.sp)
                }
                Switch(checked = isManager, onCheckedChange = { isManager = it })
            }
            HorizontalDivider(color = Line)
            Text("连接(Provider 预设一次配置、处处复用;或为这个 Bot 单独填写)", color = Sub, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val selCustom = providerId.isEmpty()
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(if (selCustom) Honey else Card)
                        .clickable { providerId = "" }.padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("自定义", color = if (selCustom) Ink else Fg, fontSize = 14.sp) }
                store.providers.forEach { p ->
                    val sel = providerId == p.id
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) Honey else Card)
                            .clickable { providerId = p.id; models = emptyList(); modelMsg = "" }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text(p.name, color = if (sel) Ink else Fg, fontSize = 14.sp) }
                }
            }
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
                    Switch(checked = allowInsecureHttp, onCheckedChange = { allowInsecureHttp = it })
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    models.forEach { mid ->
                        val sel = model == mid
                        Box(
                            Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Honey else Card)
                                .clickable { model = mid }.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text(mid, color = if (sel) Ink else Fg, fontSize = 12.sp) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Shell 命令先经我批准", color = Fg, fontSize = 14.sp)
                    Text("开启后,它每次想执行命令都会先在对话里发一张批准卡,你点执行才会真跑(安全策略拦截仍然先生效)", color = Sub, fontSize = 11.sp)
                }
                Switch(checked = shellAppr, onCheckedChange = { shellAppr = it })
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
                    if (saved) go(Screen.Chat("bot:$id"))
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
            IconButton(onClick = { go(Screen.Home) }) { Icon(Ic.Back, null, tint = Fg) }
            Text("新建群聊", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
