package app.opengrok

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import app.opengrok.ThemeAccent
import java.util.Calendar

/** Opens the app-wide history / work-status drawer (provided by [App]). */
val LocalOpenDrawer = staticCompositionLocalOf<() -> Unit> { {} }

private val ACTIVE_STAGES = setOf(Stage.PLANNING, Stage.AWAIT_CONFIRM, Stage.EXECUTING, Stage.REVIEWING, Stage.REPORTING)

// ---------------------------------------------------------------- UI-only persisted state

/** Remembers when each chat was last opened (drawer ordering) and the last Bot-mode chat (mode switch target). */
object UiState {
    private var prefs: SharedPreferences? = null
    val opened = mutableStateMapOf<String, Long>()
    var lastBotChat: String? = null
        private set

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("ui_state", Context.MODE_PRIVATE)
        prefs = p
        lastBotChat = p.getString("last_bot_chat", null)
        try {
            val o = JSONObject(p.getString("opened", "{}") ?: "{}")
            for (k in o.keys()) opened[k] = o.optLong(k)
        } catch (_: Exception) {
        }
    }

    fun touch(id: String) {
        opened[id] = System.currentTimeMillis()
        if (opened.size > 300) {
            val drop = opened.entries.sortedBy { it.value }.take(opened.size - 300).map { it.key }
            for (k in drop) opened.remove(k)
        }
        if (id.startsWith("bot:") || id.startsWith("group:")) lastBotChat = id
        prefs?.edit()?.putString("opened", JSONObject(opened.toMap()).toString())?.putString("last_bot_chat", lastBotChat)?.apply()
    }
}

/** Where the "Bot" capsule leads: the most recently opened Bot/group chat, else the first one, else Bot creation. */
fun resolveBotTarget(store: Store): Screen {
    val chats = store.chatList().filter { it.startsWith("bot:") || it.startsWith("group:") }
    val last = UiState.lastBotChat
    return when {
        last != null && last in chats -> Screen.Chat(last)
        chats.isNotEmpty() -> Screen.Chat(chats.first())
        else -> Screen.EditBot(null)
    }
}

// ---------------------------------------------------------------- greetings

private fun hello(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 5 -> "夜深了"
        h < 11 -> "早上好"
        h < 14 -> "中午好"
        h < 18 -> "下午好"
        else -> "晚上好"
    }
}

fun timeAgo(ms: Long): String {
    if (ms <= 0) return ""
    val d = System.currentTimeMillis() - ms
    return when {
        d < 60_000L -> "刚刚"
        d < 3_600_000L -> "${d / 60_000L} 分钟前"
        d < 86_400_000L -> "${d / 3_600_000L} 小时前"
        d < 7 * 86_400_000L -> "${d / 86_400_000L} 天前"
        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}

// ---------------------------------------------------------------- history model

data class ChatEntry(
    val key: String,
    val title: String,
    val preview: String,
    val tag: String,
    val seed: String,
    val group: Boolean,
    val busy: Boolean,
    val time: Long,
    val screen: Screen,
    val sessionId: String? = null,
    val pinned: Boolean = false
)

private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim().take(80)

/** All conversations (Bots, groups, private threads, the live Agent chat and archived Agent chats), newest first; pinned ones first. */
fun buildEntries(store: Store): List<ChatEntry> {
    val out = mutableListOf<ChatEntry>()
    for (id in store.chatList()) {
        val last = store.chat(id).lastOrNull { it.text.isNotBlank() }?.text ?: store.subtitle(id)
        val tag = when {
            id.startsWith("bot:") -> "Bot"
            id.startsWith("group:") -> "群聊"
            else -> "私聊"
        }
        out.add(ChatEntry(id, store.title(id), oneLine(last), tag, id, !id.startsWith("bot:"),
            id in store.busy, UiState.opened[id] ?: 0L, Screen.Chat(id), pinned = id in store.pinned))
    }
    if (store.agent.value != null) {
        val last = store.chat(AGENT_ID).lastOrNull { it.text.isNotBlank() }?.text ?: "设备 Agent"
        out.add(ChatEntry(AGENT_ID, store.title(AGENT_ID), oneLine(last), "Agent", AGENT_ID, false,
            AGENT_ID in store.busy, UiState.opened[AGENT_ID] ?: 0L, Screen.AgentChat, pinned = AGENT_ID in store.pinned))
    }
    for (s in store.agentSessions) {
        val last = store.chat("agentarc:${s.id}").lastOrNull { it.text.isNotBlank() }?.text ?: ""
        out.add(ChatEntry("agentarc:${s.id}", s.title, oneLine(last), "Agent", "agentarc:${s.id}", false,
            false, s.time, Screen.AgentChat, s.id, pinned = false))
    }
    // Pinned entries float to the top (still newest-first within each group).
    return out.sortedWith(compareByDescending<ChatEntry> { it.pinned }.thenByDescending { it.time })
}

private fun openEntry(store: Store, e: ChatEntry, go: (Screen) -> Unit) {
    if (e.sessionId != null) store.openAgentSession(e.sessionId)
    go(e.screen)
}

// ---------------------------------------------------------------- shared pieces

/** Shared small desktop-companion mark used in empty states and the drawer. */
@Composable
fun BrandOrb(size: Dp) {
    Box(Modifier.size(size * 2.25f), contentAlignment = Alignment.Center) {
        PixelBeePet(size = size * 1.55f, mood = BeeMood.Cheerful)
    }
}

/** Top bar shared by every main screen: menu left · mode capsule centre · settings gear right. */
@Composable
fun AppTopBar(store: Store, current: String, go: (Screen) -> Unit) {
    val open = LocalOpenDrawer.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp)) {
            IconButton(onClick = open, modifier = Modifier.fillMaxSize()) { Icon(Ic.Menu, "菜单", tint = Fg) }
            if (store.busy.isNotEmpty()) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(8.dp).clip(CircleShape).background(BeeGreen))
            }
        }
        if (current.isNotBlank()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ModeCapsule(current, go) }
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = { go(Screen.Settings) }, modifier = Modifier.size(44.dp)) { Icon(Ic.Gear, "设置", tint = Fg) }
    }
}

