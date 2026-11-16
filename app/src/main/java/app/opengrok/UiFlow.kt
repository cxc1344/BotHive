package app.opengrok

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import app.opengrok.ThemeAccent
import app.opengrok.ThemeAccentPale

private fun fmtTime(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/** In-chat approval cards (permission broker): pending shell commands for this chat. */
@Composable
fun ApprovalCards(store: Store, chatId: String) {
    store.pendingApprovals.filter { it.chatId == chatId }.forEach { ap ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp)).background(BeePanelRaised).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("待批准命令 · ${store.botName(ap.botId)}", color = ThemeAccentPale, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(ap.command, color = Fg, fontSize = 13.sp, fontFamily = HiveFonts.Mono, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("执行", modifier = Modifier.weight(1f)) { store.executeApproval(ap) }
                PillButton("拒绝", filled = false, modifier = Modifier.weight(1f)) { store.rejectApproval(ap) }
            }
        }
    }
}

/** Multi-select chips for attaching skill packs to a Bot / the Agent. */
@Composable
fun SkillsPicker(store: Store, selected: androidx.compose.runtime.snapshots.SnapshotStateList<String>) {
    if (store.skills.isEmpty()) {
        Text("还没有技能包:在「设置 → 技能包」里新建(一段可复用的指令,如某个领域的规范或风格)。", color = Sub, fontSize = 12.sp)
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        store.skills.forEach { sk ->
            val sel = sk.id in selected
            Box(
                Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) ThemeAccent else Card)
                    .clickable { if (sel) selected.remove(sk.id) else selected.add(sk.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text(sk.name, color = if (sel) Ink else Fg, fontSize = 12.sp) }
        }
    }
}

@Composable
fun EditSkillScreen(store: Store, skillId: String?, go: (Screen) -> Unit) {
    val old = store.skills.firstOrNull { it.id == skillId }
    var name by remember { mutableStateOf(old?.name ?: "") }
    var content by remember { mutableStateOf(old?.content ?: "") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.Settings) }) { Icon(Ic.Back, null, tint = Fg) }
            Text(if (old == null) "新建技能包" else "编辑技能包", style = HiveSerif.SheetTitle())
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Field("名称", name, { name = it })
            Field("内容(会拼进启用它的 Bot 的 System Prompt)", content, { content = it }, singleLine = false, minLines = 8)
            Text("例子:「做数学题时只许用初二上册学过的方法,禁用勾股定理与三角函数。」或某个项目的代码规范、写作风格。", color = Sub, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("保存", modifier = Modifier.fillMaxWidth()) {
                if (name.isNotBlank()) {
                    val id = old?.id ?: UUID.randomUUID().toString().take(8)
                    store.saveSkill(Skill(id, name.trim(), content))
                    go(Screen.Settings)
                }
            }
            if (old != null) PillButton("删除这个技能包", filled = false, modifier = Modifier.fillMaxWidth()) {
                store.deleteSkill(old.id); go(Screen.Settings)
            }
        }
    }
}

