package app.opengrok

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.Base64

/**
* Tiered whole-device file access for the Agent (Operit-style channels):
* - standard: the app itself, after the user grants 所有文件访问 (MANAGE_EXTERNAL_STORAGE) —
* full shared storage via plain file APIs.
* - shizuku: commands run as the shell user through Shizuku — reaches places the app
* cannot (e.g. /data/local/tmp), still bounded by the shell identity.
* - root: same pipe, but only when the identity on the other end is uid=0 (Shizuku started
* with root, or `su` available) — the only channel that can touch other apps' /data.
* channel "auto" picks standard when possible and falls back to shizuku.
* Shell-channel operations are built as commands and still pass the Guard denylist.
*/
object FsTools {

fun allFilesGranted(): Boolean =
Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

private fun q(path: String): String = "'" + path.replace("'", "'\\''") + "'"

private suspend fun shellChecked(command: String): String {
val why = Guard.check(command, "/")
if (why!= null) return "$why"
return ShizukuTools.shell(command)
}

private suspend fun rootShell(command: String): String {
val idOut = ShizukuTools.shell("id")
if (idOut.contains("uid=0")) return shellChecked(command)
// Shizuku running as shell on a rooted device may still reach root via su.
val suProbe = ShizukuTools.shell("su -c id")
if (suProbe.contains("uid=0")) {
val wrapped = "su -c " + q(command)
val why = Guard.check(wrapped, "/")
if (why!= null) return "$why"
return ShizukuTools.shell(wrapped)
}
return "Root 通道不可用:Shizuku 不是以 root 身份运行,且 su 不可用(id 返回:$idOut)。只有 root 通道能访问其他应用的 /data 私有目录。"
}

private fun channelError(channel: String): String = when (channel) {
"standard" -> "普通通道不可用:用户还没给「所有文件访问」授权(在 Agent 面板点「全部文件访问」开启),或系统版本过低。"
else -> "该通道不可用。"
}

suspend fun list(ctx: Context, path: String, channel: String): String {
val p = path.ifBlank { "/sdcard"}
return when (channel) {
"shizuku" -> shellChecked("ls -la -- ${q(p)}")
"root" -> rootShell("ls -la -- ${q(p)}")
else -> {
if (allFilesGranted()) stdList(p)
else if (channel == "auto") shellChecked("ls -la -- ${q(p)}")
else channelError("standard")
}
}
}

suspend fun read(ctx: Context, path: String, channel: String): String {
return when (channel) {
"shizuku" -> shellChecked("head -c 24000 -- ${q(path)}")
"root" -> rootShell("head -c 24000 -- ${q(path)}")
else -> {
if (allFilesGranted()) stdRead(path)
else if (channel == "auto") shellChecked("head -c 24000 -- ${q(path)}")
else channelError("standard")
}
}
}

suspend fun write(ctx: Context, path: String, content: String, channel: String): String {
return when (channel) {
"shizuku", "root" -> {
val b64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
val cmd = "mkdir -p -- ${q(parentOf(path))} && printf %s ${q(b64)} | base64 -d > ${q(path)} && echo OK"
if (channel == "root") rootShell(cmd) else shellChecked(cmd)
}
else -> {
if (allFilesGranted()) stdWrite(path, content)
else if (channel == "auto") write(ctx, path, content, "shizuku")
else channelError("standard")
}
}
}

suspend fun delete(ctx: Context, path: String, channel: String): String {
return when (channel) {
"shizuku" -> shellChecked("rm -rf -- ${q(path)} && echo DELETED")
"root" -> rootShell("rm -rf -- ${q(path)} && echo DELETED")
else -> {
if (allFilesGranted()) {
val f = File(path)
if (!f.exists()) "不存在:$path"
else if (f.deleteRecursively()) "已删除:$path"
else "删除失败:$path"
} else if (channel == "auto") shellChecked("rm -rf -- ${q(path)} && echo DELETED")
else channelError("standard")
}
}
}

private fun parentOf(path: String): String = path.substringBeforeLast('/', "/")

private fun stdList(path: String): String {
val f = File(path)
if (!f.exists()) return "不存在:$path"
if (!f.isDirectory) return "不是目录:$path(${f.length()} 字节)"
val items = f.listFiles()?: return "无法读取目录(权限不足):$path"
if (items.isEmpty()) return "(空目录)"
return items.sortedWith(compareByDescending<File> { it.isDirectory}.thenBy { it.name})
.take(200)
.joinToString("\n") { if (it.isDirectory) "📁 ${it.name}/" else "📄 ${it.name} (${it.length()} B)"}
}

private fun stdRead(path: String): String {
val f = File(path)
if (!f.exists()) return "不存在:$path"
if (f.isDirectory) return "这是目录,请用 fs_list:$path"
if (f.length() > 512 * 1024) return "文件过大(${f.length()} 字节),只支持 512KB 以内的文本读取。"
val bytes = f.readBytes()
if (bytes.any { it == 0.toByte()}) return "(二进制文件,不支持文本读取,共 ${bytes.size} 字节)"
val text = String(bytes, Charsets.UTF_8)
return if (text.length > 20_000) text.take(20_000) + "\n…(已截断)" else text
}

private fun stdWrite(path: String, content: String): String {
return try {
val f = File(path)
f.parentFile?.mkdirs()
f.writeText(content, Charsets.UTF_8)
"已写入:$path(${content.length} 字符)"
} catch (e: Exception) {
"写入失败:${e.message}"
}
}
}
