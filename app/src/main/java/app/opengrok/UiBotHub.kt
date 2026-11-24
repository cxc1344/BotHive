package app.opengrok

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.UUID

private data class BotTemplate(
    val name: String,
    val badge: String,
    val desc: String,
    val prompt: String,
    val isManager: Boolean = false
)

private val HIVE_TEMPLATES = listOf(
    BotTemplate(
        name = "蜂巢总经理",
        badge = "统筹派工",
        desc = "自动拆解复杂任务、分工派给其他 Bot 并验收汇总",
        prompt = "你是蜂巢总经理，擅长把复杂目标拆成清晰的执行步骤，合理派发给最合适的专员 Bot，并把关质量、输出简洁结论。",
        isManager = true
    ),
    BotTemplate(
        name = "全栈架构师",
        badge = "写码/沙盒",
        desc = "擅长 Kotlin/Python/Web 工程架构与沙盒实测验证",
        prompt = "你是资深全栈架构师。动手前先用一句话说明方案；写代码优先追求简洁可维护，并尽量在 Ubuntu 沙盒里运行验证后再汇报。"
    ),
    BotTemplate(
        name = "代码审计官",
        badge = "查 Bug",
        desc = "专盯并发竞争、边界条件、安全漏洞与性能瓶颈",
        prompt = "你是严苛的代码审计官。审查代码时直接指出致命 Bug、内存泄漏、边界条件和可优化点，按严重程度排序并给出最小修复补丁。"
    ),
    BotTemplate(
        name = "爆款文案手",
        badge = "抖音/社媒",
        desc = "高留存开头钩子、克制高级感文案与评论区互动设计",
        prompt = "你是懂独立开发者和科技产品的创意文案手。拒绝油腻营销词，擅长用真实细节、反差感和极简节奏写出高转发率的短视频与图文文案。"
    ),
    BotTemplate(
        name = "联网研究员",
        badge = "独立浏览器",
        desc = "善用内置 AI 独立浏览器实地查资料、比价与核验信息",
        prompt = "你是深度研究员。遇到需要最新事实或网页信息的问题，主动使用独立浏览器工具打开网页核实，再提炼结构化结论与来源链接。"
    ),
    BotTemplate(
        name = "魔鬼代言人",
        badge = "决策压测",
        desc = "专门挑刺、反驳假设，帮你提前堵住产品和方案漏洞",
        prompt = "你是冷静犀利的魔鬼代言人。无论用户提出什么想法，先肯定最核心亮点，再从成本、用户习惯、最坏情况三个维度给出尖锐反例和替代方案。"
    )
)

/**
 * Dedicated Bot Mode Command Hub ("蜂巢指挥台"):
 *  - Living Bix Commander reacting to busy bots & active projects.
 *  - Hive Broadcast: send ONE prompt to multiple selected Bots in parallel for instant multi-model comparison.
 *  - Searchable & filterable Liquid Glass Bot/Group roster with inline model switcher, 1-tap clone, and safe JSON export/import.
 *  - 1-tap Specialist Bot Template Hiring carousel.
 */
