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
                        Text(sk.content, color = Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            Text("Bot 与 Agent 用 schedule_task 工具给自己派的定时任务会出现在这里。注意:任务在 App 进程活着时触发,App 被系统杀掉期间到点的任务会顺延到下次打开。", color = Sub, fontSize = 12.sp)
            if (store.routines.isEmpty()) Text("暂无例行任务", color = Sub)
            store.routines.sortedBy { it.nextRunMs }.forEach { r ->
                Column(Modifier.fillMaxWidth().harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 12.dp, fallbackBg = Card).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${store.botName(r.ownerId)} · ${fmt.format(Date(r.nextRunMs))}${if (r.repeatDaily) " · 每天" else ""} · 由 ${r.createdBy} 创建",
                            color = Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("删除", color = Sub, modifier = Modifier.clickable { store.deleteRoutine(r.id) }.padding(6.dp))
                    }
                    Text(r.task, color = Fg, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    if (r.lastResult.isNotBlank()) Text("上次结果:${r.lastResult}", color = Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            SettingsDivider()
            Text("后台保活", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("开启后会有一个常驻通知的前台服务守着进程:例行任务能按时触发、主动消息通道不断线;被划掉后台时会自动尝试拉起,重启手机后也会自启。最终能不能活下来还要看系统放行——vivo 等机型请再给下面两项权限。", color = Sub, fontSize = 12.sp)
            run {
                val ctx = LocalContext.current
                var kaOn by remember { mutableStateOf(KeepAlive.isEnabled(ctx)) }
                var notifGranted by remember {
                    mutableStateOf(
                        Build.VERSION.SDK_INT < 33 ||
                            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    )
                }
                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g -> notifGranted = g }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("保活服务", color = Fg, fontSize = 15.sp)
                        Text(if (kaOn) "运行中(常驻通知可见)" else "未开启", color = Sub, fontSize = 12.sp)
                    }
                    LiquidSwitch(checked = kaOn, onCheckedChange = { on ->
                        kaOn = on
                        KeepAlive.setEnabled(ctx, on)
                    })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (KeepAlive.isBatteryExempt(ctx)) "电池豁免 ✓" else "电池优化豁免", filled = false, modifier = Modifier.weight(1f)) {
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
            Text("BotHive 0.6.4", color = Sub, fontSize = 12.sp)
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
 * "视觉效果" tier picker (the iOS-27-style intensity control): 关 / 中等 / 最高.
 *
 * The Highest tier is a promise, not a suggestion: it never silently downgrades. The GPU shader
 * path only engages where the platform can run it (API 33+); everything else — liquid switch,
 * glass layers, spring motion — runs at full spec regardless of device, because the user chose it.
 */
@Composable
fun GlassTierCard() {
    val context = LocalContext.current
    val tier = GlassStyle.tier.value
    Column(
        Modifier.fillMaxWidth().harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = UiTokens.RadiusCard, fallbackBg = Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("视觉效果", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "界面玻璃与动画强度,按手机性能自选。",
            color = Sub, fontSize = 12.sp
        )
        val tiers = listOf(GlassTier.Off, GlassTier.Medium, GlassTier.Highest)
        LiquidSegmented(
            options = listOf("不要", "中等", "最高"),
            selected = tiers.indexOf(tier),
            onSelect = { i -> GlassStyle.set(context, tiers[i]) },
            modifier = Modifier.fillMaxWidth(), height = 44.dp
        )
        val tip = when (tier) {
            GlassTier.Off -> "已关闭所有玻璃与动画,纯扁平配色。"
            GlassTier.Medium -> "玻璃层用渐变材质模拟,动画标准速度,老设备也流畅。"
            GlassTier.Highest -> "物理级液态玻璃：16-tap Vogel 散景磨砂 + 全内反射暗槽(TIR) + 棚拍镜面高光 + 实时 RGB 色散折射(Android 13+,低版本自动优雅回退)。"
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
