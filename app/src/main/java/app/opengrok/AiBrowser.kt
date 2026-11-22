package app.opengrok

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Independent AI Browser Engine built on native Android WebView (zero external dependencies).
 *
 * Capabilities:
 *  - Persistent live session that runs offscreen or onscreen seamlessly.
 *  - Full desktop-grade pointer/mouse/wheel control via 0..1000 normalized coordinates:
 *      left / middle / right button × click / double_click / long_press / drag / hover / wheel.
 *  - Live visual cursor HUD state so the user can watch the AI operate in real time.
 *  - One-tap human Takeover (`userTakeover = true`): suspends incoming AI actions cleanly
 *    until the user clicks "交还 AI" to hand control back.
 */
object AiBrowser {
    var active by mutableStateOf(false)
    var sheetOpen by mutableStateOf(false)
    var userTakeover by mutableStateOf(false)
    var desktopMode by mutableStateOf(false)

    var currentUrl by mutableStateOf("")
    var pageTitle by mutableStateOf("AI 独立浏览器")
    var loadingProgress by mutableIntStateOf(0)
    var lastActionLabel by mutableStateOf("准备就绪")

    // Normalized 0..1000 live AI cursor position for watch-mode overlay
    var cursorX by mutableFloatStateOf(500f)
    var cursorY by mutableFloatStateOf(500f)
    var cursorVisible by mutableStateOf(false)
    var cursorPressed by mutableStateOf(false)
    var cursorBadge by mutableStateOf("")
    var cursorPulseMs by mutableLongStateOf(0L)

