package app.opengrok

import android.content.Context
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
            "[Linux 环境错误] ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Reset both the Bot's workspace and its Linux installation. */
    fun reset() {
        dir.deleteRecursively()
        rootfs.deleteRecursively()
        dir.mkdirs()
    }

    fun deleteAll() {
        reset()
        dir.deleteRecursively()
        rootfs.parentFile?.let { if (it.exists() && it.list()?.isEmpty() == true) it.delete() }
    }
}
