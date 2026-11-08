package app.opengrok

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Shared connection picker (provider preset or custom fields) + model field with a
 * "fetch models" picker. Used by the Agent editor; the Bot editor has its own inline copy.
 */
@Composable
fun ConnSection(
    store: Store,
    providerId: String, onProvider: (String) -> Unit,
    baseUrl: String, onBaseUrl: (String) -> Unit,
    apiKey: String, onKey: (String) -> Unit,
    allowHttp: Boolean, onHttp: (Boolean) -> Unit,
    model: String, onModel: (String) -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Text("连接(Provider 预设一次配置、处处复用;或单独填写)", color = Sub, fontSize = 13.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val selCustom = providerId.isEmpty()
        Box(
            Modifier.clip(RoundedCornerShape(20.dp)).background(if (selCustom) Color.White else Card)
                .clickable { onProvider("") }.padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Text("自定义", color = if (selCustom) Color.Black else Fg, fontSize = 14.sp) }
        store.providers.forEach { p ->
            val sel = providerId == p.id
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) Color.White else Card)
                    .clickable { onProvider(p.id); models = emptyList(); modelMsg = "" }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(p.name, color = if (sel) Color.Black else Fg, fontSize = 14.sp) }
        }
    }
    val prov = store.provider(providerId)
    if (prov != null) {
        Text("使用预设「${prov.name}」:${prov.baseUrl} · Key 已保存在预设中(在设置里管理)", color = Sub, fontSize = 12.sp)
    } else {
        if (store.providers.isEmpty()) Text("还没有预设:可在「设置 → Provider 预设」里添加", color = Sub, fontSize = 12.sp)
        Field("Base URL", baseUrl, onBaseUrl, keyboard = KeyboardType.Uri)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("允许 HTTP 明文连接", color = Fg, fontSize = 14.sp)
                Text("仅对可信的本地或内网 API 开启", color = Sub, fontSize = 11.sp)
            }
            Switch(checked = allowHttp, onCheckedChange = onHttp)
        }
        Field(
            "API Key", apiKey, onKey, keyboard = KeyboardType.Uri,
            visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示", color = Sub) }
            }
        )
    }
    Field("模型名", model, onModel, keyboard = KeyboardType.Uri)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            val eu = prov?.baseUrl ?: baseUrl; val ek = prov?.apiKey ?: apiKey; val eh = prov?.allowInsecureHttp ?: allowHttp
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
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Color.White else Card)
                        .clickable { onModel(mid) }.padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(mid, color = if (sel) Color.Black else Fg, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun AgentPanel(store: Store, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val agent = store.agent.value
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            store.agentTreeUri.value = uri.toString()
            store.save()
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (agent == null) {
            Spacer(Modifier.height(30.dp))
            Text("设备 Agent", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "住在手机本地的一位 Agent:能读写你授权的手机文件夹、查看设备状态与应用使用时长、打开应用," +
                    "还有自己的 Ubuntu 环境。它能和 Bot 模式里的云端 Bot 互相派活:在对话里用 ask_bot 找对方即可,私聊全程可见。",
                color = Sub, fontSize = 14.sp, lineHeight = 21.sp
            )
            PillButton("创建 Agent", modifier = Modifier.fillMaxWidth()) { go(Screen.EditAgent) }
        } else {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)
                    .clickable { go(Screen.AgentChat) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(AGENT_ID, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(agent.name, color = Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val provName = store.provider(agent.providerId)?.name
                    Text(
                        listOfNotNull(agent.model, provName).joinToString(" · "),
                        color = Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (AGENT_ID in store.busy) Text("工作中", color = Sub, fontSize = 12.sp)
                IconButton(onClick = { go(Screen.EditAgent) }) { Icon(Ic.Edit, null, tint = Fg) }
            }
            Text("权限与能力", color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("手机文件夹", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "当前授权:" + (DeviceFiles.grantLabel(store)?.let { "…/$it" } ?: "未授权(Agent 读不到任何手机文件)"),
                    color = Sub, fontSize = 12.sp
                )
                PillButton(if (store.agentTreeUri.value == null) "授权文件夹" else "更换授权文件夹", filled = false,
                    modifier = Modifier.fillMaxWidth()) { picker.launch(null) }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("应用使用时长", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Agent 查看「今天各应用用了多久」需要系统「使用情况访问」授权。", color = Sub, fontSize = 12.sp)
                PillButton("开启使用情况访问", filled = false, modifier = Modifier.fillMaxWidth()) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("通知读取", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (Notifications.isEnabled(context)) "状态:已开启。Agent 可用 recent_notifications 查看最近通知(微信、短信、应用提醒等)。"
                    else "状态:未开启。开启后 Agent 能读最近的通知,适合让它盯消息、汇总提醒。",
                    color = Sub, fontSize = 12.sp
                )
                if (!Notifications.isEnabled(context)) {
                    PillButton("开启通知使用权", filled = false, modifier = Modifier.fillMaxWidth()) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("无障碍(屏幕操作)", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (A11y.enabled.value) "状态:已开启。Agent 可用 screen_dump 看屏、tap_text/tap_xy 点击、swipe 滑动、input_text 输入、press_key 按系统键。"
                    else "状态:未开启。开启后 Agent 能看屏并操作其他应用(点按、滑动、输入)。开关必须由你在系统无障碍设置里亲手打开,任何应用都无法代开。",
                    color = Sub, fontSize = 12.sp
                )
                if (!A11y.enabled.value) {
                    PillButton("去开启无障碍", filled = false, modifier = Modifier.fillMaxWidth()) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Shizuku 特权通道(可选)", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                LaunchedEffect(Unit) { ShizukuTools.refresh() }
                val szRunning = ShizukuTools.running.value
                val szGranted = ShizukuTools.granted.value
                Text(
                    when {
                        szGranted -> "状态:已激活并授权。Agent 可用 shizuku_shell 执行 shell 级命令(点击/滑动/截屏/系统状态)。"
                        szRunning -> "状态:Shizuku 已运行,尚未授权本应用。"
                        else -> "状态:未运行。安装 Shizuku 应用并通过无线调试或电脑 ADB 激活后,Agent 能获得 shell 级能力(模拟点击、截屏、dumpsys 等)。"
                    },
                    color = Sub, fontSize = 12.sp
                )
                if (szRunning && !szGranted) {
                    PillButton("请求 Shizuku 授权", filled = false, modifier = Modifier.fillMaxWidth()) {
                        ShizukuTools.requestPermission()
                    }
                }
                PillButton("刷新状态", filled = false, modifier = Modifier.fillMaxWidth()) { ShizukuTools.refresh() }
            }
            Text(
                "已有工具:shell(Ubuntu)、list_dir/read_file/write_file(授权文件夹)、device_status、app_usage、list_apps、launch_app、shizuku_shell(可选特权通道)、" +
                    "screen_dump/tap_text/tap_xy/swipe/input_text/press_key(无障碍屏幕操作)、remember/recall(长期记忆)、ask_bot(找云端 Bot)。" +
                    "云端 Bot 也能用 ask_bot 找 Agent 派设备类任务。",
                color = Sub, fontSize = 12.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun AgentChatScreen(store: Store, go: (Screen) -> Unit) {
    val agent = store.agent.value
    val msgs = store.chat(AGENT_ID)
    val busy = AGENT_ID in store.busy
    var input by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val last = msgs.lastOrNull()
    LaunchedEffect(msgs.size, last?.tools?.size, last?.text) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.Home) }) { Icon(Ic.Back, null, tint = Fg) }
            Avatar(AGENT_ID, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(agent?.name ?: "Agent", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(agent?.model ?: "", color = Sub, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = { confirm = true }) { Icon(Ic.Refresh, null, tint = Fg) }
            IconButton(onClick = { go(Screen.EditAgent) }) { Icon(Ic.Edit, null, tint = Fg) }
        }
        HorizontalDivider(color = Line)
        ApprovalCards(store, AGENT_ID)
        LazyColumn(
            state = listState, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(msgs, key = { it.id }) { m ->
                MsgView(store, m, showName = false, thinking = busy && m.id == msgs.last().id, go = go)
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), placeholder = { Text("吩咐 Agent 做点什么…") },
                shape = RoundedCornerShape(26.dp), maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Card, unfocusedContainerColor = Card,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Fg, unfocusedTextColor = Fg
                )
            )
            Spacer(Modifier.width(8.dp))
            val can = input.isNotBlank() && !busy && agent != null
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (can) Color.White else Line)
                    .clickable(enabled = can) {
                        store.sendAgent(input.trim())
                        input = ""
                    },
                contentAlignment = Alignment.Center
            ) { Icon(Ic.Send, null, tint = Color.Black) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("重置沙盒?") },
            text = { Text("会清空 Agent 沙盒(含 Ubuntu 环境)里的所有文件,聊天记录保留。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        store.withSandboxLock(AGENT_ID) {
                            withContext(Dispatchers.IO) { store.sandbox(AGENT_ID).reset() }
                        }
                        store.chat(AGENT_ID).add(Msg(role = "system", text = "沙盒已重置"))
                        store.save()
                    }
                }) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
}

@Composable
fun EditAgentScreen(store: Store, go: (Screen) -> Unit) {
    val old = store.agent.value
    var name by remember { mutableStateOf(old?.name ?: "Agent") }
    var prompt by remember { mutableStateOf(old?.prompt ?: AGENT_DEFAULT_PROMPT) }
    var baseUrl by remember { mutableStateOf(old?.baseUrl ?: "https://api.openai.com/v1") }
    var model by remember { mutableStateOf(old?.model ?: "gpt-4o-mini") }
    var key by remember { mutableStateOf(old?.apiKey ?: "") }
    var allowHttp by remember { mutableStateOf(old?.allowInsecureHttp ?: false) }
    var providerId by remember { mutableStateOf(old?.providerId ?: "") }
    val skillSel = remember { mutableStateListOf<String>().apply { addAll(old?.skillIds ?: emptyList()) } }
    var shellAppr by remember { mutableStateOf(old?.shellApproval ?: false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.Home) }) { Icon(Ic.Back, null, tint = Fg) }
            Text(if (old == null) "创建 Agent" else "编辑 Agent", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Field("名字", name, { name = it })
            Field("System Prompt", prompt, { prompt = it }, singleLine = false, minLines = 5)
            HorizontalDivider(color = Line)
            ConnSection(
                store,
                providerId, { providerId = it },
                baseUrl, { baseUrl = it },
                key, { key = it },
                allowHttp, { allowHttp = it },
                model, { model = it }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Shell 命令先经我批准", color = Fg, fontSize = 14.sp)
                    Text("开启后,Agent 的每条命令都先在对话里等你点「执行」(Shizuku 命令不受此限,请谨慎授权 Shizuku)", color = Sub, fontSize = 11.sp)
                }
                androidx.compose.material3.Switch(checked = shellAppr, onCheckedChange = { shellAppr = it })
            }
            Text("技能包", color = Fg, fontSize = 14.sp)
            SkillsPicker(store, skillSel)
            store.persistenceError.value?.let {
                Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(20.dp)) {
            PillButton("保存", modifier = Modifier.fillMaxWidth()) {
                if (name.isNotBlank()) {
                    val prov = store.provider(providerId)
                    val b = if (prov != null) {
                        Bot(AGENT_ID, name.trim(), prompt, prov.baseUrl, "", model.trim(), false, prov.allowInsecureHttp, prov.id, skillSel.toList(), shellAppr)
                    } else {
                        Bot(AGENT_ID, name.trim(), prompt, baseUrl.trim(), key.trim(), model.trim(), false, allowHttp, "", skillSel.toList(), shellAppr)
                    }
                    if (store.saveAgent(b)) go(Screen.Home)
                }
            }
        }
    }
}

