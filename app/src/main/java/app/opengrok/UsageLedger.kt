package app.opengrok

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject

/** One day of one Bot's model usage (v0.7.0). [day] is "yyyy-MM-dd" in local time. */
data class UsageRow(
    val day: String,
    val botId: String,
    val calls: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalMs: Long
)

/**
 * v0.7.0 usage ledger (用量与预算): per-day, per-Bot token counts parsed from the `usage` field of
 * every chat/completions response, optional daily token budgets and optional model prices
 * (USD per 1M tokens, entered by the user, used only for an estimate).
 *
 * Thread-safe: [record] is called from OkHttp callback threads.
 */
object UsageLedger {
    private const val PREFS = "usage_ledger"
    private const val KEEP_DAYS = 62

    @Volatile
    private var appCtx: Context? = null
    private val lock = Any()
    private val rows = HashMap<String, UsageRow>()
    private var loaded = false

    val revision: MutableIntState = mutableIntStateOf(0)

    fun init(context: Context) {
        appCtx = context.applicationContext
        synchronized(lock) { ensureLoaded() }
    }

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayOf(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))

    private fun today(): String = dayOf(System.currentTimeMillis())

    private fun ensureLoaded() {
        if (loaded) return
        val p = prefs() ?: return
        loaded = true
        val raw = p.getString("rows", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val r = UsageRow(
                    o.optString("d"), o.optString("b"), o.optInt("c"),
                    o.optLong("p"), o.optLong("o"), o.optLong("ms")
                )
                if (r.day.isNotEmpty() && r.botId.isNotEmpty()) rows[r.day + "|" + r.botId] = r
            }
        } catch (_: Exception) {
            // A damaged ledger only loses usage statistics; start fresh.
        }
    }

    private fun persistLocked() {
        val p = prefs() ?: return
        val arr = JSONArray()
        for (r in rows.values) {
            arr.put(
                JSONObject().put("d", r.day).put("b", r.botId).put("c", r.calls)
                    .put("p", r.promptTokens).put("o", r.completionTokens).put("ms", r.totalMs)
            )
        }
        p.edit().putString("rows", arr.toString()).apply()
    }

    private fun cutoff(days: Int): String =
        dayOf(System.currentTimeMillis() - (days.coerceAtLeast(1) - 1).toLong() * 86_400_000L)

    /** Records one model call. [model] is kept for future per-model accounting (prices are looked up by the caller). */
    fun record(botId: String, model: String, promptTokens: Long, completionTokens: Long, latencyMs: Long) {
        if (botId.isBlank()) return
        synchronized(lock) {
            ensureLoaded()
            val day = today()
            val key = "$day|$botId"
            val old = rows[key] ?: UsageRow(day, botId, 0, 0, 0, 0)
            rows[key] = old.copy(
                calls = old.calls + 1,
                promptTokens = old.promptTokens + promptTokens.coerceAtLeast(0),
                completionTokens = old.completionTokens + completionTokens.coerceAtLeast(0),
                totalMs = old.totalMs + latencyMs.coerceAtLeast(0)
            )
            val keepFrom = cutoff(KEEP_DAYS)
            rows.entries.removeAll { it.value.day < keepFrom }
            persistLocked()
        }
        revision.intValue = revision.intValue + 1
    }

    /** Rows of the last [days] days (today included), newest day first. */
    fun rows(days: Int = 30): List<UsageRow> = synchronized(lock) {
        ensureLoaded()
        val from = cutoff(days)
        rows.values.filter { it.day >= from }.sortedWith(compareByDescending<UsageRow> { it.day }.thenBy { it.botId })
    }

    fun todayTokens(botId: String): Long = synchronized(lock) {
        ensureLoaded()
        val r = rows[today() + "|" + botId] ?: return@synchronized 0L
        r.promptTokens + r.completionTokens
    }

    /** Daily token budget for [botId]; 0 = none. */
    fun budget(botId: String): Long = prefs()?.getLong("budget_$botId", 0L) ?: 0L

    fun setBudget(context: Context, botId: String, tokens: Long) {
        appCtx = context.applicationContext
        val e = prefs()?.edit() ?: return
        if (tokens <= 0) e.remove("budget_$botId") else e.putLong("budget_$botId", tokens)
        e.apply()
        revision.intValue = revision.intValue + 1
    }

    /** True when [botId] has a budget and today's usage reached it. */
    fun overBudget(botId: String): Boolean {
        val b = budget(botId)
        return b > 0 && todayTokens(botId) >= b
    }

    /** USD per 1M (input, output) tokens for [model], or null when the user has not entered a price. */
    fun price(model: String): Pair<Double, Double>? {
        val raw = prefs()?.getString("price_" + model.trim(), null) ?: return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val i = parts[0].toDoubleOrNull() ?: return null
        val o = parts[1].toDoubleOrNull() ?: return null
        return i to o
    }

    fun setPrice(context: Context, model: String, inPerM: Double, outPerM: Double) {
        appCtx = context.applicationContext
        val m = model.trim()
        if (m.isEmpty()) return
        val e = prefs()?.edit() ?: return
        if (inPerM <= 0.0 && outPerM <= 0.0) e.remove("price_$m")
        else e.putString("price_$m", "${inPerM.coerceAtLeast(0.0)},${outPerM.coerceAtLeast(0.0)}")
        e.apply()
        revision.intValue = revision.intValue + 1
    }

    fun estimateUsd(row: UsageRow, model: String): Double? {
        val p = price(model) ?: return null
        return row.promptTokens / 1_000_000.0 * p.first + row.completionTokens / 1_000_000.0 * p.second
    }
}
