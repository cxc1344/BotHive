package app.opengrok

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
* The device Agent's phone-file tools, scoped to the single folder tree the user granted
* through the system picker (SAF). Paths are relative to that root; ".." is rejected.
* Nothing outside the granted tree is reachable through these functions.
*/
object DeviceFiles {
private fun root(store: Store): DocumentFile? {
val uriStr = store.agentTreeUri.value?: return null
return try {
DocumentFile.fromTreeUri(store.appContext(), Uri.parse(uriStr))
} catch (_: Exception) {
null
}
}

private fun segments(path: String): List<String>? {
val parts = path.split('/').map { it.trim()}.filter { it.isNotEmpty() && it!= "."}
if (parts.any { it == ".."}) return null
return parts
}

private fun find(root: DocumentFile, parts: List<String>): DocumentFile? {
var cur = root
for (p in parts) {
cur = cur.findFile(p)?: return null
}
return cur
}

private fun errNoGrant() =
"用户还没有给 Agent 授权手机文件夹。请让用户在 Agent 模式页面点「授权文件夹」选择一个目录后再试。"

suspend fun listDir(store: Store, path: String): String = withContext(Dispatchers.IO) {
val r = root(store)?: return@withContext errNoGrant()
val parts = segments(path)?: return@withContext "路径不合法:不允许.. "
val dir = find(r, parts)?: return@withContext "目录不存在:$path"
if (!dir.isDirectory) return@withContext "不是目录:$path"
val files = dir.listFiles()
if (files.isEmpty()) return@withContext "(空目录)"
files.take(200).joinToString("\n") { f ->
(if (f.isDirectory) "[目录] " else "[文件] ") + (f.name?: "?") +
if (f.isFile) " (${f.length()} B)" else ""
} + if (files.size > 200) "\n…(共 ${files.size} 项,只列前 200)" else ""
}

suspend fun readFile(store: Store, path: String): String = withContext(Dispatchers.IO) {
val r = root(store)?: return@withContext errNoGrant()
val parts = segments(path)?: return@withContext "路径不合法:不允许.. "
if (parts.isEmpty()) return@withContext "请给出文件路径"
val f = find(r, parts)?: return@withContext "文件不存在:$path"
if (!f.isFile) return@withContext "不是文件:$path"
if (f.length() > 512 * 1024) return@withContext "文件过大(${f.length()} B),暂只支持读取 512KB 以内的文本文件"
try {
val bytes = store.contentResolver().openInputStream(f.uri)?.use { it.readBytes()}
?: return@withContext "读取失败:无法打开 $path"
if (bytes.any { it.toInt() == 0}) return@withContext "该文件疑似二进制,不按文本读取"
val text = String(bytes, Charsets.UTF_8)
if (text.length > 20_000) text.take(20_000) + "\n…(已截断,共 ${text.length} 字符)" else text
} catch (e: Exception) {
"读取失败:${e.message}"
}
}

suspend fun writeFile(store: Store, path: String, content: String): String = withContext(Dispatchers.IO) {
val r = root(store)?: return@withContext errNoGrant()
val parts = segments(path)?: return@withContext "路径不合法:不允许.. "
if (parts.isEmpty()) return@withContext "请给出文件路径"
try {
var dir = r
for (p in parts.dropLast(1)) {
dir = dir.findFile(p)?.takeIf { it.isDirectory}
?: dir.createDirectory(p)
?: return@withContext "无法创建目录:$p"
}
val name = parts.last()
val existing = dir.findFile(name)
val target = if (existing!= null && existing.isFile) existing
else dir.createFile("text/plain", name)?: return@withContext "无法创建文件:$path"
store.contentResolver().openOutputStream(target.uri, "wt")?.use {
it.write(content.toByteArray(Charsets.UTF_8))
}?: return@withContext "写入失败:无法打开 $path"
"已写入 $path(${content.length} 字符)"
} catch (e: Exception) {
"写入失败:${e.message}"
}
}

/** Human-readable label for the granted folder, for settings/agent UI. */
fun grantLabel(store: Store): String? {
val uriStr = store.agentTreeUri.value?: return null
return try {
val docId = DocumentsContract.getTreeDocumentId(Uri.parse(uriStr))
docId.substringAfterLast(':').ifBlank { docId}
} catch (_: Exception) {
"已授权"
}
}
}
