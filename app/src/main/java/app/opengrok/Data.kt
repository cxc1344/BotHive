package app.opengrok

import java.util.UUID

data class ToolCall(
    val id: String,
    val name: String,
    val args: String,
    val output: String = "",
    /** Optional screenshot produced by vision tools (screen_shot); shown as a thumbnail in the tool card. */
    val image: String = ""
)

data class Msg(
    val id: String = UUID.randomUUID().toString(),
    val role: String,            // "user" | "assistant" | "system" | "project"(text = project id)
    val text: String,
    val tools: List<ToolCall> = emptyList(),
    val from: String? = null,    // bot id of the author; null = the human user
    val images: List<String> = emptyList(), // attached image file names under files/attachments/
    val time: Long = System.currentTimeMillis(),  // 0 = unknown (messages saved before v0.4.8)
    /** v0.7.0: user feedback on an assistant message: +1 (helpful), -1 (not helpful), 0 (none). */
    val feedback: Int = 0
)

/** A file/photo the user picked to attach to a chat message (bytes already read from the Uri). */
class AttachItem(val name: String, val mime: String, val bytes: ByteArray)

data class Bot(
    val id: String,
    val name: String,
    val prompt: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val isManager: Boolean = false,
    val allowInsecureHttp: Boolean = false,
    /** When set (and the provider still exists), baseUrl/apiKey/allowInsecureHttp come from the provider preset. */
    val providerId: String = "",
    /** Attached skill packs whose content joins the system prompt. */
    val skillIds: List<String> = emptyList(),
    /** When true, shell commands pause for the user's in-chat approval before running. */
    val shellApproval: Boolean = false
)

/** A reusable API connection preset (Operit-style model config): one base URL + key, many Bots can share it. */
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val allowInsecureHttp: Boolean = false
)

/** Reserved Bot id of the on-device Agent (Agent mode). It is stored separately from the cloud Bot list. */
const val AGENT_ID = "agent"

/** Built-in skill packs seeded once on first run (deletable; never re-seeded after the flag is set). */
val PRESET_SKILLS: List<Skill> = listOf(
    Skill("preset-exec", "靠谱执行者",
        "执行纪律:①动手前用一句话复述目标与计划;②长任务分步做,每完成一步简要汇报;③工具报错先读懂原因、换思路重试一次,仍失败再如实报告,绝不编造结果;④涉及删除、覆盖、发送等不可逆操作,先停下来问用户;⑤回复用用户的语言,结论先行,不啰嗦。"),
    Skill("preset-butler", "手机管家",
        "巡检方法:被要求巡检或汇报手机状态时,依次查 device_status(电量与存储)、app_usage(今日时长)、recent_notifications(未读通知),按「电量与存储 / 今天最耗时的应用 / 重要通知」三段汇总,只报异常与要点,不写流水账。"),
    Skill("preset-files", "文件整理术",
        "整理文件时:①先用 fs_scan 统计各目录大小摸清现状(fs_list 逐个看太慢);②给出按类型/日期的归类方案清单让用户确认;③只移动不删除,删除必须逐项经用户同意;④重名加后缀,绝不覆盖;⑤整理完汇报移动了什么、还剩什么。"),
    Skill("preset-frugal", "省额度模式",
        "省钱省额度:①回答尽量简短直接;②能一次工具调用解决的不分多次,查多项信息时先合并规划;③长输出先给摘要,用户要细节再展开;④遇到 429 不要连环重试,等一等或提示用户在 Key 池里多备几个 Key 自动轮换。"),
    Skill("preset-tutor", "结构化学习辅导",
        "辅导学习时:①先确认对方当前阶段与已知基础,不用超纲方法(绕不开时先说明);②先拆核心思路再分步推导,关键概念用生活类比讲透;③讲完给一道难度相当的同类小题让对方独立练习,再针对薄弱点复盘。"),
    Skill("preset-ubuntu", "Ubuntu 工具箱",
        "沙盒速查:工作目录默认 /workspace 且会跨命令保持(支持工作区内的相对 cd 或 cd /workspace/...);装包用 apt-get update && apt-get install -y <包名>;已预装 python3、nodejs、git、curl、jq、ffmpeg;单条命令超时 30 秒,长任务拆开跑或 nohup 写日志再读结果;和手机互传文件用 fs_* 工具。")
)

/** A reusable instruction pack ("技能") that can be attached to Bots / the Agent; its content joins the system prompt. */
data class Skill(
    val id: String,
    val name: String,
    val content: String,
    /** v0.7.0: one-line "when to use" description; Bots see it in list_skills and load skills on demand via use_skill. */
    val description: String = ""
)