@Composable
fun WorkflowsScreen(store: Store, go: (Screen) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // List-page top bar: back left, serif centred title, add right (STUDY §2.7).
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.Home) }) { Icon(Ic.Back, null, tint = Fg) }
            Text("工作流", style = HiveSerif.SheetTitle(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { go(Screen.EditWorkflow(null)) }) { Icon(Ic.Add, null, tint = Fg) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("把固定套路编成步骤链:每步指定一个执行者(Bot 或 Agent),上一步的输出会填进下一步指令里的 {prev}。点工作流可看运行记录。", color = Sub, fontSize = 12.sp)
            if (store.workflows.isEmpty()) Text("还没有工作流,点右上角 + 新建", color = Sub)
            store.workflows.forEach { w ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { go(Screen.Chat("wf:${w.id}")) }) {
                            Text(w.name, color = Fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text("${w.steps.size} 步 · 上次运行 ${fmtTime(w.lastRunMs)}", color = Sub, fontSize = 12.sp)
                        }
                        IconButton(onClick = { go(Screen.EditWorkflow(w.id)) }) { Icon(Ic.Edit, null, tint = Sub) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(if ("wf:${w.id}" in store.busy) "运行中…" else "运行", modifier = Modifier.weight(1f)) {
                            store.runWorkflow(w)
                        }
                        PillButton("看记录", filled = false, modifier = Modifier.weight(1f)) { go(Screen.Chat("wf:${w.id}")) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun EditWorkflowScreen(store: Store, wfId: String?, go: (Screen) -> Unit) {
    val old = store.workflows.firstOrNull { it.id == wfId }
    var name by remember { mutableStateOf(old?.name ?: "") }
    val steps = remember {
        mutableStateListOf<WStep>().apply {
            addAll(old?.steps ?: listOf(WStep(store.bots.firstOrNull()?.id ?: AGENT_ID, "")))
        }
    }
    val candidates: List<Pair<String, String>> =
        store.bots.map { it.id to it.name } + listOfNotNull(store.agent.value?.let { AGENT_ID to it.name })
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.Workflows) }) { Icon(Ic.Back, null, tint = Fg) }
            Text(if (old == null) "新建工作流" else "编辑工作流", style = HiveSerif.SheetTitle())
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Field("名称", name, { name = it })
            steps.forEachIndexed { idx, step ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("步骤 ${idx + 1}", color = Fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (steps.size > 1) Text("删除", color = Sub, modifier = Modifier.clickable { steps.removeAt(idx) }.padding(6.dp))
                    }
                    LiquidChoice(
                        options = candidates.map { it.second },
                        selected = candidates.indexOfFirst { it.first == step.botId },
                        onSelect = { i -> candidates.getOrNull(i)?.let { steps[idx] = step.copy(botId = it.first) } },
                        height = 36.dp, fontSize = 12.sp
                    )
                    Field("指令(可用 {prev} 引用上一步输出)", step.instruction, { steps[idx] = step.copy(instruction = it) },
                        singleLine = false, minLines = 2)
                }
            }
            PillButton("添加步骤", filled = false, modifier = Modifier.fillMaxWidth()) {
                steps.add(WStep(candidates.firstOrNull()?.first ?: AGENT_ID, ""))
            }
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("保存", modifier = Modifier.fillMaxWidth()) {
                if (name.isNotBlank() && steps.isNotEmpty()) {
                    val id = old?.id ?: UUID.randomUUID().toString().take(8)
                    store.saveWorkflow(Workflow(id, name.trim(), steps.toList(), old?.lastOutputs ?: emptyList(), old?.lastRunMs ?: 0))
                    go(Screen.Workflows)
                }
            }
            if (old != null) PillButton("删除这个工作流", filled = false, modifier = Modifier.fillMaxWidth()) {
                store.deleteWorkflow(old.id); go(Screen.Workflows)
            }
        }
    }
}

@Composable
fun EditMcpScreen(store: Store, serverId: String?, go: (Screen) -> Unit) {
    val old = store.mcpServer(serverId)
    var name by remember { mutableStateOf(old?.name ?: "") }
    var url by remember { mutableStateOf(old?.url ?: "") }
    var headers by remember { mutableStateOf(old?.headers ?: "") }
    var enabled by remember { mutableStateOf(old?.enabled ?: true) }
    var testMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.Settings) }) { Icon(Ic.Back, null, tint = Fg) }
            Text(if (old == null) "添加 MCP 服务" else "编辑 MCP 服务", style = HiveSerif.SheetTitle())
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Field("名称", name, { name = it })
            Field("服务地址(Streamable HTTP,如 https://example.com/mcp)", url, { url = it }, keyboard = KeyboardType.Uri)
            Field("请求头(每行一个,如 Authorization: Bearer xxx;含密钥,会加密保存)", headers, { headers = it },
                singleLine = false, minLines = 3)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用", color = Fg, fontSize = 15.sp)
                    Text("启用后,它的工具会出现在所有 Bot 与 Agent 的工具列表里", color = Sub, fontSize = 12.sp)
                }
                LiquidSwitch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    testMsg = "连接中…"
                    scope.launch {
                        testMsg = try {
                            val tools = Mcp.testList(McpServer("probe", name, url.trim(), headers, enabled))
                            "连通成功:${tools.size} 个工具 ${tools.take(6).joinToString("、")}"
                        } catch (e: Exception) {
                            "失败:${e.message}"
                        }
                    }
                }) { Text("测试连接(列出工具)", color = Fg, fontSize = 13.sp) }
                if (testMsg.isNotBlank()) Text(testMsg, color = Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            Text("仅支持远程 MCP(Streamable HTTP / JSON-RPC)。本地 stdio 型服务(npx/uvx 启动)暂不支持。", color = Sub, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("保存", modifier = Modifier.fillMaxWidth()) {
                if (name.isNotBlank() && url.isNotBlank()) {
                    val id = old?.id ?: UUID.randomUUID().toString().take(8)
                    store.saveMcpServer(McpServer(id, name.trim(), url.trim(), headers, enabled))
                    go(Screen.Settings)
                }
            }
            if (old != null) PillButton("删除这个 MCP 服务", filled = false, modifier = Modifier.fillMaxWidth()) {
                store.deleteMcpServer(old.id); go(Screen.Settings)
            }
        }
    }
}

