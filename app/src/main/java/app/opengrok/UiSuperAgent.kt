package app.opengrok

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.7.1 Super-Agent Command Board ("超级工单"):
 *  - Live Animated Beehive Topology Graph (Router center -> orbiting Worker hexes with glowing pollen pulses).
 *  - Hive Debate Arena (Pro vs Con vs Judge multi-Bot structured debate writing verdict to Blackboard).
 *  - Hive Blackboard (Cross-Bot live shared semantic memory wall).
 *  - Persistent Ticket Kanban + Fast/Slow Path Tester + Watchdog & Spill Grep.
 */
@Composable
fun SuperAgentScreen(store: Store, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var tab by remember { mutableIntStateOf(0) } // 0=进行中, 1=待续/阻塞, 2=共享黑板, 3=已完成, 4=看门狗与Spill
    var showCreate by remember { mutableStateOf(false) }
    var showRouterTest by remember { mutableStateOf(false) }
    var showDebate by remember { mutableStateOf(false) }
    var showAddNote by remember { mutableStateOf(false) }
    var activeSpillId by remember { mutableStateOf<String?>(null) }

    val allTickets = SuperAgent.tickets.toList()
    val runningList = allTickets.filter { it.status == TicketStatus.RUNNING || it.status == TicketStatus.QUEUED }
    val pausedList = allTickets.filter { it.status == TicketStatus.PAUSED || it.status == TicketStatus.BLOCKED || it.status == TicketStatus.FAILED }
    val doneList = allTickets.filter { it.status == TicketStatus.DONE }
    val boardNotes = HiveBlackboard.notes.toList()
    val spills = remember(tab, allTickets.size) { ContextSpill.listRecent(24) }

    Column(Modifier.fillMaxSize()) {
        HivePageHeader(
            title = "超级工单 · Super-Agent",
            bix = when (tab) {
                2 -> BixAction.Blackboard
                4 -> BixAction.Watchdog
                else -> if (runningList.isNotEmpty()) BixAction.Router else BixAction.Ticket
            },
            onBack = { go(Screen.Home) },
            trailing = {
                SmallChip("+ 新工单") { showCreate = true }
            }
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Live Interactive Beehive Topology Canvas (Router -> Workers with glowing energy pulses!)
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 20.dp, fallbackBg = Card)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("活体蜂巢神经拓扑", color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            val chiefName = store.chiefId.value.takeIf { it.isNotBlank() }?.let { store.botName(it) } ?: "全局 Router"
                            Text("中心：$chiefName · 点任意节点直达对话", color = Sub, fontSize = 11.sp)
                        }
                        SmallChip("辩论赛") { showDebate = true }
                    }

                    HiveTopologyCanvas(store = store, runningTickets = runningList, onOpenBot = { botId ->
                        go(if (botId == AGENT_ID) Screen.AgentChat else Screen.Chat("bot:$botId"))
                    })

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricMiniCard("活跃工单", "${runningList.size}", BeeGreen, Modifier.weight(1f))
                        MetricMiniCard("黑板词条", "${boardNotes.size}", ThemeAccentPale, Modifier.weight(1f))
                        MetricMiniCard("FastPath", "${SuperAgent.fastPathSavedCount.intValue}", SelectBlue, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("派发工单", modifier = Modifier.weight(1f)) { showCreate = true }
                        PillButton("测试路由", filled = false, modifier = Modifier.weight(0.85f)) { showRouterTest = true }
                        PillButton("巡检", filled = false, modifier = Modifier.weight(0.75f)) {
                            val n = HiveWatchdog.scanOnce(store.busy.toSet(), System.currentTimeMillis())
                            Toast.makeText(
                                ctx,
                                if (n > 0) "Watchdog 已标记 $n 个异常工单为可续跑" else "全部 Worker 心跳正常",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }

            // 2. Kanban Filter Tabs + Bix Parabolic Shuttle Track
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HiveChip("进行中 (${runningList.size})", tab == 0) { tab = 0 }
                        HiveChip("待续/阻塞 (${pausedList.size})", tab == 1) { tab = 1 }
                        HiveChip("共享黑板 (${boardNotes.size})", tab == 2) { tab = 2 }
                        HiveChip("已完成 (${doneList.size})", tab == 3) { tab = 3 }
                        HiveChip("看门狗 & Spill", tab == 4) { tab = 4 }
                    }
                    BixTabFlightTrack(
                        selectedIndex = tab,
                        tabCount = 5,
                        poses = listOf(
                            BixAction.Router,
                            BixAction.Watchdog,
                            BixAction.Blackboard,
                            BixAction.Done,
                            BixAction.Spill
                        )
                    )
                }
            }

            // 3. Tab Body
            if (tab == 2) {
                // Hive Blackboard Tab
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("跨 Bot 共享语义黑板", color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        SmallChip("+ 贴一条") { showAddNote = true }
                    }
                }
                if (boardNotes.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 18.dp, fallbackBg = Card)
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BixScene(action = BixAction.Blackboard, compact = false)
                            Text("蜂巢黑板还是空的", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("任意 Bot 调用 board_write 写下的调研结论、接口参数或辩论裁决都会实时贴在这里，其他 Bot 可直接 board_read 读取。", color = Sub, fontSize = 12.sp)
                        }
                    }
                } else {
                    items(boardNotes, key = { it.key }) { note ->
                        Column(
                            Modifier.fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 16.dp, fallbackBg = Card)
                                .padding(13.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(note.key, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                HiveBadge(note.tag, ThemeAccent)
                            }
                            Text(note.value, color = Sub, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "作者：${if (note.authorBotId == "user") "你" else store.botName(note.authorBotId)} · ${fmt.format(Date(note.updatedMs))}",
                                    color = Sub.copy(alpha = 0.72f), fontSize = 10.sp, modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "擦除", color = BeeDanger, fontSize = 11.sp,
                                    modifier = Modifier.clickable { HiveBlackboard.remove(note.key) }.padding(4.dp)
                                )
                            }
                        }
                    }
                }
            } else if (tab == 0 || tab == 1 || tab == 3) {
                val shown = when (tab) {
                    0 -> runningList
                    1 -> pausedList
                    else -> doneList
                }.sortedBy { it.priority }
                if (shown.isEmpty()) {
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 18.dp, fallbackBg = Card)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BixScene(action = if (tab == 1) BixAction.Watchdog else BixAction.Ticket, compact = false)
                            Text("这一栏还没有工单", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("点「派发工单」即可持久化执行并订阅通知直通，或发起一场「辩论赛」让两只 Bot 正反交锋。", color = Sub, fontSize = 12.sp)
                        }
                    }
                } else {
                    items(shown, key = { it.id }) { t ->
                        TicketCard(
                            store = store,
                            ticket = t,
                            fmt = fmt,
                            onResume = {
                                SuperAgent.dispatchOrResume(store, t.id)
                                Toast.makeText(ctx, "已派发/续跑工单 ${t.id}", Toast.LENGTH_SHORT).show()
                                go(if (t.ownerBotId == AGENT_ID) Screen.AgentChat else Screen.Chat("bot:${t.ownerBotId}"))
                            },
                            onOpenChat = {
                                go(if (t.ownerBotId == AGENT_ID) Screen.AgentChat else Screen.Chat("bot:${t.ownerBotId}"))
                            },
                            onInspectSpill = { spId -> activeSpillId = spId }
                        )
                    }
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BixScene(action = BixAction.Watchdog, compact = true)
                        Text("Watchdog 看门狗日志", color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (SuperAgent.watchdogLog.isEmpty()) {
                    item { Text("暂无异常记录，蜂巢运行平稳。", color = Sub, fontSize = 12.sp) }
                } else {
                    items(SuperAgent.watchdogLog.take(15)) { ev ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 14.dp, fallbackBg = PILL)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val c = when (ev.level) {
                                "WARN" -> BeeDanger
                                "RECOVER" -> ThemeAccent
                                else -> BeeGreen
                            }
                            Box(Modifier.size(7.dp).clip(CircleShape).background(c))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ev.message, color = Fg, fontSize = 12.sp)
                                Text("${fmt.format(Date(ev.timeMs))} · ${ev.ticketId}", color = Sub, fontSize = 10.sp)
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BixScene(action = BixAction.Spill, compact = true)
                        Text("Inline & Spill 上下文归档 (${spills.size})", color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (spills.isEmpty()) {
                    item { Text("暂无溢写归档。工具输出超过 2,400 字时会自动溢写到磁盘，避免撑爆上下文。", color = Sub, fontSize = 12.sp) }
                } else {
                    items(spills, key = { it.id }) { sp ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 14.dp, fallbackBg = PILL)
                                .clickable { activeSpillId = sp.id }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${sp.id} · ${sp.toolName}", color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("${sp.totalLines} 行 · ${sp.totalChars} 字符 · ${fmt.format(Date(sp.createdMs))}", color = Sub, fontSize = 11.sp)
                            }
                            Text("检索 / 查看", color = ThemeAccentPale, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateTicketDialog(
            store = store,
            onDismiss = { showCreate = false },
            onCreated = { t, startNow ->
                showCreate = false
                if (startNow) {
                    SuperAgent.dispatchOrResume(store, t.id)
                    go(if (t.ownerBotId == AGENT_ID) Screen.AgentChat else Screen.Chat("bot:${t.ownerBotId}"))
                } else {
                    Toast.makeText(ctx, "工单 ${t.id} 已加入队列", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showDebate) {
        HiveDebateDialog(
            store = store,
            onDismiss = { showDebate = false },
            onLaunch = { topic, proId, conId, judgeId ->
                showDebate = false
                val t = SuperAgent.createTicket(
                    title = "辩论赛：${topic.take(18)}",
                    goal = "正方(${store.botName(proId)}) vs 反方(${store.botName(conId)})，由 ${store.botName(judgeId)} 综合裁决并写入黑板",
                    ownerBotId = judgeId,
                    priority = 1,
                    source = "arena"
                )
                SuperAgent.updateTicket(t.id, status = TicketStatus.RUNNING, checkpoint = "正反方并行立论已启动")
                HiveBlackboard.write("辩论议题:${topic.take(16)}", "正方：${store.botName(proId)} | 反方：${store.botName(conId)} | 裁判：${store.botName(judgeId)}", "辩论赛", "user")
                val proPrompt = "【蜂巢辩论赛 · 正方立场】议题：$topic\n请用 3 条最锋利的论据全力支持该方案，并在完成后用 board_write(key=\"正方论点:${topic.take(10)}\", value=你的核心观点, tag=\"辩论赛\") 贴上黑板。"
                val conPrompt = "【蜂巢辩论赛 · 反方立场】议题：$topic\n请站在魔鬼代言人角度用 3 条最犀利的反例质疑该方案的成本与风险，并在完成后用 board_write(key=\"反方论点:${topic.take(10)}\", value=你的核心反驳, tag=\"辩论赛\") 贴上黑板。"
                if (proId == AGENT_ID) store.sendAgent(proPrompt, emptyList()) else store.send("bot:$proId", proPrompt, emptyList())
                if (conId != proId) {
                    if (conId == AGENT_ID) store.sendAgent(conPrompt, emptyList()) else store.send("bot:$conId", conPrompt, emptyList())
                }
                Toast.makeText(ctx, "蜂巢辩论赛已开打！正反方论点将自动贴上共享黑板", Toast.LENGTH_LONG).show()
                tab = 2
            }
        )
    }

    if (showAddNote) {
        var nk by remember { mutableStateOf("") }
        var nv by remember { mutableStateOf("") }
        var nt by remember { mutableStateOf("事实") }
        AlertDialog(
            onDismissRequest = { showAddNote = false },
            title = { Text("贴一条到蜂巢共享黑板") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("标题 / Key（如：接口规范）", nk, { nk = it })
                    Field("内容（全部 Bot 均可通过 board_read 读取）", nv, { nv = it }, singleLine = false, minLines = 3)
                    Field("标签（如：调研 / 决策 / 规范）", nt, { nt = it })
                }
            },
            confirmButton = {
                TextButton(enabled = nk.isNotBlank() && nv.isNotBlank(), onClick = {
                    HiveBlackboard.write(nk, nv, nt, "user")
                    showAddNote = false
                    Toast.makeText(ctx, "已贴上共享黑板", Toast.LENGTH_SHORT).show()
                }) { Text("贴上") }
            },
            dismissButton = { TextButton(onClick = { showAddNote = false }) { Text("取消") } }
        )
    }

    if (showRouterTest) {
        var simSource by remember { mutableStateOf("com.tencent.mm") }
        var simPayload by remember { mutableStateOf("CI 构建失败，报错行号 182") }
        var resultText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRouterTest = false },
            title = { Text("测试 Fast / Slow Path 事件路由") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("模拟来源（App 包名 / Webhook）", simSource, { simSource = it })
                    Field("模拟消息内容", simPayload, { simPayload = it }, singleLine = false, minLines = 2)
                    if (resultText.isNotBlank()) {
                        Text(resultText, color = ThemeAccentPale, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val d = SuperRouter.routeEvent(
                        sourceKey = simSource,
                        payload = simPayload,
                        chiefBotId = store.chiefId.value,
                        fallbackBotId = AGENT_ID
                    )
                    val kindCn = when (d.kind) {
                        RouteKind.IGNORE_NOISE -> "🚫 IGNORE_NOISE（0 Token 拦截）"
                        RouteKind.DIRECT_WORKER -> "⚡ DIRECT_WORKER（直通 ${d.targetBotId} / 工单 ${d.ticketId}）"
                        RouteKind.ESCALATE_ROUTER -> "🧠 ESCALATE_ROUTER（升级至 ${d.targetBotId}）"
                    }
                    resultText = "$kindCn\n原因：${d.reason}"
                }) { Text("模拟投递") }
            },
            dismissButton = { TextButton(onClick = { showRouterTest = false }) { Text("关闭") } }
        )
    }

    activeSpillId?.let { spId ->
        var grepQuery by remember(spId) { mutableStateOf("") }
        val body = remember(spId, grepQuery) {
            ContextSpill.recall(spId, query = grepQuery, startLine = 1, maxLines = 100)
        }
        AlertDialog(
            onDismissRequest = { activeSpillId = null },
            title = { Text("Spill 原文 · $spId") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("按关键词检索（留空显示前 100 行）", grepQuery, { grepQuery = it })
                    Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        Text(body, color = Fg, fontSize = 11.sp, fontFamily = HiveFonts.Mono)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { activeSpillId = null }) { Text("关闭") } }
        )
    }
}

/**
 * Live Animated Beehive Topology Canvas:
 * Renders the central Router hex node surrounded by orbiting Worker Bot / Agent hex nodes.
 * Active Workers emit glowing animated pollen-energy pulses traveling along the neural links!
 */
@Composable
private fun HiveTopologyCanvas(
    store: Store,
    runningTickets: List<Ticket>,
    onOpenBot: (String) -> Unit
) {
    val workers = remember(store.bots.size, store.agent.value) {
        (store.bots.take(5).map { it.id to it.name } + listOfNotNull(store.agent.value?.let { AGENT_ID to it.name })).take(6)
    }
    val activeWorkerIds = remember(store.busy.toList(), runningTickets) {
        buildSet {
            store.busy.forEach { cid ->
                if (cid == AGENT_ID) add(AGENT_ID)
                else if (cid.startsWith("bot:")) add(cid.removePrefix("bot:"))
            }
            runningTickets.forEach { add(it.ownerBotId) }
        }
    }
    val accent = ThemeAccent
    val motion = GlassStyle.tier.value.motion
    val inf = rememberInfiniteTransition(label = "hive-topo")
    val pulse by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )
    val t = if (motion) pulse else 0.4f

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(172.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x88090B0F))
    ) {
        val wDp = maxWidth
        val hDp = maxHeight
        val cxDp = wDp / 2
        val cyDp = hDp / 2
        val rxDp = (wDp * 0.34f).coerceAtMost(112.dp)
        val ryDp = (hDp * 0.35f).coerceAtMost(58.dp)

        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val rx = rxDp.toPx()
            val ry = ryDp.toPx()

            fun hexPath(center: Offset, r: Float): Path = Path().apply {
                for (i in 0..5) {
                    val a = Math.toRadians((60 * i - 30).toDouble())
                    val x = center.x + (r * cos(a)).toFloat()
                    val y = center.y + (r * sin(a)).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }

            // Orbit ring
            drawCircle(color = Color.White.copy(alpha = 0.06f), radius = minOf(rx, ry), center = Offset(cx, cy), style = Stroke(1.dp.toPx()))

            // Links & flowing energy pulses from Router to each Worker
            workers.forEachIndexed { idx, (id, _) ->
                val angle = (2.0 * PI * idx / workers.size.coerceAtLeast(1)) - PI / 2.0
                val wx = cx + (rx * cos(angle)).toFloat()
                val wy = cy + (ry * sin(angle)).toFloat()
                val isActive = id in activeWorkerIds
                val linkColor = if (isActive) accent.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.16f)
                drawLine(linkColor, Offset(cx, cy), Offset(wx, wy), strokeWidth = if (isActive) 2.dp.toPx() else 1.dp.toPx())

                if (isActive) {
                    val p = (t + idx * 0.23f) % 1f
                    val px = cx + (wx - cx) * p
                    val py = cy + (wy - cy) * p
                    drawCircle(Color(0xFFC8FF00), radius = 3.5.dp.toPx(), center = Offset(px, py))
                    drawCircle(accent.copy(alpha = 0.32f), radius = 7.dp.toPx(), center = Offset(px, py))
                }

                // Worker hex node
                drawPath(hexPath(Offset(wx, wy), 15.dp.toPx()), color = if (isActive) accent.copy(alpha = 0.22f) else Color(0xFF171B24))
                drawPath(hexPath(Offset(wx, wy), 15.dp.toPx()), color = if (isActive) accent else Color.White.copy(alpha = 0.28f), style = Stroke(1.2.dp.toPx()))
            }

            // Central Router hex
            drawPath(hexPath(Offset(cx, cy), 22.dp.toPx()), color = accent.copy(alpha = 0.20f))
            drawPath(hexPath(Offset(cx, cy), 22.dp.toPx()), color = accent, style = Stroke(1.6.dp.toPx()))
        }

        // Center Bix Router badge
        Box(
            Modifier
                .align(Alignment.Center)
                .size(34.dp),
            contentAlignment = Alignment.Center
        ) {
            BixScene(action = if (activeWorkerIds.isNotEmpty()) BixAction.Router else BixAction.Idle, compact = true)
        }

        // Interactive Worker labels positioned over each hex node
        workers.forEachIndexed { idx, (id, name) ->
            val angle = (2.0 * PI * idx / workers.size.coerceAtLeast(1)) - PI / 2.0
            val xOff = cxDp + (rxDp * cos(angle).toFloat()) - 28.dp
            val yOff = cyDp + (ryDp * sin(angle).toFloat()) - 14.dp
            val isActive = id in activeWorkerIds
            Box(
                Modifier
                    .offset(x = xOff, y = yOff)
                    .size(56.dp, 28.dp)
                    .clickable { onOpenBot(id) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name.take(4),
                    color = if (isActive) Color(0xFFFFE88A) else Fg,
                    fontSize = 10.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HiveDebateDialog(
    store: Store,
    onDismiss: () -> Unit,
    onLaunch: (topic: String, proId: String, conId: String, judgeId: String) -> Unit
) {
    val candidates = remember(store.bots.size, store.agent.value) {
        store.bots.map { it.id to it.name } + listOfNotNull(store.agent.value?.let { AGENT_ID to it.name })
    }
    var topic by remember { mutableStateOf("") }
    var proId by remember { mutableStateOf(candidates.getOrNull(0)?.first ?: AGENT_ID) }
    var conId by remember { mutableStateOf(candidates.getOrNull(1)?.first ?: proId) }
    var judgeId by remember { mutableStateOf(store.chiefId.value.ifBlank { candidates.lastOrNull()?.first ?: AGENT_ID }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BixScene(action = BixAction.Debate, compact = true)
                Text("蜂巢圆桌辩论赛")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("输入纠结的议题（如：选方案 A 还是 B）", topic, { topic = it }, singleLine = false, minLines = 2)
                Text("正方（全力支持）", color = BeeGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    candidates.forEach { (id, name) -> HiveChip(name, proId == id) { proId = id } }
                }
                Text("反方（犀利拆台）", color = BeeDanger, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    candidates.forEach { (id, name) -> HiveChip(name, conId == id) { conId = id } }
                }
                Text("裁判 / 总参谋", color = ThemeAccentPale, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    candidates.forEach { (id, name) -> HiveChip(name, judgeId == id) { judgeId = id } }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = topic.isNotBlank() && candidates.isNotEmpty(), onClick = {
                onLaunch(topic.trim(), proId, conId, judgeId)
            }) { Text("开辩！") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun MetricMiniCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 14.dp, fallbackBg = PILL)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Sub, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun TicketCard(
    store: Store,
    ticket: Ticket,
    fmt: SimpleDateFormat,
    onResume: () -> Unit,
    onOpenChat: () -> Unit,
    onInspectSpill: (String) -> Unit
) {
    val badgeColor = when (ticket.status) {
        TicketStatus.RUNNING -> BeeGreen
        TicketStatus.QUEUED -> SelectBlue
        TicketStatus.PAUSED, TicketStatus.BLOCKED -> ThemeAccent
        TicketStatus.FAILED -> BeeDanger
        TicketStatus.DONE -> Sub
    }
    Column(
        Modifier
            .fillMaxWidth()
            .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 18.dp, fallbackBg = Card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(badgeColor))
            Spacer(Modifier.width(8.dp))
            Text(ticket.title, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (ticket.priority == 1) {
                HiveBadge("P1 紧急", BeeDanger)
                Spacer(Modifier.width(4.dp))
            }
            HiveBadge(ticket.status.label, badgeColor)
        }
        Text(
            "${ticket.id} · Worker：${store.botName(ticket.ownerBotId)}" +
                (if (ticket.sourceKey.isNotBlank()) " · 订阅：${ticket.sourceKey}" else "") +
                " · ${fmt.format(Date(ticket.updatedMs))}",
            color = Sub, fontSize = 11.sp
        )
        Text(ticket.goal, color = Fg, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)

        if (ticket.checkpoints.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PILL).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text("检查点进度 (${ticket.checkpoints.size})", color = ThemeAccentPale, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                ticket.checkpoints.takeLast(3).forEach { cp ->
                    Text("✓ $cp", color = Sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (ticket.lastError.isNotBlank()) {
            Text(ticket.lastError, color = BeeDanger, fontSize = 11.sp)
        }

        if (ticket.spillIds.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ticket.spillIds.forEach { spId ->
                    SmallChip("Spill: $spId") { onInspectSpill(spId) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ticket.status != TicketStatus.DONE) {
                SmallChip(if (ticket.status == TicketStatus.PAUSED || ticket.status == TicketStatus.FAILED) "断点续跑" else "立即执行") {
                    onResume()
                }
                SmallChip("标记验收") {
                    SuperAgent.updateTicket(ticket.id, status = TicketStatus.DONE, checkpoint = "用户已手动验收完成")
                }
            }
            SmallChip("进会话") { onOpenChat() }
            Spacer(Modifier.weight(1f))
            Text(
                "删除", color = BeeDanger, fontSize = 11.sp,
                modifier = Modifier.clickable { SuperAgent.deleteTicket(ticket.id) }.padding(6.dp)
            )
        }
    }
}

@Composable
private fun CreateTicketDialog(
    store: Store,
    onDismiss: () -> Unit,
    onCreated: (Ticket, Boolean) -> Unit
) {
    val candidates = remember(store.bots.size, store.agent.value) {
        store.bots.map { it.id to it.name } + listOfNotNull(store.agent.value?.let { AGENT_ID to "${it.name} (设备 Agent)" })
    }
    var title by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var sourceKey by remember { mutableStateOf("") }
    var priority by remember { mutableIntStateOf(2) }
    var ownerId by remember { mutableStateOf(store.chiefId.value.ifBlank { candidates.firstOrNull()?.first ?: AGENT_ID }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建持久化超级工单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("工单标题（如：巡检 GitHub CI 并修 Bug）", title, { title = it })
                Field("详细目标与验收标准", goal, { goal = it }, singleLine = false, minLines = 3)
                Field("可选 Fast Path 通知订阅键（App 名或关键词）", sourceKey, { sourceKey = it })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("优先级", color = Sub, fontSize = 12.sp)
                    HiveChip("P1 紧急", priority == 1) { priority = 1 }
                    HiveChip("P2 普通", priority == 2) { priority = 2 }
                    HiveChip("P3 低", priority == 3) { priority = 3 }
                }
                Text("指派责任 Worker", color = Sub, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    candidates.forEach { (id, name) ->
                        HiveChip(name, ownerId == id) { ownerId = id }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = goal.isNotBlank(), onClick = {
                val t = SuperAgent.createTicket(
                    title = title.ifBlank { goal.take(24) },
                    goal = goal,
                    ownerBotId = ownerId,
                    priority = priority,
                    source = "user",
                    sourceKey = sourceKey
                )
                onCreated(t, true)
            }) { Text("立单并执行") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
