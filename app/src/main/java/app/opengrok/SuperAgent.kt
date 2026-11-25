package app.opengrok

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.7.1 DeepSeek-inspired Super-Agent Runtime:
 *  - Persistent [Ticket] state machine decoupled from transient chat windows (saved in `tickets.json`).
 *  - [SuperRouter] Fast Path (0-token deterministic filtering & direct-to-Worker subscription) vs Slow Path escalation,
 *    wired directly into live Android notification events with per-ticket cooldown protection.
 *  - [HiveWatchdog] external health monitor detecting stalled Workers, recovering orphaned tickets on restart,
 *    and building checkpoint-aware resume prompts.
 */
enum class TicketStatus(val label: String) {
    QUEUED("排队中"),
    RUNNING("执行中"),
    BLOCKED("待审批/阻塞"),
    PAUSED("已暂停/可续跑"),
    DONE("已完成"),
    FAILED("失败")
}

data class Ticket(
    val id: String = "tk_" + UUID.randomUUID().toString().replace("-", "").take(6),
    val title: String,
    val goal: String,
    val ownerBotId: String,
    val status: TicketStatus = TicketStatus.QUEUED,
    val priority: Int = 2, // 1=高, 2=中, 3=低
    val source: String = "user", // user | notification | webhook | router
    val sourceKey: String = "", // subscription key for Fast Path direct-to-Worker routing
    val threadChatId: String = "",
    val checkpoints: List<String> = emptyList(),
    val spillIds: List<String> = emptyList(),
    val createdMs: Long = System.currentTimeMillis(),
    val updatedMs: Long = System.currentTimeMillis(),
    val lastHeartbeatMs: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String = ""
)

data class WatchdogEvent(
    val timeMs: Long,
    val ticketId: String,
    val level: String, // INFO | WARN | RECOVER
    val message: String
)

enum class RouteKind { IGNORE_NOISE, DIRECT_WORKER, ESCALATE_ROUTER }

data class RouteDecision(
    val kind: RouteKind,
    val ticketId: String? = null,
    val targetBotId: String? = null,
    val reason: String = ""
)

object SuperAgent {
    val tickets = mutableStateListOf<Ticket>()
    val watchdogLog = mutableStateListOf<WatchdogEvent>()
    val fastPathSavedCount = mutableIntStateOf(0)
    val directRoutedCount = mutableIntStateOf(0)
    val lastWatchdogBeatMs = mutableLongStateOf(0L)

    @Volatile
    private var storageFile: File? = null
    @Volatile
    private var initialized = false