@Composable
fun BotHubScreen(store: Store, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) } // 0=全部, 1=Bot, 2=群聊
    var showBroadcast by remember { mutableStateOf(false) }
    var broadcastPrompt by remember { mutableStateOf("") }
    val selectedBots = remember { mutableStateListOf<String>() }
    var modelBot by remember { mutableStateOf<Bot?>(null) }
    var showModelSheet by remember { mutableStateOf(false) }
    var filesBot by remember { mutableStateOf<Bot?>(null) }
    var memoryBot by remember { mutableStateOf<Bot?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importJson by remember { mutableStateOf("") }

    val busyCount = store.busy.count { it.startsWith("bot:") || it.startsWith("group:") } +
        store.projects.count { it.stage in ACTIVE_STAGES }
    val q = query.trim().lowercase()
    val filteredBots = remember(store.bots.toList(), q) {
        if (q.isEmpty()) store.bots.toList()
        else store.bots.filter {
            it.name.lowercase().contains(q) || it.model.lowercase().contains(q) || it.prompt.lowercase().contains(q)
        }
    }
    val filteredGroups = remember(store.groups.toList(), q) {
        if (q.isEmpty()) store.groups.toList()
        else store.groups.filter { it.name.lowercase().contains(q) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(store, "bot", go)

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Bix Commander Hero Banner
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 20.dp, fallbackBg = Card)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BixScene(
                                action = if (busyCount > 0) BixAction.DrillingSquad else BixAction.Idle,
                                compact = false
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("蜂巢指挥台", style = HiveSerif.Title(22.sp))
                                Text(
                                    "${store.bots.size} 只 Bot · ${store.groups.size} 个群聊" +
                                        if (busyCount > 0) " · $busyCount 项运行中" else " · 全部待命",
                                    color = if (busyCount > 0) ThemeAccentPale else Sub,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.weight(1f).pressBounce { go(Screen.EditBot(null)) }
                                    .clip(RoundedCornerShape(14.dp)).background(PillWhite)
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("+ 新建 Bot", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            Box(
                                Modifier.weight(1f).pressBounce { go(Screen.NewGroup) }
                                    .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 14.dp, fallbackBg = PILL)
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("+ 建群协作", color = Fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                            Box(
                                Modifier.weight(1f).pressBounce {
                                    showBroadcast = !showBroadcast
                                    if (showBroadcast && selectedBots.isEmpty()) {
                                        selectedBots.addAll(store.bots.take(3).map { it.id })
                                    }
                                }
                                    .then(
                                        if (showBroadcast) Modifier.clip(RoundedCornerShape(14.dp)).background(ThemeAccent)
                                        else Modifier.harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 14.dp, fallbackBg = PILL)
                                    )
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "蜂巢群发",
                                    color = if (showBroadcast) Ink else ThemeAccentPale,
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // 2. Collapsible Hive Broadcast Composer (multi-Bot / multi-model parallel prompt!)
                item {
                    AnimatedVisibility(
                        visible = showBroadcast,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .harmonyGlass(
                                    thickness = ImmersiveThickness.THICK,
                                    radius = 18.dp,
                                    tint = ThemeAccent.copy(alpha = 0.10f),
                                    fallbackBg = Card
                                )
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("蜂巢广播 · 多 Bot 并行作答", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(
                                    if (selectedBots.size == store.bots.size && store.bots.isNotEmpty()) "取消全选" else "全选",
                                    color = ThemeAccentPale, fontSize = 12.sp,
                                    modifier = Modifier.clickable {
                                        if (selectedBots.size == store.bots.size) selectedBots.clear()
                                        else {
                                            selectedBots.clear()
                                            selectedBots.addAll(store.bots.map { it.id })
                                        }
                                    }.padding(4.dp)
                                )
                            }
                            if (store.bots.isEmpty()) {
                                Text("还没有 Bot，先从下方模板一键招聘一只吧。", color = Sub, fontSize = 12.sp)
                            } else {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    store.bots.forEach { b ->
                                        val sel = b.id in selectedBots
                                        Box(
                                            Modifier
                                                .then(
                                                    if (sel) Modifier.clip(RoundedCornerShape(999.dp)).background(ThemeAccent)
                                                    else Modifier.harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL)
                                                )
                                                .clickable { if (sel) selectedBots.remove(b.id) else selectedBots.add(b.id) }
                                                .padding(horizontal = 11.dp, vertical = 5.dp)
                                        ) { Text(b.name, color = if (sel) Ink else Fg, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) }
                                    }
                                }
                                Field("广播内容（同时发给已选中的 ${selectedBots.size} 只 Bot）", broadcastPrompt, { broadcastPrompt = it }, singleLine = false, minLines = 2)
                                PillButton(
                                    "向 ${selectedBots.size} 只 Bot 一键群发",
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val msg = broadcastPrompt.trim()
                                    if (msg.isNotBlank() && selectedBots.isNotEmpty()) {
                                        selectedBots.toList().forEach { bid ->
                                            store.send("bot:$bid", msg, emptyList())
                                        }
                                        broadcastPrompt = ""
                                        showBroadcast = false
                                        Toast.makeText(ctx, "已向 ${selectedBots.size} 只 Bot 并行派发！", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }

                // 2b. Proactive Routine Inbox (Dots / Duet style daily briefings & scheduled check-ins)
                item {
                    ProactiveInboxSection(store = store, go = go)
                }

                // 3. Search + Filter Tabs + Import JSON
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 16.dp, fallbackBg = Card)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Ic.Search, null, tint = Sub, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = Fg, fontSize = 14.sp),
                                cursorBrush = SolidColor(ThemeAccent),
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                                decorationBox = { inner ->
                                    Box { if (query.isEmpty()) Text("搜索 Bot 名称、模型或人设…", color = Sub, fontSize = 13.sp); inner() }
                                }
                            )
                            if (query.isNotEmpty()) {
                                Box(Modifier.size(26.dp).clickable { query = "" }, contentAlignment = Alignment.Center) {
                                    Icon(Ic.Close, "清空", tint = Sub, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("全部", "Bots (${filteredBots.size})", "群聊 (${filteredGroups.size})").forEachIndexed { idx, label ->
                                val sel = tab == idx
                                Box(
                                    Modifier
                                        .then(
                                            if (sel) Modifier.clip(RoundedCornerShape(999.dp)).background(ThemeAccent)
                                            else Modifier.harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL)
                                        )
                                        .clickable { tab = idx }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) { Text(label, color = if (sel) Ink else Fg, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) }
                            }
                            Spacer(Modifier.weight(1f))
                            SmallChip("导入 JSON") { importJson = ""; showImportDialog = true }
                        }
                    }
                }

                // 4. Bot Roster Cards
                if (tab == 0 || tab == 1) {
                    items(filteredBots, key = { "hub-bot-${it.id}" }) { b ->
                        val chatKey = "bot:${b.id}"
                        val isBusy = chatKey in store.busy
                        val lastMsg = store.chat(chatKey).lastOrNull { it.text.isNotBlank() }
                        val provName = store.provider(b.providerId)?.name
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 18.dp, fallbackBg = Card)
                                .clickable { go(Screen.Chat(chatKey)) }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // v0.7.0: status halo — working / waiting for approval / idle.
                                val halo = when {
                                    store.pendingApprovals.any { it.botId == b.id } -> HaloState.Waiting
                                    store.busy.any { it == chatKey || (it.startsWith("dm:") && it.contains(b.id)) } -> HaloState.Working
                                    else -> HaloState.Idle
                                }
                                Box(Modifier.statusHalo(halo, cornerRadius = 999.dp)) { Avatar(chatKey, 40.dp) }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(b.name, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (b.isManager) {
                                            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(ThemeAccent.copy(alpha = 0.20f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                                Text("总经理", color = ThemeAccentPale, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        if (isBusy) {
                                            Box(Modifier.size(7.dp).clip(CircleShape).background(BeeGreen))
                                        }
                                    }
                                    Text(
                                        (provName?.let { "$it · " } ?: "") + b.model,
                                        color = Sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                // Quick inline model switcher pill
                                Box(
                                    Modifier.pressBounce { modelBot = b; showModelSheet = true }
                                        .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL)
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) { Text("换模型", color = ThemeAccentPale, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
                            }

                            // Capability badges + latest reply preview
                            val preview = if (isBusy) "Bix 正在指挥执行中…" else (lastMsg?.text?.let { splitThinkingContent(it).second.ifBlank { it } } ?: b.prompt)
                            Text(
                                preview.replace(Regex("\\s+"), " ").trim(),
                                color = if (isBusy) ThemeAccentPale else Sub,
                                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SmallChip("对话") { go(Screen.Chat(chatKey)) }
                                SmallChip("文件") { filesBot = b }
                                SmallChip("记忆") { memoryBot = b }
                                SmallChip("编辑") { go(Screen.EditBot(b.id)) }
                                SmallChip("克隆") {
                                    // v0.7.0: unique "副本 n" names and never a second manager.
                                    val copy = store.duplicateBot(b.id)
                                    if (copy != null) Toast.makeText(ctx, "已克隆「${copy.name}」", Toast.LENGTH_SHORT).show()
                                }
                                SmallChip("分享配置") {
                                    // v0.7.0 (F22): every non-secret field travels; the API key never does.
                                    val js = exportBotConfig(store, b, ctx).toString(2)
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("bothive_bot", js))
                                    Toast.makeText(ctx, "已复制配置 JSON(不含密钥)", Toast.LENGTH_SHORT).show()
                                }
                                Spacer(Modifier.weight(1f))
                                if (lastMsg != null && lastMsg.time > 0) {
                                    Text(timeAgo(lastMsg.time), color = Sub.copy(alpha = 0.7f), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

                // 5. Group Roster Cards
                if (tab == 0 || tab == 2) {
                    items(filteredGroups, key = { "hub-grp-${it.id}" }) { g ->
                        val chatKey = "group:${g.id}"
                        val isBusy = chatKey in store.busy
                        val members = g.memberIds.mapNotNull { store.bot(it)?.name }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 18.dp, fallbackBg = Card)
                                .clickable { go(Screen.Chat(chatKey)) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(chatKey, 40.dp, group = true)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(g.name, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (isBusy) Box(Modifier.size(7.dp).clip(CircleShape).background(BeeGreen))
                                }
                                Text(
                                    "${members.size} 位成员 · ${members.joinToString("、").ifBlank { "无成员" }}",
                                    color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(Ic.Chevron, null, tint = Sub, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // 6. One-Tap Specialist Bot Templates Carousel ("一键招聘专员")
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("一键招聘专员模板", color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("自动复用你的首个 Provider 预设（或 Agent 接口），点一下直接入职。", color = Sub, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HIVE_TEMPLATES.forEach { tpl ->
                            Column(
                                Modifier
                                    .width(205.dp)
                                    .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 18.dp, fallbackBg = Card)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tpl.name, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(ThemeAccent.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                        Text(tpl.badge, color = ThemeAccentPale, fontSize = 10.sp)
                                    }
                                }
                                Text(tpl.desc, color = Sub, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Box(
                                    Modifier.fillMaxWidth().pressBounce {
                                        val prov = store.providers.firstOrNull()
                                        val ag = store.agent.value
                                        val baseUrl = prov?.baseUrl ?: ag?.baseUrl ?: "https://openrouter.ai/api/v1"
                                        val model = ag?.model?.ifBlank { "openrouter/free" } ?: "openrouter/free"
                                        val nb = Bot(
                                            id = UUID.randomUUID().toString().take(8),
                                            name = tpl.name,
                                            prompt = tpl.prompt,
                                            baseUrl = baseUrl,
                                            apiKey = if (prov != null) "" else (ag?.apiKey ?: ""),
                                            model = model,
                                            isManager = tpl.isManager,
                                            allowInsecureHttp = prov?.allowInsecureHttp ?: ag?.allowInsecureHttp ?: false,
                                            providerId = prov?.id ?: ag?.providerId ?: "",
                                            skillIds = emptyList(),
                                            shellApproval = false
                                        )
                                        if (store.saveBot(nb)) {
                                            Toast.makeText(ctx, "「${tpl.name}」已入职！", Toast.LENGTH_SHORT).show()
                                            go(Screen.Chat("bot:${nb.id}"))
                                        }
                                    }
                                        .clip(RoundedCornerShape(12.dp)).background(PillWhite)
                                        .padding(vertical = 7.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text("一键招聘", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }

        ModelSheet(
            bot = modelBot,
            store = store,
            visible = showModelSheet,
            onDismiss = { showModelSheet = false },
            onPick = { m ->
                modelBot?.let { b ->
                    store.saveBot(b.copy(model = m))
                    Toast.makeText(ctx, "${b.name} 已切换为 $m", Toast.LENGTH_SHORT).show()
                }
            }
        )
        filesBot?.let { fb ->
            SandboxFilesSheet(store = store, botId = fb.id, visible = true, onDismiss = { filesBot = null })
        }
        memoryBot?.let { mb ->
            MemoryBrainSheet(store = store, botId = mb.id, botName = mb.name, visible = true, onDismiss = { memoryBot = null })
        }

        if (showImportDialog) {
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = { Text("导入 Bot 配置 JSON") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("粘贴导出的 JSON", importJson, { importJson = it }, singleLine = false, minLines = 5)
                        Text("自动复用你的首个 Provider 预设，不含外部密钥，安全可靠。", color = Sub, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    TextButton(enabled = importJson.isNotBlank(), onClick = {
                        val parsed = runCatching { JSONObject(importJson.trim()) }.getOrNull()
                        if (parsed == null || parsed.optString("name").isBlank()) {
                            Toast.makeText(ctx, "JSON 格式不正确或缺少 name 字段", Toast.LENGTH_SHORT).show()
                        } else {
                            val nb = importBotConfig(store, parsed, ctx)
                            if (nb != null) {
                                showImportDialog = false
                                Toast.makeText(ctx, "已导入「${nb.name}」", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, "导入失败:本地数据未能保存", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("导入") }
                },
                dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } }
            )
        }
    }
}

/**
 * v0.7.0 (F22): shareable Bot config, schema 2. Carries every non-secret field: prompt, model,
 * manager flag, approval setting, connection (Provider name, or base URL when there is none),
 * attached skills (full text, so another device can recreate them) and model capability prefs.
 * The API key is NEVER included.
 */
internal fun exportBotConfig(store: Store, b: Bot, ctx: Context): JSONObject {
    val prov = store.provider(b.providerId)
    val skills = org.json.JSONArray()
    store.skills.filter { it.id in b.skillIds }.forEach { sk ->
        skills.put(JSONObject().put("name", sk.name).put("description", sk.description).put("content", sk.content))
    }
    return JSONObject()
        .put("schemaVersion", 2)
        .put("kind", "bothive.bot")
        .put("name", b.name)
        .put("prompt", b.prompt)
        .put("model", b.model)
        .put("isManager", b.isManager)
        .put("shellApproval", b.shellApproval)
        .put("providerName", prov?.name ?: "")
        .put("baseUrl", if (prov == null) b.baseUrl else "")
        .put("allowInsecureHttp", prov?.allowInsecureHttp ?: b.allowInsecureHttp)
        .put("skills", skills)
        .put("multimodal", ModelPrefs.isMultimodal(b.id, ctx))
        .put("reasoningEffort", ModelPrefs.reasoningEffort(b.id, ctx))
}

/**
 * Imports schema 1 (name/prompt/model/isManager/shellApproval) and schema 2 configs. The connection
 * reuses a Provider with the same name when one exists, otherwise the first Provider; skills are
 * matched by name or recreated. A second manager is never created by import.
 */
internal fun importBotConfig(store: Store, o: JSONObject, ctx: Context): Bot? {
    val ag = store.agent.value
    val wantedProvider = o.optString("providerName").trim()
    val prov = store.providers.firstOrNull { wantedProvider.isNotEmpty() && it.name == wantedProvider }
        ?: store.providers.firstOrNull()
    val skillIds = mutableListOf<String>()
    val arr = o.optJSONArray("skills")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val name = s.optString("name").trim()
            if (name.isEmpty()) continue
            val existing = store.skills.firstOrNull { it.name == name }
            val id = existing?.id ?: UUID.randomUUID().toString().take(8).also { nid ->
                store.saveSkill(Skill(nid, name, s.optString("content").take(4_000), s.optString("description").take(200)))
            }
            if (id !in skillIds) skillIds.add(id)
        }
    }
    val baseUrl = o.optString("baseUrl").trim()
    val wantsManager = o.optBoolean("isManager", false) && store.bots.none { it.isManager }
    val nb = Bot(
        id = UUID.randomUUID().toString().take(8),
        name = nextDuplicateName(o.optString("name").trim(), store.bots.map { it.name }.toSet())
            .let { if (store.bots.any { b -> b.name == o.optString("name").trim() }) it else o.optString("name").trim() },
        prompt = o.optString("prompt", ""),
        baseUrl = when {
            prov != null -> prov.baseUrl
            baseUrl.isNotEmpty() -> baseUrl
            else -> ag?.baseUrl ?: "https://openrouter.ai/api/v1"
        },
        apiKey = if (prov != null) "" else (ag?.apiKey ?: ""),
        model = o.optString("model").ifBlank { ag?.model ?: "openrouter/free" },
        isManager = wantsManager,
        allowInsecureHttp = prov?.allowInsecureHttp ?: o.optBoolean("allowInsecureHttp", false),
        providerId = prov?.id ?: ag?.providerId ?: "",
        skillIds = skillIds,
        shellApproval = o.optBoolean("shellApproval", false)
    )
    if (!store.saveBot(nb)) return null
    if (o.has("multimodal")) ModelPrefs.setMultimodal(ctx, nb.id, o.optBoolean("multimodal", true))
    val effort = o.optString("reasoningEffort")
    if (effort.isNotBlank() && ModelPrefs.EFFORT_OPTIONS.any { it.first == effort }) ModelPrefs.setReasoningEffort(ctx, nb.id, effort)
    return nb
}
