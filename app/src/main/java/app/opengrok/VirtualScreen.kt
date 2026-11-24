package app.opengrok

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.io.FileOutputStream

/**
 * Virtual-display engine for the Agent's screen operations.
 *
 * Architecture (pattern only — no third-party code is copied):
 *  - Create a secondary display via [DisplayManager.createVirtualDisplay] with a [ImageReader] surface.
 *  - Launch apps onto that display through the Shizuku shell channel (`am start --display <id>`).
 *  - Inject gestures through the same channel (`input --display <id> ...`).
 *  - Read frames straight from the ImageReader, so `screen_shot` needs neither MediaProjection nor
 *    the accessibility screenshot API while this mode is active.
 *
 * Deliberately scoped to Agent automation: no screen casting, no recording, no mirroring feature.
 *
 * Known limits (kept explicit rather than hidden):
 *  - Windows with FLAG_SECURE (banking, payment, some password screens) come back black in these
 *    frames. When that happens the Agent should tell the user to switch back to main-screen mode.
 *  - A few apps handle secondary displays poorly (wrong orientation / size). Mainstream apps such
 *    as WeChat are the reference target.
 *  - The accessibility node tree is bound to the *active* window of the default display, so
 *    `screen_dump` / `tap_text` are unavailable here; this mode is vision-only.
 */
object VirtualScreen {
    /** True while a virtual display exists. */
    val active = mutableStateOf(false)
    /** Display id of the virtual display, or -1 when not created. */
    val displayId = mutableStateOf(-1)
    /** User paused the floating window (frames stop refreshing, tools still work). */
    val paused = mutableStateOf(false)
    /** Name of the Bot currently driving this screen, shown in the floating window. */
    val ownerName = mutableStateOf("")

    private var displayManager: DisplayManager? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    /** Physical pixel size of the virtual display (matches the main screen unless reduced). */
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var density: Int = 0
        private set

    /**
     * v0.7.0 frame ownership: [latest] is the newest full-resolution frame and is owned by this
     * object only. It is never handed to a View, so it can be recycled safely when replaced.
     * Views (the floating preview) and screenshots always get their own scaled copies.
     *
     * Before, the overlay's main-thread loop consumed every ImageReader frame itself, displayed the
     * full-size bitmap and recycled the previous one while the RenderThread could still be drawing
     * it; screen_shot on an idle screen then found no new image and reported "no frame".
     */
    private val frameLock = Any()
    private var latest: Bitmap? = null
    @Volatile
    private var frameSeq = 0L

    /** Pulls a new frame if one is ready; returns true when [latest] changed. Call under [frameLock]. */
    private fun pollLocked(): Boolean {
        val fresh = grabFrame() ?: return false
        val old = latest
        latest = fresh
        frameSeq++
        if (old != null && old !== fresh && !old.isRecycled) old.recycle()
        return true
    }

    private fun swapFrame(next: Bitmap?) {
        synchronized(frameLock) {
            val old = latest
            latest = next
            if (old != null && old !== next && !old.isRecycled) old.recycle()
        }
    }

    /**
     * Scaled copy of the newest frame for the floating preview, or null when nothing changed since
     * [sinceSeq]. The caller owns the copy (never recycled by this object). Runs off the main thread.
     */
    fun previewFrame(maxWidth: Int, sinceSeq: Long): Pair<Bitmap, Long>? = synchronized(frameLock) {
        pollLocked()
        val src = latest ?: return@synchronized null
        if (frameSeq == sinceSeq || src.isRecycled) return@synchronized null
        val scale = minOf(1f, maxWidth.coerceAtLeast(64).toFloat() / src.width)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val copy = runCatching {
            if (w == src.width) src.copy(Bitmap.Config.ARGB_8888, false)
            else Bitmap.createScaledBitmap(src, w, h, true)
        }.getOrNull() ?: return@synchronized null
        copy to frameSeq
    }