    /**
     * Thread-safe state mutator: posts to Android's main Looper when called from IO/Default threads
     * so Compose SnapshotStateList never hits concurrent-snapshot conflicts, while running inline
     * on the main thread or in JVM unit tests where Looper is unmocked.
     */
    internal fun onStateThread(block: () -> Unit) {
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
        if (mainLooper == null || Looper.myLooper() === mainLooper) {
            block()
        } else {
            Handler(mainLooper).post(block)
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        ContextSpill.init(context)
        initFile(File(context.applicationContext.filesDir, "tickets.json"))
    }

    /** Test-friendly file initializer that also runs startup crash-recovery for orphaned RUNNING tickets. */
    fun initFile(file: File) {
        storageFile = file
        tickets.clear()
        watchdogLog.clear()
        SuperRouter.resetCooldowns()
        loadFromDisk()
        recoverOrphanedRunningTickets()
    }

    private fun loadFromDisk() {
        val f = storageFile ?: return
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            fastPathSavedCount.intValue = root.optInt("fastPathSaved", 0)
            directRoutedCount.intValue = root.optInt("directRouted", 0)
            val arr = root.optJSONArray("tickets") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                tickets.add(ticketFromJson(o))
            }
        }
    }

    @Synchronized
    fun save() {
        val f = storageFile ?: return
        val snapshot = tickets.toList()
        val savedFp = fastPathSavedCount.intValue
        val savedDr = directRoutedCount.intValue
        runCatching {
            val arr = JSONArray()
            snapshot.forEach { arr.put(ticketToJson(it)) }
            val root = JSONObject()
                .put("fastPathSaved", savedFp)
                .put("directRouted", savedDr)
                .put("tickets", arr)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(f)
        }
    }

    /**
     * Watchdog startup recovery: any ticket stuck in RUNNING across an OS kill or crash is
     * safely transitioned to PAUSED so the user can resume from its last checkpoint.
     */
    fun recoverOrphanedRunningTickets(): Int {
        var recovered = 0
        val now = System.currentTimeMillis()
        for (i in tickets.indices) {
            val t = tickets[i]
            if (t.status == TicketStatus.RUNNING) {
                tickets[i] = t.copy(
                    status = TicketStatus.PAUSED,
                    updatedMs = now,
                    lastError = "进程重启中断，已保留全部检查点，可一键断点续跑"
                )
                logWatchdog(t.id, "RECOVER", "检测到进程重启中断，工单「${t.title}」已安全切换为可续跑状态")
                recovered++
            }
        }
        if (recovered > 0) save()
        return recovered
    }

    fun createTicket(
        title: String,
        goal: String,
        ownerBotId: String,
        priority: Int = 2,
        source: String = "user",
        sourceKey: String = "",
        threadChatId: String = ""
    ): Ticket {
        val resolvedChat = threadChatId.ifBlank {
            if (ownerBotId == AGENT_ID) AGENT_ID else "bot:$ownerBotId"
        }
        val t = Ticket(
            title = title.trim().take(60).ifBlank { goal.trim().take(32) },
            goal = goal.trim(),
            ownerBotId = ownerBotId,
            status = TicketStatus.QUEUED,
            priority = priority.coerceIn(1, 3),
            source = source,
            sourceKey = sourceKey.trim(),
            threadChatId = resolvedChat
        )
        onStateThread {
            tickets.add(0, t)
            while (tickets.size > 120) tickets.removeAt(tickets.lastIndex)
            logWatchdog(t.id, "INFO", "立单「${t.title}」→ 分配给 $ownerBotId")
            save()
        }
        return t
    }

    fun updateTicket(
        ticketId: String,
        status: TicketStatus? = null,
        checkpoint: String = "",
        spillId: String = "",
        error: String? = null
    ): Ticket? {
        val old = tickets.firstOrNull { it.id == ticketId } ?: return null
        val now = System.currentTimeMillis()
        val nextCps = if (checkpoint.isNotBlank()) (old.checkpoints + checkpoint.trim()).takeLast(20) else old.checkpoints
        val nextSpills = if (spillId.isNotBlank() && spillId !in old.spillIds) (old.spillIds + spillId).takeLast(12) else old.spillIds
        val updated = old.copy(
            status = status ?: old.status,
            checkpoints = nextCps,
            spillIds = nextSpills,
            updatedMs = now,
            lastHeartbeatMs = now,
            lastError = error ?: old.lastError
        )
        onStateThread {
            val idx = tickets.indexOfFirst { it.id == ticketId }
            if (idx >= 0) {
                tickets[idx] = updated
                if (status != null && status != old.status) {
                    logWatchdog(updated.id, "INFO", "工单「${updated.title}」状态变更为：${status.label}")
                }
                save()
            }
        }
        return updated
    }

    fun heartbeatByChat(chatId: String, spillId: String? = null) {
        onStateThread {
            val now = System.currentTimeMillis()
            var changed = false
            for (i in tickets.indices) {
                val t = tickets[i]
                if (t.threadChatId == chatId && t.status == TicketStatus.RUNNING) {
                    val nextSpills = if (!spillId.isNullOrBlank() && spillId !in t.spillIds) (t.spillIds + spillId).takeLast(12) else t.spillIds
                    tickets[i] = t.copy(lastHeartbeatMs = now, updatedMs = now, spillIds = nextSpills)
                    changed = true
                }
            }
            if (changed) save()
        }
    }

    fun deleteTicket(ticketId: String) {
        onStateThread {
            tickets.removeAll { it.id == ticketId }
            save()
        }
    }

    /**
     * Dispatches or resumes a Ticket into its Worker Bot's chat thread, injecting prior checkpoints
     * and Spill IDs so the Worker seamlessly picks up from where it left off.
     */
    fun dispatchOrResume(store: Store, ticketId: String): Boolean {
        val idx = tickets.indexOfFirst { it.id == ticketId }
        if (idx < 0) return false
        val t = tickets[idx]
        val now = System.currentTimeMillis()
        val isResume = t.checkpoints.isNotEmpty() || t.status == TicketStatus.PAUSED || t.status == TicketStatus.FAILED
        val updated = t.copy(
            status = TicketStatus.RUNNING,
            updatedMs = now,
            lastHeartbeatMs = now,
            retryCount = if (isResume) t.retryCount + 1 else t.retryCount,
            lastError = ""
        )
        tickets[idx] = updated
        save()

        val prompt = buildResumePrompt(updated, isResume)
        logWatchdog(t.id, if (isResume) "RECOVER" else "INFO", if (isResume) "断点续跑工单「${t.title}」" else "启动执行工单「${t.title}」")
        if (t.ownerBotId == AGENT_ID) {
            store.sendAgent(prompt, emptyList())
        } else {
            store.send("bot:${t.ownerBotId}", prompt, emptyList())
        }
        return true
    }

    fun buildResumePrompt(t: Ticket, isResume: Boolean): String = buildString {
        append(if (isResume) "【Super-Agent 工单断点续跑 · ${t.id}】\n" else "【Super-Agent 工单派发 · ${t.id}】\n")
        val prioLabel = when (t.priority) { 1 -> "高"; 3 -> "低"; else -> "中" }
        append("标题：${t.title}（优先级：$prioLabel）\n")
        append("目标：${t.goal}\n")
        if (t.checkpoints.isNotEmpty()) {
            append("\n已完成检查点（请勿重复，直接接着往下推进）：\n")
            t.checkpoints.forEachIndexed { i, cp -> append("${i + 1}. $cp\n") }
        }
        if (t.spillIds.isNotEmpty()) {
            append("\n关联上下文 Spill ID（按需用 recall_spill 查阅）：${t.spillIds.joinToString(", ")}\n")
        }
        append("\n执行要求：推进关键步骤时请调用 ticket_update(id=\"${t.id}\", checkpoint=\"...\") 记录进度；完成后将 status 设为 done 并向用户简洁汇报结果。")
    }

    fun logWatchdog(ticketId: String, level: String, message: String) {
        onStateThread {
            watchdogLog.add(0, WatchdogEvent(System.currentTimeMillis(), ticketId, level, message))
            while (watchdogLog.size > 60) watchdogLog.removeAt(watchdogLog.lastIndex)
        }
    }

    fun ticketToJson(t: Ticket): JSONObject = JSONObject()
        .put("id", t.id)
        .put("title", t.title)
        .put("goal", t.goal)
        .put("ownerBotId", t.ownerBotId)
        .put("status", t.status.name)
        .put("priority", t.priority)
        .put("source", t.source)
        .put("sourceKey", t.sourceKey)
        .put("threadChatId", t.threadChatId)
        .put("checkpoints", JSONArray(t.checkpoints))
        .put("spillIds", JSONArray(t.spillIds))
        .put("createdMs", t.createdMs)
        .put("updatedMs", t.updatedMs)
        .put("lastHeartbeatMs", t.lastHeartbeatMs)
        .put("retryCount", t.retryCount)
        .put("lastError", t.lastError)

    fun ticketFromJson(o: JSONObject): Ticket {
        val cps = mutableListOf<String>()
        o.optJSONArray("checkpoints")?.let { a -> for (i in 0 until a.length()) cps.add(a.optString(i)) }
        val spills = mutableListOf<String>()
        o.optJSONArray("spillIds")?.let { a -> for (i in 0 until a.length()) spills.add(a.optString(i)) }
        val status = runCatching { TicketStatus.valueOf(o.optString("status", "QUEUED")) }.getOrDefault(TicketStatus.QUEUED)
        return Ticket(
            id = o.optString("id").ifBlank { "tk_" + UUID.randomUUID().toString().take(6) },
            title = o.optString("title"),
            goal = o.optString("goal"),
            ownerBotId = o.optString("ownerBotId", AGENT_ID),
            status = status,
            priority = o.optInt("priority", 2),
            source = o.optString("source", "user"),
            sourceKey = o.optString("sourceKey", ""),
            threadChatId = o.optString("threadChatId", ""),
            checkpoints = cps,
            spillIds = spills,
            createdMs = o.optLong("createdMs", 0L),
            updatedMs = o.optLong("updatedMs", 0L),
            lastHeartbeatMs = o.optLong("lastHeartbeatMs", 0L),
            retryCount = o.optInt("retryCount", 0),
            lastError = o.optString("lastError", "")
        )
    }
}

