package app.opengrok

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

// ============================================================
// 1. Bot / Agent Sandbox File Cabinet (沙盒文件柜)
// ============================================================

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
    else -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
}

@Composable
fun SandboxFilesSheet(
    store: Store,
    botId: String,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val ctx = LocalContext.current
    val rootDir = remember(botId) { store.sandbox(botId).dir }
    var currentDir by remember(botId) { mutableStateOf(rootDir) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var previewFile by remember { mutableStateOf<File?>(null) }

    val entries = remember(currentDir, refreshTick) {
        if (!currentDir.exists()) emptyList()
        else currentDir.listFiles()
            ?.filter { !it.name.startsWith(".og-") }
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }
    val relPath = remember(currentDir, rootDir) {
        val r = currentDir.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/')
        if (r.isBlank()) "/workspace" else "/workspace/$r"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BixSummaryHalfSheet(
            title = "沙盒文件柜",
            subtitle = "${entries.size} 项",
            bixAction = BixAction.Searching,
            visible = true,
            onDismiss = onDismiss
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentDir.absolutePath != rootDir.absolutePath && currentDir.parentFile != null) {
                    SmallChip("上级目录") { currentDir = currentDir.parentFile ?: rootDir }
                }
                Text(relPath, color = Sub, fontSize = 11.sp, fontFamily = HiveFonts.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                SmallChip("刷新") { refreshTick++ }
            }

            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("工作目录暂时为空", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("让 Bot 在 Ubuntu 沙盒里生成脚本、图表或报告后，都会出现在这里。", color = Sub, fontSize = 12.sp)
                }
            } else {
                entries.forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 14.dp, fallbackBg = PILL)
                            .clickable {
                                if (f.isDirectory) currentDir = f else previewFile = f
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (f.isDirectory) Ic.Tray else Ic.Artifacts,
                            null,
                            tint = if (f.isDirectory) ThemeAccentPale else Sub,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (f.isDirectory) "文件夹" else formatFileSize(f.length()),
                                color = Sub, fontSize = 11.sp
                            )
                        }
                        Icon(Ic.Chevron, null, tint = Sub, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    previewFile?.let { file ->
        val lower = file.name.lowercase()
        val isImage = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")
        AlertDialog(
            onDismissRequest = { previewFile = null },
            title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isImage) {
                        val bmp = remember(file.absolutePath) {
                            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                        }
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(), file.name,
                                Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text("无法解码图片文件", color = Sub, fontSize = 12.sp)
                        }
                    } else {
                        val content = remember(file.absolutePath) {
                            runCatching {
                                if (file.length() > 64 * 1024) file.readText().take(6000) + "\n…(文件较大，仅预览前 6000 字符)"
                                else file.readText()
                            }.getOrElse { "(二进制文件或无法读取：${it.message})" }
                        }
                        Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            Text(content, color = Fg, fontSize = 12.sp, fontFamily = HiveFonts.Mono)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isImage) {
                            SmallChip("复制文本") {
                                val txt = runCatching { file.readText().take(20000) }.getOrDefault("")
                                // v0.7.0 (F16): sandbox files (.env, configs) often hold secrets.
                                copySensitive(ctx, file.name, txt, "已复制内容")
                            }
                            SmallChip("分享") {
                                val txt = runCatching { file.readText().take(20000) }.getOrDefault("")
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, file.name)
                                    .putExtra(Intent.EXTRA_TEXT, txt)
                                runCatching { ctx.startActivity(Intent.createChooser(send, "分享 ${file.name}")) }
                            }
                        }
                        Box(
                            Modifier.pressBounce {
                                if (file.delete()) {
                                    refreshTick++
                                    previewFile = null
                                    Toast.makeText(ctx, "已删除 ${file.name}", Toast.LENGTH_SHORT).show()
                                }
                            }.clip(RoundedCornerShape(14.dp)).background(BeeDanger.copy(alpha = 0.16f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text("删除", color = BeeDanger, fontSize = 12.sp) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { previewFile = null }) { Text("关闭") } }
        )
    }
}

// ============================================================
// 2. Visual Memory Brain Editor (记忆大脑面板)
// ============================================================