/** Document/photo picker for chat inputs: returns a launcher function; picked files are read into memory (25MB cap each). */
@Composable
fun AttachPickerLauncher(onPicked: (List<AttachItem>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val cr = context.contentResolver
                        val mime = cr.getType(uri) ?: "application/octet-stream"
                        var name = "file"
                        cr.query(uri, null, null, null, null)?.use { c ->
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it }
                        }
                        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                        if (bytes.size > 25 * 1024 * 1024) null else AttachItem(name, mime, bytes)
                    }.getOrNull()
                }
            }
            if (items.isNotEmpty()) onPicked(items)
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/** Chips row showing files staged for the next message, each removable. */
@Composable
fun AttachChips(items: List<AttachItem>, onRemove: (AttachItem) -> Unit) {
    if (items.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).background(Card).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item.name, color = Fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(24.dp).clickable { onRemove(item) }, contentAlignment = Alignment.Center) {
                    Icon(Ic.Close, null, tint = Sub, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** Red stop button shown while a chat is working; cancels the current run. Claude style: a
 *  quiet dark round button with a square stop glyph (STUDY §2.4), not a red disc. */
@Composable
fun StopButton(onStop: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF313131))
            .clickable { onStop() },
        contentAlignment = Alignment.Center
    ) { Icon(Ic.Stop, "停止", tint = Fg, modifier = Modifier.size(16.dp)) }
}

/** Round send button shared by every composer: white when there is something to send, dark otherwise. */
@Composable
fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).pressBounce(enabled = enabled, onClick = onClick)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (enabled) PillWhite else Color(0xFF313131)),
        contentAlignment = Alignment.Center
    ) { Icon(Ic.Send, "发送", tint = if (enabled) Ink else Sub, modifier = Modifier.size(18.dp)) }
}

/**
 * Claude-style composer with two shapes (STUDY §2.6), driven by keyboard visibility:
 *  - **capsule** (keyboard hidden): one pill, leading "+" round button, hint text, trailing send/stop;
 *  - **card** (keyboard up): big rounded card, input row on top, second row with "+" round button,
 *    the extra chip slot (model chip / mode chip) and the trailing send/stop round button.
 * The morph is a continuous height+radius animation, not a swap (Off tier jumps instantly).
 * The glass material stays on this floating surface via the existing [glassSurface] pass with an
 * explicit tint — the algorithm file is untouched.
 */
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    busy: Boolean,
    canSend: Boolean,
    onPickFiles: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    extraChip: (@Composable () -> Unit)? = null
) {
    val tier = GlassStyle.tier.value
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focused = keyboard
    val expanded = if (tier.motion) {
        val anim by animateFloatAsState(if (focused) 1f else 0f, tween(GlassMotion.ms(220)), label = "composer-morph")
        anim
    } else if (focused) 1f else 0f
    val radius = lerp(26.dp, 24.dp, expanded)
    val radiusPx = with(LocalDensity.current) { radius.toPx() }

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(radius))
                .background(if (tier.glassLayers) Card.copy(alpha = 0.88f) else Card)
                .glassSurface(tier = tier, radiusPx = radiusPx, tint = SheetGlassTint)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading "+" round button (attachments).
            Box(
                Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xFF313131)).clickable { onPickFiles() },
                contentAlignment = Alignment.Center
            ) { Icon(Ic.Attach, "附件", tint = Fg, modifier = Modifier.size(17.dp)) }
            Spacer(Modifier.width(6.dp))
            // Text field expands over the whole middle; in card shape it gets its own row instead.
            BasicTextField(
                value = value, onValueChange = onValueChange,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = Fg, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(ThemeAccent),
                maxLines = if (focused) 5 else 1,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(hint, color = Sub, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                }
            )
            Spacer(Modifier.width(6.dp))
            if (busy) StopButton(onStop = onStop) else SendButton(enabled = canSend, onClick = onSend)
        }
        // Second row appears only in card shape (keyboard up).
        if (expanded > 0.5f && extraChip != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) { extraChip() }
        }
    }
}