/**
 * Fast Path / Slow Path Event Router (0-Token deterministic pre-filter + Direct-to-Worker subscription).
 */
object SuperRouter {
    private val NOISE_PATTERNS = listOf("正在运行", "后台保活", "已连接", "充电中", "USB 调试", "正在下载", "蓝牙已连接")
    private const val DIRECT_COOLDOWN_MS = 30_000L
    private val lastDirectMs = ConcurrentHashMap<String, Long>()

    fun resetCooldowns() {
        lastDirectMs.clear()
    }

    /**
     * Evaluates an incoming event deterministically at 0 Token cost:
     *  1. Drops known low-value system noise (Fast Path ignore).
     *  2. If an active Ticket subscribes to `sourceKey` (exact match, package match, or keyword contained in payload),
     *     routes directly to that Worker (bypassing Router) with per-ticket cooldown dedup.
     *  3. Otherwise escalates to Slow Path Router (Chief-of-Staff or fallback Worker).
     */
    fun routeEvent(
        sourceKey: String,
        payload: String,
        chiefBotId: String,
        fallbackBotId: String,
        nowMs: Long = System.currentTimeMillis()
    ): RouteDecision {
        val cleanKey = sourceKey.trim()
        val text = payload.trim()
        if (text.length < 3 || NOISE_PATTERNS.any { text.contains(it, ignoreCase = true) }) {
            SuperAgent.fastPathSavedCount.intValue++
            return RouteDecision(RouteKind.IGNORE_NOISE, reason = "Fast Path 已过滤低价值噪音事件（0 Token）")
        }

        val activeOwner = SuperAgent.tickets.firstOrNull { t ->
            val active = t.status == TicketStatus.RUNNING || t.status == TicketStatus.QUEUED || t.status == TicketStatus.BLOCKED
            val key = t.sourceKey.trim()
            active && key.isNotEmpty() && (
                key.equals(cleanKey, ignoreCase = true) ||
                    cleanKey.contains(key, ignoreCase = true) ||
                    text.contains(key, ignoreCase = true)
                )
        }
        if (activeOwner != null) {
            val prev = lastDirectMs[activeOwner.id] ?: 0L
            if (nowMs - prev < DIRECT_COOLDOWN_MS) {
                SuperAgent.fastPathSavedCount.intValue++
                return RouteDecision(RouteKind.IGNORE_NOISE, ticketId = activeOwner.id, reason = "Fast Path 30s 冷却防抖（0 Token）")
            }
            lastDirectMs[activeOwner.id] = nowMs
            SuperAgent.directRoutedCount.intValue++
            return RouteDecision(
                kind = RouteKind.DIRECT_WORKER,
                ticketId = activeOwner.id,
                targetBotId = activeOwner.ownerBotId,
                reason = "Fast Path 直通工单 ${activeOwner.id} 责任 Worker（绕过 Router）"
            )
        }

        val routerTarget = chiefBotId.ifBlank { fallbackBotId }
        return RouteDecision(
            kind = RouteKind.ESCALATE_ROUTER,
            targetBotId = routerTarget,
            reason = "Slow Path 升级至总参谋 Router 评估立单"
        )
    }

