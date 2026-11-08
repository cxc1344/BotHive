package app.opengrok

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import java.util.UUID

val Bg = Color(0xFF0B0B0C)
val Card = Color(0xFF1A1B1E)
val Line = Color(0xFF2A2B2F)
val Fg = Color(0xFFECECEC)
val Sub = Color(0xFF8E9096)

sealed interface Screen {
    data object Home : Screen
    data class Chat(val id: String) : Screen
    data class EditBot(val id: String?) : Screen
    data object NewGroup : Screen
    data object Settings : Screen
}

@Composable
fun App(store: Store) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(screen != Screen.Home) { screen = Screen.Home }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White, onPrimary = Color.Black,
            background = Bg, surface = Bg, onSurface = Fg,
            surfaceVariant = Card, onSurfaceVariant = Sub, outline = Line
        )
    ) {
        Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
            when (val s = screen) {
                Screen.Home -> Home(store) { screen = it }
                is Screen.Chat -> ChatScreen(store, s.id) { screen = it }
                is Screen.EditBot -> EditBotScreen(store, s.id) { screen = it }
                Screen.NewGroup -> NewGroupScreen(store) { screen = it }
                Screen.Settings -> SettingsScreen(store) { screen = it }
            }
        }
    }
}

@Composable
fun Avatar(seed: String, size: Dp, group: Boolean = false) {
    val h = Math.floorMod(seed.hashCode(), 360).toFloat()
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(
            Brush.linearGradient(listOf(Color.hsv(h, .55f, .95f), Color.hsv((h + 50f) % 360f, .7f, .55f))),
            radius = r
        )
        val w = Color.White.copy(alpha = .92f)
        val c = center
        if (!group) {
            drawCircle(w, r * .11f, Offset(c.x - r * .3f, c.y - r * .1f))
            drawCircle(w, r * .11f, Offset(c.x + r * .3f, c.y - r * .1f))
            drawLine(w, Offset(c.x - r * .24f, c.y + r * .32f), Offset(c.x + r * .24f, c.y + r * .32f),
                strokeWidth = r * .1f, cap = StrokeCap.Round)
        } else {
            drawCircle(w, r * .16f, Offset(c.x - r * .36f, c.y + r * .12f))
            drawCircle(w, r * .16f, Offset(c.x + r * .36f, c.y + r * .12f))
            drawCircle(w, r * .2f, Offset(c.x, c.y - r * .14f))
        }
    }
}

@Composable
fun Home(store: Store, go: (Screen) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Bots", color = Fg, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { go(Screen.Settings) }) { Icon(Ic.Tune, null, tint = Fg) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Ic.Add, null, tint = Fg) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("新建 Bot") }, onClick = { menu = false; go(Screen.EditBot(null)) })
                    DropdownMenuItem(text = { Text("新建群聊") }, onClick = { menu = false; go(Screen.NewGroup) })
                }
            }
        }
        val rows = store.chatList()
        if (rows.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(
                    Modifier.size(104.dp).clip(CircleShape).background(Color.White).clickable { go(Screen.EditBot(null)) },
                    contentAlignment = Alignment.Center
                ) { Icon(Ic.Add, null, tint = Color.Black, modifier = Modifier.size(52.dp)) }
                Spacer(Modifier.height(20.dp))
                Text("新建你的第一个 Bot", color = Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("建议先建几位员工,再建一位总经理来统筹", color = Sub, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it }) { id ->
                    val last = store.chat(id).lastOrNull { it.text.isNotBlank() }?.text ?: store.subtitle(id)
                    Row(
                        Modifier.fillMaxWidth().clickable { go(Screen.Chat(id)) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(id, 48.dp, group = !id.startsWith("bot:"))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(store.title(id), color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(last, color = Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (id in store.busy) Text("工作中", color = Sub, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(store: Store, id: String, go: (Screen) -> Unit) {
    val msgs = store.chat(id)
    val busy = id in store.busy
    val readOnly = id.startsWith("dm:")
    val isBot = id.startsWith("bot:")
    var input by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var projectMode by remember { mutableStateOf(true) }
    val isManager = isBot && store.bot(id.removePrefix("bot:"))?.isManager == true
    val listState = rememberLazyListState()
    val last = msgs.lastOrNull()
    LaunchedEffect(msgs.size, last?.tools?.size, last?.text) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.Home) }) { Icon(Ic.Back, null, tint = Fg) }
            Avatar(id, 36.dp, group = !isBot)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(store.title(id), color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(store.subtitle(id), color = Sub, fontSize = 12.sp, maxLines = 1)
            }
            if (isBot) {
                IconButton(onClick = { confirm = true }) { Icon(Ic.Refresh, null, tint = Fg) }
                IconButton(onClick = { go(Screen.EditBot(id.removePrefix("bot:"))) }) { Icon(Ic.Edit, null, tint = Fg) }
            }
        }
        HorizontalDivider(color = Line)
        LazyColumn(
            state = listState, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(msgs, key = { it.id }) { m ->
                MsgView(store, m, showName = !isBot, thinking = busy && m.id == msgs.last().id, go = go)
            }
        }
        if (!readOnly && isManager) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (projectMode) Color.White else Card)
                        .clickable { projectMode = !projectMode }.padding(horizontal = 14.dp, vertical = 6.dp)
                ) { Text(if (projectMode) "立项模式:开" else "立项模式:关(普通对话)", color = if (projectMode) Color.Black else Sub, fontSize = 13.sp) }
            }
        }
        if (!readOnly) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
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
                val can = input.isNotBlank() && !busy
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(if (can) Color.White else Line)
                        .clickable(enabled = can) {
                            if (isManager && projectMode) store.startProject(id, input.trim()) else store.send(id, input.trim())
                            input = ""
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Ic.Send, null, tint = Color.Black) }
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

