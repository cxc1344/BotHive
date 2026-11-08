package app.opengrok

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private fun fmtTime(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/** In-chat approval cards (permission broker): pending shell commands for this chat. */
@Composable
fun ApprovalCards(store: Store, chatId: String) {
    store.pendingApprovals.filter { it.chatId == chatId }.forEach { ap ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xFF3A2E12)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("待批准命令 · ${store.botName(ap.botId)}", color = Color(0xFFFFD54F), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(ap.command, color = Fg, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 5, overflow = TextOverflow.Ellipsis)
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
                Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Color.White else Card)
                    .clickable { if (sel) selected.remove(sk.id) else selected.add(sk.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text(sk.name, color = if (sel) Color.Black else Fg, fontSize = 12.sp) }
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
            Text(if (old == null) "新建技能包" else "编辑技能包", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.Home) }) { Icon(Ic.Back, null, tint = Fg) }
            Text("工作流", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
            Text(if (old == null) "新建工作流" else "编辑工作流", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        candidates.forEach { (cid, cname) ->
                            val sel = step.botId == cid
                            Box(
                                Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Color.White else Bg)
                                    .clickable { steps[idx] = step.copy(botId = cid) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) { Text(cname, color = if (sel) Color.Black else Fg, fontSize = 12.sp) }
                        }
                    }
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
            Text(if (old == null) "添加 MCP 服务" else "编辑 MCP 服务", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                Switch(checked = enabled, onCheckedChange = { enabled = it })
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