/** Conversation header under the top bar: serif title centred, actions right (STUDY §2.4). */
@Composable
fun ChatSubHeader(seed: String, group: Boolean, title: String, subtitle: String, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // Bot (project) name carries the serif — the reference app sets project names in serif.
            Text(title, style = HiveSerif.Title(17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** Small rounded text button for chat headers. */
@Composable
fun HeaderPill(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(Card).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (enabled) Fg else Sub, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = if (enabled) Fg else Sub, fontSize = 13.sp)
    }
}

/** Shown in the middle of an empty conversation: bee mark, serif title, grey hint (STUDY §2.3). */
@Composable
fun EmptyChatHint(title: String, hint: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BrandOrb(26.dp)
        Spacer(Modifier.height(10.dp))
        Text(title, style = HiveSerif.Title(22.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(hint, color = Sub, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
    }
}

// ---------------------------------------------------------------- home

/**
 * Home in the Claude style (STUDY §2.1): a quiet page — hamburger top-left, top-center bee mark,
 * serif time-of-day greeting, one status pill underneath, and the floating glass composer capsule
 * at the bottom. No list on the home page; recents live in the drawer.
 */
@Composable
fun HomeScreen(store: Store, go: (Screen) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val openDrawer = LocalOpenDrawer.current
    val working = store.busy.size + store.projects.count { it.stage in ACTIVE_STAGES }
    var modelSheet by remember { mutableStateOf(false) }
    var modelBot by remember { mutableStateOf<Bot?>(null) }
    var input by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Top bar: drawer toggle left. (The reference app's ghost icon has no counterpart
            // in this app; nothing here would be honest to invent, so the right side stays empty.)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp)) {
                    IconButton(onClick = openDrawer, modifier = Modifier.fillMaxSize()) { Icon(Ic.Menu, "菜单", tint = Fg) }
                    if (working > 0) {
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(8.dp).clip(CircleShape).background(ThemeAccent))
                    }
                }
                Spacer(Modifier.weight(1f))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(0.42f))
                // Brand: the pixel bee (work order 品牌标记) in place of the Claude starburst.
                AnimatedVisibility(shown, enter = fadeIn(tween(700))) { BrandOrb(40.dp) }
                Spacer(Modifier.height(18.dp))
                // Serif time-of-day greeting (the Claude signature moment).
                AnimatedVisibility(shown, enter = fadeIn(tween(700)) + slideInVertically(tween(700)) { it / 10 }) {
                    Text(hello(), style = HiveSerif.Title(30.sp), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(14.dp))
                // One status pill: current default Bot / model; tapping opens the model sheet.
                AnimatedVisibility(shown, enter = fadeIn(tween(500, delayMillis = 150))) {
                    HomeStatusPill(store) { b -> modelBot = b; modelSheet = true }
                }
                Spacer(Modifier.weight(0.58f))
            }
            // Floating composer capsule at the bottom. Typing here routes the draft into the
            // default Bot chat (this app's home has no conversation of its own).
            HomeComposer(
                value = input, onValueChange = { input = it },
                onSend = {
                    val target = resolveBotTarget(store)
                    if (target is Screen.Chat) {
                        store.send(target.id, input.trim(), emptyList())
                        input = ""
                        go(target)
                    }
                },
                focusRequester = focus
            )
        }
        ModelSheet(
            bot = modelBot, store = store, visible = modelSheet,
            onDismiss = { modelSheet = false },
            onPick = { m -> modelBot?.let { b -> store.saveBot(b.copy(model = m)) } }
        )
    }
}

