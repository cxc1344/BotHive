package app.opengrok

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v0.7.2 Personal Agent Hub ("个人智能体中枢"):
 *  - Tab 0: Proactive Goals & Draft-First Check-ins (Xiaodou + Cue style).
 *  - Tab 1: Portable Provenance Memory Pod (category, source, stated-vs-inferred, 1-tap migration).
 *  - Tab 2: Notification Privacy Fence (protects sensitive apps from leaking into SuperRouter).
 *  - Tab 3: Per-Bot Wallet Cap (Cue-style daily token budget envelope).
 */
@Composable
fun PersonalHubScreen(store: Store, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var showAddGoal by remember { mutableStateOf(false) }
    var showAddMem by remember { mutableStateOf(false) }
    var showMigrateMem by remember { mutableStateOf(false) }
    var showAddPkg by remember { mutableStateOf(false) }
    var memFilter by remember { mutableStateOf<MemoryCategory?>(null) }

    val goals = PersonalAgent.goals.toList()
    val memories = MemoryPod.records.toList().filter { memFilter == null || it.category == memFilter }
    val fenceMode = PersonalAgent.fenceMode.value
    val activeFenceList = if (fenceMode == FenceMode.BLOCKLIST) PersonalAgent.blockedPackages.toList()
    else PersonalAgent.allowedPackages.toList()

    Column(Modifier.fillMaxSize()) {
        HivePageHeader(
            title = "个人智能体中枢",
            bix = when (tab) {
                0 -> BixAction.GoalFlag
                1 -> BixAction.MemoryPod
                2 -> BixAction.PrivacyFence
                else -> BixAction.Budget
            },
            onBack = { go(Screen.Home) },
            trailing = {
                when (tab) {
                    0 -> SmallChip("+ 新目标") { showAddGoal = true }
                    1 -> SmallChip("搬家") { showMigrateMem = true }
                    2 -> SmallChip("+ 包名") { showAddPkg = true }
                    else -> SmallChip("PC 电脑") { go(Screen.HiveComputer) }
                }
            }
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HiveChip("主动目标 (${goals.size})", tab == 0) { tab = 0 }
                        HiveChip("记忆档案舱 (${MemoryPod.records.size})", tab == 1) { tab = 1 }
                        HiveChip("隐私围栏 (${PersonalAgent.blockedCount.intValue}拦)", tab == 2) { tab = 2 }
                        HiveChip("Bot 预算信封", tab == 3) { tab = 3 }
                    }
                    BixTabFlightTrack(
                        selectedIndex = tab,
                        tabCount = 4,
                        poses = listOf(BixAction.GoalFlag, BixAction.MemoryPod, BixAction.PrivacyFence, BixAction.Budget)
                    )
                }
            }

            if (tab == 0) {
                item {
                    HiveSection(
                        title = "「眼里有活儿」长期目标与主动 Check-in",
                        bix = BixAction.GoalFlag,
                        subtitle = "把长期挂念建档后，随时点「备草稿」让蜂巢结合记忆舱提前准备行动草案，等你拍板再动手。"
                    ) {
                        PillButton("+ 添加一个长期目标", modifier = Modifier.fillMaxWidth()) { showAddGoal = true }
                    }
                }
                if (goals.isEmpty()) {
                    item {
                        Text("还没有长期目标。试试添加“坚持 11 点前睡觉”或“筹备下月研学/出行”。", color = Sub, fontSize = 12.sp)
                    }
                } else {
                    items(goals, key = { it.id }) { g ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 16.dp, fallbackBg = Card)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.title, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                SmallChip("每 ${g.cadenceHours}h") { PersonalAgent.toggleGoal(g.id) }
                            }
                            if (g.notes.isNotBlank()) Text(g.notes, color = Sub, fontSize = 12.sp)
                            if (g.lastDraft.isNotBlank()) Text(g.lastDraft, color = BeeGreen, fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PillButton("立即 Check-in（备草稿）", modifier = Modifier.weight(1f)) {
                                    val tk = PersonalAgent.triggerCheckIn(store, g)
                                    Toast.makeText(ctx, "已派发工单 ${tk.id} 准备行动草案", Toast.LENGTH_SHORT).show()
                                }
                                SmallChip("删除") { PersonalAgent.removeGoal(g.id) }
                            }
                        }
                    }
                }
            } else if (tab == 1) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 16.dp, fallbackBg = Card)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("可溯源记忆档案舱", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            SmallChip("+ 记一条") { showAddMem = true }
                        }
                        Text("严格区分「用户明说」与「模型推测」；「临时情绪」不会误判成长期目标。支持一键导入其他 AI 的人物小传。", color = Sub, fontSize = 11.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HiveChip("全部", memFilter == null) { memFilter = null }
                            MemoryCategory.entries.forEach { cat ->
                                HiveChip(cat.label, memFilter == cat) { memFilter = cat }
                            }
                        }
                    }
                }
                if (memories.isEmpty()) {
                    item {
                        Text("暂无匹配记忆，可点「+ 记一条」或右上角「搬家」从其他 AI 一键导入。", color = Sub, fontSize = 12.sp)
                    }
                } else {
                    items(memories, key = { it.id }) { m ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 14.dp, fallbackBg = Card)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    m.category.label,
                                    color = Ink,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(ThemeAccent)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Text(
                                    m.confidence.label,
                                    color = if (m.confidence == MemoryConfidence.STATED) BeeGreen else Color(0xFFFFB020),
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0x18FFFFFF))
                                        .clickable { MemoryPod.toggleConfidence(m.id) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Text("来源：${m.source}", color = Sub, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                Text(
                                    "擦除",
                                    color = BeeDanger,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable { MemoryPod.delete(m.id) }
                                )
                            }
                            Text(m.content, color = Fg, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                }
            } else if (tab == 2) {
                item {
                    HiveSection(
                        title = "通知隐私围栏（防隐私消息越界入脑）",
                        bix = BixAction.PrivacyFence,
                        subtitle = "默认拦截微信、QQ、支付宝、Signal、短信及银行类通知进入 SuperRouter，彻底杜绝私密聊天被吸入记忆。"
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HiveChip("敏感黑名单", fenceMode == FenceMode.BLOCKLIST) { PersonalAgent.setFenceMode(FenceMode.BLOCKLIST) }
                            HiveChip("严格白名单", fenceMode == FenceMode.ALLOWLIST) { PersonalAgent.setFenceMode(FenceMode.ALLOWLIST) }
                        }
                        Text(
                            "本次运行已拦截 ${PersonalAgent.blockedCount.intValue} 条隐私通知",
                            color = BeeGreen,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                items(activeFenceList, key = { it }) { pkgName ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 12.dp, fallbackBg = Card)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(pkgName, color = Fg, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            "移除",
                            color = BeeDanger,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                if (fenceMode == FenceMode.BLOCKLIST) PersonalAgent.removeBlockedPackage(pkgName)
                                else PersonalAgent.removeAllowedPackage(pkgName)
                            }
                        )
                    }
                }
            } else {
                item {
                    HiveSection(
                        title = "Bot 专属预算信封（Cue 式日限额）",
                        bix = BixAction.Budget,
                        subtitle = "为每个数字员工 Bot 单独设定每日 Token 预算上限，防止后台失控烧额度。"
                    ) {}
                }
                val botEntries = listOf(AGENT_ID to (store.agent.value?.name ?: "设备 Agent")) +
                    store.bots.map { it.id to it.name }
                items(botEntries, key = { it.first }) { (bid, bname) ->
                    val w = PersonalAgent.walletFor(bid)
                    val cap = w?.dailyTokenCap ?: 120_000
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 14.dp, fallbackBg = Card)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(bname, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("日上限 ${cap / 1000}K tok", color = ThemeAccentPale, fontSize = 12.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(50_000 to "50K", 120_000 to "120K", 300_000 to "300K", 1_000_000 to "1M").forEach { (v, lbl) ->
                                HiveChip(lbl, cap == v) { PersonalAgent.setWalletCap(bid, v) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddGoal) {
        var title by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        var hours by remember { mutableStateOf("24") }
        AlertDialog(
            onDismissRequest = { showAddGoal = false },
            containerColor = Card,
            title = { Text("新建长期目标", color = Fg) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("目标名称（如：准备英语四六级 / 坚持早睡）", title, { title = it })
                    Field("跟进节奏（小时，默认 24）", hours, { hours = it })
                    Field("补充背景（可选）", notes, { notes = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) {
                        PersonalAgent.addGoal(title, hours.toIntOrNull() ?: 24, notes)
                        showAddGoal = false
                    }
                }) { Text("建档", color = ThemeAccent) }
            },
            dismissButton = { TextButton(onClick = { showAddGoal = false }) { Text("取消", color = Sub) } }
        )
    }

    if (showAddMem) {
        var content by remember { mutableStateOf("") }
        var cat by remember { mutableStateOf(MemoryCategory.FACT) }
        AlertDialog(
            onDismissRequest = { showAddMem = false },
            containerColor = Card,
            title = { Text("写入记忆档案舱", color = Fg) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MemoryCategory.entries.forEach { c -> HiveChip(c.label, cat == c) { cat = c } }
                    }
                    Field("记忆内容", content, { content = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (content.isNotBlank()) {
                        MemoryPod.add(content, cat, source = "手动录入", confidence = MemoryConfidence.STATED)
                        showAddMem = false
                    }
                }) { Text("保存", color = ThemeAccent) }
            },
            dismissButton = { TextButton(onClick = { showAddMem = false }) { Text("取消", color = Sub) } }
        )
    }

    if (showMigrateMem) {
        var paste by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMigrateMem = false },
            containerColor = Card,
            title = { Text("一键记忆搬家（导入 / 导出）", color = Fg) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("可直接粘贴 BotHive 记忆包 JSON，或从 ChatGPT / 小豆 / Gemini 复制的人物小传列表，自动拆分建档。", color = Sub, fontSize = 11.sp)
                    Field("粘贴要导入的记忆文本或 JSON", paste, { paste = it })
                    PillButton("复制当前记忆舱 JSON 到剪贴板", filled = false, modifier = Modifier.fillMaxWidth()) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("BotHiveMemoryPod", MemoryPod.exportPortableJson()))
                        Toast.makeText(ctx, "已复制 ${MemoryPod.records.size} 条记忆到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = MemoryPod.importFromText(paste)
                    Toast.makeText(ctx, if (n > 0) "成功搬家导入 $n 条记忆" else "未解析到有效记忆", Toast.LENGTH_SHORT).show()
                    if (n > 0) showMigrateMem = false
                }) { Text("导入", color = ThemeAccent) }
            },
            dismissButton = { TextButton(onClick = { showMigrateMem = false }) { Text("关闭", color = Sub) } }
        )
    }

    if (showAddPkg) {
        var pkgInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddPkg = false },
            containerColor = Card,
            title = { Text(if (fenceMode == FenceMode.BLOCKLIST) "添加拦截 App 包名" else "添加放行 App 包名", color = Fg) },
            text = { Field("如 com.tencent.mm 或 com.github.android", pkgInput, { pkgInput = it }) },
            confirmButton = {
                TextButton(onClick = {
                    if (pkgInput.isNotBlank()) {
                        if (fenceMode == FenceMode.BLOCKLIST) PersonalAgent.addBlockedPackage(pkgInput)
                        else PersonalAgent.addAllowedPackage(pkgInput)
                        showAddPkg = false
                    }
                }) { Text("添加", color = ThemeAccent) }
            },
            dismissButton = { TextButton(onClick = { showAddPkg = false }) { Text("取消", color = Sub) } }
        )
    }
}
