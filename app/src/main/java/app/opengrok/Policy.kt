package app.opengrok

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject

/**
 * v0.7.0 four-level action policy ("权限与哨兵").
 * Ordinal order matters: a higher ordinal is stricter, floors are applied with maxOf by ordinal.
 */
enum class PolicyLevel(val label: String) {
    AUTO("自动"),
    EXPLICIT("明确要求才自动"),
    ASK("先问我"),
    HANDOFF("交给我")
}

enum class ActionClass(val label: String, val desc: String, val configurable: Boolean, val default: PolicyLevel) {
    READ("读取", "查看记忆、任务、技能、文件列表、截屏与读屏等只读操作,始终自动。", false, PolicyLevel.AUTO),
    SANDBOX("沙盒命令", "在 Bot 自己的 Ubuntu 沙盒里执行 shell 命令;开启了「命令先批准」的 Bot 一律先问。", true, PolicyLevel.AUTO),
    FILE_WRITE("写手机文件", "fs_write 写入手机上的文件。", true, PolicyLevel.AUTO),
    DELETE("删除", "fs_delete 删除手机上的文件或目录。", true, PolicyLevel.ASK),
    PRIVILEGED("特权命令", "shizuku_shell 以 shell 身份执行系统命令。", true, PolicyLevel.ASK),
    SCREEN("操作屏幕", "点击、长按、拖动、输入、按键、打开应用与手机任务。", true, PolicyLevel.AUTO),
    WEB("网页操作", "AI 浏览器的打开、点击、输入,以及用系统浏览器打开链接。", true, PolicyLevel.AUTO),
    EXTERNAL("外部服务", "调用 MCP 服务提供的工具。", true, PolicyLevel.AUTO),
    SELF("自我管理", "写记忆、定时任务、技能、工作流,以及向其他 Bot 求助。", true, PolicyLevel.AUTO),
    SENSITIVE_DATA("敏感数据", "参数里出现卡号、身份证号、验证码、密钥等,或要填入保险箱凭据:至少先问你。", false, PolicyLevel.ASK),
    PAYMENT("支付与账户", "支付、转账、账户安全页面或受保护的应用:一律交给你亲自操作。", false, PolicyLevel.HANDOFF)
}

/**
 * Pure policy rules (no Android / org.json), unit-tested in PolicyTest.
 * Tool arguments are read through [arg] so tests can pass a plain map.
 */
internal object PolicyRules {
    val READ_TOOLS = setOf(
        "recall", "list_tasks", "list_skills", "use_skill", "secret_list", "team_status",
        "fs_list", "fs_read", "fs_scan", "device_status", "app_usage", "list_apps", "recent_notifications",
        "screen_shot", "screen_dump", "screen_mode", "browser_screenshot", "browser_close"
    )
    val SCREEN_TOOLS = setOf("tap_at", "long_press", "drag_at", "tap_text", "input_text", "press_key", "launch_app", "phone_task")
    val WEB_TOOLS = setOf("browser_open", "browser_mouse", "browser_type", "open_url")
    val SELF_TOOLS = setOf("remember", "schedule_task", "create_skill", "run_workflow", "ask_bot", "ticket_create", "ticket_update", "board_write")

    fun classify(tool: String, arg: (String) -> String): ActionClass = when {
        tool.startsWith("mcp_") -> ActionClass.EXTERNAL
        tool == "shell" -> ActionClass.SANDBOX
        tool == "fs_write" -> ActionClass.FILE_WRITE
        tool == "fs_delete" -> ActionClass.DELETE
        tool == "shizuku_shell" -> ActionClass.PRIVILEGED
        tool == "browser_fill_secret" -> ActionClass.SENSITIVE_DATA
        tool == "recall_spill" || tool == "ticket_list" || tool == "board_read" -> ActionClass.READ
        tool == "open_deeplink" -> ActionClass.WEB
        tool == "browser_nav" -> {
            val act = arg("action").trim().lowercase()
            if (act.isEmpty() || act == "snapshot") ActionClass.READ else ActionClass.WEB
        }
        tool in READ_TOOLS -> ActionClass.READ
        tool in SCREEN_TOOLS -> ActionClass.SCREEN
        tool in WEB_TOOLS -> ActionClass.WEB
        tool in SELF_TOOLS -> ActionClass.SELF
        // Unknown names are treated as outbound actions; runTool rejects them anyway.
        else -> ActionClass.EXTERNAL
    }

