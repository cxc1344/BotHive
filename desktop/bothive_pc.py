#!/usr/bin/env python3
"""
BotHive Desktop v0.7.2 — Always-On PC Companion & Agent Workstation
Zero-required-dependency Python 3 server + Titanium Liquid Glass Web UI + 8-bit Bix.

Optional desktop automation packages (auto-detected if installed):
    pip install pyautogui pillow mss

Run:
    python3 bothive_pc.py --port 7718
"""

import argparse
import io
import json
import os
import platform
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

START_TIME = time.time()
DATA_DIR = Path.home() / ".bothive_desktop"
DATA_DIR.mkdir(parents=True, exist_ok=True)
TOKEN_FILE = DATA_DIR / "pairing_token.txt"
WORKSPACE_DIR = DATA_DIR / "workspace"
WORKSPACE_DIR.mkdir(parents=True, exist_ok=True)

# Optional GUI automation backends (graceful degradation if missing)
try:
    import mss
    from PIL import Image
    HAS_MSS = True
except Exception:
    HAS_MSS = False

try:
    import pyautogui
    pyautogui.FAILSAFE = True
    HAS_PYAUTOGUI = True
except BaseException:
    HAS_PYAUTOGUI = False


def load_or_create_token() -> str:
    if TOKEN_FILE.is_file():
        t = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if len(t) >= 12:
            return t
    t = "bh_" + secrets.token_hex(8)
    TOKEN_FILE.write_text(t, encoding="utf-8")
    return t


PAIRING_TOKEN = load_or_create_token()
SAFE_READONLY = False
AUDIT_LOCK = threading.Lock()
AUDIT_LOG = []
DAEMON_LOCK = threading.Lock()
DAEMONS = {}  # name -> dict(proc, command, started_at, last_line)

DANGEROUS_PATTERNS = [
    "rm -rf /", "rm -rf /*", "mkfs.", "dd if=", "shutdown", "reboot",
    "format c:", "del /f /s /q c:\\", ":(){:|:&};:"
]


def add_audit(action: str, detail: str, ok: bool = True):
    entry = {
        "time": datetime.now().strftime("%H:%M:%S"),
        "action": action,
        "detail": detail[:220],
        "ok": ok,
    }
    with AUDIT_LOCK:
        AUDIT_LOG.insert(0, entry)
        del AUDIT_LOG[60:]


def get_lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def get_screen_size():
    if HAS_PYAUTOGUI:
        try:
            sz = pyautogui.size()
            return int(sz.width), int(sz.height)
        except Exception:
            pass
    if HAS_MSS:
        try:
            with mss.mss() as sct:
                mon = sct.monitors[1]
                return int(mon["width"]), int(mon["height"])
        except Exception:
            pass
    return 0, 0


def capture_screenshot_png() -> bytes:
    """Captures the primary PC desktop into PNG bytes using mss, pyautogui, or OS native tools."""
    if HAS_MSS:
        with mss.mss() as sct:
            mon = sct.monitors[1]
            raw = sct.grab(mon)
            img = Image.frombytes("RGB", raw.size, raw.bgra, "raw", "BGRX")
            if img.width > 1280:
                ratio = 1280.0 / img.width
                img = img.resize((1280, max(1, int(img.height * ratio))))
            buf = io.BytesIO()
            img.save(buf, format="PNG", optimize=True)
            return buf.getvalue()
    if HAS_PYAUTOGUI:
        img = pyautogui.screenshot()
        if img.width > 1280:
            ratio = 1280.0 / img.width
            img = img.resize((1280, max(1, int(img.height * ratio))))
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return buf.getvalue()
    # Native OS fallbacks (zero Python GUI dependencies needed on macOS / Linux!)
    tmp = Path(tempfile.gettempdir()) / f"bothive_shot_{os.getpid()}.png"
    sys_name = platform.system().lower()
    if sys_name == "darwin" and shutil.which("screencapture"):
        subprocess.run(["screencapture", "-x", str(tmp)], timeout=8, check=True)
    elif sys_name == "linux" and shutil.which("scrot"):
        subprocess.run(["scrot", "-o", str(tmp)], timeout=8, check=True)
    elif sys_name == "linux" and shutil.which("grim"):
        subprocess.run(["grim", str(tmp)], timeout=8, check=True)
    else:
        raise RuntimeError("请先安装桌面截屏支持：pip install mss pillow pyautogui")
    data = tmp.read_bytes()
    try:
        tmp.unlink()
    except Exception:
        pass
    return data


