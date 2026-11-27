package app.opengrok

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class MemoryCategory(val label: String) {
    FACT("事实"),
    PREF("偏好"),
    GOAL("目标"),
    MOOD("临时情绪")
}

enum class MemoryConfidence(val label: String) {
    STATED("用户明说"),
    INFERRED("模型推测")
}

data class MemoryRecord(
    val id: String,
    val category: MemoryCategory,
    val content: String,
    val source: String,
    val confidence: MemoryConfidence,
    val allowedBots: List<String> = emptyList(), // empty = all Bots allowed
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * v0.7.2 Portable Personal Memory Pod (inspired by Xiaodou memory provenance + Solid Portable Pod).
 *
 * Every memory carries category, provenance source, timestamp, stated-vs-inferred confidence, and
 * per-Bot access scope. Supports 1-tap portable export & import from other AI assistants.
 */
object MemoryPod {
    private val mainHandler = Handler(Looper.getMainLooper())
    val records = mutableStateListOf<MemoryRecord>()
    private var file: File? = null

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "memory_pod.json")
        load()
    }

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        runCatching {
            val arr = JSONArray(f.readText())
            val loaded = mutableListOf<MemoryRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val bots = mutableListOf<String>()
                val bArr = o.optJSONArray("allowedBots")
                if (bArr != null) {
                    for (j in 0 until bArr.length()) {
                        val id = bArr.optString(j).trim()
                        if (id.isNotBlank()) bots.add(id)
                    }
                }
                loaded.add(
                    MemoryRecord(
                        id = o.optString("id", "mp_" + UUID.randomUUID().toString().take(8)),
                        category = MemoryCategory.entries.firstOrNull { it.name == o.optString("category") } ?: MemoryCategory.FACT,
                        content = o.optString("content", ""),
                        source = o.optString("source", "对话记录"),
                        confidence = MemoryConfidence.entries.firstOrNull { it.name == o.optString("confidence") } ?: MemoryConfidence.STATED,
                        allowedBots = bots,
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
            onMain {
                records.clear()
                records.addAll(loaded)
            }
        }
    }

    private fun persist(snapshot: List<MemoryRecord>) {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            for (r in snapshot) {
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("category", r.category.name)
                        .put("content", r.content)
                        .put("source", r.source)
                        .put("confidence", r.confidence.name)
                        .put("allowedBots", JSONArray(r.allowedBots))
                        .put("createdAt", r.createdAt)
                        .put("updatedAt", r.updatedAt)
                )
            }
            f.writeText(arr.toString(2))
        }
    }

    fun add(
        content: String,
        category: MemoryCategory = MemoryCategory.FACT,
        source: String = "手动记录",
        confidence: MemoryConfidence = MemoryConfidence.STATED,
        allowedBots: List<String> = emptyList()
    ): MemoryRecord {
        val now = System.currentTimeMillis()
        val rec = MemoryRecord(
            id = "mp_" + UUID.randomUUID().toString().take(8),
            category = category,
            content = content.trim(),
            source = source.trim().ifBlank { "对话" },
            confidence = confidence,
            allowedBots = allowedBots.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            createdAt = now,
            updatedAt = now
        )
        val snap = listOf(rec) + records.toList()
        onMain { records.add(0, rec) }
        persist(snap)
        return rec
    }

    fun delete(id: String) {
        val snap = records.filterNot { it.id == id }
        onMain { records.removeAll { it.id == id } }
        persist(snap)
    }

    fun toggleConfidence(id: String) {
        val now = System.currentTimeMillis()
        val snap = records.map { r ->
            if (r.id == id) r.copy(
                confidence = if (r.confidence == MemoryConfidence.STATED) MemoryConfidence.INFERRED else MemoryConfidence.STATED,
                updatedAt = now
            ) else r
        }
        onMain {
            val idx = records.indexOfFirst { it.id == id }
            if (idx >= 0) records[idx] = snap[idx]
        }
        persist(snap)
    }

    /** Returns only memories authorized for [botId] (MOOD entries excluded unless explicitly queried). */
    fun queryForBot(botId: String, query: String = "", includeMood: Boolean = false): List<MemoryRecord> {
        val q = query.trim().lowercase()
        return records.toList().filter { r ->
            (r.allowedBots.isEmpty() || botId in r.allowedBots) &&
                (includeMood || r.category != MemoryCategory.MOOD) &&
                (q.isBlank() || r.content.lowercase().contains(q) || r.source.lowercase().contains(q) || r.category.label.contains(q))
        }
    }

    /** Exports the entire Memory Pod into a portable JSON bundle for backup or cross-app migration. */
    fun exportPortableJson(): String {
        val root = JSONObject()
            .put("schema", "bothive-memory-pod/v1")
            .put("exported_at", System.currentTimeMillis())
        val arr = JSONArray()
        for (r in records.toList()) {
            arr.put(
                JSONObject()
                    .put("category", r.category.name)
                    .put("content", r.content)
                    .put("source", r.source)
                    .put("confidence", r.confidence.name)
                    .put("allowedBots", JSONArray(r.allowedBots))
            )
        }
        root.put("records", arr)
        return root.toString(2)
    }

    /**
     * Imports memories from either a BotHive Memory Pod JSON bundle or plain-text bio/bullet lines
     * exported from ChatGPT / Gemini / Xiaodou / Cue. Returns number of imported entries.
     */
    fun importFromText(raw: String, sourceLabel: String = "记忆搬家导入"): Int {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return 0
        // Try structured JSON first
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsedCount = runCatching {
                val arr = if (trimmed.startsWith("[")) JSONArray(trimmed)
                else JSONObject(trimmed).optJSONArray("records") ?: JSONArray()
                var added = 0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val content = o.optString("content").trim()
                    if (content.isBlank()) continue
                    val cat = MemoryCategory.entries.firstOrNull { it.name == o.optString("category") } ?: MemoryCategory.FACT
                    val conf = MemoryConfidence.entries.firstOrNull { it.name == o.optString("confidence") } ?: MemoryConfidence.STATED
                    add(content, cat, o.optString("source", sourceLabel), conf)
                    added++
                }
                added
            }.getOrDefault(0)
            if (parsedCount > 0) return parsedCount
        }
        // Fallback: split plain-text / Markdown bullet points
        var count = 0
        for (line in trimmed.lines()) {
            val clean = line.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim()
            if (clean.length < 3 || clean.startsWith("#")) continue
            val cat = when {
                clean.contains("喜欢") || clean.contains("偏好") || clean.contains("不吃") || clean.contains("习惯") -> MemoryCategory.PREF
                clean.contains("目标") || clean.contains("计划") || clean.contains("打算") || clean.contains("准备") -> MemoryCategory.GOAL
                else -> MemoryCategory.FACT
            }
            add(clean, cat, sourceLabel, MemoryConfidence.STATED)
            count++
            if (count >= 40) break
        }
        return count
    }
}
