package app.opengrok

import java.io.File

/**
 * Heuristic blacklist applied to every shell tool call BEFORE execution.
 * It is a speed bump against accidents, NOT a security boundary. Sandbox currently provides a
 * working directory only; shell processes still run with the app UID. Do not describe this as OS isolation.
 * Mirrored in tools/guard_selfcheck.py (keep both in sync).
 */
object Guard {
    private val CRITICAL = listOf(
        "/bin", "/sbin", "/usr", "/etc", "/lib", "/lib64", "/system", "/vendor", "/product",
        "/data", "/storage", "/sdcard", "/mnt", "/proc", "/sys", "/dev", "/boot"
    )
    private val EXACT_ONLY = listOf("/var")

    private val FORK = Regex("""(\w+)\s*\(\)\s*\{\s*\1\s*\|\s*\1\s*&\s*\}\s*;\s*\1""")
    private val PIPE_SH = Regex("""\b(curl|wget)\b[^|;&\n]*\|\s*(sudo\s+)?(ba|z|da|a)?sh\b""")
    private val B64_SH = Regex("""\b(base64|openssl\s+enc)\b[^|;&\n]*\|\s*(sudo\s+)?(ba|z|da|a)?sh\b""")
    private val SH_C_CURL = Regex("""\b(ba|z|da|a)?sh\s+-c\s+["']?\s*\$\(\s*(curl|wget)\b""")
    private val SH_PROC_SUBST = Regex("""\b(ba|z|da|a)?sh\s*<\s*\(\s*(curl|wget)\b""")
    private val EVAL_CURL = Regex("""\beval\b[^;&|\n]*\$\(\s*(curl|wget)\b""")
    private val MKFS = Regex("""\bmkfs(\.\w+)?\b""")
    private val DD_DEV = Regex("""\bdd\b[^;&|\n]*\bof=\s*/dev/""")
    private val REDIR_BLK = Regex(""">\s*/dev/(sd|mmcblk|nvme|block|vd|hd)""")
    private val SPLIT = Regex("""[;&|\n]+""")
    private val WS = Regex("""\s+""")
    private val NESTED_SHELL = Regex("""\b(sh|bash|ash|dash)\s+-c\b""")
    private val WRAPPERS = setOf("sudo", "env", "command", "nohup", "exec", "time", "busybox", "toybox")

    /** Returns a human-readable reason if the command must be blocked, else null. */
    fun check(cmd: String, allowDir: String? = null): String? {
        val whole = cmd.trim()
        if (NESTED_SHELL.containsMatchIn(whole)) return "不允许通过嵌套 shell -c 绕过命令检查"
        if (whole.replace(WS, "").contains(":(){:|:&};:") || FORK.containsMatchIn(whole)) return "疑似 fork 炸弹"
        if (PIPE_SH.containsMatchIn(whole)) return "下载后直接管道执行脚本 (curl|sh)"
        if (B64_SH.containsMatchIn(whole)) return "解码后直接执行脚本"
        if (SH_C_CURL.containsMatchIn(whole) || SH_PROC_SUBST.containsMatchIn(whole) || EVAL_CURL.containsMatchIn(whole))
            return "下载后直接执行脚本"
        if (MKFS.containsMatchIn(whole)) return "格式化文件系统 (mkfs)"
        if (DD_DEV.containsMatchIn(whole)) return "dd 写入设备文件"
        if (REDIR_BLK.containsMatchIn(whole)) return "重定向写入块设备"

        for (seg in whole.split(SPLIT)) {
            val toks = seg.trim().split(WS).filter { it.isNotEmpty() }.toMutableList()
            while (toks.isNotEmpty() && toks[0] in WRAPPERS) toks.removeAt(0)
            if (toks.isEmpty()) continue
            val c = toks[0].substringAfterLast('/')
            if (c in setOf("cd", "pushd", "popd")) return "不允许切换工作目录"
            if (c in setOf("reboot", "shutdown", "poweroff", "halt")) return "关机/重启命令"
            val args = toks.drop(1)
            val flags = args.filter { it.startsWith("-") }
            val targets = args.filter { !it.startsWith("-") }.map { it.trim('"', '\'') }
            if (c == "rm") {
                if ("--no-preserve-root" in flags) return "rm --no-preserve-root"
                val recursive = flags.any { f -> f == "--recursive" || (!f.startsWith("--") && (f.contains('r') || f.contains('R'))) }
                if (recursive) {
                    for (p in targets) {
                        if (p.split('/').contains("..")) return "rm -r 目标含上级目录跳转:$p"
                        if (badPath(p, allowDir)) return "rm -r 目标是系统路径:$p"
                    }
                }
            }
            if (c == "chmod" || c == "chown" || c == "chgrp") {
                val rec = flags.any { it == "--recursive" || (!it.startsWith("--") && (it.contains('R') || it.contains('r'))) }
                if (rec && targets.any { badPath(it, allowDir) }) return "递归修改系统路径的权限/属主"
            }
        }
        return null
    }

    private fun badPath(raw: String, allowDir: String?): Boolean {
        if (allowDir == null && !File(raw).isAbsolute) return false
        return try {
            val candidate = if (allowDir == null || File(raw).isAbsolute) {
                File(raw)
            } else {
                File(File(allowDir).canonicalFile, raw)
            }
            val target = candidate.canonicalFile.toPath()

            if (allowDir != null) {
                // Path.startsWith compares path components and canonicalFile resolves existing symlinks.
                // This blocks ../ traversal, absolute paths outside the workspace, and symlink escapes.
                val root = File(allowDir).canonicalFile.toPath()
                !target.startsWith(root)
            } else {
                val p = target.toString().trimEnd('/')
                p.isEmpty() || p == "/" ||
                    CRITICAL.any { p == it || p.startsWith("$it/") } ||
                    EXACT_ONLY.any { p == it || p.startsWith("$it/") }
            }
        } catch (_: Exception) {
            // An unresolvable path is not safe to approve.
            true
        }
    }
}
