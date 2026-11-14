# TODO-CLAUDE-UI.md — vNext 施工自述

本次为 BotHive 换肤 + 交互模式对齐（Claude 安卓端风格），依据 `_claude_ui_refs/WORK-ORDER-CLAUDE-UI.md`
与 `_claude_ui_refs/STUDY-CLAUDE-UI.md`。未跑本地构建（按指示交云端 CI 验证）；每个文件做过
括号配平、包级重名声明、对象成员解析、import 冗余四项自查。

## 红线核验

- `BeeUi.kt` 逐字节未动，`md5sum` = `3e609a2342851fd6999136705c386ade` ✅
- 核心逻辑零改动（md5 与开工前一致）：Guard / Secrets / FsTools / Sandbox / VirtualScreen /
  PhoneModel / PhoneOperator / ShizukuTools / Agent / Store / Data / Markdown ✅
- 玻璃算法文件零改动：LiquidGlass.kt / BackdropLens.kt / LiquidSwitch.kt ✅
  （玻璃 tint 一律由调用点显式传 `SheetGlassTint`，未改 `glassSurface` 默认值）
- 未新增任何依赖、未新增字体/图片资源；未动 `.github/`、`versionCode`/`versionName`、构建脚本 ✅
- 全仓无 `positionInRoot()`、无 `Animatable.value =` 赋值 ✅
- 玻璃层尺寸全部来自布局自身（Row 内容测量/matchParentSize），无任何 onGloballyPositioned
  回写决定玻璃尺寸（ModeCapsule 的 onGloballyPositioned 只上报坐标给 backdrop 采样，与 0.5.8 相同）✅
- API<33：沿用既有 `GlassShader.supported()` 静默回退材质路径，未新增分支 ✅

## 逐文件改动

### Ui.kt
- 色板 13 个全局 val 重指向 Claude 实测值（Bg#151515、Card#20201E、Fg#F5F5F2、Sub#9B9B97、
  Honey→珊瑚 #D47755、Ink 改深底色以配白/珊瑚钮面等）。~230 处旧名调用点自动换肤。
- `colorScheme` 同步（onPrimaryContainer 改 Fg）。
- 全局 `LocalTextStyle` 衬线覆盖移除：正文回到系统 sans。
- `MsgView`：用户气泡 → `UserBubble`#2A2A2D、右对齐、全 20dp 圆角；助手分支去气泡，正文直排版，
  行高 1.45（16sp/23sp）。
- 新增 `AssistantActions`：助手消息下复制+重发（重发走既有 `store.resendFrom`，与旧"重发"同语义，
  只在 bot/group/agent 线程出现）；朗读按工单不做。
- `ToolsPanel` 重做：N 个工具调用收敛为一条灰色单行（图标+单行摘要+chevron），点开新
  `ClaudeSheet("执行摘要")` 时间线（`SummaryStepRow`：方形圆角图标片+竖连线+当前步珊瑚点+
  衬线斜体"思考中…"）；展开态参数/输出/复制/重跑整体迁入弹层（内容不减）。
- `ThinkingDots` 珊瑚化、加 compact 形态；危险工具以红点缀于步骤行尾。
- `PillButton` 实心改白底黑字（Claude 主钮），空心情景不受影响。
- `ChatScreen` 输入区换新 `ChatComposer`；"立项模式"开关降为 composer 第二行的模型 chip 位（键盘
  弹出时可见），行为（`startProject` 分支）原样保留。

### UiTokens.kt（新建）
- `UiTokens`：实测色 const、圆角尺度（卡 20 / sheet 28 / 全圆）。
- `HiveSerif`：衬线三个字阶（Title 28sp/SheetTitle 18sp/Thinking 斜体），正文以外的显示位专用。
- 顶层新色 val：PILL / HAIRLINE / Coral / SelectBlue / UserBubble / SheetGlassTint / PillWhite。
- `ClaudeSheet`：通用底部弹层（scrim+上滑+拖柄+✕+衬线居中标题+玻璃体），Summary 与模型弹层共用。
- `SummaryStepRow`：时间线行。

### Fonts.kt
- `hiveTypography()` 不再把衬线塞进全部字阶：基线=系统 sans，衬线只走 `HiveSerif` 显式位。
- 未动捆绑字体文件与 `HiveFonts.Roman/Mono`（Roman 仍是衬线来源，Mono 继续管代码）。

