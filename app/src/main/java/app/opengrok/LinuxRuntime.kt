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

/** Setup/launch failure that names the failing step and carries diagnostics, so a screenshot is enough to debug. */
internal class LinuxSetupException(
    val step: String,
    detail: String,
    cause: Throwable? = null,
    val diag: String = ""
) : IOException(
    "[" + step + "] " + detail + (if (diag.isNotBlank()) "\n诊断: $diag" else ""),
    cause
)

/**
 * Downloads and unpacks an Alpine minirootfs for each Bot, then executes commands through
 * the Android PRoot binary packaged in nativeLibraryDir by tools/fetch_proot_android.sh.
 * The compressed archive is cached once per device ABI; unpacked rootfs trees are per-Bot.
 */
internal object LinuxRuntime {
    private const val ALPINE_VERSION = "3.24.4"
    private const val ALPINE_BRANCH = "v3.24"
    private const val MAX_ROOTFS_ARCHIVE_BYTES = 32L * 1024L * 1024L
    private const val DOWNLOAD_DEADLINE_MS = 300_000L
    private const val TOTAL_STEPS = 8
    private const val USER_AGENT = "OpenGrokBot-Android/0.3.2"

    /** Official CDN first; well-known public mirrors as fallback for networks where the CDN is slow or blocked. */
    private val MIRRORS = listOf(
        "dl-cdn.alpinelinux.org" to "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/releases",
        "mirrors.aliyun.com" to "https://mirrors.aliyun.com/alpine/$ALPINE_BRANCH/releases",
        "mirrors.tuna.tsinghua.edu.cn" to "https://mirrors.tuna.tsinghua.edu.cn/alpine/$ALPINE_BRANCH/releases"
    )

    private val botLocks = ConcurrentHashMap<String, Any>()
    private val processSlots = Semaphore(2, true)

    @Volatile
    private var seccompDisabled = false

    private data class Abi(val android: String, val alpine: String)

    private val supported = listOf(
        Abi("arm64-v8a", "aarch64"),
        Abi("armeabi-v7a", "armhf"),
        Abi("x86_64", "x86_64"),
        Abi("x86", "x86")
    )

    fun describe(e: Throwable): String {
        val msg = e.message?.takeIf { it.isNotBlank() }
        val head = if (e.javaClass == IOException::class.java) (msg ?: "IOException") else "${e.javaClass.simpleName}: ${msg ?: "(无消息)"}"
        val c = e.cause
        return if (c != null && c !== e && c.message != e.message) "$head ← ${c.javaClass.simpleName}: ${c.message}" else head
    }

