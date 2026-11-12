package app.opengrok

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Header action: export the conversation as Markdown, either shared or saved to a user-picked file. */
@Composable
fun ExportButton(store: Store, chatId: String) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(store.exportMarkdown(chatId).toByteArray()) }
            }.isSuccess
            Toast.makeText(ctx, if (ok) "已保存" else "保存失败", Toast.LENGTH_SHORT).show()
        }
    }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Ic.Share, "导出", tint = Fg) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("分享 Markdown") }, onClick = {
                menu = false
                val send = Intent(Intent.ACTION_SEND).setType("text/markdown")
                    .putExtra(Intent.EXTRA_SUBJECT, store.title(chatId))
                    .putExtra(Intent.EXTRA_TEXT, store.exportMarkdown(chatId))
                ctx.startActivity(Intent.createChooser(send, "导出对话"))
            })
            DropdownMenuItem(text = { Text("保存为 .md 文件") }, onClick = {
                menu = false
                val safe = store.title(chatId).replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
                saver.launch("$safe.md")
            })
            DropdownMenuItem(text = { Text("清空这段对话") }, onClick = {
                menu = false
                askClear = true
            })
        }
    }
    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text("清空这段对话?") },
            text = { Text("聊天记录会被清空,Bot/群/Agent 本身与设置不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    askClear = false
                    store.clearChat(chatId)
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { askClear = false }) { Text("取消") } }
        )
    }
}

private class Hit(val chatKey: String, val title: String, val sessionId: String?, val snippet: String, val time: Long)

private fun snippet(text: String, q: String): String {
    val one = text.replace(Regex("\\s+"), " ")
    val i = one.indexOf(q, ignoreCase = true).coerceAtLeast(0)
    val start = (i - 30).coerceAtLeast(0)
    val end = (i + q.length + 50).coerceAtMost(one.length)
    return (if (start > 0) "…" else "") + one.substring(start, end) + (if (end < one.length) "…" else "")
}

/** Full-text search over message bodies of every conversation; tap a hit to open that chat. */
@Composable
fun SearchScreen(store: Store, go: (Screen) -> Unit) {
    var q by remember { mutableStateOf("") }
    val hits = remember(q) {
        val k = q.trim()
        if (k.length < 1) emptyList() else store.searchableChats().flatMap { (key, title, sid) ->
            store.chat(key).filter { it.text.contains(k, true) }.map { Hit(key, title, sid, snippet(it.text, k), it.time) }
        }.sortedByDescending { it.time }.take(300)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(Screen.Home) }) { Icon(Ic.Back, "返回", tint = Fg) }
            TextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("搜索全部对话的消息", color = Sub) },
                leadingIcon = { Icon(Ic.Search, null, tint = Sub) },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Card, unfocusedContainerColor = Card,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Fg, unfocusedTextColor = Fg
                )
            )
        }
        HorizontalDivider(color = Line)
        if (q.isNotBlank() && hits.isEmpty()) {
            Text("没有找到", color = Sub, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(hits) { h ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable {
                        if (h.sessionId != null) store.openAgentSession(h.sessionId)
                        go(if (h.chatKey == AGENT_ID || h.sessionId != null) Screen.AgentChat else Screen.Chat(h.chatKey))
                    }.padding(12.dp)
                ) {
                    Row {
                        Text(h.title, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (h.time > 0) Text(timeAgo(h.time), color = Sub, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    val k = q.trim()
                    Text(buildAnnotatedString {
                        var rest = h.snippet
                        while (true) {
                            val i = rest.indexOf(k, ignoreCase = true)
                            if (i < 0 || k.isEmpty()) { append(rest); break }
                            append(rest.substring(0, i))
                            withStyle(SpanStyle(color = Honey, fontWeight = FontWeight.Bold)) { append(rest.substring(i, i + k.length)) }
                            rest = rest.substring(i + k.length)
                        }
                    }, color = Sub, fontSize = 13.sp, maxLines = 3)
                }
            }
        }
    }
}
