package app.opengrok

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPInputStream

internal class TarException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * In-process tar(.gz) extractor and symlink-safe helpers, so rootfs installation does not depend on the
 * device's toybox/busybox `tar` and every failure can be reported with the entry that caused it.
 * Handles ustar, GNU long names (L/K) and pax headers; skips device nodes and FIFOs.
 */
internal object TarExtract {
    data class Result(
        val files: Int, val dirs: Int, val symlinks: Int, val hardlinks: Int,
        val bytes: Long, val skipped: List<String>
    ) {
        fun summary(): String =
            "文件 $files · 目录 $dirs · 符号链接 $symlinks · 硬链接 $hardlinks · ${bytes / 1024}KB" +
                if (skipped.isEmpty()) "" else " · 跳过 ${skipped.size} 项(${skipped.take(3).joinToString("; ")})"
    }

    private const val BLOCK = 512
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_META_BYTES = 1L * 1024 * 1024

    fun extractTarGz(archive: File, dest: File): Result =
        GZIPInputStream(BufferedInputStream(FileInputStream(archive), 64 * 1024)).use { extract(it, dest) }

    fun extract(input: InputStream, dest: File): Result {
        dest.mkdirs()
        val root = dest.canonicalFile
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var files = 0
        var dirs = 0
        var syms = 0
        var hards = 0
        var total = 0L
        val skipped = mutableListOf<String>()
        var index = 0

        while (true) {
            val got = readBlock(input, header)
            if (got == 0) break
            if (got < BLOCK) throw TarException("压缩包在第 ${index + 1} 个条目的头部被截断(读到 $got/$BLOCK 字节)")
            if (isZero(header)) break
            index++
            val type = (header[156].toInt() and 0xFF).toChar()
            val size = parseNumber(header, 124, 12)
            val rawName = cString(header, 0, 100)
            val prefix = if (isPosixUstar(header)) cString(header, 345, 155) else ""
            val rawLink = cString(header, 157, 100)
            val mode = parseNumber(header, 100, 8).toInt()

            // Metadata entries that modify the next real entry.
            if (type == 'L') { longName = readText(input, size); continue }
            if (type == 'K') { longLink = readText(input, size); continue }
            if (type == 'x') {
                val m = parsePax(readText(input, size))
                paxPath = m["path"] ?: paxPath
                paxLink = m["linkpath"] ?: paxLink
                continue
            }
            if (type == 'g') { skipData(input, size); continue }

            val name0 = paxPath ?: longName ?: (if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName)
            val link0 = paxLink ?: longLink ?: rawLink
            paxPath = null; paxLink = null; longName = null; longLink = null

            try {
                val rel = cleanName(name0)
                when (type) {
                    '5' -> {
                        if (rel != null) {
                            val t = prepare(root, rel)
                            if (t == null) {
                                skipped.add("$rel(路径越界)")
                            } else {
                                if (!t.isDirectory && !t.mkdirs()) throw TarException("无法创建目录 $rel")
                                t.setReadable(true, false); t.setWritable(true, true); t.setExecutable(true, false)
                                dirs++
                            }
                        }
                    }
                    '0', '\u0000', '7' -> {
                        if (rel == null) throw TarException("文件条目名为空")
                        total += size
                        if (total > MAX_TOTAL_BYTES) throw TarException("解压总量超过上限 ${MAX_TOTAL_BYTES / 1024 / 1024}MB")
                        val t = prepare(root, rel)
                        if (t == null) {
                            skipped.add("$rel(路径越界)")
                            skipData(input, size)
                        } else {
                            deleteQuiet(t)
                            t.outputStream().buffered().use { out -> copyExactly(input, out, size) }
                            skipPadding(input, size)
                            applyMode(t, mode)
                            files++
                        }
                    }
                    '2' -> {
                        if (rel == null) throw TarException("符号链接条目名为空")
                        if (link0.isEmpty()) throw TarException("符号链接 $rel 没有目标")
                        val t = prepare(root, rel)
                        if (t == null) {
                            skipped.add("$rel(路径越界)")
                        } else {
                            deleteQuiet(t)
                            Files.createSymbolicLink(t.toPath(), Paths.get(link0))
                            syms++
                        }
                    }
                    '1' -> {
                        if (rel == null) throw TarException("硬链接条目名为空")
                        val srcRel = cleanName(link0) ?: throw TarException("硬链接 $rel 没有目标")
                        val t = prepare(root, rel)
                        val src = File(root, srcRel)
                        if (t == null || !src.isFile || !isInside(root, src.canonicalFile)) {
                            skipped.add("$rel(硬链接源 $srcRel 不可用)")
                        } else {
                            deleteQuiet(t)
                            Files.copy(src.toPath(), t.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            applyMode(t, mode)
                            hards++
                        }
                    }
                    '3', '4', '6' -> skipped.add("$name0(设备/管道节点)")
                    else -> {
                        skipData(input, size)
                        skipped.add("$name0(类型 ${printable(type)})")
                    }
                }
            } catch (e: TarException) {
                throw TarException("第 $index 个条目 '$name0'(类型 '${printable(type)}'): ${e.message}", e)
            } catch (e: Exception) {
                throw TarException("第 $index 个条目 '$name0'(类型 '${printable(type)}')失败: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
        if (index == 0) throw TarException("压缩包里没有任何条目(空文件或不是 tar)")
        return Result(files, dirs, syms, hards, total, skipped)
    }

    /**
     * Resolves [rel] as the guest would see it: absolute symlink targets are taken relative to [root], not the host.
     * Returns the final path, or null if a component is missing, the chain loops, or it escapes nothing it can reach.
     */
    fun resolveInGuest(root: File, rel: String): File? {
        val rootPath = root.canonicalFile.toPath()
        val parts = ArrayDeque(rel.split('/').filter { it.isNotEmpty() })
        var cur: Path = rootPath
        var hops = 0
        while (parts.isNotEmpty()) {
            val p = parts.removeFirst()
            if (p == ".") continue
            if (p == "..") { if (cur != rootPath) cur = cur.parent ?: rootPath; continue }
            val next = cur.resolve(p)
            if (Files.isSymbolicLink(next)) {
                if (++hops > 16) return null
                val target = Files.readSymbolicLink(next).toString()
                val tparts = target.split('/').filter { it.isNotEmpty() }
                if (target.startsWith("/")) cur = rootPath
                for (i in tparts.indices.reversed()) parts.addFirst(tparts[i])
            } else if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                cur = next
            } else {
                return null
            }
        }
        return cur.toFile()
    }

    /** Deletes a tree WITHOUT following symlinks (guest-created links must never redirect host deletions). */
    fun deleteTree(root: File) {
        val p = root.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val f = dir.toFile()
                f.setReadable(true, true); f.setWritable(true, true); f.setExecutable(true, true)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try { Files.deleteIfExists(file) } catch (_: IOException) {}
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                try { Files.deleteIfExists(dir) } catch (_: IOException) {}
                return FileVisitResult.CONTINUE
            }
        })
    }

