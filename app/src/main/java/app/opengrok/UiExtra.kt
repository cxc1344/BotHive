package app.opengrok

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun ProjectCard(store: Store, pid: String, go: (Screen) -> Unit) {
    val p = store.projects.firstOrNull { it.id == pid }
    if (p == null) {
        Text("(项目记录已不存在)", color = Sub, fontSize = 12.sp)
        return
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.Home) }) { Icon(Ic.Back, null, tint = Fg) }
            Text("设置", color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            store.persistenceError.value?.let {
                Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp)
            }
            Text("Provider 预设", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("把常用服务商的 Base URL 与 Key 存成预设,建 Bot / Agent 时直接选用并从其模型列表点选,免去反复粘贴。Key 用设备 Keystore 加密保存。", color = Sub, fontSize = 12.sp)
            if (store.providers.isEmpty()) Text("还没有预设", color = Sub)
            store.providers.forEach { p ->
                val usedBy = store.bots.count { it.providerId == p.id } + if (store.agent.value?.providerId == p.id) 1 else 0
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
                        .clickable { go(Screen.EditProvider(p.id)) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = Fg, fontWeight = FontWeight.SemiBold)
                        Text(p.baseUrl + if (usedBy > 0) " · $usedBy 处在用" else "", color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Ic.Edit, null, tint = Sub)
                }
            }
            PillButton("添加 Provider", filled = false, modifier = Modifier.fillMaxWidth()) { go(Screen.EditProvider(null)) }
            HorizontalDivider(color = Line)
            Text("技能包", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("一段可复用的指令(规范、风格、领域知识),在 Bot / Agent 的编辑页勾选后会拼进它的 System Prompt。", color = Sub, fontSize = 12.sp)
            if (store.skills.isEmpty()) Text("还没有技能包", color = Sub)
            store.skills.forEach { sk ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
                        .clickable { go(Screen.EditSkill(sk.id)) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sk.name, color = Fg, fontWeight = FontWeight.SemiBold)
                        Text(sk.content, color = Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Ic.Edit, null, tint = Sub)
                }
            }
            PillButton("新建技能包", filled = false, modifier = Modifier.fillMaxWidth()) { go(Screen.EditSkill(null)) }
            HorizontalDivider(color = Line)
            Text("MCP 服务", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("接入远程 MCP(Model Context Protocol)服务后,它的工具会出现在所有 Bot 与 Agent 的工具列表里,和内置工具一起被调用。", color = Sub, fontSize = 12.sp)
            if (store.mcpServers.isEmpty()) Text("还没有 MCP 服务", color = Sub)
            store.mcpServers.forEach { sv ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
                        .clickable { go(Screen.EditMcp(sv.id)) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sv.name, color = Fg, fontWeight = FontWeight.SemiBold)
                        Text(sv.url + if (sv.enabled) "" else " · 已停用", color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Ic.Edit, null, tint = Sub)
                }
            }
            PillButton("添加 MCP 服务", filled = false, modifier = Modifier.fillMaxWidth()) { go(Screen.EditMcp(null)) }
            HorizontalDivider(color = Line)
            Text("例行任务", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Bot 与 Agent 用 schedule_task 工具给自己派的定时任务会出现在这里。注意:任务在 App 进程活着时触发,App 被系统杀掉期间到点的任务会顺延到下次打开。", color = Sub, fontSize = 12.sp)
            if (store.routines.isEmpty()) Text("暂无例行任务", color = Sub)
            store.routines.sortedBy { it.nextRunMs }.forEach { r ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${store.botName(r.ownerId)} · ${fmt.format(Date(r.nextRunMs))}${if (r.repeatDaily) " · 每天" else ""} · 由 ${r.createdBy} 创建",
                            color = Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("删除", color = Sub, modifier = Modifier.clickable { store.deleteRoutine(r.id) }.padding(6.dp))
                    }
                    Text(r.task, color = Fg, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    if (r.lastResult.isNotBlank()) Text("上次结果:${r.lastResult}", color = Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            HorizontalDivider(color = Line)
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
                    androidx.compose.material3.Switch(checked = kaOn, onCheckedChange = { on ->
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
            HorizontalDivider(color = Line)
            Text("Linux 环境", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("每个 Bot 与 Agent 拥有独立的 Ubuntu 24.04 文件系统和工作目录。首次 shell 执行会下载约 29 MB 基础系统,并自动预装 python3、nodejs、git 等常用软件(还需再下载上百 MB,建议在 Wi-Fi 下首次使用)。PRoot 是用户态 Linux 环境,不是完整虚拟机或强安全边界。", color = Sub, fontSize = 12.sp)
            HorizontalDivider(color = Line)
            Text("连接自检", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("检查接口连通性、/models 拉取、对话接口、沙盒状态与 Key 加密存储", color = Sub, fontSize = 12.sp)
            val checkTargets = store.bots + listOfNotNull(store.agent.value)
            if (checkTargets.isEmpty()) Text("还没有 Bot", color = Sub)
            checkTargets.forEach { b ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp),
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
                            Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)
                                .clickable(enabled = b.id !in running) {
                                    running.add(b.id)
                                    scope.launch {
                                        results[b.id] = try { SelfCheck.run(store, b) } catch (e: Exception) { listOf(false to "自检异常:${e.message}") }
                                        running.remove(b.id)
                                    }
                                }.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) { Text(if (b.id in running) "检测中…" else "自检", color = Color.Black, fontSize = 13.sp) }
                    }
                    results[b.id]?.forEach { (ok, msg) ->
                        Text((if (ok) "通过  " else "失败  ") + msg,
                            color = if (ok) Fg else Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                }
            }
            HorizontalDivider(color = Line)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("安全拦截记录", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (store.guardLog.isNotEmpty()) Text("清空", color = Sub, modifier = Modifier.clickable {
                    store.guardLog.clear(); store.save()
                }.padding(8.dp))
            }
            if (store.guardLog.isEmpty()) Text("暂无。Bot 的 shell 命令执行前会被检查,危险命令会被拦下并记录在这里。", color = Sub, fontSize = 12.sp)
            store.guardLog.reversed().take(50).forEach { e ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp)) {
                    Text("${fmt.format(Date(e.time))} · ${store.botName(e.botId)} · ${e.reason}", color = Sub, fontSize = 12.sp)
                    Text(e.cmd, color = Fg, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("OpenGrokBot 0.4.0", color = Sub, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}
