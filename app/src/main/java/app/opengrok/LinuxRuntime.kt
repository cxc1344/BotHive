package app.opengrok

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Downloads and unpacks an Alpine minirootfs for each Bot, then executes commands through
 * the Android PRoot binary packaged in nativeLibraryDir by tools/fetch_proot_android.sh.
 * The compressed archive is cached once per device ABI; unpacked rootfs trees are per-Bot.
 */
internal object LinuxRuntime {
    private const val ALPINE_VERSION = "3.24.4"
    private const val MAX_ROOTFS_ARCHIVE_BYTES = 32L * 1024L * 1024L
    private const val ALPINE_BASE = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases"
    private val botLocks = ConcurrentHashMap<String, Any>()
    private val processSlots = Semaphore(2, true)

    private data class Abi(val android: String, val alpine: String)

    private val supported = listOf(
        Abi("arm64-v8a", "aarch64"),
        Abi("armeabi-v7a", "armhf"),
        Abi("x86_64", "x86_64"),
        Abi("x86", "x86")
    )

    fun exec(
        context: Context,
        botId: String,
        rootfs: File,
        workspace: File,
        command: String,
        timeoutSec: Long
    ): String {
        val abi = supported.firstOrNull { it.android in Build.SUPPORTED_ABIS }
            ?: throw IOException("当前设备 ABI 不在 PRoot 支持列表中: ${Build.SUPPORTED_ABIS.joinToString()}")
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libloader.so")
        if (!proot.isFile || !loader.isFile) {
            throw IOException("APK 缺少 PRoot 运行文件。请先运行 bash tools/fetch_proot_android.sh，再重新构建 APK")
        }

        val lock = botLocks.getOrPut(botId) { Any() }
        synchronized(lock) {
            ensureRootfs(context, botId, abi, rootfs, workspace)
            if (!processSlots.tryAcquire(120, TimeUnit.SECONDS)) {
                throw IOException("Linux 并发任务已满，请稍后重试")
            }
            try {
                return runProot(context, botId, proot, loader, rootfs, workspace, command, timeoutSec)
            } finally {
                processSlots.release()
            }
        }
    }