    /**
     * Live Android notification bus hook (invoked on main thread from OgNotificationListener).
     * Only dispatches to a Worker when an active Ticket explicitly subscribes via `sourceKey`,
     * preventing unprompted token burn on everyday notifications.
     */
    fun onIncomingNotification(store: Store, pkg: String, appLabel: String, title: String, text: String) {
        if (SuperAgent.tickets.none { it.sourceKey.isNotBlank() && it.status != TicketStatus.DONE && it.status != TicketStatus.FAILED }) {
            return
        }
        val combined = "$appLabel $title $text".trim()
        val decision = routeEvent(
            sourceKey = "$pkg $appLabel",
            payload = combined,
            chiefBotId = store.chiefId.value,
            fallbackBotId = AGENT_ID
        )
        if (decision.kind == RouteKind.DIRECT_WORKER && decision.ticketId != null && decision.targetBotId != null) {
            val tid = decision.ticketId
            val target = decision.targetBotId
            val chatId = if (target == AGENT_ID) AGENT_ID else "bot:$target"
            SuperAgent.updateTicket(tid, status = TicketStatus.RUNNING, checkpoint = "收到订阅通知($appLabel)：${title.take(20)}")
            SuperAgent.logWatchdog(tid, "INFO", "Fast Path 捕获通知($appLabel) → 直通 $target")
            if (chatId !in store.busy) {
                val msg = "【Fast Path 订阅事件 · 工单 $tid】\n来源：$appLabel ($pkg)\n标题：$title\n内容：$text\n请结合工单 $tid 进度处理，并在必要时调用 ticket_update 记录结果。"
                if (target == AGENT_ID) store.sendAgent(msg, emptyList()) else store.send(chatId, msg, emptyList())
            }
        }
        SuperAgent.save()
    }
}