    fun exec(
        context: Context,
        botId: String,
        rootfs: File,
        workspace: File,
        command: String,
        timeoutSec: Long
    ): String {
        val abi = supported.firstOrNull { it.android in Build.SUPPORTED_ABIS }
            ?: throw LinuxSetupException(
                "步骤 1/$TOTAL_STEPS 检查设备",
                "当前设备 ABI 不在 PRoot 支持列表中",
                diag = "supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}"
            )
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libloader.so")
        if (!proot.isFile || !loader.isFile) {
            throw LinuxSetupException(
                "步骤 1/$TOTAL_STEPS 检查设备",
                "APK 缺少 PRoot 运行文件。请先运行 bash tools/fetch_proot_android.sh，再重新构建 APK",
                diag = "nativeDir=${nativeDir.path} files=${nativeDir.list()?.sorted()?.joinToString().orEmpty()}"
            )
        }

        val lock = botLocks.getOrPut(botId) { Any() }
        synchronized(lock) {
            if (!isInstalled(rootfs)) Installer(context, botId, abi, rootfs, workspace).install()
            workspace.mkdirs()
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

    /**
     * Alpine's bin/sh is an ABSOLUTE symlink (/bin/busybox). File.exists() resolves it against the HOST
     * filesystem, where /bin/busybox does not exist on Android, so it must be resolved inside the rootfs instead.
     */
    private fun isInstalled(rootfs: File): Boolean =
        File(rootfs, ".opengrok-rootfs").isFile && TarExtract.resolveInGuest(rootfs, "bin/sh")?.isFile == true

    private class Installer(
        val context: Context,
        val botId: String,
        val abi: Abi,
        val rootfs: File,
        val workspace: File
    ) {
        private val diag = StringBuilder()

        private fun note(key: String, value: Any?) {
            if (diag.isNotEmpty()) diag.append(" · ")
            diag.append(key).append('=').append(value)
        }

        private fun <T> step(n: Int, name: String, block: () -> T): T = try {
            block()
        } catch (e: LinuxSetupException) {
            throw e
        } catch (e: Exception) {
            throw LinuxSetupException("步骤 $n/$TOTAL_STEPS $name", describe(e), e, diag.toString())
        }

        private fun <T> tag(label: String, block: () -> T): T = try {
            block()
        } catch (e: Exception) {
            throw IOException("$label: ${describe(e)}", e)
        }

        fun install() {
            note("abi", abi.android)
            note("alpine", ALPINE_VERSION)
            val parent = step(2, "准备目录") {
                val p = rootfs.parentFile ?: throw IOException("rootfs 路径没有父目录: ${rootfs.path}")
                if (!p.exists() && !p.mkdirs()) throw IOException("无法创建 ${p.path}")
                p
            }
            val staging = File(parent, ".$botId.installing")
            step(2, "准备目录") {
                TarExtract.deleteTree(staging)
                if (!staging.mkdirs()) throw IOException("无法创建临时目录 ${staging.path}")
            }
            try {
                val archive = fetchArchive()
                val result = step(5, "解压 Alpine") { TarExtract.extractTarGz(archive, staging) }
                note("extracted", result.summary())
                step(6, "完整性检查") { verifyLayout(staging) }
                step(7, "安装 rootfs") {
                    File(staging, "root").mkdirs()
                    File(staging, "tmp").mkdirs()
                    File(staging, "workspace").mkdirs()
                    writeResolver(staging)
                    File(staging, ".opengrok-rootfs").writeText("Alpine $ALPINE_VERSION\n")
                    TarExtract.deleteTree(rootfs)
                    if (!staging.renameTo(rootfs)) {
                        throw IOException("重命名失败 ${staging.path} → ${rootfs.path}")
                    }
                }
                workspace.mkdirs()
            } catch (e: Exception) {
                try { TarExtract.deleteTree(staging) } catch (_: Exception) {}
                throw e
            }
        }

        private fun verifyLayout(root: File) {
            val problems = mutableListOf<String>()
            val bb = File(root, "bin/busybox")
            if (!bb.isFile || bb.length() == 0L) problems.add("bin/busybox 缺失或为空")
            else if (!bb.canExecute()) problems.add("bin/busybox 没有可执行权限")
            val sh = TarExtract.resolveInGuest(root, "bin/sh")
            if (sh == null || !sh.isFile) problems.add("bin/sh 在 rootfs 内解析不到可执行文件")
            val rel = File(root, "etc/alpine-release")
            if (!rel.isFile) problems.add("etc/alpine-release 缺失")
            val hasLoader = File(root, "lib").list()?.any { it.startsWith("ld-musl-") } == true
            if (!hasLoader) problems.add("lib/ld-musl-* 缺失")
            if (problems.isNotEmpty()) throw IOException("rootfs 内容不完整: " + problems.joinToString("; "))
        }

        /** Returns a verified archive: previously verified local copy first, then each mirror in turn. */
        private fun fetchArchive(): File {
            val name = "alpine-minirootfs-$ALPINE_VERSION-${abi.alpine}.tar.gz"
            val cache = File(context.cacheDir, name)
            val okFile = File(context.cacheDir, "$name.sha256ok")

            val local = step(3, "检查本地缓存") {
                if (cache.isFile && okFile.isFile) {
                    val want = okFile.readText().trim()
                    if (want.length == 64 && sha256(cache) == want) cache else null
                } else null
            }
            if (local != null) {
                note("source", "local-cache")
                note("archive", "${local.length() / 1024}KB")
                return local
            }

            val failures = mutableListOf<String>()
            for ((host, base) in MIRRORS) {
                val url = "$base/${abi.alpine}/$name"
                val part = File(context.cacheDir, "$name.part")
                try {
                    val expected = tag("取 SHA256 校验文件") { fetchSha256("$url.sha256") }
                    part.delete()
                    val bytes = tag("下载压缩包") { download(url, part) }
                    val actual = tag("计算 SHA256") { sha256(part) }
                    if (actual != expected) {
                        throw IOException("SHA256 不一致: 期望 ${expected.take(12)}… 实际 ${actual.take(12)}…(收到 $bytes 字节)")
                    }
                    cache.delete()
                    if (!part.renameTo(cache)) throw IOException("无法把下载文件移入缓存 ${cache.path}")
                    okFile.writeText(expected)
                    note("source", host)
                    note("archive", "${bytes / 1024}KB")
                    note("sha256", expected.take(12) + "…")
                    return cache
                } catch (e: Exception) {
                    part.delete()
                    failures.add("$host → ${describe(e)}")
                }
            }
            throw LinuxSetupException(
                "步骤 3/$TOTAL_STEPS 下载 Alpine",
                "所有下载源均失败:\n" + failures.joinToString("\n"),
                diag = diag.toString()
            )
        }
    }

    // ---- network ----

    /** Follows redirects manually (HTTPS only) so proxy/VPN/CDN hops are visible and never downgrade to HTTP. */
    private fun open(startUrl: String, readTimeoutMs: Int): HttpURLConnection {
        var url = URL(startUrl)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept-Encoding", "identity") // never let a proxy gunzip a .tar.gz for us
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw IOException("HTTP $code 重定向但缺少 Location(${url.host})")
                val next = URL(url, loc)
                if (next.protocol != "https") throw IOException("拒绝重定向到非 HTTPS 地址: ${next.protocol}://${next.host}")
                url = next
            } else {
                if (code !in 200..299) {
                    val msg = c.responseMessage
                    c.disconnect()
                    throw IOException("HTTP $code ${msg ?: ""} (${url.host})")
                }
                return c
            }
        }
        throw IOException("重定向次数过多(${url.host})")
    }

