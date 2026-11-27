package app.opengrok

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.net.Socket

enum class DesktopState(val label: String) {
    STOPPED("未启动"),
    INSTALLING("正在安装 Fluxbox + noVNC…"),
    STARTING("正在启动 Ubuntu 桌面…"),
    RUNNING("运行中 · 1280×720"),
    ERROR("启动异常")
}

/**
 * v0.7.2 On-Device Ubuntu Linux Desktop Engine (PRoot Ubuntu 24.04 + Fluxbox + TigerVNC + noVNC).
 *
 * Boots a real lightweight X11 desktop inside BotHive's existing Android Ubuntu PRoot sandbox on
 * DISPLAY=:1 (1280x720) and exposes it locally over noVNC at http://127.0.0.1:6080.
 *
 * Supports live AI operation (scrot vision screenshots + xdotool mouse/keyboard + app launch)
 * with explicit "（AI名）正在使用中" ownership tracking and 1-tap human takeover / hand-back.
 */
object LinuxDesktop {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val VNC_PORT = 5901
    const val NOVNC_PORT = 6080
    const val NOVNC_URL = "http://127.0.0.1:$NOVNC_PORT/vnc.html?autoconnect=1&resize=scale&reconnect=1&show_dot=true"

    private const val DESKTOP_INSTALL_CMD =
        "export DEBIAN_FRONTEND=noninteractive; " +
        "apt-get update -qq && " +
        "apt-get install -y -qq --no-install-recommends " +
        "tigervnc-standalone-server fluxbox xterm pcmanfm xdotool scrot novnc websockify dbus-x11 fonts-wqy-microhei && " +
        "echo DESKTOP_PKG_OK"

    private val main = Handler(Looper.getMainLooper())
    private var clearOwnerRunnable: Runnable? = null

    val state = mutableStateOf(DesktopState.STOPPED)
    val statusLog = mutableStateOf("点击「启动 Ubuntu 桌面」在手机本地 PRoot 中开启轻量 Fluxbox 桌面。")
    val activeAiOwner = mutableStateOf("")
    val userTakeover = mutableStateOf(false)
    val webReloadSeq = mutableIntStateOf(0)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Records which AI Bot is currently operating the desktop (auto-clears after 18s of idle). */
    fun markAiUsing(botName: String) {
        val clean = botName.trim().ifBlank { "AI" }
        onMain {
            activeAiOwner.value = clean
            clearOwnerRunnable?.let { main.removeCallbacks(it) }
            val r = Runnable { activeAiOwner.value = "" }
            clearOwnerRunnable = r
            main.postDelayed(r, 18_000L)
        }
    }

    fun setTakeover(takeover: Boolean) {
        onMain {
            userTakeover.value = takeover
            if (takeover) statusLog.value = "你已接管 Ubuntu 桌面（AI 桌面操作已暂停，点「交回给 AI」恢复）。"
            else statusLog.value = "已交回控制权给 AI。"
        }
    }

    fun isPortOpen(port: Int = NOVNC_PORT): Boolean = runCatching {
        Socket("127.0.0.1", port).use { true }
    }.getOrDefault(false)

    private fun sandbox(ctx: Context): Sandbox = Sandbox(ctx.applicationContext, AGENT_ID)

    /** Ensures Fluxbox + TigerVNC + noVNC are installed and running on DISPLAY=:1 & port 6080. */
    fun start(ctx: Context): String {
        if (isPortOpen(NOVNC_PORT)) {
            onMain {
                state.value = DesktopState.RUNNING
                statusLog.value = "Ubuntu Fluxbox 桌面已在运行（http://127.0.0.1:$NOVNC_PORT）。"
                webReloadSeq.intValue += 1
            }
            return "Ubuntu 轻量桌面已在线（1280×720，noVNC 端口 $NOVNC_PORT）。"
        }

        val sb = sandbox(ctx)
        val check = sb.exec(
            "command -v Xvnc >/dev/null 2>&1 && command -v fluxbox >/dev/null 2>&1 && command -v websockify >/dev/null 2>&1 && echo READY || echo NEED_INSTALL",
            timeoutSec = 25
        )
        if (!check.contains("READY")) {
            onMain {
                state.value = DesktopState.INSTALLING
                statusLog.value = "首次启动：正在 Ubuntu PRoot 内安装轻量 Fluxbox + TigerVNC + noVNC + xdotool（约需 1～2 分钟）…"
            }
            val instOut = sb.exec(DESKTOP_INSTALL_CMD, timeoutSec = 240)
            if (!instOut.contains("DESKTOP_PKG_OK")) {
                onMain {
                    state.value = DesktopState.ERROR
                    statusLog.value = "桌面组件安装未完成：${instOut.takeLast(240)}"
                }
                return "安装 Ubuntu 桌面组件失败：${instOut.takeLast(300)}"
            }
        }

        onMain {
            state.value = DesktopState.STARTING
            statusLog.value = "正在启动 Xvnc (:1) + Fluxbox 窗口管理器 + noVNC 代理…"
        }

        val bootScript = """
            export HOME=/root
            export DISPLAY=:1
            mkdir -p /root/.vnc /tmp/.X11-unix
            rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null
            pkill -f "Xvnc :1" 2>/dev/null || true
            pkill -f "websockify.*$NOVNC_PORT" 2>/dev/null || true
            nohup Xvnc :1 -geometry ${WIDTH}x${HEIGHT} -depth 24 -localhost yes -SecurityTypes None -rfbport $VNC_PORT >/tmp/xvnc.log 2>&1 &
            sleep 1
            nohup fluxbox >/tmp/fluxbox.log 2>&1 &
            sleep 0.5
            nohup xterm -fa Monospace -fs 11 -bg "#0b0c0e" -fg "#9ddc77" -title "BotHive Ubuntu Terminal" >/tmp/xterm.log 2>&1 &
            NOVNC_DIR=/usr/share/novnc
            [ -d "${'$'}NOVNC_DIR" ] || NOVNC_DIR=/usr/share/novnc
            nohup websockify --web="${'$'}NOVNC_DIR" 127.0.0.1:$NOVNC_PORT 127.0.0.1:$VNC_PORT >/tmp/novnc.log 2>&1 &
            sleep 1
            echo DESKTOP_BOOT_DONE
        """.trimIndent()

        val bootOut = sb.exec(bootScript, timeoutSec = 35)
        Thread.sleep(600)
        val ok = isPortOpen(NOVNC_PORT)
        onMain {
            if (ok) {
                state.value = DesktopState.RUNNING
                statusLog.value = "Ubuntu Fluxbox 桌面运行中（1280×720 · noVNC :$NOVNC_PORT）。"
                webReloadSeq.intValue += 1
            } else {
                state.value = DesktopState.ERROR
                statusLog.value = "桌面进程未能监听 $NOVNC_PORT 端口：${bootOut.takeLast(200)}"
            }
        }
        return if (ok) "Ubuntu Fluxbox 轻量桌面已启动（1280×720，DISPLAY=:1），现在可以使用 desktop_screenshot / desktop_click / desktop_type / desktop_open 操作桌面。"
        else "Ubuntu 桌面启动失败：${bootOut.takeLast(260)}"
    }

