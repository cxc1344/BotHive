package app.opengrok

import java.util.UUID

data class ToolCall(val id: String, val name: String, val args: String, val output: String = "")

data class Msg(
    val id: String = UUID.randomUUID().toString(),
    val role: String,            // "user" | "assistant" | "system" | "project"(text = project id)
    val text: String,
    val tools: List<ToolCall> = emptyList(),
    val from: String? = null     // bot id of the author; null = the human user
)

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

/** A reusable instruction pack ("技能") that can be attached to Bots / the Agent; its content joins the system prompt. */
data class Skill(
    val id: String,
    val name: String,
    val content: String
)

/** A shell command waiting for the user's in-chat approval (permission broker). */
data class Approval(
    val id: String,
    val chatId: String,
    val botId: String,
    val command: String,
    val createdMs: Long
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
    val lastResult: String = ""
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
    "手机文件工具 list_dir/read_file/write_file(作用域是用户授权的文件夹)、系统工具(device_status 设备状态、app_usage 应用使用时长、list_apps/launch_app 应用管理、shizuku_shell 可选的 shell 级特权通道)、" +
    "屏幕操作工具(screen_dump 看屏、tap_text/tap_xy 点击、swipe 滑动、input_text 输入、press_key 系统键;需无障碍已开启;操作其他应用时先用 screen_dump 确认界面,点完关键步骤再 dump 核对)、" +
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