    private fun fetchSha256(url: String): String {
        val c = open(url, 30_000)
        try {
            val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(4096) }
            return Regex("(?i)(?<![0-9a-f])([0-9a-f]{64})(?![0-9a-f])").find(text)?.groupValues?.get(1)?.lowercase()
                ?: throw IOException("校验文件内容无法解析(前 80 字符: '${text.take(80).replace("\n", "\\n")}')")
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, target: File): Long {
        val c = open(url, 60_000)
        try {
            val length = c.contentLengthLong
            if (length > MAX_ROOTFS_ARCHIVE_BYTES) throw IOException("Content-Length $length 超过上限 ${MAX_ROOTFS_ARCHIVE_BYTES / 1024 / 1024}MB")
            val deadline = System.currentTimeMillis() + DOWNLOAD_DEADLINE_MS
            var total = 0L
            c.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ROOTFS_ARCHIVE_BYTES) throw IOException("下载内容超过上限 ${MAX_ROOTFS_ARCHIVE_BYTES / 1024 / 1024}MB")
                        if (System.currentTimeMillis() > deadline) throw IOException("下载超时(已收到 $total 字节)")
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (length >= 0 && total != length) throw IOException("下载不完整: 收到 $total 字节, 期望 $length 字节")
            return total
        } finally {
            c.disconnect()
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
        try { java.nio.file.Files.deleteIfExists(resolv.toPath()) } catch (_: Exception) {}
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

    // ---- running commands ----

    private data class ProotRun(val text: String, val exit: Int, val timedOut: Boolean, val limit: Long)

    private fun render(r: ProotRun): String {
        val body = if (r.text.isBlank()) "" else r.text
        val tail = if (r.timedOut) "\n[执行超时 ${r.limit}s，已请求终止]" else "\n[exit ${r.exit}]"
        val joined = body + tail
        return if (joined.isBlank()) "(无输出)" else if (joined.length > 4_000) joined.take(4_000) + "\n…(已截断)" else joined
    }

    /** proot's own startup failures print "proot error: ..." and exit non-zero before the guest command ever runs. */
    private fun prootItselfFailed(r: ProotRun) = !r.timedOut && r.exit != 0 && r.text.trimStart().lowercase().startsWith("proot")

    /** Only seccomp/ptrace-looking startup failures justify the PROOT_NO_SECCOMP retry; option/usage errors do not. */
    private fun looksSeccompRelated(r: ProotRun): Boolean {
        val t = r.text.lowercase()
        return prootItselfFailed(r) && ("seccomp" in t || "ptrace" in t || "operation not permitted" in t)
    }

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
        val first = runProotOnce(context, botId, proot, loader, rootfs, workspace, command, timeoutSec, seccompDisabled)
        if (!seccompDisabled && looksSeccompRelated(first)) {
            // Some kernels/ROMs reject PRoot's seccomp acceleration; retry once without it (the guest command never ran).
            val second = runProotOnce(context, botId, proot, loader, rootfs, workspace, command, timeoutSec, true)
            if (!prootItselfFailed(second)) {
                seccompDisabled = true
                return render(second) + "\n[PRoot 已自动改用 PROOT_NO_SECCOMP=1]"
            }
            return render(first) + "\n[已用 PROOT_NO_SECCOMP=1 重试，仍失败]\n" + render(second)
        }
        return render(first)
    }