@Composable
fun MsgView(store: Store, m: Msg, showName: Boolean, thinking: Boolean, go: (Screen) -> Unit) {
    when {
        m.role == "project" -> ProjectCard(store, m.text, go)
        m.role == "system" -> Text(m.text, color = Sub, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        m.from == null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = Color(0xFF2B2D31), shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                SelectionContainer { Text(m.text, color = Fg, fontSize = 16.sp, modifier = Modifier.padding(14.dp, 10.dp)) }
            }
        }
        else -> Column(Modifier.fillMaxWidth()) {
            if (showName) Text(store.botName(m.from), color = Sub, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            if (m.tools.isNotEmpty()) ToolsPanel(m.tools)
            if (m.text.isNotBlank()) SelectionContainer {
                Text(m.text, color = Fg, fontSize = 16.sp, lineHeight = 24.sp)
            } else if (thinking) Text("思考中…", color = Sub, fontSize = 15.sp)
        }
    }
}

@Composable
fun ToolsPanel(tools: List<ToolCall>) {
    var open by remember { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "rot")
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Card).clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Ic.Terminal, null, tint = Sub, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("已调用 ${tools.size} 个工具", color = Sub, fontSize = 13.sp)
            Spacer(Modifier.width(2.dp))
            Icon(Ic.Down, null, tint = Sub, modifier = Modifier.size(18.dp).rotate(rot))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tools.forEach { t ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp)) {
                        SelectionContainer {
                            Column {
                                Text(t.name, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(t.args, color = Sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 2.dp))
                                HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 8.dp))
                                Text(t.output.ifBlank { "运行中…" }, color = Fg, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
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
            focusedBorderColor = Color.White, unfocusedBorderColor = Line,
            focusedLabelColor = Color.White, unfocusedLabelColor = Sub, cursorColor = Color.White
        )
    )
}

@Composable
fun PillButton(text: String, filled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(26.dp))
            .background(if (filled) Color.White else Card)
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (filled) Color.Black else Fg, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun EditBotScreen(store: Store, botId: String?, go: (Screen) -> Unit) {
    val old = store.bot(botId)
    var name by remember { mutableStateOf(old?.name ?: "") }
    var prompt by remember { mutableStateOf(old?.prompt ?: PRESETS[1].prompt) }
    var preset by remember { mutableStateOf(if (old == null) PRESETS[1].name else "自定义") }
    var isManager by remember { mutableStateOf(old?.isManager ?: PRESETS[1].manager) }
    var baseUrl by remember { mutableStateOf(old?.baseUrl ?: "https://api.openai.com/v1") }
    var model by remember { mutableStateOf(old?.model ?: "gpt-4o-mini") }
    var key by remember { mutableStateOf(old?.apiKey ?: "") }
    var allowInsecureHttp by remember { mutableStateOf(old?.allowInsecureHttp ?: false) }
    var showKey by remember { mutableStateOf(false) }
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
                        Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) Color.White else Card)
                            .clickable {
                                preset = p.name; prompt = p.prompt; isManager = p.manager
                                if (name.isBlank() && p.prompt.isNotEmpty()) name = p.name
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text(p.name, color = if (sel) Color.Black else Fg, fontSize = 14.sp) }
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
            Text("模型(每个 Bot 独立配置,兼容 OpenAI 接口)", color = Sub, fontSize = 13.sp)
            Field("Base URL", baseUrl, { baseUrl = it }, keyboard = KeyboardType.Uri)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("允许 HTTP 明文连接", color = Fg, fontSize = 14.sp)
                    Text("仅对可信的本地或内网 API 开启，HTTP 可能泄露 API Key", color = Sub, fontSize = 11.sp)
                }
                Switch(checked = allowInsecureHttp, onCheckedChange = { allowInsecureHttp = it })
            }
            Field("模型名", model, { model = it }, keyboard = KeyboardType.Uri)
            // Plain text keyboard + visual masking: never triggers the system secure keyboard,
            // so paste/copy always works.
            Field(
                "API Key", key, { key = it }, keyboard = KeyboardType.Uri,
                visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示", color = Sub) }
                }
            )
            store.persistenceError.value?.let {
                Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("保存", modifier = Modifier.fillMaxWidth()) {
                if (name.isNotBlank()) {
                    val id = old?.id ?: UUID.randomUUID().toString().take(8)
                    val saved = store.saveBot(Bot(id, name.trim(), prompt, baseUrl.trim(), key.trim(), model.trim(), isManager, allowInsecureHttp))
                    if (saved) go(Screen.Chat("bot:$id"))
                }
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