    // ---- helpers ----

    private fun cleanName(name: String): String? {
        var n = name
        while (n.startsWith("./")) n = n.substring(2)
        n = n.trimEnd('/')
        if (n.isEmpty() || n == ".") return null
        if (n.startsWith("/")) throw TarException("条目使用绝对路径: $name")
        if (n.split('/').any { it == ".." }) throw TarException("条目含 '..' 路径: $name")
        return n
    }

    private fun prepare(root: File, rel: String): File? {
        val target = File(root, rel)
        val parent = target.parentFile ?: return null
        if (!parent.isDirectory && !parent.mkdirs()) return null
        return if (isInside(root, parent.canonicalFile)) target else null
    }

    private fun isInside(root: File, f: File) = f == root || f.path.startsWith(root.path + File.separator)

    private fun deleteQuiet(f: File) {
        try { Files.deleteIfExists(f.toPath()) } catch (_: Exception) {}
    }

    private fun applyMode(f: File, mode: Int) {
        f.setReadable(true, false)
        f.setWritable(true, true)
        if ((mode and 0x49) != 0) f.setExecutable(true, false) // any of the three x bits (octal 0111)
    }

    private fun printable(c: Char) = if (c == '\u0000') "0" else if (c.code in 32..126) c.toString() else "0x%02x".format(c.code)

    private fun isZero(b: ByteArray): Boolean {
        for (x in b) if (x.toInt() != 0) return false
        return true
    }

    private fun isPosixUstar(h: ByteArray): Boolean =
        String(h, 257, 5, Charsets.US_ASCII) == "ustar" && h[262].toInt() == 0

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseNumber(b: ByteArray, off: Int, len: Int): Long {
        if ((b[off].toInt() and 0x80) != 0) { // GNU base-256 extension
            var v = (b[off].toInt() and 0x7F).toLong()
            for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        var seen = false
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c == 0 || c == ' '.code) { if (seen) break else continue }
            if (c < '0'.code || c > '7'.code) throw TarException("tar 头部数字字段无效(字节 0x%02x)".format(c))
            v = (v shl 3) or (c - '0'.code).toLong()
            seen = true
        }
        return v
    }

    private fun readBlock(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun padding(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw TarException("数据被截断(还差 $left 字节)")
            left -= r
        }
    }

    private fun skipData(input: InputStream, size: Long) = skipFully(input, size + padding(size))
    private fun skipPadding(input: InputStream, size: Long) = skipFully(input, padding(size))

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        var left = size
        val buf = ByteArray(16 * 1024)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw TarException("文件数据被截断(还差 $left 字节)")
            out.write(buf, 0, r)
            left -= r
        }
    }

    private fun readText(input: InputStream, size: Long): String {
        if (size > MAX_META_BYTES) throw TarException("元数据条目过大: $size")
        val data = ByteArray(size.toInt())
        var n = 0
        while (n < data.size) {
            val r = input.read(data, n, data.size - n)
            if (r < 0) throw TarException("元数据被截断")
            n += r
        }
        skipPadding(input, size)
        return String(data, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun parsePax(text: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (line in text.split('\n')) {
            val sp = line.indexOf(' ')
            if (sp < 0) continue
            val kv = line.substring(sp + 1)
            val eq = kv.indexOf('=')
            if (eq < 0) continue
            m[kv.substring(0, eq)] = kv.substring(eq + 1)
        }
        return m
    }
}
