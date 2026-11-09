package app.opengrok

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Calendar

/** Opens the app-wide history / work-status drawer (provided by [App]). */
val LocalOpenDrawer = staticCompositionLocalOf<() -> Unit> { {} }

private val ACTIVE_STAGES = setOf(Stage.PLANNING, Stage.AWAIT_CONFIRM, Stage.EXECUTING, Stage.REVIEWING, Stage.REPORTING)
private val PANEL = Color(0xFF101114)
private val CHIP = Color(0xFF26282D)
private val OK = Color(0xFF4ADE80)

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

object Greetings {
    private var first = true
    private val headlines = listOf(
        "今天要如何工作？", "想先做点什么？", "有什么任务要交给他们？", "今天从哪件事开始？",
        "准备好开工了吗？", "今天的目标是什么？", "把想法交给团队吧", "说说看，今天要做成什么？"
    )
    private val sublines = listOf(
        "派一个任务，剩下的交给 Bot 团队", "你只管拍板，执行交给他们", "手机里的 Agent 随时待命",
        "先说目标，再看他们如何分工", "沙盒随便折腾，搞坏了一键重置", "重复的事，交给例行任务",
        "一个想法，几分钟变成成果", "让 Bot 互相协作，你来做决定", "文件、屏幕、通知，Agent 帮你盯着",
        "不同的活，用不同的模型"
    )

    /** First entry after launch always asks the classic question; later entries are random. */
    fun pick(): Pair<String, String> {
        val h = if (first) headlines[0] else headlines.random()
        first = false
        return h to sublines.random()
    }
}

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
    val sessionId: String? = null
)

private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim().take(80)

/** All conversations (Bots, groups, private threads, the live Agent chat and archived Agent chats), newest first. */
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
            id in store.busy, UiState.opened[id] ?: 0L, Screen.Chat(id)))
    }
    if (store.agent.value != null) {
        val last = store.chat(AGENT_ID).lastOrNull { it.text.isNotBlank() }?.text ?: "设备 Agent"
        out.add(ChatEntry(AGENT_ID, store.title(AGENT_ID), oneLine(last), "Agent", AGENT_ID, false,
            AGENT_ID in store.busy, UiState.opened[AGENT_ID] ?: 0L, Screen.AgentChat))
    }
    for (s in store.agentSessions) {
        val last = store.chat("agentarc:${s.id}").lastOrNull { it.text.isNotBlank() }?.text ?: ""
        out.add(ChatEntry("agentarc:${s.id}", s.title, oneLine(last), "Agent", "agentarc:${s.id}", false,
            false, s.time, Screen.AgentChat, s.id))
    }
    return out.sortedByDescending { it.time }
}

private fun openEntry(store: Store, e: ChatEntry, go: (Screen) -> Unit) {
    if (e.sessionId != null) store.openAgentSession(e.sessionId)
    go(e.screen)
}

// ---------------------------------------------------------------- shared pieces

/** Soft glowing brand orb used on the home page and empty states. Occupies roughly 2.2 x [size]. */
@Composable
fun BrandOrb(size: Dp) {
    Box(Modifier.size(size * 2.2f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Color(0x557C6CFF), Color.Transparent), center = c, radius = r), radius = r, center = c)
            val o = r / 2.2f
            drawCircle(
                Brush.linearGradient(listOf(Color(0xFFA99BFF), Color(0xFF4A3FD0)),
                    start = Offset(c.x - o, c.y - o), end = Offset(c.x + o, c.y + o)),
                radius = o, center = c
            )
            val w = Color.White.copy(alpha = .95f)
            drawCircle(w, o * .11f, Offset(c.x - o * .3f, c.y - o * .1f))
            drawCircle(w, o * .11f, Offset(c.x + o * .3f, c.y - o * .1f))
            drawLine(w, Offset(c.x - o * .24f, c.y + o * .32f), Offset(c.x + o * .24f, c.y + o * .32f),
                strokeWidth = o * .1f, cap = StrokeCap.Round)
        }
    }
}

/** Top bar shared by every main screen: menu (history / work status) · mode capsule · settings gear. */
@Composable
fun AppTopBar(store: Store, current: String, go: (Screen) -> Unit) {
    val open = LocalOpenDrawer.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp)) {
            IconButton(onClick = open, modifier = Modifier.fillMaxSize()) { Icon(Ic.Menu, "菜单", tint = Fg) }
            if (store.busy.isNotEmpty()) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(8.dp).clip(CircleShape).background(OK))
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ModeCapsule(current, go) }
        IconButton(onClick = { go(Screen.Settings) }, modifier = Modifier.size(44.dp)) { Icon(Ic.Gear, "设置", tint = Fg) }
    }
}

