# 更新日志

## v0.3.1 — 2026-11-08

修复 v0.3.0 真机上所有 shell 执行都返回「下载的 Alpine rootfs 不完整」的问题：

- 根因：Alpine 的 `bin/sh` 是指向 `/bin/busybox` 的**绝对符号链接**，`File.exists()` 会在宿主机路径上解析它，而安卓上没有 `/bin/busybox`，于是解压成功后仍被判为不完整。完整性检查与「已安装」判断改为在 rootfs 内部解析符号链接
- 解压不再依赖系统 `/system/bin/tar`（toybox 在各厂商 ROM 上行为不一）：改用内置的 tar.gz 解压器，支持 ustar / GNU 长文件名 / pax，跳过设备节点与 FIFO，拒绝绝对路径与 `..`，不跟随符号链接越界写入
- 下载链路：手动处理重定向（仅 HTTPS，拒绝降级到 HTTP）、显式 `Accept-Encoding: identity`（避免代理把 .tar.gz 解压）、Content-Length 与实际字节数核对、整体 5 分钟期限；官方 CDN 失败时依次尝试 mirrors.aliyun.com、mirrors.tuna.tsinghua.edu.cn；校验过的压缩包缓存在本地，之后新建 Bot 无需联网取校验文件
- 报错分级透出：错误文本包含「步骤 n/8 + 阶段名 + 底层异常类型/消息 + 诊断信息（ABI、下载源、压缩包大小、SHA256 前缀、解压统计）」；完整堆栈写入应用缓存目录 `linux-last-error.log`；连接自检失败时显示更长的输出
- PRoot 自身启动失败（输出以 `proot error` 开头且退出码非 0）时自动改用 `PROOT_NO_SECCOMP=1` 重试一次（**未验证**：是否在目标机型上需要，取决于内核）
- 重置/删除 Bot 的目录删除改为不跟随符号链接，防止 Bot 在沙盒里建立指向宿主目录的链接后，重置时误删宿主文件
- 新增 `TarExtractTest`（7 项）与 `tools/tar_mirror_selfcheck.py`

未验证：本版本未在真机上运行；PRoot 在 vivo 上的实际可用性以真机复测为准。


## v0.3.0 — 2026-11-08

Linux 环境（PRoot + Alpine）：

- 每个 Bot 独立 Alpine 3.24.4 rootfs 与包数据库，工作目录挂载为 /workspace；基础系统压缩包按 ABI 共享缓存，首次执行 shell 或连接自检时自动下载初始化（约 4 MB）
- PRoot 与 loader 在构建时按 ABI 下载并校验 SHA-256 后随包发布；同一时间最多运行两个 PRoot 任务
- 设置页新增「Linux 环境」说明；连接自检会实际进入 Alpine 环境检查（uname /etc/alpine-release）
- 注意：PRoot 是用户态 Linux 环境，不提供独立内核、namespace、cgroups 或强安全隔离；本版本未经真机完整验收

可靠性与安全加固：

- Agent 请求可取消：取消任务会真正取消正在进行的网络请求；禁用自动重定向
- Shell 执行可取消：取消时终止 shell 进程并清理读取线程；同一 Bot 的 shell 改为串行执行
- Guard 路径检查升级：递归删除/改权限的目标做规范路径校验，拦截 `..` 跳转、绝对路径越界与符号链接逃逸；禁止嵌套 shell 与切换工作目录
- 状态保存改为原子写：临时文件写入并同步后同目录原子替换，失败会在界面提示，不再静默丢数据
- HTTP 策略调整：由全局禁止改为每个 Bot 单独开关——默认只允许 HTTPS，确需内网 HTTP 时在该 Bot 设置中开启（界面有风险提示）
- 新增 Guard 单元测试，CI 改为先跑单元测试再构建 APK

## v0.2.1（P0.1）— 2026-11-07

安全加固：

- 审查失败关闭：总经理审查输出无法解析、或结论不是明确的 pass/rework 时，一律按未通过处理，不再默认放行
- Keystore 失败关闭：加密不可用时不再把 API Key 降级为明文保存（保存时加密失败则该 Key 存为空，需重新填写，不会因此崩溃）
- 默认禁止 HTTP 明文请求（usesCleartextTraffic 改为 false）；使用局域网 HTTP 模型接口的用户会受影响

另含 main 上先行落地的修复：

- 修复：项目卡片消息不再串入 Bot 的对话上下文
- 优化：新建 Bot 默认预设改为「通用助手」，不再默认总经理

## v0.2.0（P0）— 2026-11-07

- 总经理负责制流水线：立项、派活、审查、打回返工（最多一次）、结项汇报
- 危险命令守卫 Guard 与安全拦截记录
- API Key 经 Android Keystore 加密存储
- 连接自检；预设扩充至 14 个
- 首个经 GitHub Actions 构建并公开发布的版本