    @Volatile
    private var webView: WebView? = null
    @Volatile
    private var ctxWrapper: MutableContextWrapper? = null
    @Volatile
    private var takeoverGate: CompletableDeferred<Unit>? = null

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(context: Context): WebView {
        webView?.let { return it }
        val appCtx = context.applicationContext
        val wrapper = MutableContextWrapper(context)
        ctxWrapper = wrapper
        val wv = WebView(wrapper).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return false
                    if (u.startsWith("http://") || u.startsWith("https://")) {
                        currentUrl = u
                        return false
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!url.isNullOrBlank()) currentUrl = url
                    val t = view?.title?.trim().orEmpty()
                    if (t.isNotBlank()) pageTitle = t
                    loadingProgress = 100
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loadingProgress = newProgress
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) pageTitle = title
                }
            }
        }
        // Give offscreen WebView a realistic mobile viewport so screenshots & coordinates work even before sheet opens
        val wSpec = View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY)
        wv.measure(wSpec, hSpec)
        wv.layout(0, 0, 720, 1200)
        webView = wv
        return wv
    }

    fun attachTo(host: ViewGroup, context: Context): WebView {
        val wv = ensureWebView(context)
        ctxWrapper?.baseContext = context
        (wv.parent as? ViewGroup)?.removeView(wv)
        host.removeAllViews()
        host.addView(
            wv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return wv
    }

    fun detachFrom(host: ViewGroup) {
        val wv = webView ?: return
        if (wv.parent === host) {
            host.removeView(wv)
        }
        ctxWrapper?.let { it.baseContext = it.applicationContext }
    }

    fun setTakeover(enable: Boolean) {
        userTakeover = enable
        if (enable) {
            cursorVisible = false
            cursorPressed = false
            lastActionLabel = "用户已接管 · AI 暂停等待"
            if (takeoverGate == null || takeoverGate?.isCompleted == true) {
                takeoverGate = CompletableDeferred()
            }
        } else {
            lastActionLabel = "已交还 AI 继续操控"
            takeoverGate?.complete(Unit)
            takeoverGate = null
        }
    }

    fun toggleDesktopMode(context: Context) {
        desktopMode = !desktopMode
        val wv = ensureWebView(context)
        wv.settings.userAgentString = if (desktopMode) DESKTOP_UA else null
        if (currentUrl.isNotBlank()) wv.reload()
    }

    fun closeSession() {
        active = false
        sheetOpen = false
        userTakeover = false
        cursorVisible = false
        cursorPressed = false
        lastActionLabel = "已关闭"
        takeoverGate?.complete(Unit)
        takeoverGate = null
        val wv = webView
        webView = null
        ctxWrapper = null
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            runCatching {
                wv.stopLoading()
                wv.destroy()
            }
        }
    }

    /** Suspends AI browser execution cleanly whenever the user has taken over manual control. Returns false if session was closed. */
    private suspend fun awaitAiControl(): Boolean {
        while (userTakeover && active) {
            val gate = takeoverGate ?: CompletableDeferred<Unit>().also { takeoverGate = it }
            gate.await()
        }
        return active
    }

    private suspend fun evalJs(wv: WebView, script: String): String = withContext(Dispatchers.Main) {
        val d = CompletableDeferred<String>()
        wv.evaluateJavascript(script) { res -> d.complete(res ?: "null") }
        d.await()
    }

    /** Captures the live WebView viewport into a compressed JPEG file for Agent vision. */
    suspend fun captureToFile(context: Context, cacheDir: File): File? = withContext(Dispatchers.Main) {
        val wv = ensureWebView(context)
        val w = wv.width.takeIf { it > 0 } ?: 720
        val h = wv.height.takeIf { it > 0 } ?: 1200
        if (wv.width <= 0 || wv.height <= 0) {
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, w, h)
        }
        runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            wv.draw(canvas)
            val out = File(cacheDir, "ai_browser_${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, fos)
            }
            bmp.recycle()
            out
        }.getOrNull()
    }

    private suspend fun pageSummary(wv: WebView): String {
        val raw = evalJs(
            wv,
            """(function(){
                var t = (document.title || '').trim();
                var u = location.href || '';
                var txt = (document.body && document.body.innerText ? document.body.innerText : '').replace(/\s+/g,' ').trim().slice(0, 700);
                return JSON.stringify({title:t, url:u, w:window.innerWidth, h:window.innerHeight, scrollY:Math.round(window.scrollY), text:txt});
            })()"""
        )
        return runCatching {
            val unescaped = JSONObject("{\"v\":$raw}").optString("v", "")
            val o = JSONObject(unescaped)
            val title = o.optString("title", pageTitle)
            val url = o.optString("url", currentUrl)
            val w = o.optInt("w", 0)
            val h = o.optInt("h", 0)
            val sy = o.optInt("scrollY", 0)
            val txt = o.optString("text", "")
            "页面：$title\n网址：$url\n视口：${w}x${h} (scrollY=$sy)\n摘要：$txt"
        }.getOrDefault("页面：$pageTitle\n网址：$currentUrl")
    }

    /** Opens a URL in the independent AI browser, activates the live banner, and captures a screenshot. */
    suspend fun openUrl(
        context: Context,
        cacheDir: File,
        url: String,
        useDesktop: Boolean = false
    ): Pair<String, File?> {
        val cleanUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        withContext(Dispatchers.Main) {
            active = true
            desktopMode = useDesktop
            currentUrl = cleanUrl
            lastActionLabel = "正在打开 ${cleanUrl.removePrefix("https://").removePrefix("http://").take(26)}"
            val wv = ensureWebView(context)
            wv.settings.userAgentString = if (useDesktop) DESKTOP_UA else null
            loadingProgress = 10
            wv.loadUrl(cleanUrl)
        }
        awaitAiControl()
        // Wait up to 4.5s for initial load
        var waited = 0
        while (loadingProgress < 95 && waited < 4500) {
            delay(200)
            waited += 200
            awaitAiControl()
        }
        delay(250)
        val wv = withContext(Dispatchers.Main) { ensureWebView(context) }
        val info = pageSummary(wv)
        val shot = captureToFile(context, cacheDir)
        withContext(Dispatchers.Main) { lastActionLabel = "已打开：${pageTitle.take(20)}" }
        return info to shot
    }

    /**
     * Executes full mouse / pointer / wheel operations in normalized 0..1000 coordinates:
     *  - button: "left" (0) | "middle" (1) | "right" (2)
     *  - action: "click" | "double_click" | "long_press" | "drag" | "wheel" | "move"
     */
    suspend fun mouseAction(
        context: Context,
        cacheDir: File,
        action: String,
        x: Int,
        y: Int,
        button: String = "left",
        x2: Int = x,
        y2: Int = y,
        deltaX: Int = 0,
        deltaY: Int = 0,
        durationMs: Long = 500L
    ): Pair<String, File?> {
        if (!awaitAiControl()) return "用户已结束独立浏览器任务。" to null
        val nx = x.coerceIn(0, 1000)
        val ny = y.coerceIn(0, 1000)
        val nx2 = x2.coerceIn(0, 1000)
        val ny2 = y2.coerceIn(0, 1000)
        val btn = button.trim().lowercase().ifBlank { "left" }
        val act = action.trim().lowercase()
        val btnCode = when (btn) {
            "middle" -> 1
            "right" -> 2
            else -> 0
        }
        val btnMask = when (btn) {
            "middle" -> 4
            "right" -> 2
            else -> 1
        }
        val btnCn = when (btn) {
            "middle" -> "中键"
            "right" -> "右键"
            else -> "左键"
        }
        val actLabel = when (act) {
            "click" -> "${btnCn}点击 ($nx, $ny)"
            "double_click" -> "${btnCn}双击 ($nx, $ny)"
            "long_press" -> "${btnCn}长按 ${durationMs}ms ($nx, $ny)"
            "drag" -> "${btnCn}按住拖动 ($nx,$ny)→($nx2,$ny2)"
            "wheel" -> "滚轮 (${if (deltaY >= 0) "下" else "上"} ${kotlin.math.abs(deltaY)})"
            "move", "hover" -> "移动光标 ($nx, $ny)"
            else -> "$btnCn $act ($nx, $ny)"
        }

        val wv = withContext(Dispatchers.Main) {
            active = true
            cursorVisible = true
            cursorX = nx.toFloat()
            cursorY = ny.toFloat()
            cursorBadge = actLabel
            cursorPulseMs = System.currentTimeMillis()
            lastActionLabel = actLabel
            ensureWebView(context)
        }

        fun jsDispatchPoint(px: Int, py: Int, type: String, bCode: Int, bMask: Int): String = """
            (function(){
                var x = window.innerWidth * ($px / 1000.0);
                var y = window.innerHeight * ($py / 1000.0);
                var el = document.elementFromPoint(x, y) || document.body || document.documentElement;
                if(!el) return 'no-el';
                var opts = {bubbles:true, cancelable:true, composed:true, clientX:x, clientY:y, button:$bCode, buttons:$bMask, pointerId:1, pointerType:'mouse', isPrimary:true};
                try { el.dispatchEvent(new PointerEvent('$type'.replace('mouse','pointer'), opts)); } catch(e){}
                try { el.dispatchEvent(new MouseEvent('$type', opts)); } catch(e){}
                return el.tagName + (el.id ? '#'+el.id : '');
            })()
        """.trimIndent()

        var detail = ""
        when (act) {
            "move", "hover" -> {
                detail = evalJs(wv, jsDispatchPoint(nx, ny, "mousemove", 0, 0))
            }
            "click" -> {
                withContext(Dispatchers.Main) { cursorPressed = true }
                evalJs(wv, jsDispatchPoint(nx, ny, "mousemove", 0, 0))
                evalJs(wv, jsDispatchPoint(nx, ny, "mousedown", btnCode, btnMask))
                delay(55)
                evalJs(wv, jsDispatchPoint(nx, ny, "mouseup", btnCode, 0))
                detail = evalJs(
                    wv,
                    """(function(){
                        var x = window.innerWidth * ($nx / 1000.0);
                        var y = window.innerHeight * ($ny / 1000.0);
                        var el = document.elementFromPoint(x, y) || document.body;
                        if(!el) return 'none';
                        var opts = {bubbles:true, cancelable:true, composed:true, clientX:x, clientY:y, button:$btnCode, buttons:0};
                        if ($btnCode === 0) {
                            if (el.focus) try { el.focus(); } catch(e){}
                            el.dispatchEvent(new MouseEvent('click', opts));
                        } else if ($btnCode === 1) {
                            el.dispatchEvent(new MouseEvent('auxclick', opts));
                            var a = el.closest ? el.closest('a[href]') : null;
                            if (a && a.href) { location.href = a.href; return 'middle-open:' + a.href; }
                        } else if ($btnCode === 2) {
                            el.dispatchEvent(new MouseEvent('contextmenu', opts));
                        }
                        return el.tagName + ':' + (el.innerText || '').trim().slice(0, 40);
                    })()"""
                )
                withContext(Dispatchers.Main) { cursorPressed = false }
            }
            "double_click" -> {
                repeat(2) {
                    withContext(Dispatchers.Main) { cursorPressed = true }
                    evalJs(wv, jsDispatchPoint(nx, ny, "mousedown", btnCode, btnMask))
                    delay(40)
                    evalJs(wv, jsDispatchPoint(nx, ny, "mouseup", btnCode, 0))
                    withContext(Dispatchers.Main) { cursorPressed = false }
                    delay(50)
                }
                detail = evalJs(wv, jsDispatchPoint(nx, ny, "dblclick", btnCode, 0))
            }
            "long_press" -> {
                val hold = durationMs.coerceIn(250L, 4000L)
                withContext(Dispatchers.Main) { cursorPressed = true }
                evalJs(wv, jsDispatchPoint(nx, ny, "mousemove", 0, 0))
                detail = evalJs(wv, jsDispatchPoint(nx, ny, "mousedown", btnCode, btnMask))
                delay(hold)
                awaitAiControl()
                evalJs(wv, jsDispatchPoint(nx, ny, "mouseup", btnCode, 0))
                if (btnCode == 2) {
                    evalJs(wv, jsDispatchPoint(nx, ny, "contextmenu", 2, 0))
                }
                withContext(Dispatchers.Main) { cursorPressed = false }
            }
            "drag" -> {
                val dur = durationMs.coerceIn(180L, 3000L)
                val steps = 12
                val stepDelay = (dur / steps).coerceAtLeast(15L)
                withContext(Dispatchers.Main) { cursorPressed = true }
                evalJs(wv, jsDispatchPoint(nx, ny, "mousemove", 0, 0))
                evalJs(wv, jsDispatchPoint(nx, ny, "mousedown", btnCode, btnMask))
                for (s in 1..steps) {
                    awaitAiControl()
                    val t = s.toFloat() / steps
                    val cx = (nx + (nx2 - nx) * t).toInt()
                    val cy = (ny + (ny2 - ny) * t).toInt()
                    withContext(Dispatchers.Main) {
                        cursorX = cx.toFloat()
                        cursorY = cy.toFloat()
                    }
                    evalJs(wv, jsDispatchPoint(cx, cy, "mousemove", btnCode, btnMask))
                    delay(stepDelay)
                }
                detail = evalJs(wv, jsDispatchPoint(nx2, ny2, "mouseup", btnCode, 0))
                withContext(Dispatchers.Main) { cursorPressed = false }
            }
            "wheel" -> {
                val dx = deltaX
                val dy = if (deltaX == 0 && deltaY == 0) 360 else deltaY
                detail = evalJs(
                    wv,
                    """(function(){
                        var x = window.innerWidth * ($nx / 1000.0);
                        var y = window.innerHeight * ($ny / 1000.0);
                        var el = document.elementFromPoint(x, y) || document.scrollingElement || document.body;
                        if (el) {
                            try { el.dispatchEvent(new WheelEvent('wheel', {bubbles:true, cancelable:true, clientX:x, clientY:y, deltaX:$dx, deltaY:$dy, deltaMode:0})); } catch(e){}
                        }
                        var sc = el;
                        var scrolled = false;
                        while (sc && sc !== document.body && sc !== document.documentElement) {
                            var st = getComputedStyle(sc);
                            if ((st.overflowY === 'auto' || st.overflowY === 'scroll') && sc.scrollHeight > sc.clientHeight) {
                                sc.scrollBy($dx, $dy);
                                scrolled = true;
                                break;
                            }
                            sc = sc.parentElement;
                        }
                        if (!scrolled) window.scrollBy($dx, $dy);
                        return 'scrollY=' + Math.round(window.scrollY);
                    })()"""
                )
            }
            else -> return "不支持的 action：$act（可用 click / double_click / long_press / drag / wheel / move）" to null
        }

        delay(260)
        val info = pageSummary(wv)
        val shot = captureToFile(context, cacheDir)
        return "已执行：$actLabel ($detail)\n$info" to shot
    }

    /** Types text into the currently focused input/textarea/contentEditable element. */
    suspend fun typeText(
        context: Context,
        cacheDir: File,
        text: String,
        submit: Boolean = false
    ): Pair<String, File?> {
        if (!awaitAiControl()) return "用户已结束独立浏览器任务。" to null
        val wv = withContext(Dispatchers.Main) {
            active = true
            lastActionLabel = "输入文字：${text.take(18)}"
            ensureWebView(context)
        }
        val escaped = JSONObject.quote(text)
        val res = evalJs(
            wv,
            """(function(){
                var el = document.activeElement;
                if (!el || el === document.body) {
                    el = document.querySelector('input:not([type=hidden]), textarea, [contenteditable="true"]');
                    if (el && el.focus) el.focus();
                }
                if (!el) return 'no-input';
                var val = $escaped;
                if (el.isContentEditable) {
                    el.innerText = val;
                } else {
                    var proto = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')
                        || Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value');
                    if (proto && proto.set) { try { proto.set.call(el, val); } catch(e){ el.value = val; } }
                    else { el.value = val; }
                }
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                if ($submit) {
                    el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, bubbles:true}));
                    el.dispatchEvent(new KeyboardEvent('keyup', {key:'Enter', code:'Enter', keyCode:13, bubbles:true}));
                    if (el.form) try { el.form.submit(); } catch(e){}
                }
                return 'typed:' + el.tagName;
            })()"""
        )
        delay(280)
        val info = pageSummary(wv)
        val shot = captureToFile(context, cacheDir)
        return "输入完成 ($res)\n$info" to shot
    }

    /** Navigation & snapshot: back / forward / reload / snapshot. */
    suspend fun navigate(
        context: Context,
        cacheDir: File,
        navAction: String
    ): Pair<String, File?> {
        if (!awaitAiControl()) return "用户已结束独立浏览器任务。" to null
        val wv = withContext(Dispatchers.Main) {
            active = true
            val v = ensureWebView(context)
            when (navAction.trim().lowercase()) {
                "back" -> { lastActionLabel = "后退一页"; if (v.canGoBack()) v.goBack() }
                "forward" -> { lastActionLabel = "前进一页"; if (v.canGoForward()) v.goForward() }
                "reload" -> { lastActionLabel = "刷新页面"; v.reload() }
                else -> { lastActionLabel = "查看浏览器画面" }
            }
            v
        }
        if (navAction != "snapshot") delay(550)
        val info = pageSummary(wv)
        val shot = captureToFile(context, cacheDir)
        return info to shot
    }
}