@Composable
fun EditProviderScreen(store: Store, providerId: String?, go: (Screen) -> Unit) {
    val old = store.provider(providerId)
    var name by remember { mutableStateOf(old?.name ?: "") }
    var baseUrl by remember { mutableStateOf(old?.baseUrl ?: "https://api.openai.com/v1") }
    var key by remember { mutableStateOf(old?.apiKey ?: "") }
    var allowHttp by remember { mutableStateOf(old?.allowInsecureHttp ?: false) }
    var showKey by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.Settings) }) { Icon(Ic.Back, null, tint = Fg) }
            Text(if (old == null) "添加 Provider" else "编辑 Provider", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Field("名称(如 OpenAI、DeepSeek、OpenRouter,或中转站名)", name, { name = it })
            Field("Base URL", baseUrl, { baseUrl = it }, keyboard = KeyboardType.Uri)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("允许 HTTP 明文连接", color = Fg, fontSize = 14.sp)
                    Text("仅对可信的本地或内网 API 开启,HTTP 可能泄露 API Key", color = Sub, fontSize = 11.sp)
                }
                Switch(checked = allowHttp, onCheckedChange = { allowHttp = it })
            }
            Field(
                "API Key", key, { key = it }, keyboard = KeyboardType.Uri,
                visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示", color = Sub) }
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    testMsg = "测试中…"
                    scope.launch {
                        testMsg = try {
                            val ms = ModelsApi.list(baseUrl, key, allowHttp)
                            "连通成功:共 ${ms.size} 个模型"
                        } catch (e: Exception) {
                            "失败:${e.message}"
                        }
                    }
                }) { Text("测试连接(拉取模型列表)", color = Fg, fontSize = 13.sp) }
                if (testMsg.isNotBlank()) Text(testMsg, color = Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            Text("保存后,新建/编辑 Bot 与 Agent 时可直接选这个预设,再从它的模型列表里点选模型,不必重复粘贴 Key。", color = Sub, fontSize = 12.sp)
            store.persistenceError.value?.let {
                Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("保存", modifier = Modifier.fillMaxWidth()) {
                if (name.isNotBlank()) {
                    val id = old?.id ?: UUID.randomUUID().toString().take(8)
                    val saved = store.saveProvider(Provider(id, name.trim(), baseUrl.trim(), key.trim(), allowHttp))
                    if (saved) go(Screen.Settings)
                }
            }
            if (old != null) PillButton("删除这个 Provider", filled = false, modifier = Modifier.fillMaxWidth()) {
                store.deleteProvider(old.id); go(Screen.Settings)
            }
        }
    }
}