    private fun ensureRootfs(
        context: Context,
        botId: String,
        abi: Abi,
        rootfs: File,
        workspace: File
    ) {
        val marker = File(rootfs, ".opengrok-rootfs")
        if (marker.isFile && File(rootfs, "bin/sh").exists()) {
            workspace.mkdirs()
            return
        }

        val parent = rootfs.parentFile ?: throw IOException("无法创建 Linux 目录")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建 ${parent.path}")
        val staging = File(parent, ".${botId}.installing")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("无法创建临时 rootfs 目录")

        try {
            val archive = cachedArchive(context, abi)
            val log = File(context.cacheDir, "alpine-extract-$botId.log")
            val tar = File("/system/bin/tar")
            if (!tar.canExecute()) throw IOException("系统 tar 不可用")
            val extractor = ProcessBuilder(
                tar.absolutePath, "-xzf", archive.absolutePath, "-C", staging.absolutePath
            ).redirectErrorStream(true).redirectOutput(log).start()
            if (!extractor.waitFor(120, TimeUnit.SECONDS)) {
                extractor.destroy()
                if (!extractor.waitFor(2, TimeUnit.SECONDS)) extractor.destroyForcibly()
                throw IOException("Alpine 解压超时")
            }
            if (extractor.exitValue() != 0) {
                throw IOException("Alpine 解压失败: ${log.takeIf { it.isFile }?.readText()?.take(500).orEmpty()}")
            }
            if (!File(staging, "bin/sh").exists()) throw IOException("下载的 Alpine rootfs 不完整")

            File(staging, "root").mkdirs()
            File(staging, "tmp").mkdirs()
            File(staging, "workspace").mkdirs()
            writeResolver(staging)
            markerFor(staging).writeText("Alpine $ALPINE_VERSION\n")

            rootfs.deleteRecursively()
            if (!staging.renameTo(rootfs)) throw IOException("无法完成 rootfs 安装")
            workspace.mkdirs()
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun markerFor(root: File) = File(root, ".opengrok-rootfs")

    private fun cachedArchive(context: Context, abi: Abi): File {
        val name = "alpine-minirootfs-$ALPINE_VERSION-${abi.alpine}.tar.gz"
        val cache = File(context.cacheDir, name)
        val part = File(context.cacheDir, "$name.part")
        val url = "$ALPINE_BASE/${abi.alpine}/$name"
        val expected = readHttpsText("$url.sha256").let {
            Regex("(?i)\\b([0-9a-f]{64})\\b").find(it)?.groupValues?.get(1)?.lowercase()
                ?: throw IOException("Alpine SHA256 文件格式异常")
        }
        if (cache.isFile && sha256(cache) == expected) return cache
        cache.delete()
        part.delete()
        download(url, part, MAX_ROOTFS_ARCHIVE_BYTES)
        if (sha256(part) != expected) {
            part.delete()
            throw IOException("Alpine rootfs SHA256 校验失败")
        }
        if (!part.renameTo(cache)) throw IOException("无法缓存 Alpine rootfs")
        return cache
    }

    private fun readHttpsText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OpenGrokBot-Android/0.3")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("下载校验文件失败: HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(4096) }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File, maxBytes: Long) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OpenGrokBot-Android/0.3")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("下载 Alpine 失败: HTTP ${connection.responseCode}")
            val length = connection.contentLengthLong
            if (length > maxBytes) throw IOException("Alpine 压缩包超过大小限制")
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("Alpine 压缩包超过大小限制")
                        output.write(buffer, 0, n)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeResolver(rootfs: File) {
        val etc = File(rootfs, "etc")
        etc.mkdirs()
        val resolv = File(etc, "resolv.conf")
        // Remove a rootfs-provided symlink before writing, so it cannot redirect writes outside rootfs.
        if (resolv.exists() || resolv.isSymbolicLinkCompat()) resolv.delete()
        val servers = mutableListOf<String>()
        for (key in listOf("net.dns1", "net.dns2", "net.dns3", "net.dns4")) {
            runCatching {
                val p = ProcessBuilder("/system/bin/getprop", key).start()
                if (p.waitFor(1, TimeUnit.SECONDS)) {
                    val value = p.inputStream.bufferedReader().use { it.readText().trim() }
                    if (value.matches(Regex("[0-9a-fA-F:.]+")) && value !in servers) servers.add(value)
                    Unit
                } else p.destroyForcibly()
            }
        }
        if (servers.isEmpty()) servers.addAll(listOf("223.5.5.5", "1.1.1.1"))
        resolv.writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    private fun File.isSymbolicLinkCompat(): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(toPath())
    }.getOrDefault(false)

    private fun runProot(
        context: Context,
        botId: String,
        proot: File,
        loader: File,
        rootfs: File,
        workspace: File,
        command: String,
        timeoutSec: Long
    ): String {
        val temp = File(context.cacheDir, "proot-tmp/$botId").apply { mkdirs() }
        val args = listOf(
            proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "${workspace.canonicalPath}:/workspace",
            "-w", "/workspace",
            "--",
            "/bin/sh", "-lc", command
        )
        val pb = ProcessBuilder(args).directory(workspace).redirectErrorStream(true)
        pb.environment().apply {
            clear() // Do not leak unrelated Android process environment into the guest.
            put("PROOT_LOADER", loader.absolutePath)
            put("PROOT_TMP_DIR", temp.absolutePath)
            put("LD_LIBRARY_PATH", proot.parentFile?.absolutePath.orEmpty())
            put("HOME", "/root")
            put("USER", "root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("TMPDIR", "/tmp")
            put("LANG", "C.UTF-8")
        }
        val process = pb.start()
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(1024)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(output) {
                            if (output.length < 20_000) output.append(buf, 0, n)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        val limit = timeoutSec.coerceIn(1, 180)
        if (!process.waitFor(limit, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            synchronized(output) { output.append("\n[执行超时 ${limit}s，已请求终止]") }
        } else {
            synchronized(output) { output.append("\n[exit ${process.exitValue()}]") }
        }
        reader.join(1_000)
        val result = synchronized(output) { output.toString() }
        return if (result.isBlank()) "(无输出)" else if (result.length > 4_000) result.take(4_000) + "\n…(已截断)" else result
    }
}