    fun stricter(a: PolicyLevel, b: PolicyLevel): PolicyLevel = if (a.ordinal >= b.ordinal) a else b

    /**
     * Applies non-configurable defaults and floors. [configured] is the user's choice for
     * configurable classes; [shellApproval] is the Bot's legacy「命令先批准」switch.
     */
    fun effectiveLevel(cls: ActionClass, configured: PolicyLevel, shellApproval: Boolean): PolicyLevel {
        var lvl = if (cls.configurable) configured else cls.default
        if (shellApproval && (cls == ActionClass.SANDBOX || cls == ActionClass.PRIVILEGED || cls == ActionClass.DELETE)) {
            lvl = stricter(lvl, PolicyLevel.ASK)
        }
        if (cls == ActionClass.SENSITIVE_DATA) lvl = stricter(lvl, PolicyLevel.ASK)
        if (cls == ActionClass.PAYMENT) lvl = PolicyLevel.HANDOFF
        return lvl
    }

    /** The argument EXPLICIT compares against the latest human message ("" = none, so EXPLICIT falls back to ASK). */
    fun keyArg(tool: String, arg: (String) -> String): String = when (tool) {
        "shell", "shizuku_shell" -> arg("command")
        "fs_list", "fs_read", "fs_write", "fs_delete", "fs_scan" -> arg("path")
        "browser_open", "open_url" -> arg("url")
        "browser_type", "input_text", "tap_text" -> arg("text")
        "launch_app" -> arg("query")
        "remember" -> arg("text")
        "schedule_task" -> arg("task")
        "ask_bot" -> arg("message")
        "create_skill", "run_workflow" -> arg("name")
        "phone_task" -> arg("task")
        else -> ""
    }.trim()

    fun hostOf(url: String): String {
        var u = url.trim().lowercase()
        val scheme = u.indexOf("://")
        if (scheme >= 0) u = u.substring(scheme + 3)
        val end = u.indexOfFirst { it == '/' || it == '?' || it == '#' || it == ':' }
        if (end >= 0) u = u.substring(0, end)
        val at = u.lastIndexOf('@')
        if (at >= 0) u = u.substring(at + 1)
        return u.removePrefix("www.")
    }

    /** EXPLICIT: the action's key argument must appear in the latest human message of the chat. */
    fun explicitlyRequested(tool: String, key: String, humanText: String): Boolean {
        val k = key.trim()
        if (k.length < 2 || humanText.isBlank()) return false
        if (tool == "browser_open" || tool == "open_url") {
            val host = hostOf(k)
            if (host.length >= 3 && humanText.contains(host, ignoreCase = true)) return true
        }
        return humanText.contains(k, ignoreCase = true)
    }

    private val PAY_TOKENS = setOf(
        "pay", "payment", "payments", "checkout", "cashier", "billing", "wallet", "transfer",
        "alipay", "paypal", "tenpay", "unionpay", "wxpay", "remittance"
    )
    private val PAY_HOSTS = listOf(
        "alipay.com", "pay.weixin.qq.com", "tenpay.com", "unionpay.com", "95516.com",
        "paypal.com", "checkout.stripe.com", "pay.google.com", "pay.jd.com"
    )
    private val ACCOUNT_PATHS = listOf(
        "account/security", "settings/security", "security/password", "changepassword", "change-password",
        "reset-password", "resetpassword", "account/delete", "deleteaccount"
    )
    private val PAY_TITLE = listOf(
        "支付", "付款", "收银台", "转账", "汇款", "银行卡", "账户安全", "修改密码", "注销账号",
        "checkout", "payment", "billing", "transfer money"
    )
    private val PAY_SCREEN_TEXT = listOf("支付密码", "确认付款", "立即支付", "确认转账", "输入密码", "确认支付", "免密支付")

    /** Payment / account-security page detection for the AI browser (URL tokens, known hosts, title keywords). */
    fun isSensitivePage(url: String, title: String): Boolean {
        val host = hostOf(url)
        if (host.isNotEmpty() && PAY_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val lower = url.lowercase()
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.any { it in PAY_TOKENS }) return true
        if (ACCOUNT_PATHS.any { lower.contains(it) }) return true
        val t = title.lowercase()
        return PAY_TITLE.any { t.contains(it) }
    }

    /** Visible-screen keywords that mean "the next tap may move money". */
    fun isPaymentText(visible: String): Boolean = PAY_SCREEN_TEXT.any { visible.contains(it) }