@Composable
fun MemoryBrainSheet(
    store: Store,
    botId: String,
    botName: String,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val ctx = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    // v0.7.0 memory 2.0: per-Bot switch, shared user facts, feedback → rule suggestions.
    var memoryOn by remember(botId) { mutableStateOf(ModelPrefs.isMemoryEnabled(botId, ctx)) }
    var factDraft by remember { mutableStateOf("") }
    var distilling by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val picked = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val facts = remember(rev) { store.userFacts() }

    val items = remember(botId, rev) {
        store.memories[botId]?.toList().orEmpty()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BixSummaryHalfSheet(
            title = "${botName}的记忆大脑",
            subtitle = "${items.size} 条",
            bixAction = BixAction.Thinking,
            visible = true,
            onDismiss = onDismiss
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HiveSwitchRow(
                    "长期记忆", if (memoryOn) "开启:对话时会带上下面的记忆" else "关闭:不读取也不写入这只 Bot 的记忆", memoryOn
                ) { on -> ModelPrefs.setMemoryEnabled(ctx, botId, on); memoryOn = on }

                // Shared user facts: every Bot sees these (name, time zone, preferences).
                Text("关于你(所有 Bot 共享)", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (facts.isEmpty()) {
                    Text("还没有。比如「我叫小陈,时区北京时间,回复请用中文」。", color = Sub, fontSize = 12.sp)
                } else {
                    facts.forEachIndexed { i, f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("• $f", color = Fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Box(
                                Modifier.size(24.dp).pressBounce { store.removeUserFact(i); rev++ }
                                    .clip(CircleShape).background(BeeDanger.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Ic.Close, "删除", tint = BeeDanger, modifier = Modifier.size(12.dp)) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Field("添加一条关于你的事实", factDraft, { factDraft = it }) }
                    SmallChip("添加") {
                        if (factDraft.isNotBlank()) { store.addUserFact(factDraft.trim()); factDraft = ""; rev++ }
                    }
                }

                // Feedback → rules: thumbs on replies are distilled into suggestions you approve.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("从反馈里学习", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    SmallChip(if (distilling) "Bix 整理中…" else "整理反馈") {
                        if (distilling) return@SmallChip
                        distilling = true
                        scope.launch {
                            try {
                                suggestions = store.distillFeedback(botId)
                                picked.clear(); picked.addAll(suggestions)
                                if (suggestions.isEmpty()) Toast.makeText(ctx, "没有足够的反馈可整理,先给几条回复点赞或点踩吧", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(ctx, e.message ?: "整理失败", Toast.LENGTH_LONG).show()
                            } finally {
                                distilling = false
                            }
                        }
                    }
                }
                if (suggestions.isNotEmpty()) {
                    BixTip("Bix 从你的点赞和点踩里总结出这些规则,勾选后应用(会写成一个技能挂到这只 Bot 上):", BixAction.Thinking)
                    suggestions.forEach { rule ->
                        val on = rule in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) picked.remove(rule) else picked.add(rule) }
                                .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 12.dp, fallbackBg = PILL)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (on) Ic.Check else Ic.Close, null, tint = if (on) BeeGreen else Sub, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(rule, color = if (on) Fg else Sub, fontSize = 13.sp)
                        }
                    }
                    PillButton("应用选中的 ${picked.size} 条") {
                        if (picked.isNotEmpty()) {
                            store.applyFeedbackRules(botId, picked.toList())
                            Toast.makeText(ctx, "Bix 记住了,下一轮对话起生效", Toast.LENGTH_SHORT).show()
                            suggestions = emptyList()
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("这只 Bot 的记忆", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Field("写入一条新记忆（偏好、规范或事实）", draft, { draft = it }, singleLine = false, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Field("可选标签（如 偏好/项目）", category, { category = it })
                    }
                    Box(
                        Modifier.pressBounce {
                            if (draft.isNotBlank()) {
                                store.remember(botId, draft.trim(), category.trim())
                                draft = ""
                                category = ""
                                rev++
                                Toast.makeText(ctx, "已写入长期记忆", Toast.LENGTH_SHORT).show()
                            }
                        }.clip(RoundedCornerShape(14.dp)).background(PillWhite)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) { Text("记住", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }

                Spacer(Modifier.height(4.dp))
                if (items.isEmpty()) {
                    Text("这只小蜂还没有长期记忆。对话中 AI 主动调用 remember，或你直接在上方写入，都会持久化保存在这里。", color = Sub, fontSize = 12.sp, lineHeight = 18.sp)
                } else {
                    items.asReversed().forEachIndexed { revIdx, entry ->
                        val origIdx = items.lastIndex - revIdx
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 14.dp, fallbackBg = PILL)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(entry, color = Fg, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.size(26.dp).pressBounce {
                                    store.removeMemory(botId, origIdx)
                                    rev++
                                }.clip(CircleShape).background(BeeDanger.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Ic.Close, "遗忘", tint = BeeDanger, modifier = Modifier.size(13.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 3. Proactive Routine Inbox & 1-Tap Daily Briefing Creator (主动汇报收件箱)
// ============================================================

private data class QuickRoutineTpl(val label: String, val task: String, val hour: Int, val minute: Int)

private val QUICK_ROUTINES = listOf(
    QuickRoutineTpl("每日 8:30 科技早报", "用独立浏览器查看今天最新 AI 与科技头条，整理 5 条核心简报发给我。", 8, 30),
    QuickRoutineTpl("每日 21:00 存储巡检", "检查一次手机电量、存储与内存占用，如有异常或大文件提醒我。", 21, 0),
    QuickRoutineTpl("15 分钟后提醒", "15 分钟后提醒我喝水并休息 2 分钟，顺便汇报当前任务进度。", -1, 15)
)

@Composable
fun ProactiveInboxSection(store: Store, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val routines = store.routines.sortedByDescending { maxOf(it.lastRunMs, it.nextRunMs) }

    Column(
        Modifier
            .fillMaxWidth()
            .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 18.dp, fallbackBg = Card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Clock, null, tint = ThemeAccentPale, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("主动汇报收件箱", color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${routines.size} 项定时", color = Sub, fontSize = 11.sp)
        }

        if (routines.isEmpty()) {
            Text("暂无主动定时任务。点下方快捷胶囊，让 Bot 每天定时在后台跑完后主动向你汇报：", color = Sub, fontSize = 12.sp, lineHeight = 17.sp)
        } else {
            routines.take(3).forEach { r ->
                val targetScreen = if (r.ownerId == AGENT_ID) Screen.AgentChat else Screen.Chat("bot:${r.ownerId}")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .harmonyGlass(thickness = ImmersiveThickness.THIN, radius = 14.dp, fallbackBg = PILL)
                        .clickable { go(targetScreen) }
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(6.dp).clip(CircleShape)
                                .background(if (r.lastResult.isNotBlank()) BeeGreen else ThemeAccent)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${store.botName(r.ownerId)} · 下次 ${fmt.format(Date(r.nextRunMs))}" + if (r.repeatDaily) " · 每天" else "",
                            color = ThemeAccentPale, fontSize = 11.sp, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "删除", color = Sub, fontSize = 11.sp,
                            modifier = Modifier.clickable { store.deleteRoutine(r.id) }.padding(horizontal = 4.dp)
                        )
                    }
                    Text(r.task, color = Fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.lastResult.isNotBlank()) {
                        Text("最新汇报：${r.lastResult}", color = Sub, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QUICK_ROUTINES.forEach { tpl ->
                SmallChip("+ ${tpl.label}") {
                    val owner = store.bots.firstOrNull()?.id ?: AGENT_ID
                    val nextMs = if (tpl.hour < 0) {
                        System.currentTimeMillis() + tpl.minute * 60_000L
                    } else {
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, tpl.hour)
                            set(Calendar.MINUTE, tpl.minute)
                            set(Calendar.SECOND, 0)
                            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                        }
                        cal.timeInMillis
                    }
                    store.addRoutine(
                        Routine(
                            id = UUID.randomUUID().toString().take(8),
                            ownerId = owner,
                            task = tpl.task,
                            nextRunMs = nextMs,
                            repeatDaily = tpl.hour >= 0,
                            createdBy = "user"
                        )
                    )
                    Toast.makeText(ctx, "已安排「${tpl.label}」给 ${store.botName(owner)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
