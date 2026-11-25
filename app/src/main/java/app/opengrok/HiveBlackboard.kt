package app.opengrok

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.7.1 Hive Blackboard — Cross-Bot Shared Semantic Memory Wall.
 *
 * Lets any Bot or the Device Agent post structured key-value facts (`board_write`) that other
 * Bots can read immediately (`board_read`) without passing bloated chat transcripts around.
 * Persisted independently in `blackboard.json`.
 */
data class BoardNote(
    val key: String,
    val value: String,
    val tag: String = "通用",
    val authorBotId: String = "user",
    val updatedMs: Long = System.currentTimeMillis()
)

object HiveBlackboard {
    val notes = mutableStateListOf<BoardNote>()

    @Volatile
    private var storageFile: File? = null
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        initFile(File(context.applicationContext.filesDir, "blackboard.json"))
    }

    fun initFile(file: File) {
        storageFile = file
        notes.clear()
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("key").trim()
                if (k.isNotEmpty()) {
                    notes.add(
                        BoardNote(
                            key = k,
                            value = o.optString("value"),
                            tag = o.optString("tag", "通用").ifBlank { "通用" },
                            authorBotId = o.optString("author", "user"),
                            updatedMs = o.optLong("updatedMs", 0L)
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    fun save() {
        val f = storageFile ?: return
        val snap = notes.toList()
        runCatching {
            val arr = JSONArray()
            snap.forEach { n ->
                arr.put(
                    JSONObject()
                        .put("key", n.key)
                        .put("value", n.value)
                        .put("tag", n.tag)
                        .put("author", n.authorBotId)
                        .put("updatedMs", n.updatedMs)
                )
            }
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        }
    }

    fun write(key: String, value: String, tag: String = "通用", authorBotId: String = "user"): BoardNote {
        val cleanKey = key.trim().take(48)
        val cleanTag = tag.trim().take(16).ifBlank { "通用" }
        val note = BoardNote(cleanKey, value.trim().take(2000), cleanTag, authorBotId, System.currentTimeMillis())
        SuperAgent.onStateThread {
            val idx = notes.indexOfFirst { it.key.equals(cleanKey, ignoreCase = true) }
            if (idx >= 0) notes[idx] = note else notes.add(0, note)
            while (notes.size > 80) notes.removeAt(notes.lastIndex)
            save()
        }
        return note
    }

    fun read(key: String = "", tag: String = ""): List<BoardNote> {
        val k = key.trim()
        val t = tag.trim()
        return notes.filter { n ->
            (k.isEmpty() || n.key.contains(k, ignoreCase = true) || n.value.contains(k, ignoreCase = true)) &&
                (t.isEmpty() || n.tag.equals(t, ignoreCase = true))
        }
    }

    fun remove(key: String) {
        SuperAgent.onStateThread {
            notes.removeAll { it.key.equals(key, ignoreCase = true) }
            save()
        }
    }
}
