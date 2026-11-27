# BotHive Desktop v0.7.2 (PC 端伴侣 · Always-On Hive PC)

给你的 Android BotHive 配上一台 **24×7 在线、可看屏幕、可操控键鼠、可跑后台守护进程** 的专属桌面电脑（Windows / macOS / Linux 全平台通用）。

## 1. 一分钟启动
- **Windows**：双击 `start_windows.bat`
- **macOS / Linux**：终端运行 `chmod +x start_mac_linux.sh && ./start_mac_linux.sh`
- （可选）如需开启桌面鼠标键盘操控与跨平台高清截屏，运行一次：
  ```bash
  pip install pyautogui pillow mss
  ```

## 2. 手机端秒连配对
1. 启动后终端会自动打印 **Phone URL**（如 `http://192.168.1.8:7718`）和 **Pair Token**（如 `bh_xxxxxxxx`），同时浏览器自动打开液态玻璃 PC 控制台。
2. 在手机 BotHive 侧边栏打开 **「Bot 专属电脑 · Hive PC」** → 右上角 **「配对 PC」** → 填入地址与 Token 即可握手成功。
3. 连上后，你可以直接在手机里**点击远程桌面画面操控鼠标**、跑远程终端、管理 24×7 后台守护进程，且所有 Bot 自动获得 `pc_exec`、`pc_screenshot`、`pc_action`、`pc_daemon` 四项远程电脑工具。

## 3. 安全机制（PC Sentinel）
- 全部 API 强制校验 `Bearer` 配对 Token。
- 内置高危命令拦截围栏与一键 **只读安全模式** 开关，所有操作实时写入审计流。
