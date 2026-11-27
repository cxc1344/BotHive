package app.opengrok

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class PersonalGoal(
    val id: String,
    val title: String,
    val cadenceHours: Int = 24,
    val nextCheckAt: Long = System.currentTimeMillis() + 24L * 3600_000L,
    val notes: String = "",
    val lastDraft: String = "",
    val enabled: Boolean = true
)

enum class FenceMode(val label: String) {
    BLOCKLIST("敏感拦截（默认屏蔽聊天/支付/银行）"),
    ALLOWLIST("严格白名单（仅勾选 App 可进入蜂巢）")
}

data class BotWalletCap(
    val botId: String,
    val dailyTokenCap: Int = 120_000,
    val usedTodayTokens: Int = 0,
    val dayKey: String = ""
)

/**
 * v0.7.2 Personal Agent Core:
 *  1. Proactive Goals & Draft-First Check-ins (inspired by Xiaodou + Cue Scheduled Check-ins).
 *  2. Notification Privacy Fence (protects sensitive messaging/banking apps from leaking into Router/Memory).
 *  3. Per-Bot Wallet Cap (Cue-style daily budget envelope per Bot).
 */
object PersonalAgent {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var goalsFile: File? = null
    private var fenceFile: File? = null
    private var walletFile: File? = null

    val goals = mutableStateListOf<PersonalGoal>()
    val fenceMode = mutableStateOf(FenceMode.BLOCKLIST)
    val blockedPackages = mutableStateListOf<String>()
    val allowedPackages = mutableStateListOf<String>()
    val blockedCount = mutableIntStateOf(0)
    val recentBlocked = mutableStateListOf<String>()
    val wallets = mutableStateListOf<BotWalletCap>()

    val DEFAULT_SENSITIVE_PACKAGES = listOf(
        "com.tencent.mm",                  // 微信
        "com.tencent.mobileqq",            // QQ
        "com.eg.android.AlipayGphone",     // 支付宝
        "org.thoughtcrime.securesms",      // Signal
        "org.telegram.messenger",          // Telegram
        "com.whatsapp",                    // WhatsApp
        "com.android.mms",                 // 系统短信
        "com.google.android.apps.messaging"// Google Messages
    )

    private val SENSITIVE_KEYWORDS = listOf("bank", "icbc", "cmb", "ccb", "chinamworld", "unionpay", "wallet", "auth", "authenticator")

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun init(ctx: Context) {
        val dir = ctx.filesDir
        goalsFile = File(dir, "personal_goals.json")
        fenceFile = File(dir, "privacy_fence.json")
        walletFile = File(dir, "bot_wallets.json")
        loadGoals()
        loadFence()
        loadWallets()
    }

    // ---- 1. Proactive Goals & Draft-First Check-in ----

