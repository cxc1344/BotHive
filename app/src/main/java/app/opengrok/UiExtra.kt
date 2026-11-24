package app.opengrok

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.opengrok.ThemeAccent
import app.opengrok.ThemeAccentPale
import app.opengrok.ThemeColor
import app.opengrok.ThemePreset

@Composable
fun ProjectCard(store: Store, pid: String, go: (Screen) -> Unit) {
    val p = store.projects.firstOrNull { it.id == pid }
    if (p == null) {
        Text("(项目记录已不存在)", color = Sub, fontSize = 12.sp)
        return
    }
    Column(
        Modifier.fillMaxWidth().harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 18.dp, fallbackBg = Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val projBix = when (p.stage) {
                Stage.PLANNING, Stage.EXECUTING, Stage.REVIEWING, Stage.REPORTING -> BixAction.DrillingSquad
                Stage.AWAIT_CONFIRM -> BixAction.ApprovalGuard
                Stage.DONE -> BixAction.Done
                else -> BixAction.Idle
            }
            BixScene(action = projBix, compact = true)
            Spacer(Modifier.width(8.dp))
            Text(p.title, color = Fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Line).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(stageLabel(p.stage), color = Fg, fontSize = 12.sp)
            }
        }
        Text(p.request, color = Sub, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (p.note.isNotBlank()) Text(p.note, color = Sub, fontSize = 13.sp)
        p.assignments.forEach { a ->
            val dm = store.dmId(p.managerId, a.botId)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg)
                    .clickable { go(Screen.Chat(dm)) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar("bot:${a.botId}", 32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(store.botName(a.botId), color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(a.task, color = Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Text(statusLabel(a.status), color = Sub, fontSize = 12.sp)
            }
        }
        if (p.assignments.isNotEmpty()) Text("点员工可查看他与总经理的全程私聊和工具调用", color = Sub, fontSize = 11.sp)
        when (p.stage) {
            Stage.AWAIT_CONFIRM -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("确认派工", modifier = Modifier.weight(1.3f)) { Pipeline.confirm(store, pid) }
                PillButton("重新规划", filled = false, modifier = Modifier.weight(1.2f)) { Pipeline.replan(store, pid) }
                PillButton("取消", filled = false, modifier = Modifier.weight(0.8f)) { Pipeline.cancel(store, pid) }
            }
            Stage.PLANNING, Stage.EXECUTING, Stage.REVIEWING, Stage.REPORTING ->
                PillButton("终止项目", filled = false, modifier = Modifier.fillMaxWidth()) { Pipeline.cancel(store, pid) }
            else -> {}
        }
    }
}