    fun stop(ctx: Context) {
        val sb = sandbox(ctx)
        sb.exec("pkill -f 'Xvnc :1' 2>/dev/null || true; pkill -f 'websockify.*$NOVNC_PORT' 2>/dev/null || true; pkill -f fluxbox 2>/dev/null || true", timeoutSec = 15)
        onMain {
            state.value = DesktopState.STOPPED
            activeAiOwner.value = ""
            userTakeover.value = false
            statusLog.value = "Ubuntu 轻量桌面已停止。"
        }
    }

    /** Captures the live X11 desktop (:1) to PNG inside /workspace/.desktop_shot.png for vision models. */
    fun screenshot(ctx: Context, botName: String): File? {
        if (!isPortOpen(NOVNC_PORT)) return null
        markAiUsing(botName)
        val sb = sandbox(ctx)
        sb.exec("DISPLAY=:1 scrot -o /workspace/.desktop_shot.png", timeoutSec = 15)
        val f = File(sb.dir, ".desktop_shot.png")
        return if (f.isFile && f.length() > 64) f else null
    }

    /** Clicks at normalized 0..1000 coordinates on the Ubuntu X11 desktop (:1). */
    fun click(ctx: Context, botName: String, xNorm: Double, yNorm: Double, button: String = "left"): String {
        if (userTakeover.value) return "用户当前正在接管 Ubuntu 桌面，请等待用户点击「交回给 AI」。"
        if (!isPortOpen(NOVNC_PORT)) return "Ubuntu 桌面未启动，请先调用 desktop_start。"
        markAiUsing(botName)
        val px = ((xNorm.coerceIn(0.0, 1000.0) / 1000.0) * WIDTH).toInt()
        val py = ((yNorm.coerceIn(0.0, 1000.0) / 1000.0) * HEIGHT).toInt()
        val cmd = when (button.trim().lowercase()) {
            "right" -> "DISPLAY=:1 xdotool mousemove $px $py click 3"
            "double" -> "DISPLAY=:1 xdotool mousemove $px $py click --repeat 2 1"
            else -> "DISPLAY=:1 xdotool mousemove $px $py click 1"
        }
        sandbox(ctx).exec(cmd, timeoutSec = 12)
        return "已在 Ubuntu 桌面 ($px, $py) 执行 $button 点击。"
    }

    /** Types text or sends a key combo (e.g. Return, ctrl+c, alt+F4) to DISPLAY=:1. */
    fun typeOrKey(ctx: Context, botName: String, text: String = "", key: String = ""): String {
        if (userTakeover.value) return "用户当前正在接管 Ubuntu 桌面，请等待用户交回控制权。"
        if (!isPortOpen(NOVNC_PORT)) return "Ubuntu 桌面未启动，请先调用 desktop_start。"
        markAiUsing(botName)
        val sb = sandbox(ctx)
        if (text.isNotBlank()) {
            val safe = text.replace("'", "'\"'\"'")
            sb.exec("DISPLAY=:1 xdotool type --delay 18 -- '$safe'", timeoutSec = 15)
        }
        if (key.isNotBlank()) {
            val cleanKey = key.trim().replace(Regex("[^A-Za-z0-9_+\\-]"), "")
            if (cleanKey.isNotBlank()) sb.exec("DISPLAY=:1 xdotool key $cleanKey", timeoutSec = 10)
        }
        return "已向 Ubuntu 桌面发送输入。"
    }

    /** Launches a GUI app (e.g. xterm, pcmanfm) on DISPLAY=:1. */
    fun openApp(ctx: Context, botName: String, appCmd: String): String {
        if (userTakeover.value && botName != "用户") return "用户当前正在接管 Ubuntu 桌面，请等待用户交回控制权。"
        if (!isPortOpen(NOVNC_PORT)) return "Ubuntu 桌面未启动，请先调用 desktop_start。"
        if (botName != "用户") markAiUsing(botName)
        val clean = appCmd.trim().ifBlank { "xterm" }
        sandbox(ctx).exec("DISPLAY=:1 nohup $clean >/tmp/gui_app.log 2>&1 &", timeoutSec = 12)
        return "已在 Ubuntu 桌面启动：$clean"
    }
}