/** v0.7.0: six-part skill skeleton (when / inputs / steps / validation / output / approvals). */
const val SKILL_TEMPLATE: String =
    "## 何时使用\n- (什么情况下该用这个技能,一两句话)\n\n" +
    "## 需要的输入与权限\n- (需要用户提供什么、要用到哪些工具或授权)\n\n" +
    "## 步骤\n1. \n2. \n3. \n\n" +
    "## 如何验证结果\n- (怎样确认真的做成了:文件路径、截图、命令输出…)\n\n" +
    "## 输出什么\n- (最后交给用户的内容与格式)\n\n" +
    "## 哪些操作需要批准\n- (删除、发送、付款、改动系统等必须先问用户的步骤)\n"

/** v0.7.0: daily brief task for the chief-of-staff Bot (used by the「蜂巢早报」routine). */
const val CHIEF_BRIEF_TASK: String =
    "请为用户写今天的「蜂巢早报」。先调用 team_status 获取整个蜂巢的现状,然后按这个顺序输出:" +
    "①需要用户先处理的事(待批准操作、失败的例行任务、等待确认的项目),每条说明为什么需要他;" +
    "②各 Bot 过去 24 小时做了什么,一句话一条;" +
    "③今天已排期的例行任务;" +
    "④你建议的下一步,并把能直接开工的第一步草拟好(例如回复草稿、命令或清单)。" +
    "每一条都在末尾用【来源:对话名】标出出处。只依据 team_status 的内容,没有的信息不要编造;没有要处理的事就直说。简洁,结论先行。"

/** A tool call waiting for the user's approval (permission broker). */
data class Approval(
    val id: String,
    val chatId: String,
    val botId: String,
    val command: String,
    val createdMs: Long,
    /** Tool waiting for approval: "shell" (default), "shizuku_shell", "fs_delete", or (v0.7.0) any tool name. */
    val toolName: String = "shell",
    /** v0.7.0: original tool arguments (JSON) for generic replay; "" = legacy approval (command-only path). */
    val argsJson: String = "",
    /** v0.7.0: one-line human description of what will happen. */
    val summary: String = "",
    /** v0.7.0: ActionClass.name of the gated action; "" for legacy approvals. */
    val actionClass: String = "",
    /** v0.7.0: wall-clock expiry; 0 = never (attended chats). */
    val expiresMs: Long = 0,
    /** v0.7.0: sentinel review: "" | "pending" | "ok" | "warn" | "block" | "error". */
    val sentinelVerdict: String = "",
    val sentinelNote: String = ""
)

/** A remote MCP server (Streamable HTTP JSON-RPC). headers may carry secrets; stored Keystore-encrypted. */
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val headers: String = "",
    val enabled: Boolean = true
)

/** A self-scheduled task an AI (or the user) assigned to a Bot / the Agent. */
data class Routine(
    val id: String,
    val ownerId: String,
    val task: String,
    val nextRunMs: Long,
    val repeatDaily: Boolean = false,
    val createdBy: String = "user",
    val lastRunMs: Long = 0,
    val lastResult: String = "",
    /** v0.7.0: "time" | "notification" | "webhook". */
    val trigger: String = "time",
    /** v0.7.0 (notification trigger): package name or app-label substring; empty = any app. */
    val matchApp: String = "",
    /** v0.7.0 (notification trigger): keyword in title/text; empty = any. */
    val matchText: String = "",
    val paused: Boolean = false,
    /** v0.7.0: minimum minutes between two event-triggered runs. */
    val minIntervalMin: Int = 5,
    val lastFiredMs: Long = 0,
    /** v0.7.0: newest first, at most 20 records. */
    val history: List<RunRecord> = emptyList()
)

/** One routine run (v0.7.0). trigger: "time" | "notification" | "webhook" | "manual". */
data class RunRecord(val startMs: Long, val ok: Boolean, val summary: String, val trigger: String)

/** One tool call in the cross-chat audit timeline (v0.7.0). */
data class ActivityItem(
    val time: Long,
    val chatId: String,
    val chatTitle: String,
    val botId: String?,
    val botName: String,
    val msgId: String,
    val tool: ToolCall
)

data class WStep(val botId: String, val instruction: String)

/** A user-built workflow: steps run in order, each step's output feeds the next via {prev}. */
data class Workflow(
    val id: String,
    val name: String,
    val steps: List<WStep>,
    val lastOutputs: List<String> = emptyList(),
    val lastRunMs: Long = 0
)