def perform_desktop_action(body: dict) -> str:
    if SAFE_READONLY:
        raise PermissionError("PC 端当前处于只读安全模式，已拦截键鼠操控。")
    if not HAS_PYAUTOGUI:
        raise RuntimeError("PC 端未安装 pyautogui，请运行: pip install pyautogui pillow mss")
    act = str(body.get("action", "")).strip().lower()
    sw, sh = get_screen_size()
    if sw <= 0 or sh <= 0:
        sw, sh = 1920, 1080
    px = int((float(body["x"]) / 1000.0) * sw) if "x" in body and body["x"] is not None else None
    py = int((float(body["y"]) / 1000.0) * sh) if "y" in body and body["y"] is not None else None

    if act == "click":
        pyautogui.click(px, py)
        return f"已在 PC ({px}, {py}) 执行左键点击"
    if act == "double_click":
        pyautogui.doubleClick(px, py)
        return f"已在 PC ({px}, {py}) 执行双击"
    if act == "right_click":
        pyautogui.rightClick(px, py)
        return f"已在 PC ({px}, {py}) 执行右键点击"
    if act == "move" and px is not None and py is not None:
        pyautogui.moveTo(px, py, duration=0.15)
        return f"已移动鼠标至 ({px}, {py})"
    if act == "scroll":
        delta = int(body.get("delta", -3))
        pyautogui.scroll(delta * 120)
        return f"已滚动滚轮 ({delta})"
    if act == "type":
        text = str(body.get("text", ""))
        pyautogui.write(text, interval=0.02)
        return f"已键入 {len(text)} 个字符"
    if act == "hotkey":
        keys = [k.strip().lower() for k in str(body.get("keys", "")).split("+") if k.strip()]
        if not keys:
            raise ValueError("缺少 keys 参数")
        pyautogui.hotkey(*keys)
        return f"已触发组合键 {'+'.join(keys)}"
    raise ValueError(f"不支持的桌面动作: {act}")


def run_shell_command(cmd: str, timeout: int = 30):
    if SAFE_READONLY:
        return 403, "", "PC 端处于只读安全模式，已拦截命令执行。"
    lower = cmd.lower()
    for pat in DANGEROUS_PATTERNS:
        if pat in lower:
            add_audit("exec_blocked", cmd, ok=False)
            return 403, "", f"PC Sentinel 安全围栏拦截了高危命令模式: {pat}"
    proc = subprocess.run(
        cmd,
        shell=True,
        cwd=str(WORKSPACE_DIR),
        capture_output=True,
        text=True,
        timeout=max(3, min(timeout, 120)),
    )
    add_audit("exec", f"{cmd} -> exit {proc.returncode}", ok=(proc.returncode == 0))
    return proc.returncode, proc.stdout[:12000], proc.stderr[:4000]


def _daemon_reader(name: str, proc: subprocess.Popen):
    try:
        for line in proc.stdout:
            clean = line.strip()[:180]
            if clean:
                with DAEMON_LOCK:
                    if name in DAEMONS:
                        DAEMONS[name]["last_line"] = clean
    except Exception:
        pass


