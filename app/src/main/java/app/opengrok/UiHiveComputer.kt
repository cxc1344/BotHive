package app.opengrok

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.7.2 Hive Computer Screen ("Bot 专属电脑"):
 *  - Tab 0: Live Remote PC Desktop Viewer (tap anywhere to click remotely + type/hotkey/scroll).
 *  - Tab 1: Remote PC Terminal + 24x7 Background Daemon Manager + Audit Log.
 *  - Tab 2: On-Device Local Workstation (Ubuntu 24.04 PRoot + AI Browser + KeepAlive + PC Setup Guide).
 */
@Composable
fun HiveComputerScreen(store: Store, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var showPairDialog by remember { mutableStateOf(false) }
    var showTypeDialog by remember { mutableStateOf(false) }
    var showAddDaemon by remember { mutableStateOf(false) }
    var busyAction by remember { mutableStateOf(false) }
    var cmdInput by remember { mutableStateOf("uname -a || ver") }
    var cmdOutput by remember { mutableStateOf("") }

    val connected = HiveComputer.connected.value
    val shotPath = HiveComputer.lastShotPath.value
    val shotVer = HiveComputer.shotVersion.intValue

    LaunchedEffect(Unit) {
        if (HiveComputer.isConfigured()) {
            withContext(Dispatchers.IO) { HiveComputer.refreshStatus() }
        }
    }

    val previewBmp = remember(shotPath, shotVer) {
        val f = File(shotPath)
        if (shotPath.isNotBlank() && f.isFile) runCatching { BitmapFactory.decodeFile(shotPath)?.asImageBitmap() }.getOrNull()
        else null
    }

    Column(Modifier.fillMaxSize()) {
        HivePageHeader(
            title = "Bot 专属电脑 · Hive PC",
            bix = when (tab) {
                0 -> BixAction.DesktopPC
                1 -> BixAction.Coding
                else -> BixAction.Router
            },
            onBack = { go(Screen.Home) },
            trailing = {
                SmallChip(if (connected) "已连接" else "配对 PC") { showPairDialog = true }
            }
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Connection & Host Card
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 20.dp, fallbackBg = Card)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (connected) BeeGreen else Sub)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (connected) "${HiveComputer.alias.value} · ${HiveComputer.hostName.value}" else "未连接 BotHive Desktop PC 端",
                                color = Fg,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (connected) {
                                    "${HiveComputer.osInfo.value} · ${HiveComputer.screenInfo.value.ifBlank { "无头模式" }} · 在线 ${HiveComputer.uptimeSec.intValue / 60}m"
                                } else {
                                    HiveComputer.lastError.value.ifBlank { "在 PC 运行 desktop/bothive_pc.py 后点击右上角配对" }
                                },
                                color = Sub,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(if (busyAction) "连接中…" else "刷新状态", modifier = Modifier.weight(1f)) {
                            if (busyAction) return@PillButton
                            busyAction = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { HiveComputer.refreshStatus() }
                                busyAction = false
                                Toast.makeText(ctx, if (ok) "PC 端已在线握手" else "未连上：${HiveComputer.lastError.value}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        PillButton("配对设置", filled = false, modifier = Modifier.weight(0.9f)) { showPairDialog = true }
                        PillButton("截屏", filled = false, modifier = Modifier.weight(0.75f)) {
                            if (busyAction) return@PillButton
                            busyAction = true
                            scope.launch {
                                val f = withContext(Dispatchers.IO) { HiveComputer.captureScreenshot(ctx) }
                                busyAction = false
                                if (f == null) Toast.makeText(ctx, HiveComputer.lastError.value.ifBlank { "截屏失败" }, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            // 2. Tabs + Bix Shuttle
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HiveChip("桌面直播 & 操控", tab == 0) { tab = 0 }
                        HiveChip("终端 & 24×7 守护 (${HiveComputer.daemons.size})", tab == 1) { tab = 1 }
                        HiveChip("本机工作站 & 部署", tab == 2) { tab = 2 }
                    }
                    BixTabFlightTrack(
                        selectedIndex = tab,
                        tabCount = 3,
                        poses = listOf(BixAction.DesktopPC, BixAction.Coding, BixAction.Router)
                    )
                }
            }

            // 3. Tab Bodies
            if (tab == 0) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 18.dp, fallbackBg = Card)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("远程 PC 实时画面（点击画面任意位置直接操控鼠标）", color = Sub, fontSize = 12.sp)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 10f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0A0C10))
                                .pointerInput(connected) {
                                    detectTapGestures { offset ->
                                        if (!connected) {
                                            Toast.makeText(ctx, "请先连接 PC 端", Toast.LENGTH_SHORT).show()
                                            return@detectTapGestures
                                        }
                                        val nx = (offset.x / size.width.coerceAtLeast(1)) * 1000.0
                                        val ny = (offset.y / size.height.coerceAtLeast(1)) * 1000.0
                                        scope.launch {
                                            val res = withContext(Dispatchers.IO) {
                                                val r = HiveComputer.desktopAction("click", xNorm = nx, yNorm = ny)
                                                HiveComputer.captureScreenshot(ctx)
                                                r
                                            }
                                            Toast.makeText(ctx, res, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (previewBmp != null) {
                                Image(
                                    bitmap = previewBmp,
                                    contentDescription = "PC Desktop",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BixScene(action = BixAction.DesktopPC, compact = false)
                                    Text(
                                        if (connected) "点击下方「刷新画面」获取 PC 实时桌面" else "绑定 BotHive Desktop 后可实时围观并操控 PC",
                                        color = Sub,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallChip("刷新画面") {
                                scope.launch { withContext(Dispatchers.IO) { HiveComputer.captureScreenshot(ctx) } }
                            }
                            SmallChip("输入文本") { showTypeDialog = true }
                            SmallChip("Ctrl+C") {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { HiveComputer.desktopAction("hotkey", keys = "ctrl+c") }
                                    Toast.makeText(ctx, r, Toast.LENGTH_SHORT).show()
                                }
                            }
                            SmallChip("Alt+Tab") {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { HiveComputer.desktopAction("hotkey", keys = "alt+tab") }
                                    withContext(Dispatchers.IO) { HiveComputer.captureScreenshot(ctx) }
                                    Toast.makeText(ctx, r, Toast.LENGTH_SHORT).show()
                                }
                            }
                            SmallChip("向下滚动") {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        HiveComputer.desktopAction("scroll", delta = -4)
                                        HiveComputer.captureScreenshot(ctx)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (tab == 1) {
                // Remote Terminal
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 18.dp, fallbackBg = Card)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("PC 远程终端直连", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Field("输入 Shell / PowerShell 命令", cmdInput, { cmdInput = it })
                        PillButton("在 PC 上执行", modifier = Modifier.fillMaxWidth()) {
                            scope.launch {
                                cmdOutput = "执行中…"
                                cmdOutput = withContext(Dispatchers.IO) { HiveComputer.exec(cmdInput) }
                            }
                        }
                        if (cmdOutput.isNotBlank()) {
                            Text(
                                text = cmdOutput.take(1600),
                                color = BeeGreen,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0A0D10))
                                    .padding(10.dp)
                            )
                        }
                    }
                }
                // 24x7 Daemons
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("PC 24×7 后台守护进程", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        SmallChip("+ 启动常驻") { showAddDaemon = true }
                    }
                }
                if (HiveComputer.daemons.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BixScene(action = BixAction.Watchdog, compact = false)
                            Text("PC 端暂无运行中的常驻进程，可点右上角启动监控/爬虫/服务。", color = Sub, fontSize = 12.sp)
                        }
                    }
                } else {
                    items(HiveComputer.daemons.toList(), key = { it.name }) { d ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .harmonyGlass(thickness = ImmersiveThickness.REGULAR, radius = 14.dp, fallbackBg = Card)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (d.running) BeeGreen else Sub))
                            Column(Modifier.weight(1f)) {
                                Text("${d.name} (PID ${d.pid})", color = Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(d.command, color = Sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (d.lastLine.isNotBlank()) {
                                    Text(d.lastLine, color = ThemeAccentPale, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (d.running) {
                                SmallChip("停止") {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { HiveComputer.manageDaemon("stop", name = d.name) }
                                        Toast.makeText(ctx, r, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Local Workstation + PC Setup Guide
                item {
                    HiveSection(
                        title = "本机袖珍工作站（零服务器）",
                        bix = BixAction.Coding,
                        subtitle = "手机本地自带 Ubuntu 24.04 PRoot 沙盒 + 独立 AI 浏览器 + 后台保活，断网也能继续跑。"
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("进入设备 Agent 终端", modifier = Modifier.weight(1f)) { go(Screen.AgentChat) }
                            PillButton("打开超级工单", filled = false, modifier = Modifier.weight(1f)) { go(Screen.SuperAgent) }
                        }
                        Spacer(Modifier.height(6.dp))
                        val kaRunning = KeepAlive.running.value
                        Text(
                            if (kaRunning) "后台保活服务运行中（KeepAlive ON）" else "建议在设置中开启后台保活，防止息屏后中断常驻任务。",
                            color = if (kaRunning) BeeGreen else Sub,
                            fontSize = 12.sp
                        )
                    }
                }
                item {
                    HiveSection(
                        title = "一分钟启动 BotHive Desktop PC 端",
                        bix = BixAction.DesktopPC,
                        subtitle = "源码包内的 desktop/ 目录自带跨平台 PC 端（Windows / macOS / Linux），启动即带液态玻璃 Web 控制台与远程桌面 API。"
                    ) {
                        Text(
                            "1. 在电脑解压源码包进入 desktop/ 目录\n2. Windows 双击 start_windows.bat，Mac/Linux 运行 ./start_mac_linux.sh\n3. 控制台会打印局域网地址（如 http://192.168.1.8:7718）与 16 位配对 Token\n4. 回到本页点右上角「配对 PC」填入即可秒连！",
                            color = Fg,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }

    if (showPairDialog) {
        var url by remember { mutableStateOf(HiveComputer.baseUrl.value.ifBlank { "http://192.168.1.100:7718" }) }
        var tok by remember { mutableStateOf(HiveComputer.token.value) }
        var name by remember { mutableStateOf(HiveComputer.alias.value) }
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            containerColor = Card,
            title = { Text("配对 BotHive Desktop PC 端", color = Fg) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("电脑别名", name, { name = it })
                    Field("PC 地址（如 http://192.168.1.8:7718）", url, { url = it })
                    Field("配对 Token", tok, { tok = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    HiveComputer.saveConfig(url, tok, name)
                    showPairDialog = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { HiveComputer.refreshStatus() }
                        Toast.makeText(ctx, if (ok) "配对成功！PC 已上线" else "已保存，但暂未连上：${HiveComputer.lastError.value}", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存并连接", color = ThemeAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showPairDialog = false }) { Text("取消", color = Sub) }
            }
        )
    }

    if (showTypeDialog) {
        var txt by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTypeDialog = false },
            containerColor = Card,
            title = { Text("向 PC 输入文本", color = Fg) },
            text = { Field("输入内容", txt, { txt = it }) },
            confirmButton = {
                TextButton(onClick = {
                    val v = txt
                    showTypeDialog = false
                    if (v.isNotBlank()) {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                val res = HiveComputer.desktopAction("type", text = v)
                                HiveComputer.captureScreenshot(ctx)
                                res
                            }
                            Toast.makeText(ctx, r, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("发送", color = ThemeAccent) }
            },
            dismissButton = { TextButton(onClick = { showTypeDialog = false }) { Text("取消", color = Sub) } }
        )
    }

    if (showAddDaemon) {
        var dName by remember { mutableStateOf("monitor") }
        var dCmd by remember { mutableStateOf("python3 -m http.server 8088") }
        AlertDialog(
            onDismissRequest = { showAddDaemon = false },
            containerColor = Card,
            title = { Text("启动 PC 24×7 后台守护进程", color = Fg) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("守护进程名", dName, { dName = it })
                    Field("启动命令", dCmd, { dCmd = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAddDaemon = false
                    if (dName.isNotBlank() && dCmd.isNotBlank()) {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { HiveComputer.manageDaemon("start", dName, dCmd) }
                            Toast.makeText(ctx, r, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("启动", color = ThemeAccent) }
            },
            dismissButton = { TextButton(onClick = { showAddDaemon = false }) { Text("取消", color = Sub) } }
        )
    }
}
