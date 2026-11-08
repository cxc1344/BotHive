package app.opengrok

import android.content.Context
import android.util.Log
import java.io.File

/**
 * One persistent PRoot/Alpine rootfs per Bot, plus that Bot's existing workspace.
 * This is a Termux-like Linux userspace, NOT a VM or a kernel security boundary.
 */
class Sandbox(context: Context, botId: String) {
    private val app = context.applicationContext
    private val safeId = botId.also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid Bot id" }
    }

    /** Host-side workspace, retained at the old path for existing Guard and file handling. */
    val dir: File = File(app.filesDir, "sandbox/$safeId")

    /** Each Bot gets its own full unpacked Alpine tree and package database. */
    private val rootfs: File = File(app.filesDir, "linux-rootfs/$safeId")

    fun exec(cmd: String, timeoutSec: Long = 30): String {
        if (cmd.isBlank()) return "(空命令)"
        return try {
            LinuxRuntime.exec(
                context = app,
                botId = safeId,
                rootfs = rootfs,
                workspace = dir,
                command = cmd,
                timeoutSec = timeoutSec
            )
        } catch (e: Exception) {
            Log.e("OpenGrokLinux", "exec failed", e)
            runCatching { File(app.cacheDir, "linux-last-error.log").writeText(Log.getStackTraceString(e)) }
            "[Linux 环境错误] " + LinuxRuntime.describe(e).let { d ->
                // LinuxSetupException already carries "[步骤 n/8 …] detail" + diagnostics in its message.
                if (e is LinuxSetupException) e.message ?: d else d
            }
        }
    }

    /** Reset both the Bot's workspace and its Linux installation. */
    fun reset() {
        // Never follow symlinks while deleting: guest-created links must not redirect host deletions.
        TarExtract.deleteTree(dir)
        TarExtract.deleteTree(rootfs)
        dir.mkdirs()
    }

    fun deleteAll() {
        reset()
        TarExtract.deleteTree(dir)
        rootfs.parentFile?.let { if (it.exists() && it.list()?.isEmpty() == true) it.delete() }
    }
}