private fun lerp(a: androidx.compose.ui.unit.Dp, b: androidx.compose.ui.unit.Dp, t: Float): androidx.compose.ui.unit.Dp =
    a + (b - a) * t

/**
 * Model picker bottom sheet (STUDY §2.5): one grouped card, one row per model — model name, a
 * source badge pill, grey subtitle, blue check on the selected row. Data comes from the Bot's
 * provider preset (fetch via the existing [ModelsApi], no storage change); when nothing can be
 * listed, the sheet explains honestly instead of inventing entries.
 */
@Composable
fun ModelSheet(
    bot: Bot?,
    store: Store,
    visible: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    if (!visible) return
    var models by remember(visible, bot?.id) { mutableStateOf<List<String>>(emptyList()) }
    var msg by remember(visible, bot?.id) { mutableStateOf("") }
    var loading by remember(visible, bot?.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(visible, bot?.id) {
        if (!visible || bot == null) return@LaunchedEffect
        val prov = store.provider(bot.providerId)
        val url = prov?.baseUrl ?: bot.baseUrl
        val key = prov?.apiKey ?: bot.apiKey
        val http = prov?.allowInsecureHttp ?: bot.allowInsecureHttp
        if (url.isBlank() || key.isBlank()) {
            msg = "这个 Bot 还没有配置接口,先在编辑页填好 Base URL 与 Key。"
            return@LaunchedEffect
        }
        loading = true
        scope.launch {
            try {
                models = ModelsApi.list(url, key, http)
                if (models.isEmpty()) msg = "服务商没有返回模型列表。"
            } catch (e: Exception) {
                msg = "拉取失败:${e.message}"
            }
            loading = false
        }
    }
    ClaudeSheet(title = "选择模型", visible = visible, onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(UiTokens.RadiusCard)).background(BeePanel)
                .padding(vertical = 4.dp)
        ) {
            if (loading) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Sub, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在拉取模型列表…", color = Sub, fontSize = 13.sp)
                }
            } else if (models.isEmpty()) {
                Text(
                    msg.ifBlank { "暂无可选模型。" },
                    color = Sub, fontSize = 13.sp, modifier = Modifier.padding(16.dp)
                )
            } else {
                models.forEachIndexed { i, mid ->
                    val sel = bot?.model == mid
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onPick(mid); onDismiss() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(mid, color = Fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                store.provider(bot?.providerId)?.name ?: "自定义接口",
                                color = Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(UiTokens.RadiusChip))
                                .background(PILL).padding(horizontal = 8.dp, vertical = 3.dp)
                        ) { Text("API", color = Sub, fontSize = 10.sp) }
                        if (sel) {
                            Spacer(Modifier.width(10.dp))
                            Icon(Ic.Check, "已选", tint = SelectBlue, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (i < models.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(HAIRLINE).padding(horizontal = 16.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "列表来自这个 Bot 的 Provider 预设(实时拉取);切换只影响新消息,不改配置存储。",
            color = Sub, fontSize = 11.sp, lineHeight = 15.sp
        )
    }
}