const val AGENT_DEFAULT_PROMPT =
    "你是一个运行在用户安卓手机上的设备 Agent。你有这些工具:shell(Ubuntu Linux 环境,可装软件、跑脚本)、" +
    "手机文件工具 fs_list/fs_read/fs_write/fs_delete/fs_scan(绝对路径,支持普通/Shizuku/Root 三通道)、系统工具(device_status 设备状态、app_usage 应用使用时长、list_apps/launch_app 应用管理、shizuku_shell 可选的 shell 级特权通道)、" +
    "屏幕操作工具(screen_shot 截屏看屏、tap_at 点击、long_press 长按、drag_at 拖动、input_text 输入、press_key 系统键;需无障碍已开启。视觉模式:先 screen_shot 看画面,再把目标在画面中的相对位置换算成 0-1000 坐标进行点击/长按/拖动;文字备用:screen_dump 读界面文字、tap_text 按文字点击)。" +
    "remember/recall(长期记忆)、ask_bot(向云端 Bot 同伴求助或派活)。" +
    "动手前先想清楚步骤;涉及写/删文件先确认路径在授权目录内。完成任务后简洁汇报做了什么、结果如何。"

data class Group(val id: String, val name: String, val memberIds: List<String>)

data class GuardEntry(val time: Long, val botId: String, val cmd: String, val reason: String)

enum class Stage { PLANNING, AWAIT_CONFIRM, EXECUTING, REVIEWING, REPORTING, DONE, CANCELLED, INTERRUPTED }

/** status: pending | running | submitted | approved | rework | rejected */
data class Assignment(
    val botId: String,
    val task: String,
    val status: String = "pending",
    val deliverable: String = "",
    val review: String = "",
    val attempts: Int = 0
)

data class Project(
    val id: String,
    val managerId: String,
    val title: String,
    val request: String,
    val stage: Stage,
    val assignments: List<Assignment>,
    val report: String = "",
    val note: String = ""
)

data class Preset(val name: String, val prompt: String, val manager: Boolean = false)

val PRESETS = listOf(
    Preset("总经理",
        "你是总经理。职责:把用户的任务拆成可交付的工作包,按员工专长分派;审查员工提交的成果,给出明确的「通过」或「打回」结论,打回时写清具体、可执行的修改意见;最后向用户做结项汇报。你不亲手做执行性工作。审查标准:是否达成任务目标、成果是否可验证(有文件路径或实际输出)、有无明显错误或遗漏。",
        manager = true),
    Preset("通用助手", "你是一个可靠、直接的助手。先给结论,再给必要的理由;不确定时明说,不编造。"),
    Preset("程序员", "你是一名资深软件工程师。需要验证时就用 shell 工具真的去运行代码,再根据实际输出作答;改动要小而准,并说明风险。"),
    Preset("前端工程师", "你是前端工程师,负责网页与交互界面的实现:HTML/CSS/JavaScript、响应式布局、可访问性与性能。交付可直接打开运行的文件,说明文件位置与预览方式;设计取舍要讲理由。"),
    Preset("后端工程师", "你是后端工程师,负责接口、数据模型与服务逻辑:设计清晰的 API,处理错误与边界条件,写可复现的测试或调用示例,并用 shell 实际运行验证后再汇报。"),
    Preset("全栈工程师", "你是全栈工程师,能独立完成从界面到接口再到数据存储的完整小项目。先给出最小可行方案,再实现;所有产出放进工作目录并列出运行方式,用 shell 验证能跑通。"),
    Preset("办公助手", "你是办公助手,负责整理资料、起草邮件与通知、撰写会议纪要、做表格与清单。输出格式规整、语气得体,先给可直接使用的成稿,再附一两条改进建议。"),
    Preset("数据分析师", "你是数据分析师。拿到数据先检查质量(缺失、异常、口径),再分析;用 shell 运行脚本得到真实数字,不凭空估计。结论先行,附上方法、关键数字与局限。"),
    Preset("数学助理", "你是数学助理。严谨地推导并检验结果:能用 shell 数值验证的就验证;步骤清楚、记号一致,最后给出结论,并指出所用假设。"),
    Preset("文案写手", "你是文案写手。先确认受众、场景与语气,再写;给出主稿加一到两个可替换的版本。文字要具体、有画面感,避免空话套话。"),
    Preset("计划助理", "你是计划助理。把目标拆成有先后依赖的任务清单,标注优先级、预估耗时与风险,安排到具体时间段;计划要现实,留出缓冲,并说明如何检查进度。"),
    Preset("研究员", "你是一名严谨的研究助理。把问题拆成子问题,逐个核对,区分事实与推测,最后给出带不确定性说明的结论。"),
    Preset("运维管家", "你负责整理文件、写脚本和检查系统状态。执行任何有破坏性的命令前先说明将要做什么。"),
    Preset("自定义", "")
)

/** An archived Agent conversation (the live one always uses AGENT_ID); its messages live under "agentarc:<id>". */
data class AgentSession(val id: String, val title: String, val time: Long)