    fun isProtectedPackage(pkg: String, protected: List<String>): Boolean {
        val p = pkg.trim()
        return p.isNotEmpty() && protected.any { it.isNotBlank() && it.trim() == p }
    }
}

object Policy {
    private const val PREFS = "policy"

    /** Payment / banking apps the Agent never operates by itself (user editable). */
    val DEFAULT_PROTECTED_APPS: List<String> = listOf(
        "com.eg.android.AlipayGphone",      // 支付宝
        "com.unionpay",                     // 云闪付
        "com.icbc",                         // 工商银行
        "com.chinamworld.main",             // 建设银行
        "com.android.bankabc",              // 农业银行
        "com.chinamworld.bocmbci",          // 中国银行
        "cmb.pb",                           // 招商银行
        "com.bankcomm.Bankcomm",            // 交通银行
        "com.yitong.mbank.psbc",            // 邮储银行
        "com.jd.jrapp",                     // 京东金融
        "com.paypal.android.p2pmobile",     // PayPal
        "com.google.android.apps.walletnfcrel" // Google Wallet
    )

    @Volatile
    private var appCtx: Context? = null

    /** Bumped on every change so Compose screens reading it recompose. */
    val revision: MutableIntState = mutableIntStateOf(0)

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bump() {
        revision.intValue = revision.intValue + 1
    }

    /** User-configured level with floors applied (the Bot's shellApproval is applied separately by the gate). */
    fun level(cls: ActionClass): PolicyLevel = PolicyRules.effectiveLevel(cls, configured(cls), false)

    internal fun configured(cls: ActionClass): PolicyLevel {
        if (!cls.configurable) return cls.default
        val raw = prefs()?.getString("lvl_" + cls.name, null) ?: return cls.default
        return PolicyLevel.values().firstOrNull { it.name == raw } ?: cls.default
    }

    fun setLevel(context: Context, cls: ActionClass, level: PolicyLevel) {
        if (!cls.configurable) return
        appCtx = context.applicationContext
        prefs()?.edit()?.putString("lvl_" + cls.name, level.name)?.apply()
        bump()
    }

    var sentinelEnabled: Boolean
        get() = prefs()?.getBoolean("sentinel_on", false) ?: false
        set(v) {
            prefs()?.edit()?.putBoolean("sentinel_on", v)?.apply()
            bump()
        }

    /** Reviewer Bot id; "" = the acting Bot reviews itself. */
    var sentinelReviewerId: String
        get() = prefs()?.getString("sentinel_reviewer", "") ?: ""
        set(v) {
            prefs()?.edit()?.putString("sentinel_reviewer", v)?.apply()
            bump()
        }

    /** Also quick-review AUTO outbound actions (browser typing, MCP calls, input_text). */
    var sentinelReviewAuto: Boolean
        get() = prefs()?.getBoolean("sentinel_auto", false) ?: false
        set(v) {
            prefs()?.edit()?.putBoolean("sentinel_auto", v)?.apply()
            bump()
        }

    /** Mask verification codes / card numbers in notifications before they reach a model (default on). */
    var maskOtp: Boolean
        get() = prefs()?.getBoolean("mask_otp", true) ?: true
        set(v) {
            prefs()?.edit()?.putBoolean("mask_otp", v)?.apply()
            bump()
        }

    /** Clear AI-browser cookies and web storage when the browser closes (default off). */
    var clearBrowserOnClose: Boolean
        get() = prefs()?.getBoolean("clear_browser", false) ?: false
        set(v) {
            prefs()?.edit()?.putBoolean("clear_browser", v)?.apply()
            bump()
        }

    fun protectedApps(): List<String> {
        val raw = prefs()?.getString("protected_apps", null) ?: return DEFAULT_PROTECTED_APPS
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setProtectedApps(context: Context, pkgs: List<String>) {
        appCtx = context.applicationContext
        val clean = pkgs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs()?.edit()?.putString("protected_apps", clean.joinToString("\n"))?.apply()
        bump()
    }

    fun classify(tool: String, args: JSONObject): ActionClass =
        PolicyRules.classify(tool) { key -> args.optString(key, "") }

    fun isSensitivePage(url: String, title: String): Boolean = PolicyRules.isSensitivePage(url, title)

    fun isPaymentText(visible: String): Boolean = PolicyRules.isPaymentText(visible)

    fun isProtectedApp(pkg: String): Boolean = PolicyRules.isProtectedPackage(pkg, protectedApps())
}