### UiHome.kt
- 首页照 §2.1 重做：左上抽屉钮（忙时珊瑚点）→ 居中像素蜜蜂（`BrandOrb`，替 Claude 星标）→
  衬线时段问候 30sp → 一颗状态药丸（默认 Bot · 模型名，点开模型弹层）→ 底部悬浮玻璃输入胶囊。
  首页不放列表（旧 hero 卡/继续卡/四宫格磁贴移除；工作流等入口保留在抽屉）。
- 抽屉照 §2.2 重做：衬线 BotHive 字标、导航行（会话/设备 Agent/员工 Bots/工作流/搜索，均为真实
  目的地）、最近段（类型线描图标+单行省略+忙点/删除）、待批准命令置顶条（原"工作情况"数据保留）、
  左下珊瑚头像+设置入口、右下白底黑字「+ 新建」药丸。
- `ChatSubHeader` 改衬线 Bot/会话名；`EmptyChatHint` 改衬线标题。

### UiFlow.kt
- `ModeCapsule`：玻璃算法/液态透镜管线原样，只换 tint 与选中色（#313131 滑块、未选中灰字）。
- `StopButton`/`SendButton`：改 Claude 圆钮（停止=深灰圆+白方块、发送=白圆黑标，空态深灰）。
- 新增 `ChatComposer`：胶囊↔双行卡连续形变（ime insets 驱动 `animateFloatAsState`，Off 档跳变），
  第二行承载 chip 槽（模型 chip/立项模式 chip）；玻璃 tint 显式传参。
- 新增 `ModelSheet`（§2.5 分组大卡+行式模型+来源徽章+蓝对勾）：数据走既有 `ModelsApi.list` 实时
  拉取，不改配置存储；失败/为空时如实提示。
- `WorkflowsScreen` 等编辑页标题换衬线居中。

### UiAgent.kt
- Agent 会话输入区接 `ChatComposer`；Agent 空状态页照 §2.3（蜜蜂+衬线标题+说明+主钮）。

### UiExtra.kt
- Settings 照 §2.8：衬线居中标题、分组卡片流（SettingsRow / 发丝线 SettingsDivider）。
- `GlassTierCard` 改三张预览缩略卡（不要/中等/最高），选中蓝描边+蓝标签；LiquidSwitch 原样保留。

### UiTools.kt
- SearchScreen 改衬线居中标题+搜索胶囊（#20201E 圆角卡+放大镜）；命中高亮改珊瑚。

### Icons.kt
- 新增 11 个矢量（Copy / ChatBubbles / Tray / Code / Artifacts / Mic / VoiceWave / Info / Filter /
  Clock / Check），全部 `PathParser` 画法，未加资源文件。其中 Mic/VoiceWave/Info/Filter/Artifacts/
  Code 本版暂无调用点（语音与 Artifacts 无对应能力，见下"未达"）。

## 工单条目对照

1. 令牌层 ✅（UiTokens.kt + Ui.kt 色板重指向；衬线=既有 Noto Serif SC，无新文件）
2. 首页 ✅（蜜蜂+衬线问候+状态药丸+玻璃输入胶囊，无列表）
3. 抽屉 ✅（衬线字标/导航行/最近段/左下设置/右下白药丸；滑入动效沿用 GlassMotion.drawer，
   主页圆角露边因主页面本体不透明改为纯滑入——见"未达"）
4. 会话页 ✅（用户气泡/助手去气泡/工具收敛+Summary 弹层/动作行/珊瑚三点/停止钮）
5. 输入框双形态 ✅（ChatComposer 连续形变；麦克风无语音能力故不显示——见"未达"）
6. 模型弹层 ✅（Provider 预设实时拉取；只做展示与切换，不改存储）
7. 列表页与设置页 ✅（衬线居中标题+搜索胶囊；设置分组卡流+三段预览卡+LiquidSwitch 原样）
8. 动效 ✅（抽屉滑入、sheet 上滑+压暗、输入框形变、生成三点、消息入场 RowEnter 挂点原样）

## 降级 / 未做（诚实清单）

- **麦克风圆钮、语音模式钮**：应用当前没有任何语音输入能力，按红线"设置文案诚实"直接隐藏，
  未做占位假钮（工单 5 允许"无则隐藏"）。
