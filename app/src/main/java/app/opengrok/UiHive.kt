package app.opengrok

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/*
 * v0.7.0「蜂巢自治」screens: 需要你 (approval inbox), 权限与哨兵 (+ 凭据保险箱), 例行任务 (+ Webhook),
 * 用量与预算, 活动看板 (+ 回放), 蜂巢中枢 (总参谋 / 蜂巢早报 / 示教). Bix is the face of every section.
 */

// ---------------------------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------------------------

/** Page header: round back button, serif title, a small Bix pose on the right. */
@Composable
fun HivePageHeader(title: String, bix: BixAction, onBack: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).pressBounce(onClick = onBack)
                .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card),
            contentAlignment = Alignment.Center
        ) { Icon(Ic.Back, "返回", tint = Fg, modifier = Modifier.size(20.dp)) }
        Text(title, style = HiveSerif.SheetTitle(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimatedContent(
                targetState = bix,
                transitionSpec = {
                    (scaleIn(initialScale = 0.72f, animationSpec = spring(dampingRatio = 0.55f, stiffness = 480f)) +
                        fadeIn(tween(180))) togetherWith fadeOut(tween(120))
                },
                label = "header-bix"
            ) { pose ->
                BixScene(action = pose, compact = true)
            }
            if (trailing != null) trailing()
        }
    }
}

/** A titled glass section; Bix in [bix] pose sits next to the title. */
@Composable
fun HiveSection(title: String, bix: BixAction, subtitle: String = "", content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 18.dp, fallbackBg = Card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BixScene(action = bix, compact = true)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, color = Sub, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        content()
    }
}

/** A label + LiquidSwitch row. */
@Composable
fun HiveSwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Fg, fontSize = 14.sp)
            if (desc.isNotBlank()) Text(desc, color = Sub, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        LiquidSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/** Small selectable chip (accent when selected). */
@Composable
fun HiveChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.then(
            if (selected) Modifier.clip(RoundedCornerShape(14.dp)).background(ThemeAccent)
            else Modifier.harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 14.dp, fallbackBg = PILL)
        ).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(text, color = if (selected) Ink else Fg, fontSize = 12.sp) }
}

/** Coloured badge text. */
@Composable
fun HiveBadge(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

/**
 * Copies text to the clipboard, flagged as sensitive on Android 13+ so it is hidden in the
 * clipboard preview overlay and keyboard clipboard sync (tool output and tokens often hold secrets).
 */
fun copySensitive(ctx: Context, label: String, text: String, toast: String = "已复制") {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
    }
}

/**
 * True while the hosting activity is resumed. Polling loops key on this so they stop when the app
 * goes to the background with the screen still on top (v0.7.0, F8).
 */
@Composable
fun rememberIsResumed(): Boolean {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumed by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            when (ev) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> resumed = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return resumed
}

private val hiveTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private fun hiveTime(ms: Long): String = if (ms <= 0) "—" else synchronized(hiveTimeFmt) { hiveTimeFmt.format(Date(ms)) }

/** Owner candidates for routines / chief / teach: every Bot plus the device Agent. */
private fun ownerCandidates(store: Store): List<Pair<String, String>> =
    store.bots.map { it.id to it.name } + listOfNotNull(store.agent.value?.let { AGENT_ID to it.name })

private fun actionClassOf(name: String): ActionClass? = ActionClass.values().firstOrNull { it.name == name }

private fun levelColor(level: PolicyLevel): Color = when (level) {
    PolicyLevel.AUTO -> BeeGreen
    PolicyLevel.EXPLICIT -> ThemeAccentPale
    PolicyLevel.ASK -> MotionAmber
    PolicyLevel.HANDOFF -> BeeDanger
}

// ---------------------------------------------------------------------------------------------
// 需要你 — approval inbox
// ---------------------------------------------------------------------------------------------

/**
 * One approval: what will happen, which class, sentinel verdict (Bix scans it), expiry countdown,
 * execute / reject. A "block" verdict needs an extra confirmation before executing.
 */
