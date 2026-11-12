# BotHive（Android）

[![Build APK](https://github.com/cxc1344/BotHive/actions/workflows/build.yml/badge.svg)](https://github.com/cxc1344/BotHive/actions) [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

安卓版多 Bot 协作应用：用你自己的 API Key 自建 AI「员工」，给每个 Bot 写人设、派模型、建群协作；还能立项让**总经理 Bot** 自己拆任务、派活、审查、打回返工，最后向你汇报。v0.4 起新增 **Agent 模式**：一位住在手机本地的设备 Agent，能读写你授权的手机文件夹、查看设备状态与应用使用时长、打开应用，并与云端 Bot 互相派活。

**v0.5.0** 在保留全部协作与 Agent 能力的基础上完成界面全面升级：以像素蜜蜂和蜂巢为核心视觉语言，并用桌宠式像素角色替换原先的无表情人头，让工作台更有陪伴感与辨识度。

**v0.5.8** 把液态玻璃从「自绘材质」推进到「真透镜」：在 v0.5.7 三档强度（不要 / 中等 / 最高）的基础上，最高档下模式胶囊指示器与开关滑块会采样应用背景层并实时折射（中心放大、边缘 SDF 法线推移、RGB 色散、速度拉伸形变）；采样边界与实现细节如实记录在 [TODO-GLASS-V2.md](TODO-GLASS-V2.md)。详见 [CHANGELOG](CHANGELOG.md)。

原生 Kotlin + Jetpack Compose 编写，无网页套壳。

## 下载

到 [Releases](https://github.com/cxc1344/BotHive/releases) 下载最新版 APK（当前 **v0.5.8**）。

直达当前版 APK：[BotHive-v0.5.8.apk](https://github.com/cxc1344/BotHive/releases/download/v0.5.8/BotHive-v0.5.8.apk)

> 注意：自 v0.4.8 起正式版使用固定项目签名，后续版本可直接覆盖安装。之前装的是调试版的话，换到正式版仍需先卸载一次（建议先用对话页的导出功能把重要对话存为 Markdown）。应用显示名已统一为 **BotHive**；包名仍为 `app.opengrok`，可与同类应用并存。

## 快速开始

1. 安装并打开应用，先在「设置 → Provider 预设」里把常用服务商的 Base URL 与 Key 存成预设（Key 经 Android Keystore 加密保存），之后建 Bot / Agent 时直接选用并点选模型。默认只允许 HTTPS；确实需要连接可信内网 HTTP 服务时，可在预设或对应 Bot 设置中明确开启，但 HTTP 会暴露请求内容与 API Key。
2. 点「连接自检」确认模型列表、对话、沙盒都通。
3. 新建 Bot：选预设（共 14 个，也可以把某个 Bot 设为总经理），或自写 System Prompt，并为它单独指定模型。
4. 单聊、拉群，或在项目模式里立个项，看总经理带队干活。

## 功能（已实现）

- **总经理负责制流水线**：立项 → 总经理出计划 → 你确认 → 员工并行执行 → 总经理审查 → 不合格打回返工（最多一次）→ 结项汇报
- **Bot 体系**：增删改、预设/自写 Prompt、逐 Bot 独立 BaseURL / Key / 模型、真头像（种子生成）与矢量图标
- **协作可见**：群聊全员回复；Bot 之间用 `ask_bot` 私聊对齐进度，私聊记录对你只读可见
- **原生工具调用**：OpenAI Function Calling，Bot 可执行沙盒命令、互相提问；调用过程有折叠时间线面板
- **Linux 环境**：每个 Bot / Agent 独立的 Ubuntu 24.04 rootfs，通过 PRoot 提供 Termux 式 Linux 用户空间，工作目录挂载为 /workspace，用 apt 安装软件，首次初始化自动预装 python3、nodejs、git 等常用工具；rootfs 从 Ubuntu 官方 cdimage 下载（镜像兜底）并按 SHA256SUMS 校验，安装失败会给出分步骤的诊断信息。注意：PRoot 不是独立内核，也不构成强安全边界。
- **危险命令守卫 Guard**：对部分明显危险命令做启发式拦截（如 fork 炸弹、管道远程执行、格式化、写块设备），拦截记录在设置页可查；不是安全边界，不能替代 OS 级进程隔离。
- **Provider 预设**：服务商连接（Base URL + Key）一次保存、处处复用；建 Bot 时可拉取该服务商的模型列表点选，不再逐个粘贴 Key
- **例行任务**：Bot 与 Agent 能用 schedule_task 给自己派定时活（一次性或每日），到点自动执行并在对话里汇报；设置页统一查看与删除
- **MCP 接入**：可添加远程 MCP 服务，其工具与内置工具一起出现在 Bot / Agent 的工具列表里
- **工作流**：步骤链编排——每步指定执行者与指令，{prev} 传递上一步输出，运行记录可翻看
- **后台保活**：前台服务守进程、被划掉自动拉起、开机自启，例行任务与主动消息不掉线（效果取决于系统授权）
- **技能包与命令批准**：可复用指令包可挂到任意 Bot / Agent；可开启「命令先批准」——每条 Shell 命令先过用户这关再执行
- **Agent 虚拟屏悬浮窗（v0.5.1）**：Agent 可在一块独立虚拟显示屏里操作 App，画面显示在可拖动的悬浮小窗中（带暂停/停止），你的主屏照常使用。前置条件为 Shizuku 已授权 + 已授予「显示在其他应用上层」，不满足时会明确提示并保持主屏模式
- **阅读体验（v0.5.4）**：聊天气泡支持 Markdown 渲染（标题、粗体、列表、代码块、GFM 表格）；内置子集化 Noto Serif SC 衬线字体，中英混排同一字族，代码与表格走等宽字体。同版修复虚拟屏在 Android 14+ 创建失败、Shizuku 授权后命令卡死与无障碍状态误报
- **专用手机模型（v0.5.5）**：可另配一个 AutoGLM 风格的看屏操作模型（任意 OpenAI 兼容接口，密钥经 Keystore 加密），用 `phone_task` 把整条操作任务交给它闭环执行（最多 15 步、卡住与黑屏自动中止）；虚拟屏改为跨轮次保持，不再每轮对话结束就释放
- **液态玻璃视觉系统（v0.5.7–v0.5.8）**：设置页三档自选界面玻璃与动画强度（不要 / 中等 / 最高）；v0.5.7 以自研 AGSL 着色器绘制玻璃面（圆角 SDF 透镜曲率明暗、边缘棱镜色散、漂移高光光斑）并把全部开关换成液态开关，v0.5.8 进一步让模式胶囊指示器与开关滑块在最高档下采样应用背景层做真实折射（Picture 录制背景层 → AGSL 采样，动画期间零重栅格化，失败或低版本自动回退材质）；开关支持拖动 1:1 跟手、过中点预览翻转、松手提交、橡皮筋与弹簧落位
- **Agent 模式（设备端）**：本地 Agent 可读写用户授权的手机文件夹、查看设备状态与今日应用使用时长、打开应用；可选接入 Shizuku 特权通道获得 shell 级能力；无障碍开启后以**视觉模式**操作屏幕——先截屏看图，再按画面方位用 0-1000 相对坐标点击 / 长按 / 拖动；与云端 Bot 通过同一套 ask_bot 私聊双向派活，全程可见
- **视觉看屏操作**：`screen_shot` 截屏并把图片直接发给模型，模型据此判断目标方位，再用 `tap_at` / `long_press` / `drag_at` 以相对坐标操作，应用按真实分辨率换算成像素执行手势；文字读屏（`screen_dump` / `tap_text`）作为备用通道。每次截屏的缩略图都保留在工具卡里可回看
- **对话置顶与消息重发**：侧边栏可置顶常用对话（最多 20 条，排在列表最前）；用户消息可一键「重发」——清除该条之后的内容再重新发送
- **复制 Bot**：一键复制一个人设/模型/Provider/技能包完全一致的 Bot（不含对话记录）
- **连接自检**：一键检查模型接口、对话与 Keystore，并实际进入 Ubuntu 环境验证 PRoot 可执行
- **请求控制**：API 请求可随时取消，接口地址会做格式与协议校验；明文 HTTP 默认禁止，可按 Bot 单独开启

## 截图

> 仓库当前保留的是 v0.4.x 的功能截图，展示 Bot 列表、新建 Bot 与设置/连接自检；它们不代表 v0.5.0 的新视觉。v0.5.0 运行时已升级为像素蜜蜂主题与桌宠式角色，发布版截图资源将随首个正式构建补充。

| 归档：Bot 列表 | 归档：新建 Bot | 归档：设置与连接自检 |
| --- | --- | --- |
| <img src="screenshots/bots.jpg" width="260"> | <img src="screenshots/new-bot.jpg" width="260"> | <img src="screenshots/settings.jpg" width="260"> |

## 路线图

- [x] M1：Bot 管理、聊天/群聊/私聊、工具调用、沙盒目录、图标与头像
- [x] P0：总经理流水线、Guard 守卫、Keystore 加密、连接自检、预设扩充
- [x] v0.5.0：像素蜜蜂视觉主题、蜂巢装饰与桌宠式 Bot 角色
- [x] PRoot + Alpine Linux 用户空间（每 Bot 独立 rootfs；非内核级隔离，见 V0.3_ROADMAP.md）
- [x] Ubuntu 24.04 环境、Provider 预设、Bot/Agent 双模式与设备工具（v0.4.0）
- [x] Agent 屏幕操作（无障碍看屏/点击/滑动/输入）与长期记忆（v0.4.0）
- [x] 例行任务（AI 自派定时任务）、远程 MCP 接入、工作流步骤链（v0.4.0）
- [ ] 每 Bot 独立浏览器：独立 WebView + 截屏 + 点击/长按/右键/中键/滚轮/拖动
- [ ] 收件箱与交付、主动消息治理、例行任务、用量账本

> 安全说明：proot 主要提供用户态文件系统环境，不应单独视为安全边界。真正的隔离仍需独立 UID/isolated process 或其他经过验证的系统级执行隔离方案。

## 构建

本仓库带 GitHub Actions：推送到 `main` 自动构建，产物在 Actions 的 artifact 或 Releases 中下载。
本地构建：先运行 `bash tools/fetch_proot_android.sh` 下载并校验 PRoot 二进制，再用 Android Studio 构建，或运行 `gradle assembleDebug`（JDK 17、AGP 8.5.2、Gradle 8.9、compileSdk 35）。

## 许可

[MIT](LICENSE)