    private fun runProotOnce(
        context: Context,
        botId: String,
        proot: File,
        loader: File,
        rootfs: File,
        workspace: File,
        command: String,
        timeoutSec: Long,
        noSeccomp: Boolean
    ): ProotRun {
        val temp = File(context.cacheDir, "proot-tmp/$botId").apply { mkdirs() }
        val args = ProotArgs.build(proot.absolutePath, rootfs.absolutePath, workspace.canonicalPath, command)
        val pb = ProcessBuilder(args).directory(workspace).redirectErrorStream(true)
        pb.environment().apply {
            clear() // Do not leak unrelated Android process environment into the guest.
            put("PROOT_LOADER", loader.absolutePath)
            put("PROOT_TMP_DIR", temp.absolutePath)
            put("LD_LIBRARY_PATH", proot.parentFile?.absolutePath.orEmpty())
            if (noSeccomp) put("PROOT_NO_SECCOMP", "1")
            put("HOME", "/root")
            put("USER", "root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("TMPDIR", "/tmp")
            put("LANG", "C.UTF-8")
        }
        val process = try {
            pb.start()
        } catch (e: Exception) {
            throw LinuxSetupException(
                "步骤 8/$TOTAL_STEPS 启动 PRoot",
                describe(e),
                e,
                "proot=${proot.path} exists=${proot.exists()} canExecute=${proot.canExecute()} size=${proot.length()} " +
                    "loader=${loader.exists()} workspace=${workspace.exists()} rootfs=${rootfs.exists()} noSeccomp=$noSeccomp"
            )
        }
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
        var timedOut = false
        var exit = -1
        if (!process.waitFor(limit, TimeUnit.SECONDS)) {
            timedOut = true
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        } else {
            exit = process.exitValue()
        }
        reader.join(1_000)
        return ProotRun(synchronized(output) { output.toString() }, exit, timedOut, limit)
    }
}