@Composable
fun ApprovalItemCard(store: Store, ap: Approval, showChat: Boolean) {
    var confirmBlocked by remember(ap.id) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (ap.expiresMs > 0) {
        LaunchedEffect(ap.id) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    val cls = actionClassOf(ap.actionClass)
    val kindLabel = cls?.label ?: when (ap.toolName) {
        "shizuku_shell" -> "特权命令"
        "fs_delete" -> "删除文件"
        else -> "命令"
    }
    val displayCmd = if (ap.toolName == "fs_delete" && ap.command.contains("|")) {
        val parts = ap.command.split("|", limit = 2)
        "[${parts[0]}] ${parts[1]}"
    } else ap.command
    val verdict = ap.sentinelVerdict
    SentinelScanBox(verdict = verdict, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .harmonyGlass(thickness = ImmersiveThickness.THICK, radius = 16.dp, fallbackBg = BeePanelRaised)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HiveBadge(kindLabel, if (cls == ActionClass.PAYMENT || cls == ActionClass.SENSITIVE_DATA) BeeDanger else ThemeAccentPale)
                Spacer(Modifier.width(6.dp))
                Text(
                    store.botName(ap.botId) + if (showChat) " · " + store.title(ap.chatId) else "",
                    color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (ap.expiresMs > 0) {
                    val left = ((ap.expiresMs - now) / 1000).coerceAtLeast(0)
                    Text(
                        if (left > 0) "%d:%02d 后过期".format(left / 60, left % 60) else "即将过期",
                        color = if (left < 300) MotionAmber else Sub, fontSize = 11.sp
                    )
                }
            }
            if (ap.summary.isNotBlank()) Text(ap.summary, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                displayCmd, color = if (ap.summary.isBlank()) Fg else Sub, fontSize = 12.sp,
                fontFamily = HiveFonts.Mono, maxLines = 6, overflow = TextOverflow.Ellipsis
            )
            if (verdict.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(sentinelVerdictColor(verdict)))
                    Spacer(Modifier.width(6.dp))
                    val head = when (verdict) {
                        "pending" -> "哨兵蜂正在核对…"
                        "ok" -> "哨兵蜂:与指令一致"
                        "warn" -> "哨兵蜂:有点可疑"
                        "block" -> "哨兵蜂:建议拦下"
                        else -> "哨兵蜂:这次没能审查"
                    }
                    Text(
                        head + if (ap.sentinelNote.isNotBlank()) " · " + ap.sentinelNote else "",
                        color = sentinelVerdictColor(verdict), fontSize = 12.sp, lineHeight = 16.sp
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("执行", modifier = Modifier.weight(1f)) {
                    if (verdict == "block") confirmBlocked = true else store.executeApproval(ap)
                }
                PillButton("拒绝", filled = false, modifier = Modifier.weight(1f)) { store.rejectApproval(ap) }
            }
        }
    }
    if (confirmBlocked) {
        AlertDialog(
            onDismissRequest = { confirmBlocked = false },
            title = { Text("哨兵蜂建议拦下") },
            text = {
                Text(
                    "Bix 审查后认为这一步和你的指令不符或有风险:" +
                        ap.sentinelNote.ifBlank { "(未给出理由)" } + "\n\n确定仍要执行吗?",
                    color = Fg
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmBlocked = false; store.executeApproval(ap) }) { Text("仍然执行", color = BeeDanger) }
            },
            dismissButton = { TextButton(onClick = { confirmBlocked = false }) { Text("算了") } }
        )
    }
}

