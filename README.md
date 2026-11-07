# Open GrokBot (Android)

开源的多 Bot Agent 安卓应用:自建 Bot、预设或自写 System Prompt、每个 Bot 独立沙盒与独立模型、Bot 私聊与群聊、工具调用时间线。

## 构建 APK
本仓库带 GitHub Actions:推到 GitHub → Actions → Build APK → 下载 artifact `OpenGrokBot-debug-apk`(debug 签名,可直接安装)。
本地构建:Android Studio 打开,或 `gradle assembleDebug`(JDK 17,AGP 8.5.2,Gradle ≥ 8.7)。

## 里程碑
- [x] M1:Bot 增删改、预设/自写 Prompt、逐 Bot 模型配置、聊天、群聊、Bot 私聊(ask_bot,只读可见)、工具调用折叠面板、沙盒目录 + 一键重置、渲染矢量图标
- [ ] M2:proot + Alpine rootfs 真正的 Linux 沙盒(接口不变,替换 Sandbox.exec)
- [ ] M3:每 Bot 独立 WebView(独立 Profile)+ 截图 + 注入点击/长按/右键/中键/滚轮/拖动/双击
- [ ] M4:Keystore 加密 Key、沙盒快照回滚、Bot 模板导入导出、MCP、流式输出
