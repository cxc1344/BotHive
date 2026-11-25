package app.opengrok

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * v0.7.1 Inline & Spill + Chain of Recall context engine (inspired by DeepSeek Super-agent).
 *
 * When a tool output exceeds [INLINE_LIMIT] characters, the full payload is spilled to disk
 * under `files/spills/<id>.txt`, and the model receives a compact inline preview (head + tail
 * + spill ID + line count). The model can later inspect any slice or grep keywords via `recall_spill`
 * without bloating its context window.
 */
object ContextSpill {
    const val INLINE_LIMIT = 2400
    private const val HEAD_CHARS = 900
    private const val TAIL_CHARS = 500
    private const val MAX_SPILL_FILES = 120

    @Volatile
    private var rootDir: File? = null

    fun init(context: Context) {
        initDir(File(context.applicationContext.filesDir, "spills"))
    }

    /** Test-friendly directory initializer (no Android Context required in unit tests). */
    fun initDir(dir: File) {
        if (!dir.exists()) dir.mkdirs()
        rootDir = dir
    }

    data class SpillMeta(
        val id: String,
        val toolName: String,
        val totalChars: Int,
        val totalLines: Int,
        val createdMs: Long
    )

    /**
     * Returns [raw] untouched if short; otherwise spills full text to disk and returns an inline
     * preview carrying the spill ID for on-demand Chain of Recall.
     */
    fun inlineOrSpill(toolName: String, raw: String): Pair<String, String?> {
        if (raw.length <= INLINE_LIMIT) return raw to null
        val dir = rootDir ?: return raw to null
        if (!dir.exists()) dir.mkdirs()

        val id = "sp_" + UUID.randomUUID().toString().replace("-", "").take(8)
        val lines = raw.lines()
        val f = File(dir, "$id.txt")
        val header = "# spill=$id tool=$toolName lines=${lines.size} chars=${raw.length} time=${System.currentTimeMillis()}\n"
        runCatching { f.writeText(header + raw) }.onFailure { return raw to null }
        pruneOldSpills(dir)

        val head = raw.take(HEAD_CHARS).trimEnd()
        val tail = raw.takeLast(TAIL_CHARS).trimStart()
        val preview = buildString {
            append(head)
            append("\n\n... [Inline & Spill 已自动折叠中间 ${raw.length - HEAD_CHARS - TAIL_CHARS} 字符 · 共 ${lines.size} 行] ...\n\n")
            append(tail)
            append("\n\n[Spill ID: $id · 工具: $toolName · 如需查看完整片段或搜索关键词，请调用 recall_spill(id=\"$id\", query=\"关键词\") 或 recall_spill(id=\"$id\", start_line=1, max_lines=80)]")
        }
        return preview to id
    }

    /**
     * Chain of Recall: reads a spilled output by [id], either filtering lines matching [query]
     * (case-insensitive with 1 line of context) or returning a paginated line slice.
     */
    fun recall(id: String, query: String = "", startLine: Int = 1, maxLines: Int = 80): String {
        val cleanId = id.trim()
        if (!cleanId.matches(Regex("sp_[a-fA-F0-9]{6,16}"))) return "无效的 Spill ID：$id"
        val dir = rootDir ?: return "Spill 存储尚未初始化。"
        val f = File(dir, "$cleanId.txt")
        if (!f.exists()) return "未找到 Spill 记录 $cleanId（可能已被清理）。"

        val allLines = runCatching { f.readLines() }.getOrElse { return "读取 Spill 失败：${it.message}" }
        val body = if (allLines.firstOrNull()?.startsWith("# spill=") == true) allLines.drop(1) else allLines
        if (body.isEmpty()) return "Spill $cleanId 内容为空。"

        val limit = maxLines.coerceIn(10, 200)
        val q = query.trim()
        if (q.isNotEmpty()) {
            val hits = mutableListOf<String>()
            for (i in body.indices) {
                if (body[i].contains(q, ignoreCase = true)) {
                    hits.add("L${i + 1}: ${body[i]}")
                    if (hits.size >= limit) break
                }
            }
            return if (hits.isEmpty()) {
                "Spill $cleanId（共 ${body.size} 行）中未找到包含「$q」的行。"
            } else {
                "Spill $cleanId 关键词「$q」命中 ${hits.size} 行（共 ${body.size} 行）：\n" + hits.joinToString("\n")
            }
        }

        val start = (startLine - 1).coerceIn(0, (body.size - 1).coerceAtLeast(0))
        val end = (start + limit).coerceAtMost(body.size)
        val slice = (start until end).joinToString("\n") { i -> "L${i + 1}: ${body[i]}" }
        return "Spill $cleanId 第 ${start + 1}–$end 行（共 ${body.size} 行）：\n$slice"
    }

    /** Lists recent spilled payloads for the Super-Agent inspector UI. */
    fun listRecent(limit: Int = 20): List<SpillMeta> {
        val dir = rootDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { _, name -> name.startsWith("sp_") && name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.mapNotNull { f ->
                val first = runCatching { f.bufferedReader().use { it.readLine().orEmpty() } }.getOrNull().orEmpty()
                val id = f.name.removeSuffix(".txt")
                val tool = Regex("tool=([^\\s]+)").find(first)?.groupValues?.get(1) ?: "tool"
                val lines = Regex("lines=(\\d+)").find(first)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val chars = Regex("chars=(\\d+)").find(first)?.groupValues?.get(1)?.toIntOrNull() ?: f.length().toInt()
                val time = Regex("time=(\\d+)").find(first)?.groupValues?.get(1)?.toLongOrNull() ?: f.lastModified()
                SpillMeta(id, tool, chars, lines, time)
            }
            .orEmpty()
    }

    private fun pruneOldSpills(dir: File) {
        val files = dir.listFiles { _, name -> name.startsWith("sp_") && name.endsWith(".txt") } ?: return
        if (files.size <= MAX_SPILL_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_SPILL_FILES)
            .forEach { runCatching { it.delete() } }
    }
}