def start_daemon(name: str, cmd: str) -> str:
    if SAFE_READONLY:
        raise PermissionError("PC 端处于只读安全模式，无法启动守护进程。")
    clean_name = "".join(c for c in name if c.isalnum() or c in "-_")[:32] or "daemon"
    with DAEMON_LOCK:
        old = DAEMONS.get(clean_name)
        if old and old["proc"].poll() is None:
            raise RuntimeError(f"守护进程 {clean_name} 已在运行中 (PID {old['proc'].pid})")
        proc = subprocess.Popen(
            cmd,
            shell=True,
            cwd=str(WORKSPACE_DIR),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        DAEMONS[clean_name] = {
            "proc": proc,
            "command": cmd,
            "started_at": time.time(),
            "last_line": "started",
        }
    t = threading.Thread(target=_daemon_reader, args=(clean_name, proc), daemon=True)
    t.start()
    add_audit("daemon_start", f"{clean_name}: {cmd}", ok=True)
    return f"已启动 24×7 守护进程 {clean_name} (PID {proc.pid})"


def stop_daemon(name: str) -> str:
    with DAEMON_LOCK:
        info = DAEMONS.get(name)
        if not info:
            raise RuntimeError(f"未找到守护进程: {name}")
        proc = info["proc"]
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=4)
            except Exception:
                proc.kill()
        DAEMONS.pop(name, None)
    add_audit("daemon_stop", name, ok=True)
    return f"已停止守护进程 {name}"


def list_daemons():
    out = []
    now = time.time()
    with DAEMON_LOCK:
        for name, info in DAEMONS.items():
            proc = info["proc"]
            running = proc.poll() is None
            out.append({
                "name": name,
                "command": info["command"],
                "running": running,
                "pid": proc.pid,
                "uptime_sec": int(now - info["started_at"]) if running else 0,
                "last_line": info.get("last_line", ""),
            })
    return out


