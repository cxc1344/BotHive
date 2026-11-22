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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            BixScene(
                action = BixAction.ApprovalGuard,
                compact = true,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp).offset(y = (-18).dp)
            )
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp)
                .harmonyGlass(thickness = ImmersiveThickness.THICK, radius = 16.dp, fallbackBg = BeePanelRaised)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val kindLabel = when (ap.toolName) {
                "shizuku_shell" -> "待批准 Shizuku 命令"
                "fs_delete" -> "待批准删除文件"
                else -> "待批准命令"
            }
            val displayCmd = if (ap.toolName == "fs_delete" && ap.command.contains("|")) {
                val parts = ap.command.split("|", limit = 2)
                "[${parts[0]}] ${parts[1]}"
            } else ap.command
            Text("$kindLabel · ${store.botName(ap.botId)}", color = ThemeAccentPale, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(displayCmd, color = Fg, fontSize = 13.sp, fontFamily = HiveFonts.Mono, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("执行", modifier = Modifier.weight(1f)) { store.executeApproval(ap) }
                PillButton("拒绝", filled = false, modifier = Modifier.weight(1f)) { store.rejectApproval(ap) }
            }
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
                Modifier.then(
                    if (sel) Modifier.clip(RoundedCornerShape(16.dp)).background(ThemeAccent)
                    else Modifier.harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 16.dp, fallbackBg = Card)
                )
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
            Box(Modifier.size(40.dp).pressBounce { go(Screen.Home) }.harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card), contentAlignment = Alignment.Center) { Icon(Ic.Back, null, tint = Fg, modifier = Modifier.size(20.dp)) }
            Text("工作流", style = HiveSerif.SheetTitle(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Box(Modifier.size(40.dp).pressBounce { go(Screen.EditWorkflow(null)) }.harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card), contentAlignment = Alignment.Center) { Icon(Ic.Add, null, tint = Fg, modifier = Modifier.size(20.dp)) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("把固定套路编成步骤链:每步指定一个执行者(Bot 或 Agent),上一步的输出会填进下一步指令里的 {prev}。点工作流可看运行记录。", color = Sub, fontSize = 12.sp)
            if (store.workflows.isEmpty()) Text("还没有工作流,点右上角 + 新建", color = Sub)
            store.workflows.forEach { w ->
                Column(
                    Modifier.fillMaxWidth().harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 16.dp, fallbackBg = Card).padding(14.dp),
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
                    Modifier.fillMaxWidth().harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 14.dp, fallbackBg = Card).padding(12.dp),
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
                Modifier.harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 16.dp, fallbackBg = Card).padding(horizontal = 10.dp, vertical = 6.dp),
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
        Modifier.size(40.dp).pressBounce { onStop() }
            .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, tint = BeeDanger.copy(alpha = 0.16f), fallbackBg = PILL),
        contentAlignment = Alignment.Center
    ) { Icon(Ic.Stop, "停止", tint = Fg, modifier = Modifier.size(16.dp)) }
}

/** Round send button shared by every composer: luminous white pill when ready, crystal glass otherwise. */
@Composable
fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).pressBounce(enabled = enabled, onClick = onClick)
            .then(
                if (enabled) Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(PillWhite)
                else Modifier.harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL)
            ),
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
    val expanded by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(if (tier.motion) GlassMotion.ms(220) else 0),
        label = "composer-morph"
    )
    val radius = lerp(26.dp, 24.dp, expanded)
    val radiusPx = with(LocalDensity.current) { radius.toPx() }

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = radius, tint = SheetGlassTint, fallbackBg = Card)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading "+" round button (attachments).
            Box(
                Modifier.size(38.dp).pressBounce { onPickFiles() }
                    .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL),
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
/**
 * Reusable searchable Liquid Glass model picker for huge provider catalogs (e.g. OpenRouter 300+ models).
 * Includes real-time keyword search + a 1-tap ":free" filter chip whenever free models are present.
 */
