package app.opengrok

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography for BotHive — a bundled serif face, LaTeX-adjacent.
 *
 * History worth keeping: an earlier attempt bundled only Latin Modern, which has **no CJK glyphs
 * at all**. Chinese then fell back to the platform's sans-serif, so the UI came out half-serif
 * (Latin) and half-sans (Chinese) — exactly the mismatch this file now avoids.
 *
 * The bundled face is **Noto Serif SC**, subset to GB2312 + ASCII + Greek + math operators +
 * dingbats (~7.6k glyphs, 2.3 MB per weight instead of 11 MB). It covers Chinese, Latin, digits,
 * punctuation and symbols in one family, so every script shares the same weight and rhythm —
 * that is what makes it read as a single typeset document rather than mixed fallbacks.
 *
 * Rendering still falls back to the system for anything outside the subset (rare CJK, emoji),
 * which is normal Android/Skia behaviour; the subset covers ordinary Chinese text.
 *
 * vNext (Claude-style reskin): the serif is no longer the global body family. It is reserved for
 * the display moments (greeting, page titles, sheet titles, thinking word) via [HiveSerif]; body
 * text uses the platform sans. No new font files — the same subset does both jobs.
 */
object HiveFonts {
    /** Body / UI family: the serif face, one file per weight. */
    val Roman: FontFamily = FontFamily(
        Font(R.font.serif_regular, FontWeight.Normal, FontStyle.Normal),
        // Noto Serif SC ships Regular/Bold in this subset; intermediate weights map to Bold so
        // headings stay crisp instead of getting a synthesised faux-bold.
        Font(R.font.serif_bold, FontWeight.Medium, FontStyle.Normal),
        Font(R.font.serif_bold, FontWeight.SemiBold, FontStyle.Normal),
        Font(R.font.serif_bold, FontWeight.Bold, FontStyle.Normal),
        Font(R.font.serif_regular, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.serif_bold, FontWeight.Bold, FontStyle.Italic)
    )

    /**
     * Math family. Kept as an alias of [Roman] on purpose: the serif face already contains the
     * operators, Greek letters and arrows we need, so switching families per-span would only
     * introduce a visible weight jump.
     */
    val Math: FontFamily = Roman

    /**
     * Code, tool output, tables: a real monospace face (Latin Modern Mono, ~130 KB per weight).
     * Monospace matters here — shell output and tables are scanned column-wise, and a
     * proportional face makes them unreadable. It has no CJK glyphs, so Chinese inside code
     * blocks falls back to the system font; that is acceptable because code is mostly ASCII.
     */
    val Mono: FontFamily = FontFamily(
        Font(R.font.mono_regular, FontWeight.Normal, FontStyle.Normal),
        Font(R.font.mono_bold, FontWeight.Bold, FontStyle.Normal)
    )

    /** Display family for large headings; same faces, kept separate for future tuning. */
    val Display: FontFamily = Roman

    /** Kept for call-site compatibility; the serif subset already covers these ranges. */
    fun isMathChar(c: Char): Boolean {
        val code = c.code
        return when {
            code < 0x80 -> false
            code in 0x0370..0x03FF -> true
            code in 0x2190..0x21FF -> true
            code in 0x2200..0x22FF -> true
            code in 0x2A00..0x2AFF -> true
            code == 0x00B1 || code == 0x00D7 || code == 0x00F7 -> true
            code in 0x00B2..0x00B3 || code == 0x00B0 -> true
            else -> false
        }
    }
}

/**
 * Material 3 typography. vNext (Claude-style reskin): the **base family is the system sans**
 * — body copy, list rows, buttons and labels are all sans, matching the reference app.
 * The bundled serif ([HiveFonts.Roman]) stays for the display moments that carry the brand
 * voice: greetings, page titles, Bot (project) names, sheet titles and the thinking-state word.
 * Those call sites opt in explicitly via [HiveSerif] (see UiTokens.kt).
 */
fun hiveTypography(): Typography {
    val base = Typography()
    // One place decides the family: everything inherits the system sans, so the ~230 Text call
    // sites need no editing (and cannot drift apart later).
    return base
}

/** Line height helper so mixed CJK/Latin lines keep even spacing. */
fun bodyLineHeight(size: androidx.compose.ui.unit.TextUnit) = (size.value * 1.5f).sp