/** The single pill under the greeting: status + current default Bot name (STUDY §2.1). */
@Composable
private fun HomeStatusPill(store: Store, onPickBot: (Bot?) -> Unit) {
    val working = store.busy.size + store.projects.count { it.stage in ACTIVE_STAGES }
    val defaultBot = store.bots.firstOrNull { "bot:${it.id}" == UiState.lastBotChat } ?: store.bots.firstOrNull()
    Row(
        Modifier.clip(RoundedCornerShape(UiTokens.RadiusChip)).background(PILL)
            .clickable { onPickBot(defaultBot) }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (working > 0) BeeGreen else ThemeAccent))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                working > 0 -> "$working 项任务进行中"
                defaultBot != null -> "${defaultBot.name} · ${defaultBot.model}"
                else -> "新建一只小蜂，开始工作"
            },
            color = Fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (defaultBot != null) {
            Spacer(Modifier.width(2.dp))
            Icon(Ic.Chevron, null, tint = Sub, modifier = Modifier.size(15.dp))
        }
    }
}

/** Home-bottom glass composer capsule (STUDY §2.1): "+" round button, hint, send round button. */
@Composable
private fun HomeComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    focusRequester: FocusRequester
) {
    val tier = GlassStyle.tier.value
    val density = LocalDensity.current
    val radiusPx = with(density) { 28.dp.toPx() }
    val keyboard = WindowInsets.ime.getBottom(density) > 0
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).navigationBarsPadding()
            .clip(RoundedCornerShape(28.dp))
            .background(if (tier.glassLayers) Card.copy(alpha = 0.88f) else Card)
            .glassSurface(tier = tier, radiusPx = radiusPx, tint = SheetGlassTint)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF313131))
                .clickable { if (keyboard) onSend() else focusRequester.requestFocus() },
            contentAlignment = Alignment.Center
        ) { Icon(Ic.Add, null, tint = Fg, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester).padding(vertical = 8.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = Fg, fontSize = 16.sp),
            cursorBrush = SolidColor(ThemeAccent),
            maxLines = 1,
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text("说点什么，交给小蜂团队…", color = Sub, fontSize = 16.sp); inner() }
            }
        )
        Spacer(Modifier.width(10.dp))
        SendButton(enabled = value.isNotBlank(), onClick = onSend)
    }
}

// ---------------------------------------------------------------- drawer


@Composable
fun AppDrawer(store: Store, visible: Boolean, current: Screen, onClose: () -> Unit, go: (Screen) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(GlassMotion.drawer())), exit = fadeOut(tween(GlassMotion.drawer()))) {
            Box(
                Modifier.fillMaxSize().background(Ink.copy(alpha = 0.62f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInHorizontally(tween(GlassMotion.drawer(), easing = FastOutSlowInEasing)) { -it } +
                fadeIn(tween(GlassMotion.screenFade())),
            exit = slideOutHorizontally(tween(GlassMotion.drawer(), easing = FastOutSlowInEasing)) { -it } +
                fadeOut(tween(GlassMotion.screenFade()))
        ) {
            DrawerPanel(store, current, onClose, go)
        }
    }
}

@Composable
private fun DrawerPanel(store: Store, current: Screen, onClose: () -> Unit, go: (Screen) -> Unit) {
    var deleteId by remember { mutableStateOf<String?>(null) }
    val entries = buildEntries(store).filter { it.time > 0L }
    Column(
        Modifier.fillMaxHeight().fillMaxWidth(0.88f).widthIn(max = 360.dp)
            .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)).background(BeePanel)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .systemBarsPadding().imePadding()
    ) {
        Spacer(Modifier.height(18.dp))
        // Serif wordmark (STUDY §2.2).
        Text(
            "BotHive",
            style = HiveSerif.Title(30.sp), color = Fg,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(16.dp))
        // Nav rows: only destinations that actually exist in this app (honest copy).
        DrawerNav("会话", Ic.ChatBubbles) { go(Screen.BotMode) }
        if (store.agent.value != null) DrawerNav("设备 Agent", Ic.Phone) { go(Screen.AgentChat) }
        DrawerNav("员工 Bots", Ic.Group) { go(Screen.BotMode) }
        DrawerNav("工作流", Ic.Tree) { go(Screen.Workflows) }
        DrawerNav("搜索", Ic.Search) { go(Screen.Search) }
        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 12.dp))
        // Recents: single-line rows with a line icon, one ellipsised line (STUDY §2.2).
        Text(
            "最近", color = Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
        )
        LazyColumn(Modifier.weight(1f)) {
            // Running-work rows + stop-all, restored during landing verification (the reskin
            // had reduced the old "工作情况" section to just the approvals banner below).
            item { DrawerWorkStatus(store, go) }
            // Pending approvals banner.
            val ap = store.pendingApprovals.firstOrNull()
            if (ap != null) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(12.dp)).background(Color(0xFF2E2E2C))
                            .clickable { go(if (ap.chatId == AGENT_ID) Screen.AgentChat else Screen.Chat(ap.chatId)) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Ic.Terminal, null, tint = ThemeAccent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("${store.pendingApprovals.size} 条命令待批准", color = Fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Ic.Chevron, null, tint = Sub, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (entries.isEmpty()) {
                item {
                    Text("还没有对话，先新建一个 Bot 或开始 Agent 对话",
                        color = Sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                }
            }
            items(entries, key = { it.key }) { e ->
                DrawerRow(e, selected = e.sessionId == null && current == e.screen,
                    onClick = { openEntry(store, e, go) },
                    onDelete = if (e.sessionId != null) ({ deleteId = e.sessionId }) else null,
                    onPin = { if (e.sessionId == null) store.togglePin(e.key) })
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        // Bottom row: settings entry on the left, white "+ 新建" pill on the right (STUDY §2.2).
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(ThemeAccent), contentAlignment = Alignment.Center) {
                Text("B", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text("设置", color = Fg, fontSize = 15.sp, modifier = Modifier.weight(1f).clickable { go(Screen.Settings) })
            NewChatPill("新建") {
                onClose()
                go(Screen.BotMode)
            }
        }
    }
    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("删除这段 Agent 对话？") },
            text = { Text("这段对话记录将被永久删除。") },
            confirmButton = {
                TextButton(onClick = { store.deleteAgentSession(deleteId ?: ""); deleteId = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } }
        )
    }
}

