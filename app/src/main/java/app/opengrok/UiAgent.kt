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
            "API Key(多个 Key 可每行一个,遇 429 自动轮换)", apiKey, onKey, keyboard = KeyboardType.Uri,
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
fun AgentChatScreen(store: Store, go: (Screen) -> Unit) {
    val agent = store.agent.value
    val msgs = store.chat(AGENT_ID)
    val busy = AGENT_ID in store.busy
    var input by remember { mutableStateOf("") }
    val staged = remember { mutableStateListOf<AttachItem>() }
    val pickFiles = AttachPickerLauncher { staged.addAll(it) }
    val listState = rememberLazyListState()
    val last = msgs.lastOrNull()
    LaunchedEffect(msgs.size, last?.tools?.size, last?.text) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex)
    }
    if (agent == null) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(store, "agent", go)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("设备 Agent", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "住在手机本地的一位 Agent:管理手机文件(普通 / Shizuku / Root 分档)、看屏操作、查状态与通知," +
                        "还有自己的 Ubuntu 沙盒;能和云端 Bot 互相派活。",
                    color = Sub, fontSize = 14.sp, lineHeight = 21.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                PillButton("创建 Agent", modifier = Modifier.fillMaxWidth()) { go(Screen.EditAgent) }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(store, "agent", go)
        ChatSubHeader(AGENT_ID, false, store.title(AGENT_ID), store.subtitle(AGENT_ID)) {
            HeaderPill("新对话", Ic.Add, enabled = !busy && msgs.isNotEmpty()) { store.newAgentSession() }
            IconButton(onClick = { go(Screen.AgentSettings) }) { Icon(Ic.Tune, null, tint = Fg) }
        }
        HorizontalDivider(color = Line)
        ApprovalCards(store, AGENT_ID)
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(msgs, key = { it.id }) { m ->
                    MsgView(store, m, showName = false, thinking = busy && m.id == msgs.last().id, go = go)
                }
            }
            if (msgs.isEmpty()) EmptyChatHint("新对话已就绪", "让 Agent 帮你整理手机文件、查看状态与通知、操作屏幕，或把活派给云端 Bot。")
        }
        AttachChips(staged) { staged.remove(it) }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { pickFiles() }, modifier = Modifier.size(48.dp)) {
                Icon(Ic.Attach, null, tint = Fg)
            }
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
            if (busy) {
                StopButton { store.stopChat(AGENT_ID) }
            } else {
                val can = (input.isNotBlank() || staged.isNotEmpty()) && agent != null
                SendButton(can) {
                    store.sendAgent(input.trim(), staged.toList())
                    input = ""
                    staged.clear()
                }
            }
        }
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
                    if (store.saveAgent(b)) go(Screen.AgentChat)
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

/** The Agent's connection & behaviour form (provider / model / prompt / approval / skills), shared by the create page and Agent settings. */
@Composable
fun AgentForm(store: Store, onSaved: () -> Unit) {
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
    Field("名字", name, { name = it })
    Spacer(Modifier.height(14.dp))
    Field("System Prompt", prompt, { prompt = it }, singleLine = false, minLines = 5)
    Spacer(Modifier.height(14.dp))
    ConnSection(
        store,
        providerId, { providerId = it },
        baseUrl, { baseUrl = it },
        key, { key = it },
        allowHttp, { allowHttp = it },
        model, { model = it }
    )
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Shell 命令先经我批准", color = Fg, fontSize = 14.sp)
            Text("开启后,Agent 的每条命令都先在对话里等你点「执行」(Shizuku 命令不受此限,请谨慎授权 Shizuku)", color = Sub, fontSize = 11.sp)
        }
        Switch(checked = shellAppr, onCheckedChange = { shellAppr = it })
    }
    Spacer(Modifier.height(14.dp))
    Text("技能包", color = Fg, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    SkillsPicker(store, skillSel)
    store.persistenceError.value?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp)
    }
    Spacer(Modifier.height(16.dp))
    PillButton("保存", modifier = Modifier.fillMaxWidth()) {
        if (name.isNotBlank()) {
            val prov = store.provider(providerId)
            val b = if (prov != null) {
                Bot(AGENT_ID, name.trim(), prompt, prov.baseUrl, "", model.trim(), false, prov.allowInsecureHttp, prov.id, skillSel.toList(), shellAppr)
            } else {
                Bot(AGENT_ID, name.trim(), prompt, baseUrl.trim(), key.trim(), model.trim(), false, allowHttp, "", skillSel.toList(), shellAppr)
            }
            if (store.saveAgent(b)) onSaved()
        }
    }
}

/** Permission & channel cards for the Agent (all-files access, usage stats, notifications, accessibility, Shizuku). */
@Composable
fun AgentPermissions(store: Store) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("全部文件访问(普通通道)", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (FsTools.allFilesGranted()) "状态:已授权。Agent 的 fs_* 工具可用普通通道管理整个共享存储(绝对路径,如 /sdcard/Download)。"
            else "状态:未授权。开启后 Agent 能用普通通道读写整个共享存储;其他应用的 /data 私有目录仍需 Shizuku / Root 通道。",
            color = Sub, fontSize = 12.sp
        )
        if (!FsTools.allFilesGranted()) {
            PillButton("开启全部文件访问", filled = false, modifier = Modifier.fillMaxWidth()) {
                try {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + context.packageName)
                        )
                    )
                } catch (_: Exception) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
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
    Spacer(Modifier.height(14.dp))
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
    Spacer(Modifier.height(14.dp))
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
    Spacer(Modifier.height(14.dp))
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
                szGranted -> "状态:已激活并授权。Agent 可用 shizuku_shell 执行 shell 级命令(点击/滑动/截屏/系统状态),fs 工具的 Shizuku / Root 通道也走这里。"
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
}

/** Agent settings: everything in one page — connection & behaviour, permissions & channels, sandbox reset. */
@Composable
fun AgentSettingsScreen(store: Store, go: (Screen) -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.AgentChat) }) { Icon(Ic.Back, null, tint = Fg) }
            Text("Agent 设置", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("连接与行为", color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            AgentForm(store) { go(Screen.AgentChat) }
            HorizontalDivider(color = Line)
            Text("权限与通道", color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            AgentPermissions(store)
            HorizontalDivider(color = Line)
            Text("危险区", color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            PillButton("重置 Agent 沙盒", filled = false, modifier = Modifier.fillMaxWidth()) { confirmReset = true }
            Text("会清空 Agent 沙盒(含 Ubuntu 环境)里的所有文件,聊天记录保留。", color = Sub, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置沙盒?") },
            text = { Text("会清空 Agent 沙盒(含 Ubuntu 环境)里的所有文件,聊天记录保留。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        store.withSandboxLock(AGENT_ID) {
                            withContext(Dispatchers.IO) { store.sandbox(AGENT_ID).reset() }
                        }
                        store.chat(AGENT_ID).add(Msg(role = "system", text = "沙盒已重置"))
                        store.save()
                    }
                }) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } }
        )
    }
}
