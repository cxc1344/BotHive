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
            Text("连接自检", color = Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("检查接口连通性、/models 拉取、对话接口、沙盒状态与 Key 加密存储", color = Sub, fontSize = 12.sp)
            if (store.bots.isEmpty()) Text("还没有 Bot", color = Sub)
            store.bots.forEach { b ->
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
            Text("Open GrokBot 0.2.0(P0)", color = Sub, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}