/** One drawer navigation line: line-drawn icon + label, generous row height (STUDY §2.2). */
@Composable
private fun DrawerNav(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Sub, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = Fg, fontSize = 16.sp)
    }
}

/**
 * Running-work rows for the drawer (busy chats + active projects) with the stop-all action —
 * the data the old "工作情况" section carried, restyled as quiet rows. Renders nothing when idle.
 */
@Composable
private fun DrawerWorkStatus(store: Store, go: (Screen) -> Unit) {
    val rows = mutableListOf<Triple<String, String, Screen>>()
    for (id in store.busy.toList().distinct()) {
        if (id == AGENT_ID) rows.add(Triple(store.title(AGENT_ID), "Agent 正在工作", Screen.AgentChat))
        else if (id.startsWith("bot:") || id.startsWith("group:")) rows.add(Triple(store.title(id), "正在回复", Screen.Chat(id)))
    }
    for (p in store.projects.filter { it.stage in ACTIVE_STAGES }) {
        rows.add(Triple("项目 · ${p.title}", stageLabel(p.stage), Screen.Chat("bot:${p.managerId}")))
    }
    if (rows.isEmpty()) return
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (store.busy.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "全部停止", color = BeeDanger, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { store.stopAll() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        for ((title, sub, screen) in rows) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { go(screen) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(BeeGreen))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, color = Sub, fontSize = 12.sp)
                }
                Icon(Ic.Chevron, null, tint = Sub, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** One recent row in the drawer: line icon by conversation type, single ellipsised line (STUDY §2.2). */
@Composable
private fun DrawerRow(e: ChatEntry, selected: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)?, onPin: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF2E2E2C) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when {
                e.key.startsWith("group:") -> Ic.Group
                e.key.startsWith("bot:") -> Ic.Tray
                else -> Ic.ChatBubbles
            },
            null, tint = Sub, modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        if (e.pinned) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(ThemeAccent))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            e.title, color = Fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (e.busy) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(7.dp).clip(CircleShape).background(BeeGreen))
        }
        if (onPin != null) {
            // Pin toggle restored during landing verification (the reskin had dropped it,
            // leaving Store.togglePin unreachable from the UI).
            Box(Modifier.size(28.dp).clickable { onPin() }, contentAlignment = Alignment.Center) {
                Text(if (e.pinned) "已置顶" else "置顶", color = if (e.pinned) ThemeAccent else Sub, fontSize = 10.sp)
            }
        }
        if (onDelete != null) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(28.dp).clickable { onDelete() }, contentAlignment = Alignment.Center) {
                Icon(Ic.Close, "删除", tint = Sub, modifier = Modifier.size(14.dp))
            }
        }
    }
}