    /**
     * Creates the virtual display. Returns null on success, or a human-readable error string.
     * Safe to call repeatedly: an existing display is reused.
     */
    fun ensure(context: Context, owner: String): String? {
        ownerName.value = owner
        if (active.value && virtualDisplay != null) return null
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            // Keep the full main-screen size: identical pixels means 0-1000 coordinates map the same
            // way in both modes, and apps see the size they were built for.
            width = metrics.widthPixels
            height = metrics.heightPixels
            density = if (metrics.densityDpi > 0) metrics.densityDpi else 320
            if (width <= 0 || height <= 0) return "读不到主屏尺寸,无法创建虚拟屏。"

            val ir = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            // Android 14+ treats PUBLIC/PRESENTATION as "screen sharing" (mirroring) and then demands
            // ADD_MIRROR_DISPLAY (a signature permission) or a MediaProjection token. OWN_CONTENT_ONLY
            // declares that this display hosts its own content instead of mirroring the main screen,
            // which is exactly what we do: apps launched onto it render there directly.
            //
            // We try progressively simpler flag sets because OEM ROMs differ in what they accept; the
            // first one that succeeds wins, and the failure text from the last attempt is reported.
            val attempts = listOf(
                "OWN_CONTENT_ONLY|PUBLIC|PRESENTATION" to (
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION),
                "OWN_CONTENT_ONLY|PUBLIC" to (
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC),
                "OWN_CONTENT_ONLY" to DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            var vd: VirtualDisplay? = null
            var lastError = ""
            for ((label, flags) in attempts) {
                vd = try {
                    dm.createVirtualDisplay("BotHive-Agent", width, height, density, ir.surface, flags)
                } catch (e: Exception) {
                    lastError = "$label → ${e.message ?: e.javaClass.simpleName}"
                    null
                }
                if (vd != null) break
                if (lastError.isEmpty()) lastError = "$label → 返回 null"
            }
            val created = vd
            if (created == null) {
                runCatching { ir.close() }
                return "系统拒绝创建虚拟屏(已尝试 3 种 flag 组合)。最后一次:$lastError"
            }

            displayManager = dm
            reader = ir
            virtualDisplay = created
            displayId.value = created.display.displayId
            active.value = true
            paused.value = false
            null
        } catch (e: Exception) {
            release()
            "创建虚拟屏失败:${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Releases the display and its frame reader. Safe to call when nothing is active. */
    fun release() {
        runCatching { reader?.close() }
        runCatching { virtualDisplay?.release() }
        reader = null
        virtualDisplay = null
        displayManager = null
        swapFrame(null)
        displayId.value = -1
        active.value = false
        paused.value = false
    }

    /**
     * Pulls the newest frame from the ImageReader. Callers own the returned bitmap.
     * Returns null when no frame is ready (the display is idle or was just created).
     * Only called under [frameLock] so the preview loop and screen_shot never race on the reader.
     */
    private fun grabFrame(): Bitmap? {
        val ir = reader ?: return null
        val image = try {
            ir.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            // Row padding must be included in the copy, then cropped off.
            val paddedWidth = width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            if (paddedWidth == width) padded else {
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                padded.recycle()
                cropped
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { image.close() }
        }
    }

    /** True when a frame is available without waiting for a new one. */
    fun hasFrame(): Boolean = synchronized(frameLock) { latest != null }

    /**
     * Writes a scaled PNG of the virtual display for `screen_shot`, reusing the main-screen
     * screenshot conventions (720px wide cap, rolling 20-file history).
     * An idle screen produces no new ImageReader frame, so the newest known frame is used.
     */
    fun captureToFile(cacheDir: File): Pair<File, Pair<Int, Int>>? {
        // A freshly created display needs a moment before the first frame lands. Poll briefly so a
        // just-launched app does not look like a broken screen. The lock is never held while sleeping.
        var waited = 0L
        var shot: Bitmap? = null
        while (true) {
            shot = synchronized(frameLock) {
                pollLocked()
                val src = latest
                if (src == null || src.isRecycled) null else {
                    val scale = minOf(1f, 720f / src.width.toFloat())
                    runCatching {
                        if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
                        else src.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                }
            }
            if (shot != null || waited >= 1_500L) break
            Thread.sleep(120)
            waited += 120
        }
        val out = shot ?: return null
        return try {
            val dir = File(cacheDir, "screenshots").apply { mkdirs() }
            val f = File(dir, "vshot_${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 90, it) }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
            f to (out.width to out.height)
        } catch (_: Exception) {
            null
        } finally {
            if (!out.isRecycled) out.recycle()
        }
    }

    /** Heuristic: an all-black frame usually means the foreground window is FLAG_SECURE. */
    fun looksBlack(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 24).coerceAtLeast(1)
        val stepY = (bmp.height / 48).coerceAtLeast(1)
        var samples = 0
        var dark = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r + g + b < 24) dark++
                samples++
                x += stepX
            }
            y += stepY
        }
        return samples > 0 && dark.toFloat() / samples > 0.985f
    }

    /** Human-readable one-liner for tool output. */
    fun describe(): String =
        if (!active.value) "虚拟屏未运行"
        else "虚拟屏 #${displayId.value} · ${width}x${height} @${density}dpi" +
            (if (paused.value) " · 已暂停" else "")

    /** Maps a 0-1000 normalized coordinate to virtual-display pixels; null when size is unknown. */
    fun toPixels(nx: Int, ny: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        return (nx.coerceIn(0, 1000) * width / 1000) to (ny.coerceIn(0, 1000) * height / 1000)
    }

    /** Whether this OS build supports the overlay + virtual display combination we need. */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
