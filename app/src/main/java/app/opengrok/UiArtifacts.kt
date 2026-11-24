package app.opengrok

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class LiveArtifact(
    val kind: String,
    val title: String,
    val htmlDoc: String
)

private val FENCE_BLOCK_RE = Regex("```([a-zA-Z0-9_-]*)\\s*\\n([\\s\\S]*?)```")

/**
 * Scans assistant Markdown replies for runnable HTML or SVG code blocks so the user can
 * launch an interactive live preview card directly beside the message.
 */
fun extractLiveArtifacts(markdown: String): List<LiveArtifact> {
    if (!markdown.contains("```")) return emptyList()
    val out = mutableListOf<LiveArtifact>()
    for (m in FENCE_BLOCK_RE.findAll(markdown)) {
        val lang = m.groupValues[1].trim().lowercase()
        val body = m.groupValues[2].trim()
        if (body.length < 40) continue
        when {
            lang == "html" || lang == "htm" || body.startsWith("<!DOCTYPE", true) || body.startsWith("<html", true) -> {
                val titleMatch = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(body)
                val title = titleMatch?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "交互式网页预览" }
                out.add(LiveArtifact("HTML", title, body))
            }
            lang == "svg" || (body.startsWith("<svg", true) && body.contains("</svg>", true)) -> {
                val wrapped = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    |<style>html,body{margin:0;height:100%;background:#0B0C0E;display:flex;align-items:center;justify-content:center;}
                    |svg{max-width:96vw;max-height:92vh;height:auto;}</style></head><body>$body</body></html>""".trimMargin()
                out.add(LiveArtifact("SVG", "矢量图形实时预览", wrapped))
            }
        }
        if (out.size >= 3) break
    }
    return out
}

/**
 * A per-artifact origin (stable for the same content) under the reserved .invalid TLD, so storage
 * written by one artifact is invisible to every other artifact and never collides with a real site.
 */
internal fun artifactOrigin(art: LiveArtifact): String {
    val h = Integer.toHexString((art.kind + "\u0000" + art.htmlDoc).hashCode())
    return "https://a$h.artifact.bothive.invalid/"
}

/** Compact Liquid Glass artifact launch cards shown underneath assistant messages containing HTML/SVG. */
@Composable
fun ArtifactPreviewRow(markdown: String) {
    val artifacts = remember(markdown) { extractLiveArtifacts(markdown) }
    if (artifacts.isEmpty()) return
    var active by remember { mutableStateOf<LiveArtifact?>(null) }

    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        artifacts.forEach { art ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressBounce { active = art }
                    .harmonyGlass(
                        thickness = ImmersiveThickness.REGULAR,
                        radius = 16.dp,
                        tint = ThemeAccent.copy(alpha = 0.12f),
                        fallbackBg = Card
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ThemeAccent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Ic.Artifacts, null, tint = ThemeAccentPale, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(art.title, color = Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${art.kind} Artifact · 点开实时交互预览", color = Sub, fontSize = 11.sp)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(PillWhite)
                        .padding(horizontal = 11.dp, vertical = 5.dp)
                ) { Text("运行", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    active?.let { art ->
        ArtifactPreviewDialog(art = art, onDismiss = { active = null })
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArtifactPreviewDialog(art: LiveArtifact, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(art.htmlDoc.toByteArray()) }
            }.isSuccess
            Toast.makeText(ctx, if (ok) "已导出 HTML 文件" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f))
                .clickable { onDismiss() }
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .padding(8.dp)
                    .navigationBarsPadding()
                    .clickable(enabled = false) {}
                    .harmonyGlass(
                        thickness = ImmersiveThickness.ULTRA_THICK,
                        radius = 24.dp,
                        tint = SheetGlassTint,
                        fallbackBg = Card
                    )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(34.dp).pressBounce(onClick = onDismiss)
                            .harmonyGlass(thickness = ImmersiveThickness.ULTRA_THIN, radius = 999.dp, fallbackBg = PILL),
                        contentAlignment = Alignment.Center
                    ) { Icon(Ic.Close, "关闭", tint = Fg, modifier = Modifier.size(17.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(art.title, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${art.kind} 实时沙盒预览", color = Sub, fontSize = 11.sp)
                    }
                    SmallChip("导出 .html") {
                        val safe = art.title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(28).ifBlank { "artifact" }
                        saver.launch("$safe.html")
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0B0C0E))
                ) {
                    AndroidView(
                        factory = { c ->
                            WebView(c).apply {
                                // Model-generated HTML is untrusted. v0.7.0 hardening:
                                //  - each artifact gets its own origin, so one artifact cannot read
                                //    localStorage/IndexedDB written by another Bot's artifact;
                                //  - no file:// or content:// access, no mixed content;
                                //  - top-level navigation leaves the sandbox and opens in the system browser.
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val req = request ?: return true
                                        val uri = req.url ?: return true
                                        if (!req.isForMainFrame) return false
                                        if (uri.scheme == "http" || uri.scheme == "https") {
                                            runCatching {
                                                c.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                            }
                                        }
                                        return true
                                    }
                                }
                                loadDataWithBaseURL(artifactOrigin(art), art.htmlDoc, "text/html", "UTF-8", null)
                            }
                        },
                        onRelease = { wv ->
                            wv.stopLoading()
                            wv.destroy()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