@Composable
fun SettingsScreen(store: Store, go: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<String, List<Pair<Boolean, String>>>() }
    val running = remember { mutableStateListOf<String>() }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val scrollState = rememberScrollState()
    val headerBlurFraction by remember(scrollState) {
        derivedStateOf { ((scrollState.value - 8f) / 48f).coerceIn(0f, 1f) }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, top = 68.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            store.persistenceError.value?.let {
                Text(it, color = BeeDanger, fontSize = 12.sp)
            }
            store.loadNotice.value?.let {
                Text(it, color = BeeDanger, fontSize = 12.sp)
                SmallChip("知道了") { store.dismissLoadNotice() }
            }
            // v0.7.0「蜂巢自治」: every hub page has Bix in the matching pose.
            Text("蜂巢自治", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            HiveHubRow(BixAction.Sentinel, "权限与哨兵", "四档规则、哨兵蜂审查、受保护应用、凭据保险箱") { go(Screen.Policy) }
            HiveHubRow(BixAction.Alarm, "例行任务", "定时 / 通知触发 / Webhook,测试与运行记录") { go(Screen.Routines) }
            HiveHubRow(BixAction.Budget, "用量与预算", "每只 Bot 的 token 用量、每日预算、估算费用") { go(Screen.Usage) }
            HiveHubRow(BixAction.Briefing, "蜂巢中枢", "总参谋、蜂巢早报、示教录制") { go(Screen.HiveCenter) }
            SettingsDivider()
            GlassTierCard()
            SettingsDivider()
            ThemeColorCard()
            SettingsDivider()
            BixBadgeCard()
            SettingsDivider()
            Text("Provider 预设", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("把常用服务商的 Base URL 与 Key 存成预设,建 Bot / Agent 时直接选用并从其模型列表点选,免去反复粘贴。Key 用设备 Keystore 加密保存。", color = Sub, fontSize = 12.sp)
            if (store.providers.isEmpty()) Text("还没有预设", color = Sub)
            store.providers.forEach { p ->
                val usedBy = store.bots.count { it.providerId == p.id } + if (store.agent.value?.providerId == p.id) 1 else 0
                SettingsRow(onClick = { go(Screen.EditProvider(p.id)) }) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = Fg, fontWeight = FontWeight.SemiBold)
                        Text(p.baseUrl + if (usedBy > 0) " · $usedBy 处在用" else "", color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Ic.Edit, null, tint = Sub)
                }
            }
            PillButton("添加 Provider", filled = false, modifier = Modifier.fillMaxWidth()) { go(Screen.EditProvider(null)) }
            SettingsDivider()
            Text("技能包", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("一段可复用的指令(规范、风格、领域知识),在 Bot / Agent 的编辑页勾选后会拼进它的 System Prompt。", color = Sub, fontSize = 12.sp)
            if (store.skills.isEmpty()) Text("还没有技能包", color = Sub)
            store.skills.forEach { sk ->
                SettingsRow(onClick = { go(Screen.EditSkill(sk.id)) }) {
                    Column(Modifier.weight(1f)) {
                        Text(sk.name, color = Fg, fontWeight = FontWeight.SemiBold)
                        Text(sk.description.ifBlank { sk.content }, color = Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Ic.Edit, null, tint = Sub)
                }
            }
            PillButton("新建技能包", filled = false, modifier = Modifier.fillMaxWidth()) { go(Screen.EditSkill(null)) }
            SettingsDivider()
            Text("MCP 服务", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("接入远程 MCP(Model Context Protocol)服务后,它的工具会出现在所有 Bot 与 Agent 的工具列表里,和内置工具一起被调用。", color = Sub, fontSize = 12.sp)
            if (store.mcpServers.isEmpty()) Text("还没有 MCP 服务", color = Sub)
            store.mcpServers.forEach { sv ->
                SettingsRow(onClick = { go(Screen.EditMcp(sv.id)) }) {
                    Column(Modifier.weight(1f)) {
                        Text(sv.name, color = Fg, fontWeight = FontWeight.SemiBold)
                        Text(sv.url + if (sv.enabled) "" else " · 已停用", color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Ic.Edit, null, tint = Sub)
                }
            }
            PillButton("添加 MCP 服务", filled = false, modifier = Modifier.fillMaxWidth()) { go(Screen.EditMcp(null)) }
            SettingsDivider()
            Text("例行任务", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("共 ${store.routines.size} 个,其中 ${store.routines.count { it.paused }} 个已暂停。任务在 App 进程活着时触发,被系统杀掉期间到点的任务会顺延到下次打开;建议同时开启下面的后台保活。", color = Sub, fontSize = 12.sp)
            PillButton("管理例行任务", filled = false, modifier = Modifier.fillMaxWidth()) { go(Screen.Routines) }
            SettingsDivider()
            Text("后台保活", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("开启后会有一个常驻通知的前台服务守着进程:例行任务能按时触发、主动消息通道不断线;被划掉后台时会自动尝试拉起,重启手机后也会自启。最终能不能活下来还要看系统放行——vivo 等机型请再给下面两项权限。", color = Sub, fontSize = 12.sp)
            run {
                val ctx = LocalContext.current
                var kaOn by remember { mutableStateOf(KeepAlive.isEnabled(ctx)) }
                fun notifOk() = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                var notifGranted by remember { mutableStateOf(notifOk()) }
                var batteryExempt by remember { mutableStateOf(KeepAlive.isBatteryExempt(ctx)) }
                // v0.7.0 (F5): grants made in system settings show up as soon as the user comes back.
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
                        if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            notifGranted = notifOk()
                            batteryExempt = KeepAlive.isBatteryExempt(ctx)
                            kaOn = KeepAlive.isEnabled(ctx)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }
                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
                    notifGranted = g
                    // After a permanent denial the dialog no longer appears: send the user to settings.
                    if (!g && Build.VERSION.SDK_INT >= 33) {
                        val act = ctx as? android.app.Activity
                        val canAsk = act?.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) ?: false
                        if (!canAsk) runCatching {
                            ctx.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("保活服务", color = Fg, fontSize = 15.sp)
                        // v0.7.0 (F6): report what the service actually does, not what the switch says.
                        val status = when {
                            !kaOn -> "未开启"
                            KeepAlive.running.value -> "运行中 · " + BixVoice.KEEP_ALIVE_TITLE
                            else -> "已开启但系统没让它启动,请给下面两项权限后重开 App"
                        }
                        Text(status, color = if (kaOn && !KeepAlive.running.value) BeeDanger else Sub, fontSize = 12.sp)
                    }
                    LiquidSwitch(checked = kaOn, onCheckedChange = { on ->
                        kaOn = on
                        KeepAlive.setEnabled(ctx, on)
                    })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (batteryExempt) "电池豁免 ✓" else "电池优化豁免", filled = false, modifier = Modifier.weight(1f)) {
                        runCatching { ctx.startActivity(KeepAlive.batteryExemptIntent(ctx)) }
                    }
                    PillButton("自启动设置", filled = false, modifier = Modifier.weight(1f)) {
                        runCatching { ctx.startActivity(KeepAlive.autoStartIntent(ctx)) }
                    }
                }
                if (!notifGranted) PillButton("开启通知权限(保活通知需要)", filled = false, modifier = Modifier.fillMaxWidth()) {
                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            SettingsDivider()
            Text("Linux 环境", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("每个 Bot 与 Agent 拥有独立的 Ubuntu 24.04 文件系统和工作目录。首次 shell 执行会下载约 29 MB 基础系统,并自动预装 python3、nodejs、git 等常用软件(还需再下载上百 MB,建议在 Wi-Fi 下首次使用)。PRoot 是用户态 Linux 环境,不是完整虚拟机或强安全边界。", color = Sub, fontSize = 12.sp)
            SettingsDivider()
            Text("连接自检", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("检查接口连通性、/models 拉取、对话接口、沙盒状态与 Key 加密存储", color = Sub, fontSize = 12.sp)
            val checkTargets = store.bots + listOfNotNull(store.agent.value)
            if (checkTargets.isEmpty()) Text("还没有 Bot", color = Sub)
            checkTargets.forEach { b ->
                Column(
                    Modifier.fillMaxWidth().harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 16.dp, fallbackBg = Card).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar("bot:${b.id}", 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.name, color = Fg, fontWeight = FontWeight.SemiBold)
                            Text(b.model, color = Sub, fontSize = 12.sp)
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(16.dp)).background(ThemeAccent)
                                .clickable(enabled = b.id !in running) {
                                    running.add(b.id)
                                    scope.launch {
                                        results[b.id] = try { SelfCheck.run(store, b) } catch (e: Exception) { listOf(false to "自检异常:${e.message}") }
                                        running.remove(b.id)
                                    }
                                }.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) { Text(if (b.id in running) "检测中…" else "自检", color = Ink, fontSize = 13.sp) }
                    }
                    results[b.id]?.forEach { (ok, msg) ->
                        Text((if (ok) "通过  " else "失败  ") + msg,
                            color = if (ok) Fg else BeeDanger, fontSize = 12.sp)
                    }
                }
            }
            SettingsDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("安全拦截记录", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (store.guardLog.isNotEmpty()) Text("清空", color = Sub, modifier = Modifier.clickable {
                    store.guardLog.clear(); store.save()
                }.padding(8.dp))
            }
            if (store.guardLog.isEmpty()) Text("暂无。Bot 的 shell 命令执行前会被检查,危险命令会被拦下并记录在这里。", color = Sub, fontSize = 12.sp)
            store.guardLog.reversed().take(50).forEach { e ->
                Column(Modifier.fillMaxWidth().harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 12.dp, fallbackBg = Card).padding(12.dp)) {
                    Text("${fmt.format(Date(e.time))} · ${store.botName(e.botId)} · ${e.reason}", color = Sub, fontSize = 12.sp)
                    Text(e.cmd, color = Fg, fontSize = 12.sp, fontFamily = HiveFonts.Mono, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("BotHive 0.6.5", color = Sub, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .drawWithContent {
                    if (!AppBackdrop.isCapturing && headerBlurFraction > 0.01f) {
                        drawRect(
                            Brush.verticalGradient(
                                0f to Bg.copy(alpha = 0.70f * headerBlurFraction),
                                0.72f to Bg.copy(alpha = 0.24f * headerBlurFraction),
                                1f to Color.Transparent
                            )
                        )
                    }
                    drawContent()
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card)) {
                IconButton(onClick = { go(Screen.Home) }, modifier = Modifier.fillMaxSize()) { Icon(Ic.Back, null, tint = Fg, modifier = Modifier.size(20.dp)) }
            }
            Text("设置", style = HiveSerif.SheetTitle(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Spacer(Modifier.width(40.dp))
        }
    }
}

/** Section separator in the settings card flow (STUDY §2.8). */
@Composable
private fun SettingsDivider() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .height(1.dp).background(Line)
    )
}

/** One tappable settings row on a #20201E card with generous padding (STUDY §2.8). */
/** v0.7.0: settings entry to a「蜂巢自治」page, fronted by Bix in the matching pose. */
@Composable
private fun HiveHubRow(bix: BixAction, title: String, subtitle: String, onClick: () -> Unit) {
    SettingsRow(onClick = onClick) {
        BixScene(action = bix, compact = true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Fg, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Ic.Chevron, null, tint = Sub, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SettingsRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = UiTokens.RadiusCard, fallbackBg = Card)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * "视觉效果" picker, v0.7.0: an iOS-27-style continuous glass intensity slider (纯色 → 极透) with
 * three quick presets (不要 / 中等 / 最高) and a live preview. The tier used by existing callers is
 * derived from the slider. Battery saver / system "remove animations" temporarily cap the intensity.
 */
@Composable
fun GlassTierCard() {
    val context = LocalContext.current
    val tier = GlassStyle.tier.value
    val userValue = GlassStyle.intensity.floatValue
    Column(
        Modifier.fillMaxWidth().harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = UiTokens.RadiusCard, fallbackBg = Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("视觉效果", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("玻璃通透度与动画强度,拖动滑块连续调节。", color = Sub, fontSize = 12.sp)
            }
            BixScene(action = if (tier == GlassTier.Off) BixAction.Sleeping else if (tier == GlassTier.Highest) BixAction.Done else BixAction.Idle, compact = true)
        }
        val tiers = listOf(GlassTier.Off, GlassTier.Medium, GlassTier.Highest)
        LiquidSegmented(
            options = listOf("不要", "中等", "最高"),
            selected = tiers.indexOf(GlassStyle.userTier()),
            onSelect = { i -> GlassStyle.set(context, tiers[i]) },
            modifier = Modifier.fillMaxWidth(), height = 44.dp
        )
        // Continuous intensity slider (iOS 27 transparency slider): left = solid tint, right = clear lens.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("纯色", color = Sub, fontSize = 11.sp)
            androidx.compose.material3.Slider(
                value = userValue,
                onValueChange = { v -> GlassStyle.setIntensity(context, v, persist = false) },
                onValueChangeFinished = { GlassStyle.setIntensity(context, GlassStyle.intensity.floatValue, persist = true) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = ThemeAccent,
                    activeTrackColor = ThemeAccent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.14f)
                )
            )
            Text("通透", color = Sub, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "强度 ${(userValue * 100f).roundToInt()}% · ${GlassStyle.intensityLabel(userValue)}",
                color = Fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
            )
            Text(
                when (tier) { GlassTier.Off -> "扁平"; GlassTier.Medium -> "渐变玻璃"; GlassTier.Highest -> "实时透镜" },
                color = ThemeAccentPale, fontSize = 11.sp
            )
        }
        // Live preview: honey stripes behind a glass capsule rendered with the current intensity.
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp))
                .drawWithContent {
                    val stripe = size.width / 8f
                    var i = 0
                    while (i < 8) {
                        drawRect(
                            color = if (i % 2 == 0) ThemeAccent.copy(alpha = 0.55f) else Color(0xFF1B1E24),
                            topLeft = androidx.compose.ui.geometry.Offset(i * stripe, 0f),
                            size = androidx.compose.ui.geometry.Size(stripe, size.height)
                        )
                        i++
                    }
                    drawContent()
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                Modifier
                    .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 999.dp, fallbackBg = Card)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BixScene(action = BixAction.Idle, compact = true)
                Spacer(Modifier.width(6.dp))
                Text("液态玻璃实时预览", color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (GlassStyle.capped) {
            val why = when {
                GlassStyle.powerSave.value && GlassStyle.reduceMotion.value -> "省电模式与「移除动画」已开启"
                GlassStyle.powerSave.value -> "系统省电模式已开启"
                else -> "系统已开启「移除动画」"
            }
            BixTip(
                text = "$why,玻璃强度暂时限制在 ${(GlassStyle.cap * 100f).roundToInt()}%,关闭后自动恢复。",
                action = BixAction.Sleeping
            )
        }
        val tip = when (tier) {
            GlassTier.Off -> "已关闭所有玻璃与动画,纯扁平配色。"
            GlassTier.Medium -> "玻璃层用渐变材质模拟,越往左越接近纯色,老设备也流畅。"
            GlassTier.Highest -> "物理级液态玻璃：散景磨砂 + 暗边轮廓 + 棚拍高光 + 实时色散折射(Android 13+,低版本自动优雅回退)。越往右越通透。"
        }
        Text(tip, color = ThemeAccentPale, fontSize = 11.sp, lineHeight = 15.sp)
        // A live LiquidSwitch so the user sees the effect immediately on this very card (plus Bix rapid-toggle easter egg!).
        var demo by remember { mutableStateOf(true) }
        var toggleCount by remember { mutableIntStateOf(0) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (toggleCount >= 6) {
                BixScene(action = BixAction.Dizzy, compact = true)
                Spacer(Modifier.width(6.dp))
                Text(
                    BIX_SWITCH_QUIPS[(toggleCount - 6) % BIX_SWITCH_QUIPS.size],
                    color = ThemeAccentPale, fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
            } else {
                Text("开关效果预览", color = Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            LiquidSwitch(checked = demo, onCheckedChange = { demo = it; toggleCount++ })
        }
    }
}

/**
 * "主题色" picker: 8 presets + custom HSV color wheel.
 * Persisted via SharedPreferences (ThemeColor), separate from Store.kt.
 * Scope: UI accent color — switch track, capsule selected glow, thinking dots,
 * status dots, chip selected, send button ready, pinned/pending marks,
 * drawer busy dot. NOT: BeeUi pixel bee, SelectBlue (semantic), BeeDanger (semantic).
 */
@Composable
fun ThemeColorCard() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = UiTokens.RadiusCard, fallbackBg = Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("主题色", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "强调色用于开关轨道、胶囊选中态、思考三点、状态点、Chip 选中、发送钮就绪态、置顶/待批准标记等。蜜蜂像素画风、选中蓝、危险红不随主题变。",
            color = Sub, fontSize = 12.sp
        )
        // Current color preview
        Box(
            Modifier.fillMaxWidth().height(54.dp)
                .harmonyGlass(
                    thickness = ImmersiveThickness.THIN,
                    radius = 16.dp,
                    tint = ThemeAccent.copy(alpha = 0.24f),
                    fallbackBg = Card
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BixThemePollenBadge()
                Text("液态玻璃主题染色实时预览", color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        // Preset grid
        Text("预设", color = Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeColor.presets.forEach { preset ->
                val sel = ThemeColor.hue == preset.hue && ThemeColor.saturation == preset.saturation && ThemeColor.value == preset.value && !ThemeColor.isCustom
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(preset.composeColor)
                        .border(if (sel) 3.dp else 0.dp, SelectBlue, RoundedCornerShape(12.dp))
                        .clickable { ThemeColor.setPreset(context, preset) }
                        .padding(2.dp)
                )
            }
        }
        // Custom HSV picker
        Text("自定义", color = Sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        var customHue by remember { mutableStateOf(ThemeColor.hue) }
        var customSat by remember { mutableStateOf(ThemeColor.saturation) }
        var customVal by remember { mutableStateOf(ThemeColor.value) }
        // Sync with current if not custom
        if (!ThemeColor.isCustom) {
            customHue = ThemeColor.hue
            customSat = ThemeColor.saturation
            customVal = ThemeColor.value
        }
        // Hue slider (0-360)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("色相", color = Sub, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                Text("${customHue.roundToInt()}", color = Fg, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                androidx.compose.material3.Slider(
                    value = customHue, onValueChange = { customHue = it },
                    valueRange = 0f..360f, steps = 360,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = ThemeAccent,
                        activeTrackColor = ThemeAccent, inactiveTrackColor = Line
                    )
                )
            }
            // Saturation slider (0-1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("饱和度", color = Sub, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                Text("${(customSat * 100).roundToInt()}%", color = Fg, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                androidx.compose.material3.Slider(
                    value = customSat, onValueChange = { customSat = it },
                    valueRange = 0f..1f, steps = 100,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = ThemeAccent,
                        activeTrackColor = ThemeAccent, inactiveTrackColor = Line
                    )
                )
            }
            // Value/Brightness slider (0-1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("明度", color = Sub, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                Text("${(customVal * 100).roundToInt()}%", color = Fg, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                androidx.compose.material3.Slider(
                    value = customVal, onValueChange = { customVal = it },
                    valueRange = 0f..1f, steps = 100,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = ThemeAccent,
                        activeTrackColor = ThemeAccent, inactiveTrackColor = Line
                    )
                )
            }
        }
        // Live preview of custom color
        Box(
            Modifier.fillMaxWidth().height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(android.graphics.Color.HSVToColor(floatArrayOf(customHue, customSat, customVal))))
                .padding(vertical = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("应用自定义色", modifier = Modifier.weight(1f)) {
                ThemeColor.setCustom(context, customHue, customSat, customVal)
            }
            PillButton("恢复默认", filled = false, modifier = Modifier.weight(1f)) {
                ThemeColor.setPreset(context, ThemeColor.presets[0])
            }
        }
    }
}