    private fun loadGoals() {
        val f = goalsFile ?: return
        if (!f.isFile) return
        runCatching {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<PersonalGoal>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    PersonalGoal(
                        id = o.optString("id", "gl_" + UUID.randomUUID().toString().take(6)),
                        title = o.optString("title", ""),
                        cadenceHours = o.optInt("cadenceHours", 24),
                        nextCheckAt = o.optLong("nextCheckAt", System.currentTimeMillis() + 24L * 3600_000L),
                        notes = o.optString("notes", ""),
                        lastDraft = o.optString("lastDraft", ""),
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
            onMain { goals.clear(); goals.addAll(out) }
        }
    }

    private fun persistGoals(list: List<PersonalGoal>) {
        val f = goalsFile ?: return
        runCatching {
            val arr = JSONArray()
            for (g in list) {
                arr.put(
                    JSONObject()
                        .put("id", g.id)
                        .put("title", g.title)
                        .put("cadenceHours", g.cadenceHours)
                        .put("nextCheckAt", g.nextCheckAt)
                        .put("notes", g.notes)
                        .put("lastDraft", g.lastDraft)
                        .put("enabled", g.enabled)
                )
            }
            f.writeText(arr.toString(2))
        }
    }

    fun addGoal(title: String, cadenceHours: Int = 24, notes: String = ""): PersonalGoal {
        val hrs = cadenceHours.coerceIn(1, 168)
        val g = PersonalGoal(
            id = "gl_" + UUID.randomUUID().toString().take(6),
            title = title.trim(),
            cadenceHours = hrs,
            nextCheckAt = System.currentTimeMillis() + hrs * 3600_000L,
            notes = notes.trim()
        )
        val snap = listOf(g) + goals.toList()
        onMain { goals.add(0, g) }
        persistGoals(snap)
        return g
    }

    fun removeGoal(id: String) {
        val snap = goals.filterNot { it.id == id }
        onMain { goals.removeAll { it.id == id } }
        persistGoals(snap)
    }

    fun toggleGoal(id: String) {
        val snap = goals.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        onMain {
            val idx = goals.indexOfFirst { it.id == id }
            if (idx >= 0) goals[idx] = snap[idx]
        }
        persistGoals(snap)
    }

    /** Triggers an immediate Draft-First Super-Agent Check-in for a goal. */
    fun triggerCheckIn(store: Store, goal: PersonalGoal): Ticket {
        val targetBotId = store.chiefId.value.takeIf { it.isNotBlank() } ?: AGENT_ID
        val chatId = if (targetBotId == AGENT_ID) AGENT_ID else "bot:$targetBotId"
        val relevantMem = MemoryPod.queryForBot(targetBotId, goal.title).take(5)
            .joinToString("\n") { "- [${it.category.label}/${it.confidence.label}] ${it.content}" }
        val tk = SuperAgent.createTicket(
            title = "目标跟进：${goal.title}",
            goal = goal.title,
            ownerBotId = targetBotId,
            priority = 2,
            source = "personal_goal",
            threadChatId = chatId
        )
        SuperAgent.updateTicket(tk.id, checkpoint = "触发主动 Check-in（Draft-First）")
        val prompt = buildString {
            append("请为我的长期目标「${goal.title}」做一次轻量、眼里有活儿的主动 Check-in（Draft-First）：\n")
            if (goal.notes.isNotBlank()) append("目标备注：${goal.notes}\n")
            if (relevantMem.isNotBlank()) append("参考个人记忆舱：\n$relevantMem\n")
            append("请直接备好今天最值得推进的 1～3 步行动草案，等我确认后再执行外部操作。关联工单：${tk.id}。")
        }
        if (targetBotId == AGENT_ID) store.sendAgent(prompt) else store.send(chatId, prompt)
        val now = System.currentTimeMillis()
        val snap = goals.map {
            if (it.id == goal.id) it.copy(
                nextCheckAt = now + it.cadenceHours * 3600_000L,
                lastDraft = "已派发工单 ${tk.id} 准备草案"
            ) else it
        }
        onMain {
            val idx = goals.indexOfFirst { it.id == goal.id }
            if (idx >= 0) goals[idx] = snap[idx]
        }
        persistGoals(snap)
        return tk
    }

    // ---- 2. Notification Privacy Fence ----

    private fun loadFence() {
        val f = fenceFile
        if (f == null || !f.isFile) {
            onMain {
                blockedPackages.clear()
                blockedPackages.addAll(DEFAULT_SENSITIVE_PACKAGES)
            }
            return
        }
        runCatching {
            val o = JSONObject(f.readText())
            val mode = FenceMode.entries.firstOrNull { it.name == o.optString("mode") } ?: FenceMode.BLOCKLIST
            val bList = mutableListOf<String>()
            val aList = mutableListOf<String>()
            val bArr = o.optJSONArray("blocked")
            if (bArr != null) for (i in 0 until bArr.length()) bList.add(bArr.optString(i))
            val aArr = o.optJSONArray("allowed")
            if (aArr != null) for (i in 0 until aArr.length()) aList.add(aArr.optString(i))
            onMain {
                fenceMode.value = mode
                blockedPackages.clear(); blockedPackages.addAll(bList.ifEmpty { DEFAULT_SENSITIVE_PACKAGES })
                allowedPackages.clear(); allowedPackages.addAll(aList)
            }
        }
    }

    private fun persistFence() {
        val f = fenceFile ?: return
        runCatching {
            val o = JSONObject()
                .put("mode", fenceMode.value.name)
                .put("blocked", JSONArray(blockedPackages.toList()))
                .put("allowed", JSONArray(allowedPackages.toList()))
            f.writeText(o.toString(2))
        }
    }

    fun setFenceMode(mode: FenceMode) {
        onMain { fenceMode.value = mode }
        persistFence()
    }

    fun addBlockedPackage(pkg: String) {
        val clean = pkg.trim()
        if (clean.isBlank()) return
        onMain { if (clean !in blockedPackages) blockedPackages.add(0, clean) }
        persistFence()
    }

    fun removeBlockedPackage(pkg: String) {
        onMain { blockedPackages.remove(pkg) }
        persistFence()
    }

    fun addAllowedPackage(pkg: String) {
        val clean = pkg.trim()
        if (clean.isBlank()) return
        onMain { if (clean !in allowedPackages) allowedPackages.add(0, clean) }
        persistFence()
    }

    fun removeAllowedPackage(pkg: String) {
        onMain { allowedPackages.remove(pkg) }
        persistFence()
    }

    /** Called by OgNotificationListener before SuperRouter touches any notification. */
    fun allowNotification(pkg: String, appLabel: String): Boolean {
        val lower = pkg.lowercase()
        val ok = when (fenceMode.value) {
            FenceMode.ALLOWLIST -> pkg in allowedPackages
            FenceMode.BLOCKLIST -> {
                if (pkg in blockedPackages) false
                else SENSITIVE_KEYWORDS.none { lower.contains(it) }
            }
        }
        if (!ok) {
            onMain {
                blockedCount.intValue += 1
                val entry = "${appLabel.ifBlank { pkg }} ($pkg)"
                recentBlocked.add(0, entry)
                while (recentBlocked.size > 20) recentBlocked.removeAt(recentBlocked.lastIndex)
            }
        }
        return ok
    }

    // ---- 3. Per-Bot Wallet Cap (Daily Budget Envelope) ----

    private fun loadWallets() {
        val f = walletFile ?: return
        if (!f.isFile) return
        runCatching {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<BotWalletCap>()
            val today = todayKey()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val dk = o.optString("dayKey", today)
                out.add(
                    BotWalletCap(
                        botId = o.optString("botId", ""),
                        dailyTokenCap = o.optInt("dailyTokenCap", 120_000),
                        usedTodayTokens = if (dk == today) o.optInt("usedTodayTokens", 0) else 0,
                        dayKey = today
                    )
                )
            }
            onMain { wallets.clear(); wallets.addAll(out) }
        }
    }

    private fun persistWallets(list: List<BotWalletCap>) {
        val f = walletFile ?: return
        runCatching {
            val arr = JSONArray()
            for (w in list) {
                arr.put(
                    JSONObject()
                        .put("botId", w.botId)
                        .put("dailyTokenCap", w.dailyTokenCap)
                        .put("usedTodayTokens", w.usedTodayTokens)
                        .put("dayKey", w.dayKey)
                )
            }
            f.writeText(arr.toString(2))
        }
    }

    fun setWalletCap(botId: String, dailyTokenCap: Int) {
        val cap = dailyTokenCap.coerceAtLeast(5_000)
        val today = todayKey()
        val existing = wallets.firstOrNull { it.botId == botId }
        val updated = existing?.copy(dailyTokenCap = cap, dayKey = today)
            ?: BotWalletCap(botId = botId, dailyTokenCap = cap, usedTodayTokens = 0, dayKey = today)
        val snap = wallets.filterNot { it.botId == botId } + updated
        onMain {
            wallets.clear()
            wallets.addAll(snap)
        }
        persistWallets(snap)
    }

    fun walletFor(botId: String): BotWalletCap? = wallets.firstOrNull { it.botId == botId }
}