@Composable
fun ApprovalInboxScreen(store: Store, go: (Screen) -> Unit) {
    val list = store.pendingApprovals.toList().sortedBy { if (it.expiresMs > 0) it.expiresMs else Long.MAX_VALUE }
    Column(Modifier.fillMaxSize()) {
        HivePageHeader("需要你", if (list.isEmpty()) BixAction.Done else BixAction.Sentinel, { go(Screen.Home) })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (list.isEmpty()) {
                BixEmptyState(BixAction.Done, "都处理完啦", "Bot 想删除、发送、付款或执行特权命令时,会先在这里排队等你点头。")
            } else {
                BixTip(
                    "共 ${list.size} 件事等你决定。来自例行任务或 Bot 之间私聊的审批 30 分钟后自动过期。",
                    BixAction.Sentinel
                )
                list.forEach { ap ->
                    ApprovalItemCard(store, ap, showChat = true)
                    TextButton(onClick = { go(if (ap.chatId == AGENT_ID) Screen.AgentChat else Screen.Chat(ap.chatId)) }) {
                        Text("打开所在对话", color = Sub, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 权限与哨兵 + 凭据保险箱
// ---------------------------------------------------------------------------------------------

@Composable
fun PolicyScreen(store: Store, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rev = Policy.revision.intValue
    var sentinelOn by remember { mutableStateOf(Policy.sentinelEnabled) }
    var reviewAuto by remember { mutableStateOf(Policy.sentinelReviewAuto) }
    var reviewer by remember { mutableStateOf(Policy.sentinelReviewerId) }
    var maskOtp by remember { mutableStateOf(Policy.maskOtp) }
    var clearCookies by remember { mutableStateOf(Policy.clearBrowserOnClose) }
    var protectedText by remember { mutableStateOf(Policy.protectedApps().joinToString("\n")) }
    Column(Modifier.fillMaxSize()) {
        HivePageHeader("权限与哨兵", BixAction.Sentinel, { go(Screen.Settings) })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HiveSection(
                "四档权限规则", BixAction.ApprovalGuard,
                "每类操作选一个档位:自动 / 你明确要求过才自动 / 先问我 / 交给我亲自做。敏感数据至少先问,支付与账户一律交给你,不能放宽。"
            ) {
                ActionClass.values().forEach { cls ->
                    val level = Policy.level(cls)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(cls.label, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            HiveBadge(level.label, levelColor(level))
                        }
                        Text(cls.desc, color = Sub, fontSize = 11.sp, lineHeight = 15.sp)
                        if (cls.configurable) {
                            val levels = PolicyLevel.values().toList()
                            LiquidSegmented(
                                options = levels.map { it.label },
                                selected = levels.indexOf(Policy.configured(cls)).coerceAtLeast(0),
                                onSelect = { i -> Policy.setLevel(ctx, cls, levels[i]) },
                                modifier = Modifier.fillMaxWidth(),
                                scrollable = true,
                                height = 34.dp,
                                fontSize = 12.sp,
                                itemPadding = 10.dp
                            )
                        }
                    }
                }
            }
            HiveSection(
                "哨兵蜂", BixAction.Sentinel,
                "另一只 Bot 在操作执行前独立核对:是否符合你最后的指令、有没有夹带数据。审查规则存在所有 Bot 都碰不到的地方。"
            ) {
                HiveSwitchRow("开启哨兵审查", "每张审批卡都会先被审查,结论显示在卡片上", sentinelOn) {
                    Policy.sentinelEnabled = it; sentinelOn = it
                }
                HiveSwitchRow("也审查自动放行的对外操作", "网页输入、MCP 调用、屏幕输入等;会多花一些额度", reviewAuto) {
                    Policy.sentinelReviewAuto = it; reviewAuto = it
                }
                Text("由谁来审查", color = Sub, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HiveChip("执行者自己", reviewer.isBlank()) { Policy.sentinelReviewerId = ""; reviewer = "" }
                    store.bots.forEach { b ->
                        HiveChip(b.name, reviewer == b.id) { Policy.sentinelReviewerId = b.id; reviewer = b.id }
                    }
                }
                BixTip("建议选一只用便宜模型的 Bot 专职当哨兵,和干活的 Bot 分开。", BixAction.Sentinel)
            }
            HiveSection("隐私", BixAction.Sentinel) {
                HiveSwitchRow("通知里的验证码先打码", "通知内容交给模型或触发例行任务前,把 4-8 位验证码遮住", maskOtp) {
                    Policy.maskOtp = it; maskOtp = it
                }
                HiveSwitchRow("关闭 AI 浏览器时清除 Cookie", "下一只 Bot 打开浏览器时不会沿用上一次的登录", clearCookies) {
                    Policy.clearBrowserOnClose = it; clearCookies = it
                }
            }
            HiveSection("受保护的应用", BixAction.Handoff, "这些应用在前台时,屏幕操作一律交还给你(每行一个包名)。") {
                Field("包名列表", protectedText, { protectedText = it }, singleLine = false, minLines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("保存", modifier = Modifier.weight(1f)) {
                        Policy.setProtectedApps(ctx, protectedText.lines())
                        protectedText = Policy.protectedApps().joinToString("\n")
                        Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                    }
                    PillButton("恢复默认", filled = false, modifier = Modifier.weight(1f)) {
                        Policy.setProtectedApps(ctx, Policy.DEFAULT_PROTECTED_APPS)
                        protectedText = Policy.protectedApps().joinToString("\n")
                    }
                }
            }
            VaultSection()
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 凭据保险箱: names are listed, values are write-only (never shown again, never sent to a model). */
@Composable
fun VaultSection() {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rev = Vault.revision.intValue
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }
    HiveSection(
        "凭据保险箱", BixAction.Sentinel,
        "密码、令牌经 Keystore 加密存在本机。Bot 只能看到名字,用 browser_fill_secret 把值直接填进网页输入框,值本身永远不会进入对话或模型。"
    ) {
        val names = Vault.names()
        if (names.isEmpty()) {
            Text("还没有凭据", color = Sub, fontSize = 12.sp)
        } else {
            names.forEach { n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Key, null, tint = ThemeAccentPale, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(n, color = Fg, fontSize = 14.sp, fontFamily = HiveFonts.Mono, modifier = Modifier.weight(1f))
                    SmallChip("删除") { removing = n }
                }
            }
        }
        Field("名称(字母、数字、_ - .)", name, { name = it })
        Field("值", value, { value = it }, visual = PasswordVisualTransformation(), keyboard = KeyboardType.Password)
        PillButton("存入保险箱") {
            when {
                !Vault.isValidName(name) -> Toast.makeText(ctx, "名称只能用字母、数字和 _ - .", Toast.LENGTH_SHORT).show()
                value.isEmpty() -> Toast.makeText(ctx, "值不能为空", Toast.LENGTH_SHORT).show()
                Vault.put(ctx, name, value) -> {
                    Toast.makeText(ctx, "Bix 把「${name.trim()}」锁进保险箱了", Toast.LENGTH_SHORT).show()
                    name = ""; value = ""
                }
                else -> Toast.makeText(ctx, "保存失败:Keystore 不可用", Toast.LENGTH_SHORT).show()
            }
        }
    }
    removing?.let { n ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删除凭据") },
            text = { Text("删除「$n」后,用到它的技能或例行任务将无法自动登录。", color = Fg) },
            confirmButton = { TextButton(onClick = { Vault.remove(ctx, n); removing = null }) { Text("删除", color = BeeDanger) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 例行任务 + Webhook
// ---------------------------------------------------------------------------------------------

private fun triggerLabel(t: String): String = when (t) {
    "notification" -> "通知触发"
    "webhook" -> "Webhook"
    else -> "定时"
}

@Composable
fun RoutinesScreen(store: Store, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Routine?>(null) }
    var creating by remember { mutableStateOf(false) }
    var historyOf by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        HivePageHeader("例行任务", BixAction.Alarm, { go(Screen.Home) }) {
            Box(
                Modifier.size(40.dp).pressBounce { creating = true }
                    .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = Card),
                contentAlignment = Alignment.Center
            ) { Icon(Ic.Add, "新建", tint = Fg, modifier = Modifier.size(20.dp)) }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BixTip("除了定时,还能在收到某个 App 的通知、或有程序调用 Webhook 时自动开工。新建后可以先点「测试」跑一次。", BixAction.Alarm)
            if (store.routines.isEmpty()) {
                BixEmptyState(BixAction.Sleeping, "还没有例行任务", "点右上角 + 新建,或者在对话里让 Bot 用 schedule_task 给自己派活。")
            }
            store.routines.toList().forEach { r ->
                Column(
                    Modifier.fillMaxWidth()
                        .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 16.dp, fallbackBg = Card)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BixScene(action = if (r.paused) BixAction.Sleeping else BixAction.Alarm, compact = true)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HiveBadge(triggerLabel(r.trigger), if (r.trigger == "time") ThemeAccentPale else BeeGreen)
                                Spacer(Modifier.width(6.dp))
                                Text(store.botName(r.ownerId), color = Sub, fontSize = 12.sp)
                            }
                            Text(r.task, color = Fg, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            val whenText = when (r.trigger) {
                                "notification" -> "收到 ${r.matchApp.ifBlank { "任意应用" }} 的通知" +
                                    (if (r.matchText.isNotBlank()) " 且包含「${r.matchText}」" else "") + " · 间隔 ≥ ${r.minIntervalMin} 分钟"
                                "webhook" -> "POST " + WebhookServer.urlFor(r.id)
                                else -> "下次 ${hiveTime(r.nextRunMs)}" + if (r.repeatDaily) " · 每天" else ""
                            }
                            Text(whenText, color = Sub, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        LiquidSwitch(checked = !r.paused, onCheckedChange = { on -> store.setRoutinePaused(r.id, !on) })
                    }
                    val last = r.history.firstOrNull()
                    if (last != null) {
                        Text(
                            (if (last.ok) "上次成功 · " else "上次失败 · ") + hiveTime(last.startMs) + " · " + last.summary.take(60),
                            color = if (last.ok) Sub else BeeDanger, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallChip("测试") {
                            val why = store.runRoutineNow(r.id)
                            Toast.makeText(ctx, why ?: "Bix 已经开工,结果会出现在 ${store.botName(r.ownerId)} 的对话里", Toast.LENGTH_SHORT).show()
                        }
                        SmallChip("记录 ${r.history.size}") { historyOf = r.id }
                        SmallChip("编辑") { editing = r }
                        if (r.trigger == "webhook") SmallChip("复制地址") { copySensitive(ctx, "webhook", WebhookServer.urlFor(r.id)) }
                        SmallChip("删除") { store.deleteRoutine(r.id) }
                    }
                }
            }
            WebhookSection(store)
            Spacer(Modifier.height(20.dp))
        }
    }
    if (creating || editing != null) {
        RoutineEditorDialog(store, editing) { creating = false; editing = null }
    }
    historyOf?.let { id ->
        val r = store.routines.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { historyOf = null },
            title = { Text("运行记录") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hist = r?.history.orEmpty()
                    if (hist.isEmpty()) Text("还没有运行过", color = Sub)
                    hist.forEach { h ->
                        Column {
                            Text(
                                hiveTime(h.startMs) + " · " + triggerLabel(h.trigger) + " · " + if (h.ok) "成功" else "失败",
                                color = if (h.ok) BeeGreen else BeeDanger, fontSize = 12.sp
                            )
                            Text(h.summary.take(200), color = Fg, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { historyOf = null }) { Text("关闭") } }
        )
    }
}

/** Next local time today/tomorrow at [hour]:[minute] (ms). */
private fun nextAt(hour: Int, minute: Int): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
    c.set(Calendar.MINUTE, minute.coerceIn(0, 59))
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1)
    return c.timeInMillis
}

@Composable
private fun RoutineEditorDialog(store: Store, old: Routine?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val owners = ownerCandidates(store)
    var owner by remember { mutableStateOf(old?.ownerId ?: owners.firstOrNull()?.first.orEmpty()) }
    var task by remember { mutableStateOf(old?.task ?: "") }
    var trigger by remember { mutableStateOf(old?.trigger ?: "time") }
    val oldCal = remember { Calendar.getInstance().apply { timeInMillis = old?.nextRunMs?.takeIf { it > 0 } ?: (System.currentTimeMillis() + 3_600_000L) } }
    var hour by remember { mutableStateOf(oldCal.get(Calendar.HOUR_OF_DAY).toString()) }
    var minute by remember { mutableStateOf("%02d".format(oldCal.get(Calendar.MINUTE))) }
    var daily by remember { mutableStateOf(old?.repeatDaily ?: true) }
    var matchApp by remember { mutableStateOf(old?.matchApp ?: "") }
    var matchText by remember { mutableStateOf(old?.matchText ?: "") }
    var interval by remember { mutableStateOf((old?.minIntervalMin ?: 5).toString()) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (old == null) "新建例行任务" else "编辑例行任务") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("交给谁", color = Sub, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    owners.forEach { (id, n) -> HiveChip(n, owner == id) { owner = id } }
                }
                Field("要做什么", task, { task = it }, singleLine = false, minLines = 3)
                Text("什么时候开工", color = Sub, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HiveChip("定时", trigger == "time") { trigger = "time" }
                    HiveChip("收到通知", trigger == "notification") { trigger = "notification" }
                    HiveChip("Webhook", trigger == "webhook") { trigger = "webhook" }
                }
                when (trigger) {
                    "time" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { Field("时(0-23)", hour, { hour = it.filter(Char::isDigit).take(2) }, keyboard = KeyboardType.Number) }
                            Box(Modifier.weight(1f)) { Field("分(0-59)", minute, { minute = it.filter(Char::isDigit).take(2) }, keyboard = KeyboardType.Number) }
                        }
                        HiveSwitchRow("每天重复", "", daily) { daily = it }
                    }
                    "notification" -> {
                        Field("应用名或包名(留空 = 任意应用)", matchApp, { matchApp = it })
                        Field("通知里包含的关键词(留空 = 任意)", matchText, { matchText = it })
                        Field("两次触发至少间隔(分钟)", interval, { interval = it.filter(Char::isDigit).take(4) }, keyboard = KeyboardType.Number)
                        BixTip("需要先开启「通知使用权」。验证码会按隐私设置打码后再交给 Bot。", BixAction.Alarm)
                    }
                    else -> {
                        Field("两次触发至少间隔(分钟)", interval, { interval = it.filter(Char::isDigit).take(4) }, keyboard = KeyboardType.Number)
                        BixTip("保存后在列表里「复制地址」。请求要带 Authorization: Bearer <令牌>,请求体就是交给 Bot 的事件内容。", BixAction.Alarm)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (task.isBlank() || owner.isBlank()) {
                    Toast.makeText(ctx, "请填写任务并选择执行者", Toast.LENGTH_SHORT).show()
                    return@TextButton
                }
                val base = old ?: Routine(UUID.randomUUID().toString().take(8), owner, task.trim(), 0L, createdBy = "user")
                val next = if (trigger == "time") nextAt(hour.toIntOrNull() ?: 8, minute.toIntOrNull() ?: 0) else 0L
                store.saveRoutine(
                    base.copy(
                        ownerId = owner, task = task.trim(), trigger = trigger, nextRunMs = next,
                        repeatDaily = trigger == "time" && daily,
                        matchApp = if (trigger == "notification") matchApp.trim() else "",
                        matchText = if (trigger == "notification") matchText.trim() else "",
                        minIntervalMin = (interval.toIntOrNull() ?: 5).coerceIn(1, 1440)
                    )
                )
                onDone()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("取消") } }
    )
}

@Composable
fun WebhookSection(store: Store) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(WebhookServer.isEnabled(ctx)) }
    var lan by remember { mutableStateOf(WebhookServer.allowLan(ctx)) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(WebhookServer.token(ctx)) }
    HiveSection(
        "Webhook 触发", BixAction.Alarm,
        "在手机上开一个小服务(端口 ${WebhookServer.port})。快捷指令、Tasker、电脑脚本 POST 到对应地址,就能叫醒一个例行任务。"
    ) {
        HiveSwitchRow("开启 Webhook", if (WebhookServer.running.value) "运行中" else "未运行", on) {
            WebhookServer.setEnabled(ctx, it, store); on = it
        }
        HiveSwitchRow("允许局域网访问", "默认只接受本机请求;开启后同一 Wi-Fi 下的设备也能调用", lan) {
            WebhookServer.setAllowLan(ctx, it, store); lan = it
        }
        Text("令牌", color = Sub, fontSize = 12.sp)
        Text(
            if (showToken) token else token.take(4) + "••••••••" + token.takeLast(2),
            color = Fg, fontSize = 13.sp, fontFamily = HiveFonts.Mono
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallChip(if (showToken) "隐藏" else "显示") { showToken = !showToken }
            SmallChip("复制") { copySensitive(ctx, "webhook token", token, "已复制令牌") }
            SmallChip("重新生成") { token = WebhookServer.regenerateToken(ctx) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 用量与预算
// ---------------------------------------------------------------------------------------------

private fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.2fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

@Composable
fun UsageScreen(store: Store, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rev = UsageLedger.revision.intValue
    var budgetOf by remember { mutableStateOf<Pair<String, String>?>(null) }
    var priceOf by remember { mutableStateOf<String?>(null) }
    val rows = UsageLedger.rows(30)
    val owners = ownerCandidates(store)
    Column(Modifier.fillMaxSize()) {
        HivePageHeader("用量与预算", BixAction.Budget, { go(Screen.Settings) })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val total = rows.sumOf { it.promptTokens + it.completionTokens }
            BixTip("近 30 天一共用了 ${fmtTokens(total)} tokens。给 Bot 设每日预算,用完 Bix 会让它停下并提醒你。", BixAction.Budget)
            if (owners.isEmpty()) BixEmptyState(BixAction.Budget, "还没有 Bot", "建好 Bot 并聊几句,这里就会记账。")
            owners.forEach { (id, name) ->
                val mine = rows.filter { it.botId == id }
                val today = UsageLedger.todayTokens(id)
                val budget = UsageLedger.budget(id)
                val model = (if (id == AGENT_ID) store.agent.value?.model else store.bot(id)?.model).orEmpty()
                val usd = mine.mapNotNull { UsageLedger.estimateUsd(it, model) }.takeIf { it.isNotEmpty() }?.sum()
                val fill = if (budget > 0) (1f - today.toFloat() / budget).coerceIn(0f, 1f) else 1f
                Column(
                    Modifier.fillMaxWidth()
                        .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 16.dp, fallbackBg = Card)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BixScene(action = BixAction.Budget, compact = true, fill = fill)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(model.ifBlank { "未设置模型" }, color = Sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (budget > 0 && today >= budget) HiveBadge("今日已到预算", BeeDanger)
                    }
                    Text(
                        "今天 ${fmtTokens(today)}" + (if (budget > 0) " / 预算 ${fmtTokens(budget)}" else " · 未设预算") +
                            " · 30 天 ${fmtTokens(mine.sumOf { it.promptTokens + it.completionTokens })} · ${mine.sumOf { it.calls }} 次调用" +
                            (if (usd != null) " · 约 $%.2f".format(usd) else ""),
                        color = Sub, fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallChip("每日预算") { budgetOf = id to (if (budget > 0) budget.toString() else "") }
                        if (model.isNotBlank()) SmallChip("模型单价") { priceOf = model }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    budgetOf?.let { (id, initial) ->
        var v by remember(id) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { budgetOf = null },
            title = { Text("每日 token 预算") },
            text = { Field("tokens(0 或留空 = 不限)", v, { v = it.filter(Char::isDigit).take(10) }, keyboard = KeyboardType.Number) },
            confirmButton = {
                TextButton(onClick = { UsageLedger.setBudget(ctx, id, v.toLongOrNull() ?: 0L); budgetOf = null }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { budgetOf = null }) { Text("取消") } }
        )
    }
    priceOf?.let { model ->
        val p = UsageLedger.price(model)
        var inP by remember(model) { mutableStateOf(p?.first?.toString() ?: "") }
        var outP by remember(model) { mutableStateOf(p?.second?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { priceOf = null },
            title = { Text("单价 · $model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("美元 / 每百万 tokens,用于估算费用(不影响计费)。", color = Sub, fontSize = 12.sp)
                    Field("输入单价", inP, { inP = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, keyboard = KeyboardType.Decimal)
                    Field("输出单价", outP, { outP = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, keyboard = KeyboardType.Decimal)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    UsageLedger.setPrice(ctx, model, inP.toDoubleOrNull() ?: 0.0, outP.toDoubleOrNull() ?: 0.0)
                    priceOf = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { priceOf = null }) { Text("取消") } }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 活动看板 + 回放
// ---------------------------------------------------------------------------------------------

@Composable
fun ActivityScreen(store: Store, go: (Screen) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var replayChat by remember { mutableStateOf<String?>(null) }
    var replayIndex by remember { mutableStateOf(0) }
    val timeline = remember(store.busy.size, store.pendingApprovals.size, tab) { store.activityTimeline(300) }
    Column(Modifier.fillMaxSize()) {
        HivePageHeader("活动看板", BixAction.DrillingSquad, { go(Screen.Home) })
        LiquidSegmented(
            options = listOf("进行中", "已排期", "已完成", "审计"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            height = 38.dp
        )
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (tab) {
                0 -> {
                    val busy = store.busy.toList().distinct()
                    val projects = store.projects.filter { it.stage in setOf(Stage.PLANNING, Stage.AWAIT_CONFIRM, Stage.EXECUTING, Stage.REVIEWING, Stage.REPORTING) }
                    if (busy.isEmpty() && projects.isEmpty() && store.pendingApprovals.isEmpty()) {
                        BixEmptyState(BixAction.Idle, "蜂巢很安静", "现在没有 Bot 在干活。")
                    }
                    if (store.pendingApprovals.isNotEmpty()) {
                        ActivityRow(BixAction.Sentinel, "${store.pendingApprovals.size} 件事等你批准", "点开「需要你」处理") { go(Screen.Approvals) }
                    }
                    busy.forEach { id ->
                        ActivityRow(BixAction.Coding, store.title(id), "正在工作") {
                            go(if (id == AGENT_ID) Screen.AgentChat else Screen.Chat(id))
                        }
                    }
                    projects.forEach { p ->
                        ActivityRow(BixAction.DrillingSquad, "项目 · ${p.title}", stageLabel(p.stage) + " · " +
                            p.assignments.joinToString("、") { store.botName(it.botId) + statusLabel(it.status) }) {
                            go(Screen.Chat("bot:${p.managerId}"))
                        }
                    }
                }
                1 -> {
                    val list = store.routines.toList().sortedBy { if (it.trigger == "time" && it.nextRunMs > 0) it.nextRunMs else Long.MAX_VALUE }
                    if (list.isEmpty()) BixEmptyState(BixAction.Sleeping, "没有排期", "在「例行任务」里新建一个吧。")
                    list.forEach { r ->
                        ActivityRow(
                            if (r.paused) BixAction.Sleeping else BixAction.Alarm,
                            r.task.take(40),
                            store.botName(r.ownerId) + " · " + (if (r.trigger == "time") hiveTime(r.nextRunMs) else triggerLabel(r.trigger)) +
                                if (r.paused) " · 已暂停" else ""
                        ) { go(Screen.Routines) }
                    }
                }
                2 -> {
                    val done = store.projects.filter { it.stage == Stage.DONE }.reversed()
                    val runs = store.routines.flatMap { r -> r.history.map { r to it } }.sortedByDescending { it.second.startMs }.take(40)
                    if (done.isEmpty() && runs.isEmpty()) BixEmptyState(BixAction.Done, "还没有完成记录", "项目结项和例行任务的运行结果会出现在这里。")
                    done.forEach { p ->
                        ActivityRow(BixAction.Done, "项目 · ${p.title}", p.report.lineSequence().firstOrNull().orEmpty().take(60)) {
                            go(Screen.Chat("bot:${p.managerId}"))
                        }
                    }
                    runs.forEach { (r, h) ->
                        ActivityRow(
                            if (h.ok) BixAction.Done else BixAction.Dizzy,
                            r.task.take(40),
                            hiveTime(h.startMs) + " · " + (if (h.ok) "成功" else "失败") + " · " + h.summary.take(50)
                        ) { go(if (r.ownerId == AGENT_ID) Screen.AgentChat else Screen.Chat("bot:${r.ownerId}")) }
                    }
                }
                else -> {
                    BixTip("每一次工具调用都留痕。点一条进入回放,拖动滑块逐步查看 Bot 当时看到了什么、做了什么。", BixAction.Searching)
                    if (timeline.isEmpty()) BixEmptyState(BixAction.Idle, "还没有工具调用", "Bot 执行命令、看屏、浏览网页后,记录会出现在这里。")
                    timeline.take(150).forEach { item ->
                        ActivityRow(
                            classifyToolBixAction(item.tool.name),
                            item.botName + " · " + humanToolSummary(item.tool),
                            hiveTime(item.time) + " · " + item.chatTitle
                        ) {
                            val sameChat = timeline.filter { it.chatId == item.chatId }.reversed()
                            replayChat = item.chatId
                            replayIndex = sameChat.indexOfFirst { it.msgId == item.msgId && it.tool.id == item.tool.id }.coerceAtLeast(0)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    val rc = replayChat
    val steps = if (rc == null) emptyList() else timeline.filter { it.chatId == rc }.reversed()
    ClaudeSheet(title = "回放 · " + (rc?.let { store.title(it) } ?: ""), visible = rc != null && steps.isNotEmpty(), onDismiss = { replayChat = null }) {
        if (steps.isNotEmpty()) ReplayPane(steps, replayIndex.coerceIn(0, steps.lastIndex)) { replayIndex = it }
    }
}

@Composable
private fun ActivityRow(bix: BixAction, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 14.dp, fallbackBg = Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BixScene(action = bix, compact = true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Sub, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Ic.Chevron, null, tint = Sub, modifier = Modifier.size(16.dp))
    }
}

/** Step-through of one chat's tool calls: slider, screenshot, args and output for the selected step. */
@Composable
private fun ReplayPane(steps: List<ActivityItem>, index: Int, onIndex: (Int) -> Unit) {
    val ctx = LocalContext.current
    val item = steps[index]
    val t = item.tool
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BixScene(action = classifyToolBixAction(t.name), compact = true)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("第 ${index + 1} / ${steps.size} 步 · ${item.botName}", color = Sub, fontSize = 12.sp)
                Text(humanToolSummary(t), color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(hiveTime(item.time) + " · " + t.name, color = Sub, fontSize = 11.sp)
            }
        }
        if (steps.size > 1) {
            Slider(
                value = index.toFloat(),
                onValueChange = { onIndex(it.toInt().coerceIn(0, steps.lastIndex)) },
                valueRange = 0f..steps.lastIndex.toFloat(),
                steps = (steps.size - 2).coerceAtLeast(0)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("上一步", filled = false, modifier = Modifier.weight(1f)) { onIndex((index - 1).coerceAtLeast(0)) }
                PillButton("下一步", modifier = Modifier.weight(1f)) { onIndex((index + 1).coerceAtMost(steps.lastIndex)) }
            }
        }
        if (t.image.isNotBlank()) {
            val bmp = remember(t.image) {
                runCatching {
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                    android.graphics.BitmapFactory.decodeFile(t.image, opts)
                }.getOrNull()
            }
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), "这一步的截图",
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }
        Text("参数", color = Sub, fontSize = 11.sp)
        SelectionContainer {
            Text(prettyJson(t.args), color = Fg, fontSize = 12.sp, fontFamily = HiveFonts.Mono)
        }
        Text("输出", color = Sub, fontSize = 11.sp)
        Box(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            SelectionContainer {
                Text(
                    t.output.ifBlank { "(无输出)" }, color = if (t.output.isBlank()) Sub else Fg,
                    fontSize = 12.sp, fontFamily = HiveFonts.Mono,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallChip("复制输出") { copySensitive(ctx, "tool output", t.output) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun prettyJson(raw: String): String =
    runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw).take(4_000)

// ---------------------------------------------------------------------------------------------
// 蜂巢中枢: 总参谋 / 蜂巢早报 / 示教
// ---------------------------------------------------------------------------------------------

@Composable
fun HiveCenterScreen(store: Store, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val owners = ownerCandidates(store)
    var goal by remember { mutableStateOf("") }
    var teachOwner by remember { mutableStateOf(owners.firstOrNull()?.first.orEmpty()) }
    var lastSteps by remember { mutableStateOf<List<String>>(emptyList()) }
    var drafting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        HivePageHeader("蜂巢中枢", BixAction.Briefing, { go(Screen.Home) })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HiveSection(
                "超级工单 · Super-Agent", BixAction.Router,
                "持久化任务工单 + Fast Path 0-Token 事件路由 + Inline & Spill 链式召回 + 看门狗断点续跑。"
            ) {
                val activeCount = SuperAgent.tickets.count { it.status == TicketStatus.RUNNING || it.status == TicketStatus.QUEUED }
                val pausedCount = SuperAgent.tickets.count { it.status == TicketStatus.PAUSED }
                Text("活跃工单 $activeCount · 可续跑 $pausedCount · Fast Path 已过滤 ${SuperAgent.fastPathSavedCount.intValue} 次噪音", color = Sub, fontSize = 12.sp)
                PillButton("打开超级工单中枢", modifier = Modifier.fillMaxWidth()) { go(Screen.SuperAgent) }
            }
            HiveSection(
                "Bot 专属电脑 · Hive PC (v0.7.2)", BixAction.DesktopPC,
                "连接 BotHive Desktop PC 端（24×7 在线远程桌面、键鼠操控与后台守护进程），或使用本机工作站。"
            ) {
                val st = if (HiveComputer.connected.value) "已在线：${HiveComputer.hostName.value}" else "未连接 PC 端（支持 Windows / Mac / Linux）"
                Text(st, color = if (HiveComputer.connected.value) BeeGreen else Sub, fontSize = 12.sp)
                PillButton("打开 Bot 专属电脑", modifier = Modifier.fillMaxWidth()) { go(Screen.HiveComputer) }
            }
            HiveSection(
                "个人智能体中枢 (v0.7.2)", BixAction.MemoryPod,
                "主动目标 Check-in + 可溯源记忆档案舱（一键搬家）+ 通知隐私围栏 + Bot 预算信封。"
            ) {
                Text("目标 ${PersonalAgent.goals.size} · 记忆舱 ${MemoryPod.records.size} · 隐私围栏已拦 ${PersonalAgent.blockedCount.intValue} 条", color = Sub, fontSize = 12.sp)
                PillButton("打开个人智能体中枢", modifier = Modifier.fillMaxWidth()) { go(Screen.PersonalHub) }
            }
            HiveSection(
                "总参谋", BixAction.Briefing,
                "指定一只 Bot 统管全局。问它「今天有什么新情况」,它会用 team_status 汇总所有 Bot、待批事项和例行任务。"
            ) {
                val chief = store.chiefId.value
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HiveChip("不设", chief.isBlank()) { store.setChief("") }
                    owners.forEach { (id, n) -> HiveChip(n, chief == id) { store.setChief(id) } }
                }
                if (chief.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("开对话", filled = false, modifier = Modifier.weight(1f)) {
                            go(if (chief == AGENT_ID) Screen.AgentChat else Screen.Chat("bot:$chief"))
                        }
                        PillButton("每天 8:00 发早报", modifier = Modifier.weight(1f)) {
                            val exists = store.routines.any { it.ownerId == chief && it.task == CHIEF_BRIEF_TASK }
                            if (exists) {
                                Toast.makeText(ctx, "已经有蜂巢早报了", Toast.LENGTH_SHORT).show()
                            } else {
                                store.saveRoutine(
                                    Routine(UUID.randomUUID().toString().take(8), chief, CHIEF_BRIEF_TASK, nextAt(8, 0), repeatDaily = true, createdBy = "user")
                                )
                                Toast.makeText(ctx, "Bix 每天早上 8 点会把早报送到 ${store.botName(chief)} 的对话里", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    SmallChip("现在就来一份") {
                        val r = store.routines.firstOrNull { it.ownerId == chief && it.task == CHIEF_BRIEF_TASK }
                        if (r != null) {
                            val why = store.runRoutineNow(r.id)
                            Toast.makeText(ctx, why ?: "早报生成中…", Toast.LENGTH_SHORT).show()
                        } else {
                            val chatId = if (chief == AGENT_ID) AGENT_ID else "bot:$chief"
                            if (chief == AGENT_ID) store.sendAgent(CHIEF_BRIEF_TASK) else store.send(chatId, CHIEF_BRIEF_TASK)
                            go(if (chief == AGENT_ID) Screen.AgentChat else Screen.Chat(chatId))
                        }
                    }
                } else {
                    BixTip("先选一只 Bot 当总参谋,推荐用推理能力强的模型。", BixAction.Briefing)
                }
            }
            HiveSection(
                "示教录制", BixAction.Teaching,
                "你在任意 App 里亲手做一遍,Bix 通过无障碍服务记下每一步(密码会被打码,最长 10 分钟),再整理成一份六段式技能。"
            ) {
                val recording = TeachRecorder.recording.value
                Field("你要示范的目标(例如:在某 App 里导出本月账单)", goal, { goal = it }, singleLine = false, minLines = 2)
                Text("学会后交给谁", color = Sub, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    owners.forEach { (id, n) -> HiveChip(n, teachOwner == id) { teachOwner = id } }
                }
                if (!recording) {
                    PillButton("开始示教") {
                        val err = TeachRecorder.start()
                        if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                        else Toast.makeText(ctx, BixVoice.teachRecording(), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BixScene(action = BixAction.Teaching, compact = true)
                        Spacer(Modifier.width(8.dp))
                        Text("录制中 · 已记下 ${TeachRecorder.steps.size} 步", color = BeeDanger, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                    PillButton("结束录制") {
                        lastSteps = TeachRecorder.stop()
                        Toast.makeText(ctx, BixVoice.teachSaved(lastSteps.size), Toast.LENGTH_SHORT).show()
                    }
                }
                val shown = if (recording) TeachRecorder.steps.toList() else lastSteps
                if (shown.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        shown.takeLast(60).forEachIndexed { i, s ->
                            Text("${i + 1}. $s", color = Sub, fontSize = 11.sp, fontFamily = HiveFonts.Mono)
                        }
                    }
                }
                if (!recording && lastSteps.isNotEmpty()) {
                    PillButton(if (drafting) "Bix 正在整理…" else "生成技能草稿") {
                        if (drafting) return@PillButton
                        if (teachOwner.isBlank()) {
                            Toast.makeText(ctx, "先选一个执行者", Toast.LENGTH_SHORT).show()
                            return@PillButton
                        }
                        drafting = true
                        scope.launch {
                            try {
                                val sk = store.draftSkillFromTeach(teachOwner, goal.ifBlank { "示教任务" }, lastSteps)
                                store.saveSkill(sk)
                                go(Screen.EditSkill(sk.id))
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "生成失败:${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                drafting = false
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
