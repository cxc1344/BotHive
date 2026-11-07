package app.opengrok

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Per-Bot sandbox. Milestone 1: a private working directory + Android's own /system/bin/sh.
 * Milestone 2 will swap exec() for a proot-based Alpine/Debian rootfs (same interface).
 */
class Sandbox(val dir: File) {
    fun exec(cmd: String, timeoutSec: Long = 30): String {
        dir.mkdirs()
        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", dir.absolutePath)
            put("TMPDIR", dir.absolutePath)
        }
        val p = pb.start()
        val sb = StringBuilder()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(1024)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(sb) { if (sb.length < 20000) sb.append(buf, 0, n) }
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            synchronized(sb) { sb.append("\n[超时 ${timeoutSec}s,已终止]") }
        }
        reader.join(1000)
        val out = synchronized(sb) { sb.toString() }
        val cut = if (out.length > 4000) out.take(4000) + "\n…(已截断)" else out
        return cut.ifBlank { "(无输出)" }
    }

    fun reset() {
        dir.deleteRecursively()
        dir.mkdirs()
    }
}
