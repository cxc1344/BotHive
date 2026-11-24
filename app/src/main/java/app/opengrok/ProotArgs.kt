package app.opengrok

/**
 * Command line for the packaged Termux PRoot build. PRoot stops parsing options at the first non-option
 * argument (the guest program), so NO "--" separator is used: this build rejects it
 * ("proot error: unknown option '--'").
 *
 * v0.7.0: [extraBinds] (host path to guest path) mounts extra directories, e.g. a project's shared
 * exchange folder at /exchange. Unsafe binds (relative guest path, ':' in a path, or a guest
 * path that would shadow / or /workspace or a system directory) are dropped.
 */
internal object ProotArgs {
    private val RESERVED_GUEST = setOf("/", "/workspace", "/dev", "/proc", "/sys", "/bin", "/sbin", "/usr", "/etc", "/lib", "/root", "/tmp")

    fun isSafeBind(host: String, guest: String): Boolean {
        if (host.isBlank() || guest.isBlank()) return false
        if (host.contains(':') || guest.contains(':')) return false
        if (!host.startsWith("/") || !guest.startsWith("/")) return false
        if (guest.split('/').contains("..") || host.split('/').contains("..")) return false
        val g = guest.trimEnd('/').ifEmpty { "/" }
        return g !in RESERVED_GUEST
    }

    fun build(
        proot: String,
        rootfs: String,
        workspaceHostPath: String,
        command: String,
        extraBinds: List<Pair<String, String>> = emptyList()
    ): List<String> {
        val out = mutableListOf(
            proot,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "$workspaceHostPath:/workspace"
        )
        for ((host, guest) in extraBinds) {
            if (isSafeBind(host, guest)) {
                out.add("-b")
                out.add("$host:${guest.trimEnd('/')}")
            }
        }
        out.addAll(listOf("-w", "/workspace", "/bin/sh", "-lc", command))
        return out
    }
}
