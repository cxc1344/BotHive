package app.opengrok

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.7.2 On-Device Ubuntu Linux Desktop Screen ("Ubuntu 桌面 · AI 电脑"):
 *  - Embeds live noVNC WebView of BotHive's on-device PRoot Ubuntu 24.04 Fluxbox desktop.
 *  - Shows a pulsing Liquid Glass banner "（AI名）正在使用中" whenever any Agent/Bot is driving it.
 *  - Provides 1-tap "我来接管" / "交回给 AI" control switch plus quick Ubuntu desktop toolbar.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LinuxDesktopScreen(store: Store, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showTypeDialog by remember { mutableStateOf(false) }

    val deskState = LinuxDesktop.state.value
    val aiOwner = LinuxDesktop.activeAiOwner.value
    val takeover = LinuxDesktop.userTakeover.value
    val reloadSeq = LinuxDesktop.webReloadSeq.intValue

    LaunchedEffect(Unit) {
        val up = withContext(Dispatchers.IO) { LinuxDesktop.isPortOpen() }
        if (up && LinuxDesktop.state.value != DesktopState.RUNNING) {
            LinuxDesktop.state.value = DesktopState.RUNNING
            LinuxDesktop.webReloadSeq.intValue += 1
        }
    }

    Column(Modifier.fillMaxSize()) {
        HivePageHeader(
            title = "Ubuntu 轻量桌面",
            bix = when {
                aiOwner.isNotBlank() && !takeover -> BixAction.Coding
                takeover -> BixAction.Handoff
                deskState == DesktopState.RUNNING -> BixAction.Router
                else -> BixAction.Idle
            },
            onBack = { go(Screen.Home) },
            trailing = {
                if (deskState == DesktopState.RUNNING) {
                    SmallChip(if (takeover) "交回给 AI" else "我来接管") {
                        LinuxDesktop.setTakeover(!takeover)
                    }
                } else {
                    SmallChip("启动") {
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) { LinuxDesktop.start(ctx) }
                            Toast.makeText(ctx, msg.take(60), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )

        // Live Ownership / Status Banner
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 16.dp, fallbackBg = Card)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val dotColor = when {
                aiOwner.isNotBlank() && !takeover -> Color(0xFFC8FF00)
                takeover -> ThemeAccent
                deskState == DesktopState.RUNNING -> BeeGreen
                deskState == DesktopState.ERROR -> BeeDanger
                else -> Sub
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(dotColor))
            Column(Modifier.weight(1f)) {
                val headline = when {
                    takeover -> "你正在接管 Ubuntu 桌面（AI 已暂停触碰）"
                    aiOwner.isNotBlank() -> "（$aiOwner）正在使用中"
                    deskState == DesktopState.RUNNING -> "Ubuntu Fluxbox 桌面待命中（AI 与你均可使用）"
                    else -> deskState.label
                }
                Text(headline, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(LinuxDesktop.statusLog.value, color = Sub, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (deskState == DesktopState.RUNNING) {
                SmallChip(if (takeover) "交回 AI" else "接管") {
                    LinuxDesktop.setTakeover(!takeover)
                }
            }
        }

        // Main Interactive Ubuntu noVNC Desktop Surface
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF090B0E))
        ) {
            if (deskState == DesktopState.RUNNING) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            webViewClient = WebViewClient()
                            loadUrl(LinuxDesktop.NOVNC_URL)
                        }
                    },
                    update = { wv ->
                        if (wv.tag != reloadSeq) {
                            wv.tag = reloadSeq
                            wv.loadUrl(LinuxDesktop.NOVNC_URL)
                        }
                    }
                )

                // Soft protective AI-active badge overlay while AI is driving & user hasn't taken over
                if (aiOwner.isNotBlank() && !takeover) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0x14000000))
                            .clickable {
                                LinuxDesktop.setTakeover(true)
                                Toast.makeText(ctx, "已接管 Ubuntu 桌面，现在可直接点击操作", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Row(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 999.dp, fallbackBg = Card)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BixScene(action = BixAction.Coding, compact = true)
                            Text("（$aiOwner）正在使用中 · 点画面随时接管", color = Color(0xFFC8FF00), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                // Stopped / Installing / Starting Landing Stage
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    BixScene(
                        action = if (deskState == DesktopState.INSTALLING || deskState == DesktopState.STARTING) BixAction.Coding else BixAction.Idle,
                        compact = false
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (deskState == DesktopState.INSTALLING) "首次安装 Fluxbox 轻量桌面中…" else "手机本地 Ubuntu 24.04 轻量桌面",
                        color = Fg,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "直接复用 BotHive 本地 Ubuntu PRoot 容器开启 Fluxbox + noVNC（1280×720），无需外接电脑。AI 可看屏操作并标示「（AI名）正在使用中」，你随时一键接管。",
                        color = Sub,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    val busy = deskState == DesktopState.INSTALLING || deskState == DesktopState.STARTING
                    PillButton(if (busy) "正在准备桌面…" else "启动 Ubuntu 轻量桌面", modifier = Modifier.fillMaxWidth(0.75f)) {
                        if (busy) return@PillButton
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) { LinuxDesktop.start(ctx) }
                            Toast.makeText(ctx, msg.take(70), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // Bottom Quick Desktop Controls
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallChip("重连画面") { LinuxDesktop.webReloadSeq.intValue += 1 }
            SmallChip("开终端 xterm") {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { LinuxDesktop.openApp(ctx, "用户", "xterm -fa Monospace -fs 11") }
                    Toast.makeText(ctx, r, Toast.LENGTH_SHORT).show()
                }
            }
            SmallChip("文件管理") {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { LinuxDesktop.openApp(ctx, "用户", "pcmanfm") }
                    Toast.makeText(ctx, r, Toast.LENGTH_SHORT).show()
                }
            }
            SmallChip("输入文本") { showTypeDialog = true }
            SmallChip("Ctrl+C") {
                scope.launch { withContext(Dispatchers.IO) { LinuxDesktop.typeOrKey(ctx, "用户", key = "ctrl+c") } }
            }
            SmallChip("重启/停止") {
                scope.launch {
                    withContext(Dispatchers.IO) { LinuxDesktop.stop(ctx) }
                    Toast.makeText(ctx, "Ubuntu 桌面已停止", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showTypeDialog) {
        var txt by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTypeDialog = false },
            containerColor = Card,
            title = { Text("向 Ubuntu 桌面输入文本", color = Fg) },
            text = { Field("输入内容（发送后自动回车）", txt, { txt = it }) },
            confirmButton = {
                TextButton(onClick = {
                    val v = txt
                    showTypeDialog = false
                    if (v.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { LinuxDesktop.typeOrKey(ctx, "用户", text = v, key = "Return") }
                        }
                    }
                }) { Text("发送", color = ThemeAccent) }
            },
            dismissButton = { TextButton(onClick = { showTypeDialog = false }) { Text("取消", color = Sub) } }
        )
    }
}
