package app.opengrok

/**
 * Command line for the packaged Termux PRoot build. PRoot stops parsing options at the first non-option
 * argument (the guest program), so NO "--" separator is used: this build rejects it
 * ("proot error: unknown option '--'").
 */
internal object ProotArgs {
    fun build(proot: String, rootfs: String, workspaceHostPath: String, command: String): List<String> = listOf(
        proot,
        "--kill-on-exit",
        "--link2symlink",
        "-0",
        "-r", rootfs,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "$workspaceHostPath:/workspace",
        "-w", "/workspace",
        "/bin/sh", "-lc", command
    )
}