- **「思考强度」行卡**：现有 Bot/Agent 参数里没有可映射的思考强度项，按工单 6"无则隐藏"未显示。
- **抽屉"主页圆角露边"**：Claude 的效果建立在主页面内容可滑移之上；本应用主页面是全屏填充布局，
  硬造圆角露边需要重排 ScreenHost 的层级（风险大于收益）。抽屉保留 0.5.8 的右缘圆角+滑入+
  压暗，观感接近。
- **顶栏幽灵图标 / ⋮ / 文档图标**：无对应功能（无痕模式不存在、无独立文档面板），不造假入口。
  会话页顶栏保留既有真实动作（导出/重置/编辑）。
- **Artifacts/Code 导航行**：无对应页面，未放进抽屉（工单 3 列举的"员工 Bots/技能包/例行任务"
  按真实目的地映射：员工 Bots→BotMode、技能包与例行任务在设置页内，抽屉不重复挂假入口）。
- **首页输入胶囊语义**：Claude 首页胶囊即当前会话输入；本应用首页不属于任何会话，方案是
  聚焦后输入、发送时落到默认 Bot 会话并跳转。行为不同但诚实，未写成"和 Claude 一样"。
- **朗读/赞/踩**：按工单 4 明确排除，未出现。
- **Markdown 渲染器**：零改动，只改了调用处的行高参数（16/23sp）。

## 存疑点（交 CI/真机验证）

- `ChatComposer` 的形变以 `WindowInsets.ime > 0` 为焦点信号：物理键盘或第三方输入法若不上报
  ime inset，会停留在胶囊形态（功能不受影响，只是形态不变）。如需可换成 `onFocusChanged`。
- `ClaudeSheet` 在 Off 档（无动画）下直接跳现，无过渡——符合"关=瞬时"的既有约定。
- `ToolsPanel` 收敛后，长输出滚动区从 280dp 降为 220dp（在 sheet 内），极长输出略变挤。
- 首页 `Spacer(weight)` 用 0.42/0.58 分割让问候块略偏上（照截图比例）；极小屏可能显得紧。
- `ModelSheet` 每次 visible 变化重新拉模型列表（无缓存）；与编辑页现拉行为一致，但高频开合会
  多发请求。存储结构未动。
- 版本字符串照工单未动 versionName，仅把设置页自述文案 "BotHive 0.5.8" 改为 "BotHive vNext"，
  落地发版时如需回写请以正式版本号为准。

## 落地校验与修复（Muse 验包，2026-11-14）

基线核对：工作副本由 Muse 于派工前从已发布 0.5.8+修复树（HEAD c05bb41）复制，基线现行。
红线复核通过：BeeUi MD5 一致；核心逻辑/玻璃算法/LiquidSwitch/Markdown 逐字节未动；
改动文件清单与自述一致（8 改 + UiTokens.kt 新建）；文件头/括号/符号交叉核对已逐一复查。
新 API 均经锁定版本核实存在（MarkdownText 的 fontSize/lineHeight 参数、GlassMotion.ms、
Store.provider(String?)、Icons 的 vec 画法）。

验出并已就地修复三处（自查未发现）：
1. **编译错误**：`AssistantActions` 调用 `store.resendFrom` 但形参缺 `store`——已补参并改调用点。
2. **功能回退**：Summary 弹层内的 `ToolDetail` 丢了工具截图（t.image）渲染——已按旧 ToolRow
   的实现恢复（含 BitmapFactory 解码与 320dp 限高）。
3. **功能回退（自述未披露）**：抽屉丢了「置顶/取消置顶」与「工作情况」运行行 +「全部停止」
   ——已在新视觉下恢复：DrawerRow 加回置顶钮（珊瑚点标记），LazyColumn 顶部加回
   DrawerWorkStatus（忙碌会话/活跃项目行 + 全部停止）。

其余如实记录（不阻断发版）：ClaudeSheet 为整屏高度而非内容自适应高度、顶角圆角因
clip 顺序可能不圆；ChatComposer 的形变动画在 tier.motion 条件内调用 animateFloatAsState，
切换玻璃档位时有组合顺序风险；首页「+」钮为聚焦输入而非附件；模型弹层只接在首页药丸，
会话页输入舱未挂模型 chip（工单 6 以首页路径达成）。