/** Conversation header under the top bar: avatar, title, subtitle and per-chat actions. */
@Composable
fun ChatSubHeader(seed: String, group: Boolean, title: String, subtitle: String, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(seed, 36.dp, group)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** Shown in the middle of an empty conversation. */
@Composable
fun EmptyChatHint(title: String, hint: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BrandOrb(26.dp)
        Text(title, color = Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(hint, color = Sub, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
    }
}

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(store: Store, go: (Screen) -> Unit) {
    val greeting = remember { Greetings.pick() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val openDrawer = LocalOpenDrawer.current
    val recent = buildEntries(store).firstOrNull { it.time > 0L }
    val working = store.busy.size + store.projects.count { it.stage in ACTIVE_STAGES }
    val hasBot = store.bots.isNotEmpty()

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF14151A), Bg)))) {
        AppTopBar(store, "", go)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            AnimatedVisibility(shown, enter = fadeIn(tween(700)) + slideInVertically(tween(700)) { it / 10 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BrandOrb(44.dp)
                    Text(greeting.first, color = Fg, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, lineHeight = 38.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(greeting.second, color = Sub, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    StatusPill(store, working, openDrawer)
                }
            }
            Spacer(Modifier.height(28.dp))
            AnimatedVisibility(shown, enter = fadeIn(tween(900)) + slideInVertically(tween(900)) { it / 8 }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (recent != null) ContinueCard(recent) { openEntry(store, recent, go) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeTile("新建 Bot", "派一位新员工", Ic.Add, !hasBot, Modifier.weight(1f)) { go(Screen.EditBot(null)) }
                        HomeTile("Agent 新对话", "管文件 · 看屏幕", Ic.Phone, false, Modifier.weight(1f)) {
                            store.newAgentSession(); go(Screen.AgentChat)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeTile("新建群聊", "多位 Bot 协作", Ic.Group, false, Modifier.weight(1f)) { go(Screen.NewGroup) }
                        HomeTile("工作流", "编排与例行任务", Ic.Tree, false, Modifier.weight(1f)) { go(Screen.Workflows) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("点左上角 ☰ 查看全部聊天记录与工作情况", color = Sub, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusPill(store: Store, working: Int, onClick: () -> Unit) {
    val text = buildString {
        append(hello()); append(" · ")
        when {
            working > 0 -> append("$working 项任务进行中")
            store.bots.isNotEmpty() -> {
                append("${store.bots.size} 位 Bot 待命")
                if (store.agent.value != null) append("，Agent 空闲")
            }
            else -> append("还没有 Bot，从新建开始")
        }
    }
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(Card).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (working > 0) OK else Sub))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Fg, fontSize = 13.sp)
        Spacer(Modifier.width(2.dp))
        Icon(Ic.Chevron, null, tint = Sub, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ContinueCard(e: ChatEntry, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Card).border(1.dp, Line, shape).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(e.seed, 44.dp, e.group)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("继续上次对话 · ${timeAgo(e.time)}", color = Sub, fontSize = 11.sp)
            Text(e.title, color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Text(e.preview.ifBlank { "暂无消息" }, color = Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Ic.Chevron, null, tint = Sub)
    }
}

@Composable
private fun HomeTile(title: String, sub: String, icon: ImageVector, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.clip(shape).background(if (primary) Color.White else Card)
            .border(1.dp, if (primary) Color.White else Line, shape).clickable(onClick = onClick).padding(16.dp)
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(if (primary) Color(0xFFE6E6EA) else CHIP), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (primary) Color.Black else Fg, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = if (primary) Color.Black else Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = if (primary) Color(0xFF55565C) else Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

// ---------------------------------------------------------------- drawer

private class WorkRow(val title: String, val sub: String, val running: Boolean, val screen: Screen)

@Composable
fun AppDrawer(store: Store, visible: Boolean, current: Screen, onClose: () -> Unit, go: (Screen) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInHorizontally(tween(260)) { -it },
            exit = slideOutHorizontally(tween(220)) { -it }
        ) {
            DrawerPanel(store, current, onClose, go)
        }
    }
}

@Composable
private fun DrawerPanel(store: Store, current: Screen, onClose: () -> Unit, go: (Screen) -> Unit) {
    var query by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val entries = buildEntries(store)
    val shown = if (query.isBlank()) entries else entries.filter { it.title.contains(query, true) || it.preview.contains(query, true) }
    Column(
        Modifier.fillMaxHeight().fillMaxWidth(0.86f).widthIn(max = 340.dp)
            .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)).background(PANEL)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .systemBarsPadding().imePadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { BrandOrb(10.dp) }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("OpenGrokBot", color = Fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("本地多 Bot · 设备 Agent", color = Sub, fontSize = 12.sp)
            }
            IconButton(onClick = onClose) { Icon(Ic.Close, "关闭", tint = Sub) }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DrawerChip("Agent 新对话", Ic.Edit, true) { store.newAgentSession(); go(Screen.AgentChat) }
            DrawerChip("新建 Bot", Ic.Add, false) { go(Screen.EditBot(null)) }
            DrawerChip("新建群聊", Ic.Group, false) { go(Screen.NewGroup) }
            DrawerChip("工作流", Ic.Tree, false) { go(Screen.Workflows) }
        }
        LazyColumn(Modifier.weight(1f)) {
            item { SectionLabel("工作情况") }
            item { WorkStatus(store, go) }
            item { SectionLabel("聊天记录") }
            item {
                TextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("搜索聊天记录", color = Sub, fontSize = 14.sp) },
                    leadingIcon = { Icon(Ic.Search, null, tint = Sub, modifier = Modifier.size(20.dp)) },
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Card, unfocusedContainerColor = Card,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Fg, unfocusedTextColor = Fg
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            if (shown.isEmpty()) {
                item {
                    Text(if (query.isBlank()) "还没有对话，先新建一个 Bot 或开始 Agent 对话" else "没有匹配的对话",
                        color = Sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                }
            }
            items(shown, key = { it.key }) { e ->
                DrawerRow(
                    e, selected = e.sessionId == null && current == e.screen,
                    onClick = { openEntry(store, e, go) },
                    onDelete = if (e.sessionId != null) ({ deleteId = e.sessionId }) else null
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        HorizontalDivider(color = Line)
        Row(
            Modifier.fillMaxWidth().clickable { go(Screen.Settings) }.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Ic.Gear, null, tint = Fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("设置", color = Fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(Ic.Chevron, null, tint = Sub, modifier = Modifier.size(18.dp))
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

@Composable
private fun DrawerChip(text: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (primary) Color.White else Card)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (primary) Color.Black else Fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (primary) Color.Black else Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun WorkStatus(store: Store, go: (Screen) -> Unit) {
    val rows = mutableListOf<WorkRow>()
    for (id in store.busy.toList().distinct()) {
        if (id == AGENT_ID) rows.add(WorkRow(store.title(AGENT_ID), "Agent 正在工作", true, Screen.AgentChat))
        else if (id.startsWith("bot:") || id.startsWith("group:")) rows.add(WorkRow(store.title(id), "正在回复", true, Screen.Chat(id)))
    }
    for (p in store.projects.filter { it.stage in ACTIVE_STAGES }) {
        rows.add(WorkRow("项目 · ${p.title}", stageLabel(p.stage), p.stage != Stage.AWAIT_CONFIRM, Screen.Chat("bot:${p.managerId}")))
    }
    val ap = store.pendingApprovals.firstOrNull()
    if (ap != null) {
        rows.add(WorkRow("${store.pendingApprovals.size} 条命令待批准", "点击前往批准", false,
            if (ap.chatId == AGENT_ID) Screen.AgentChat else Screen.Chat(ap.chatId)))
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (rows.isEmpty()) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Sub))
                Spacer(Modifier.width(10.dp))
                Text("当前空闲，没有进行中的任务", color = Sub, fontSize = 13.sp)
            }
        }
        for (r in rows) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable { go(r.screen) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (r.running) CircularProgressIndicator(Modifier.size(16.dp), color = OK, strokeWidth = 2.dp)
                else Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFFD54F)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.sub, color = Sub, fontSize = 12.sp)
                }
                Icon(Ic.Chevron, null, tint = Sub, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DrawerRow(e: ChatEntry, selected: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) Card else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(e.seed, 40.dp, e.group)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(CHIP).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text(e.tag, color = Sub, fontSize = 10.sp)
                }
            }
            Text(e.preview.ifBlank { "暂无消息" }, color = Sub, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        if (e.busy) {
            CircularProgressIndicator(Modifier.size(14.dp), color = OK, strokeWidth = 2.dp)
        } else {
            Text(timeAgo(e.time), color = Sub, fontSize = 11.sp)
            if (onDelete != null) {
                Box(Modifier.size(28.dp).clickable { onDelete() }, contentAlignment = Alignment.Center) {
                    Icon(Ic.Close, "删除", tint = Sub, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