/**
 * External Watchdog monitor: periodically scans active Tickets for stalls or silent drops.
 */
object HiveWatchdog {
    private const val CHECK_INTERVAL_MS = 25_000L
    const val DEFAULT_STALL_MS = 4 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    fun start(store: Store) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                val busySnapshot = store.busy.toSet()
                SuperAgent.onStateThread {
                    scanOnce(busySnapshot, System.currentTimeMillis(), DEFAULT_STALL_MS)
                }
            }
        }
    }

    /** Pure, deterministic watchdog sweep (directly unit-testable). */
    fun scanOnce(busyChats: Set<String>, nowMs: Long, stallTimeoutMs: Long = DEFAULT_STALL_MS): Int {
        SuperAgent.lastWatchdogBeatMs.longValue = nowMs
        var flagged = 0
        for (i in SuperAgent.tickets.indices) {
            val t = SuperAgent.tickets[i]
            if (t.status != TicketStatus.RUNNING) continue
            val chatStillBusy = t.threadChatId in busyChats
            val elapsed = nowMs - t.lastHeartbeatMs
            if (!chatStillBusy && elapsed > 30_000L) {
                SuperAgent.tickets[i] = t.copy(
                    status = TicketStatus.PAUSED,
                    updatedMs = nowMs,
                    lastError = "Worker 会话已结束但工单未结单，已转入可续跑"
                )
                SuperAgent.logWatchdog(t.id, "WARN", "Watchdog 发现工单「${t.title}」会话已停，已置为可续跑")
                flagged++
            } else if (elapsed > stallTimeoutMs) {
                SuperAgent.tickets[i] = t.copy(
                    status = TicketStatus.PAUSED,
                    updatedMs = nowMs,
                    lastError = "超过 ${stallTimeoutMs / 60_000L} 分钟无心跳进展，Watchdog 自动挂起待续跑"
                )
                SuperAgent.logWatchdog(t.id, "WARN", "Watchdog 检测到工单「${t.title}」心跳超时，已挂起防空转")
                flagged++
            }
        }
        if (flagged > 0) SuperAgent.save()
        return flagged
    }
}
