package app.opengrok

/**
 * v0.7.0: Bix (the pixel bee) speaks every user-facing notification and UI-only notice in the
 * first person: short, friendly, a little playful, still clear.
 *
 * Rules:
 *  - Only for text the USER reads (notifications, UI notices, overlay labels). Tool results and
 *    anything a model must parse stay neutral and factual: never route them through BixVoice.
 *  - Never put secrets or amounts in the text: every dynamic part goes through [safe].
 *
 * Pure Kotlin (no Android types) so the UI (Worker B) and tests can reuse it.
 */
object BixVoice {
    private val MONEY = Regex("(?:[¥￥\$€£]\\s?[0-9][0-9,.]*)|(?:[0-9][0-9,.]*\\s?(?:元|块钱|块|美元|美金|USD|RMB|CNY|EUR))")

    /** Masks secrets, removes money amounts, flattens whitespace and caps the length. */
    fun safe(raw: String, max: Int = 40): String {
        val masked = LeakGuard.mask(raw)
        val noMoney = MONEY.replace(masked, "…")
        val flat = noMoney.replace(Regex("\\s+"), " ").trim()
        return if (flat.length > max) flat.take(max) + "…" else flat
    }

    const val TITLE = "BotHive · Bix"

    fun approvalNeeded(botName: String, actionLabel: String): String =
        "Bix 拦下了一条待批准的「${safe(actionLabel, 16)}」操作,来自 ${safe(botName, 16)},等你点头~"

    fun approvalExpired(botName: String, actionLabel: String): String =
        "Bix 等了 30 分钟没等到你,${safe(botName, 16)} 的「${safe(actionLabel, 16)}」审批已过期,这一步没有执行。"

    /** [triggerLabel] e.g. "一条通知" / "一个 Webhook" / "闹钟". */
    fun routineFired(task: String, triggerLabel: String): String =
        "Bix 听到${safe(triggerLabel, 12)}响了,已让蜂巢开工:${safe(task, 24)}"

    fun routineFailed(task: String): String =
        "Bix 发现例行任务「${safe(task, 24)}」没跑成,点开看看原因吧"

    /** [reason] e.g. "连续失败 3 次" / "你 7 天没来蜂巢了". */
    fun routinePaused(what: String, reason: String): String =
        "Bix 发现${safe(what, 24)}${safe(reason, 24)},已先暂停,回来点一下就能恢复"

    fun briefReady(botName: String): String =
        "蜂巢早报出炉啦 · ${safe(botName, 16)} 把今天要你过目的事整理好了"

    fun budgetHit(botName: String): String =
        "Bix 帮你踩了刹车:${safe(botName, 16)} 今天的用量到预算上限啦,明天自动恢复"

    fun handoff(reason: String): String =
        "Bix 把这一步交还给你:${safe(reason, 40)}"

    fun teachRecording(): String = "Bix 正在看你示范 · 点此结束"

    fun teachSaved(steps: Int): String = "Bix 记下了 $steps 步,去生成技能吧"

    const val KEEP_ALIVE_TITLE = "Bix 在蜂巢门口守着"

    const val KEEP_ALIVE_TEXT = "例行任务、通知触发和 Webhook 都由我盯着,放心去忙吧"
}