WEB_UI_HTML = r"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BotHive Desktop v0.7.2 · Always-On Hive PC</title>
<style>
  :root {
    --bg: #0b0c0e;
    --glass: rgba(22, 24, 29, 0.72);
    --line: rgba(255, 255, 255, 0.12);
    --fg: #f6f7f9;
    --sub: #969ba6;
    --gold: #ffd166;
    --volt: #c8ff00;
    --green: #9ddc77;
    --danger: #e0705a;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background: radial-gradient(circle at 18% 12%, rgba(255,209,102,0.12), transparent 42%),
                radial-gradient(circle at 82% 85%, rgba(92,225,230,0.10), transparent 45%), var(--bg);
    color: var(--fg); min-height: 100vh; padding: 24px;
  }
  .wrap { max-width: 1120px; margin: 0 auto; display: grid; gap: 16px; }
  .card {
    background: var(--glass); border: 1px solid var(--line); border-radius: 20px;
    padding: 18px; backdrop-filter: blur(22px); -webkit-backdrop-filter: blur(22px);
    box-shadow: 0 12px 36px rgba(0,0,0,0.45);
  }
  .hero { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 16px; }
  .hero-left { display: flex; align-items: center; gap: 14px; }
  h1 { margin: 0; font-size: 22px; letter-spacing: -0.02em; }
  .sub { color: var(--sub); font-size: 13px; margin-top: 4px; }
  .pill {
    display: inline-flex; align-items: center; gap: 8px; padding: 6px 12px;
    border-radius: 999px; border: 1px solid var(--line); background: rgba(255,255,255,0.04);
    font-size: 12px; font-family: ui-monospace, monospace;
  }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--green); box-shadow: 0 0 8px var(--green); }
  .grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 16px; }
  @media (max-width: 860px) { .grid { grid-template-columns: 1fr; } }
  .shot-box {
    width: 100%; aspect-ratio: 16/10; border-radius: 14px; background: #07080b;
    border: 1px solid var(--line); overflow: hidden; display: flex; align-items: center;
    justify-content: center; cursor: crosshair; position: relative;
  }
  .shot-box img { width: 100%; height: 100%; object-fit: contain; }
  .btn-row { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
  button {
    border: 1px solid var(--line); background: rgba(255,255,255,0.06); color: var(--fg);
    padding: 8px 14px; border-radius: 999px; cursor: pointer; font-size: 12px; font-weight: 600;
  }
  button.primary { background: var(--gold); color: #0b0c0e; border-color: transparent; }
  button:hover { opacity: 0.9; }
  input {
    width: 100%; padding: 9px 12px; border-radius: 12px; border: 1px solid var(--line);
    background: rgba(0,0,0,0.35); color: var(--fg); font-size: 13px; font-family: ui-monospace, monospace;
  }
  pre {
    background: #07090d; border: 1px solid var(--line); border-radius: 12px;
    padding: 10px; font-size: 12px; color: var(--green); max-height: 180px; overflow: auto;
  }
  .log-item { font-size: 12px; padding: 6px 0; border-bottom: 1px solid rgba(255,255,255,0.06); display: flex; gap: 8px; }
  .tag { color: var(--gold); font-family: ui-monospace, monospace; }
</style>
</head>
<body>
<div class="wrap">
  <div class="card hero">
    <div class="hero-left">
      <canvas id="bixCanvas" width="76" height="56"></canvas>
      <div>
        <h1>BotHive Desktop · Always-On Hive PC</h1>
        <div class="sub" id="hostMeta">连接中…</div>
      </div>
    </div>
    <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;">
      <span class="pill"><span class="dot"></span>24×7 在线</span>
      <span class="pill" id="pairInfo">配对信息加载中…</span>
      <button id="safeBtn" onclick="toggleSafe()">安全模式: 正常</button>
    </div>
  </div>

  <div class="grid">
    <div class="card">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">
        <strong>实时桌面画面（点击画面可直接测试远程点击）</strong>
        <button class="primary" onclick="refreshShot()">刷新截屏</button>
      </div>
      <div class="shot-box" id="shotBox" onclick="clickShot(event)">
        <img id="shotImg" alt="Desktop Preview" style="display:none">
        <span id="shotHint" class="sub">点击右上角「刷新截屏」查看实时桌面</span>
      </div>
    </div>

    <div class="card" style="display:flex;flex-direction:column;gap:12px;">
      <div>
        <strong>远程 Shell 控制台</strong>
        <div class="btn-row">
          <input id="cmdInput" value="python3 --version" placeholder="输入 Shell / PowerShell 命令">
          <button class="primary" onclick="runCmd()">执行</button>
        </div>
        <pre id="cmdOut">等待指令…</pre>
      </div>
      <div>
        <strong>24×7 后台守护进程</strong>
        <div id="daemonList" class="sub" style="margin-top:6px;">暂无运行中守护进程</div>
      </div>
    </div>
  </div>

  <div class="card">
    <strong>PC Sentinel 实时操作审计流</strong>
    <div id="auditList" style="margin-top:8px;"></div>
  </div>
</div>

<script>
const TOKEN = "__PAIRING_TOKEN__";
const LAN_URL = "__LAN_URL__";
document.getElementById("pairInfo").textContent = `${LAN_URL}  |  Token: ${TOKEN}`;

// Pure 8-bit Canvas Bix Desktop Guard animation
const c = document.getElementById("bixCanvas");
const ctx = c.getContext("2d");
let tick = 0;
function drawBix() {
  ctx.clearRect(0, 0, c.width, c.height);
  const u = 4;
  const wingUp = Math.floor(tick / 8) % 2 === 0;
  const p = (x, y, w, h, col) => { ctx.fillStyle = col; ctx.fillRect(x*u, y*u, w*u, h*u); };
  // Wings
  p(3, wingUp ? 1 : 2, 3, 2, "rgba(220,245,255,0.85)");
  p(6, wingUp ? 1 : 2, 2, 2, "rgba(220,245,255,0.65)");
  // Body
  p(2, 4, 7, 5, "#ffd166");
  p(4, 4, 1.4, 5, "#191c23");
  p(7, 4, 1.4, 5, "#191c23");
  // Eye
  p(2.4, 5, 1, 1.4, "#0b0c0e");
  // Mini dual-screen PC
  p(11, 3, 7, 5, "#232834");
  p(11.8, 3.8, 5.4, 3.4, "#0d1620");
  p(12.2, 4.4, 3.2, 0.7, "#c8ff00");
  p(12.2 + (tick % 20) * 0.14, 5.8, 1.0, 0.8, "#5ce1e6");
  p(13.8, 8, 1.4, 1.2, "#232834");
  p(12.2, 9.2, 4.6, 0.8, "#232834");
  tick++;
  requestAnimationFrame(drawBix);
}
drawBix();

async function api(path, opts = {}) {
  opts.headers = Object.assign({ "Authorization": "Bearer " + TOKEN, "Content-Type": "application/json" }, opts.headers || {});
  const res = await fetch(path, opts);
  return res;
}

async function refreshStatus() {
  try {
    const res = await api("/api/status");
    const d = await res.json();
    document.getElementById("hostMeta").textContent =
      `${d.hostname} · ${d.os} · 屏幕 ${d.screen || "无头"} · 已在线 ${Math.floor(d.uptime_sec / 60)} 分钟`;
    document.getElementById("safeBtn").textContent = d.safe_readonly ? "安全模式: 只读锁定" : "安全模式: 正常";
    const dl = document.getElementById("daemonList");
    dl.innerHTML = d.daemons.length ? d.daemons.map(x =>
      `<div class="log-item"><span class="tag">${x.name}</span><span>PID ${x.pid} · ${x.command}</span></div>`
    ).join("") : "暂无运行中守护进程";
    const al = document.getElementById("auditList");
    al.innerHTML = d.audit.length ? d.audit.slice(0, 15).map(a =>
      `<div class="log-item"><span class="sub">${a.time}</span><span class="tag">${a.action}</span><span>${a.detail}</span></div>`
    ).join("") : '<div class="sub">暂无操作记录</div>';
  } catch (e) {}
}

async function refreshShot() {
  const res = await api("/api/screenshot");
  if (!res.ok) {
    document.getElementById("shotHint").textContent = await res.text();
    return;
  }
  const blob = await res.blob();
  const img = document.getElementById("shotImg");
  img.src = URL.createObjectURL(blob);
  img.style.display = "block";
  document.getElementById("shotHint").style.display = "none";
}

async function clickShot(ev) {
  const box = document.getElementById("shotBox").getBoundingClientRect();
  const nx = ((ev.clientX - box.left) / box.width) * 1000;
  const ny = ((ev.clientY - box.top) / box.height) * 1000;
  await api("/api/desktop/action", { method: "POST", body: JSON.stringify({ action: "click", x: nx, y: ny }) });
  setTimeout(refreshShot, 350);
  refreshStatus();
}

async function runCmd() {
  const cmd = document.getElementById("cmdInput").value;
  document.getElementById("cmdOut").textContent = "执行中…";
  const res = await api("/api/exec", { method: "POST", body: JSON.stringify({ command: cmd }) });
  const d = await res.json();
  document.getElementById("cmdOut").textContent = `[exit=${d.exit_code}]\n${d.output || ""}${d.error ? "\n--- stderr ---\n" + d.error : ""}`;
  refreshStatus();
}

async function toggleSafe() {
  await api("/api/safe_toggle", { method: "POST", body: "{}" });
  refreshStatus();
}

refreshStatus();
setInterval(refreshStatus, 4000);
</script>
</body>
</html>
"""


class BotHivePcHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        return  # quiet console

    def _send_json(self, code: int, obj: dict):
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _authorized(self) -> bool:
        auth = self.headers.get("Authorization", "")
        return auth == f"Bearer {PAIRING_TOKEN}"

    def _read_json(self) -> dict:
        n = int(self.headers.get("Content-Length", "0") or "0")
        if n <= 0:
            return {}
        return json.loads(self.rfile.read(n).decode("utf-8"))

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/" or path == "/index.html":
            lan_url = f"http://{get_lan_ip()}:{self.server.server_port}"
            html = WEB_UI_HTML.replace("__PAIRING_TOKEN__", PAIRING_TOKEN).replace("__LAN_URL__", lan_url)
            raw = html.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return

        if not self._authorized():
            self._send_json(401, {"error": "Unauthorized: invalid Bearer pairing token"})
            return

        if path == "/api/status":
            sw, sh = get_screen_size()
            with AUDIT_LOCK:
                aud = list(AUDIT_LOG[:25])
            self._send_json(200, {
                "version": "0.7.2",
                "hostname": socket.gethostname(),
                "os": f"{platform.system()} {platform.release()} ({platform.machine()})",
                "screen": f"{sw}x{sh}" if sw > 0 else "",
                "desktop_capable": HAS_PYAUTOGUI or HAS_MSS,
                "safe_readonly": SAFE_READONLY,
                "uptime_sec": int(time.time() - START_TIME),
                "daemons": list_daemons(),
                "audit": aud,
            })
            return

        if path == "/api/screenshot":
            try:
                png = capture_screenshot_png()
                add_audit("screenshot", f"{len(png)//1024} KB", ok=True)
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(png)))
                self.end_headers()
                self.wfile.write(png)
            except Exception as e:
                add_audit("screenshot_err", str(e), ok=False)
                self._send_json(500, {"error": str(e)})
            return

        if path == "/api/audit":
            with AUDIT_LOCK:
                self._send_json(200, {"audit": list(AUDIT_LOG)})
            return

        self._send_json(404, {"error": "Not found"})

    def do_POST(self):
        global SAFE_READONLY
        if not self._authorized():
            self._send_json(401, {"error": "Unauthorized"})
            return
        path = urlparse(self.path).path
        try:
            body = self._read_json()
        except Exception as e:
            self._send_json(400, {"error": f"Invalid JSON: {e}"})
            return

        if path == "/api/safe_toggle":
            SAFE_READONLY = not SAFE_READONLY
            add_audit("safe_mode", "ON" if SAFE_READONLY else "OFF", ok=True)
            self._send_json(200, {"safe_readonly": SAFE_READONLY})
            return

        if path == "/api/exec":
            cmd = str(body.get("command", "")).strip()
            if not cmd:
                self._send_json(400, {"error": "command is required"})
                return
            try:
                code, out, err = run_shell_command(cmd, int(body.get("timeout", 30)))
                self._send_json(200, {"exit_code": code, "output": out, "error": err})
            except subprocess.TimeoutExpired:
                add_audit("exec_timeout", cmd, ok=False)
                self._send_json(408, {"exit_code": -1, "output": "", "error": "Command timed out"})
            except Exception as e:
                self._send_json(500, {"exit_code": -1, "output": "", "error": str(e)})
            return

        if path == "/api/desktop/action":
            try:
                msg = perform_desktop_action(body)
                add_audit("desktop_action", msg, ok=True)
                self._send_json(200, {"ok": True, "message": msg})
            except Exception as e:
                add_audit("desktop_action_err", str(e), ok=False)
                self._send_json(400, {"ok": False, "error": str(e)})
            return

        if path == "/api/daemons/start":
            try:
                msg = start_daemon(str(body.get("name", "task")), str(body.get("command", "")))
                self._send_json(200, {"ok": True, "message": msg})
            except Exception as e:
                self._send_json(400, {"ok": False, "error": str(e)})
            return

        if path == "/api/daemons/stop":
            try:
                msg = stop_daemon(str(body.get("name", "")))
                self._send_json(200, {"ok": True, "message": msg})
            except Exception as e:
                self._send_json(400, {"ok": False, "error": str(e)})
            return

        self._send_json(404, {"error": "Not found"})


def main():
    parser = argparse.ArgumentParser(description="BotHive Desktop v0.7.2 Always-On PC Companion")
    parser.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=7718, help="Bind port (default: 7718)")
    args = parser.parse_args()

    lan_ip = get_lan_ip()
    srv = ThreadingHTTPServer((args.host, args.port), BotHivePcHandler)
    add_audit("boot", f"BotHive Desktop v0.7.2 listening on {lan_ip}:{args.port}", ok=True)
    print("=" * 62)
    print("  BotHive Desktop v0.7.2 — Always-On PC Companion")
    print(f"  Web Console : http://127.0.0.1:{args.port}")
    print(f"  Phone URL   : http://{lan_ip}:{args.port}")
    print(f"  Pair Token  : {PAIRING_TOKEN}")
    print(f"  Desktop GUI : {'READY (mss/pyautogui)' if (HAS_MSS or HAS_PYAUTOGUI) else 'Shell-only (run: pip install pyautogui pillow mss)'}")
    print("=" * 62)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nBotHive Desktop shutting down...")
        srv.server_close()


if __name__ == "__main__":
    main()