@Composable
fun SearchableModelList(
    models: List<String>,
    selectedModel: String,
    onPick: (String) -> Unit,
    providerLabel: String? = null,
    inlineMaxHeight: androidx.compose.ui.unit.Dp? = 250.dp
) {
    var query by remember(models) { mutableStateOf("") }
    var freeOnly by remember(models) { mutableStateOf(false) }
    val hasFree = remember(models) {
        models.any { it.contains(":free", ignoreCase = true) || it.endsWith("/free", ignoreCase = true) }
    }
    val filtered = remember(models, query, freeOnly) {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        models.filter { mid ->
            val lower = mid.lowercase()
            val freeOk = !freeOnly || lower.contains(":free") || lower.endsWith("/free")
            freeOk && tokens.all { tok -> lower.contains(tok) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Search bar capsule
        Row(
            Modifier
                .fillMaxWidth()
                .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 16.dp, fallbackBg = Card)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Ic.Search, null, tint = Sub, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Fg, fontSize = 14.sp),
                cursorBrush = SolidColor(ThemeAccent),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("搜索模型（如 free / deepseek / gemini）", color = Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                Box(
                    Modifier.size(26.dp).clickable { query = "" },
                    contentAlignment = Alignment.Center
                ) { Icon(Ic.Close, "清空", tint = Sub, modifier = Modifier.size(15.dp)) }
            }
        }

        // Quick filter row + match counter
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasFree) {
                Box(
                    Modifier
                        .then(
                            if (freeOnly) Modifier.clip(RoundedCornerShape(999.dp)).background(ThemeAccent)
                            else Modifier.harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL)
                        )
                        .clickable { freeOnly = !freeOnly }
                        .padding(horizontal = 11.dp, vertical = 5.dp)
                ) {
                    Text(
                        if (freeOnly) "只看免费 (:free) ✓" else "只看免费 (:free)",
                        color = if (freeOnly) Ink else ThemeAccentPale,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Text(
                "匹配 ${filtered.size} / ${models.size}",
                color = Sub, fontSize = 11.sp, modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }

        // Filtered model list
        if (filtered.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = UiTokens.RadiusCard, fallbackBg = Card)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("没有匹配的模型，换个关键词试试", color = Sub, fontSize = 13.sp)
            }
        } else if (inlineMaxHeight != null) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = inlineMaxHeight)
                    .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = UiTokens.RadiusCard, fallbackBg = Card)
                    .padding(vertical = 4.dp)
            ) {
                itemsIndexed(filtered, key = { _, mid -> mid }) { i, mid ->
                    ModelListRow(mid, selected = selectedModel == mid, providerLabel = providerLabel, showDivider = i < filtered.lastIndex) {
                        onPick(mid)
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = UiTokens.RadiusCard, fallbackBg = BeePanel)
                    .padding(vertical = 4.dp)
            ) {
                filtered.take(120).forEachIndexed { i, mid ->
                    ModelListRow(mid, selected = selectedModel == mid, providerLabel = providerLabel, showDivider = i < filtered.take(120).lastIndex) {
                        onPick(mid)
                    }
                }
                if (filtered.size > 120) {
                    Text(
                        "仅展示前 120 项，请输入关键词进一步筛选（共 ${filtered.size} 项）",
                        color = Sub, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelListRow(
    mid: String,
    selected: Boolean,
    providerLabel: String?,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val isFree = mid.contains(":free", ignoreCase = true) || mid.endsWith("/free", ignoreCase = true)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                mid,
                color = if (selected) ThemeAccentPale else Fg,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!providerLabel.isNullOrBlank()) {
                Text(providerLabel, color = Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
            }
        }
        if (isFree) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.clip(RoundedCornerShape(999.dp))
                    .background(ThemeAccent.copy(alpha = 0.18f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) { Text("FREE", color = ThemeAccentPale, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Ic.Check, "已选", tint = SelectBlue, modifier = Modifier.size(16.dp))
        }
    }
    if (showDivider) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(HAIRLINE))
    }
}

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
        if (loading) {
            Row(
                Modifier.fillMaxWidth()
                    .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = UiTokens.RadiusCard, fallbackBg = BeePanel)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Sub, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在拉取模型列表…", color = Sub, fontSize = 13.sp)
            }
        } else if (models.isEmpty()) {
            Box(
                Modifier.fillMaxWidth()
                    .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = UiTokens.RadiusCard, fallbackBg = BeePanel)
                    .padding(16.dp)
            ) {
                Text(msg.ifBlank { "暂无可选模型。" }, color = Sub, fontSize = 13.sp)
            }
        } else {
            SearchableModelList(
                models = models,
                selectedModel = bot?.model ?: "",
                onPick = { mid -> onPick(mid); onDismiss() },
                providerLabel = store.provider(bot?.providerId)?.name ?: "自定义接口",
                inlineMaxHeight = null
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "列表来自这个 Bot 的 Provider 预设(实时拉取);切换只影响新消息,不改配置存储。",
            color = Sub, fontSize = 11.sp, lineHeight = 15.sp
        )
    }
}
