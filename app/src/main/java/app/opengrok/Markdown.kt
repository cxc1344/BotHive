package app.opengrok

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A small, dependency-free Markdown renderer for chat replies.
 *
 * Why hand-rolled: the project ships no Markdown library, and pulling one in for a phone chat
 * view is heavy. This covers what models actually emit — headings, bold/italic/strike/inline code,
 * links (shown as text), bullet and numbered lists, block quotes, fenced code blocks, horizontal
 * rules and GFM pipe tables — which is what was previously shown raw as `**text**` and `| a | b |`.
 *
 * Deliberately not a full CommonMark implementation: nested structures beyond one level, HTML,
 * and reference links are out of scope.
 */
private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** Splits a document into blocks. Line-based, which is how chat Markdown is actually written. */
private fun parseMarkdown(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) out.add(MdBlock.Paragraph(para.toString().trim()))
        para.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // fenced code block
        if (trimmed.startsWith("```")) {
            flushPara()
            val lang = trimmed.removePrefix("```").trim()
            val buf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                buf.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++ // closing fence
            out.add(MdBlock.Code(lang, buf.toString().trimEnd('\n')))
            continue
        }

        // horizontal rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushPara(); out.add(MdBlock.Rule); i++; continue
        }

        // heading
        val h = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (h != null) {
            flushPara()
            out.add(MdBlock.Heading(h.groupValues[1].length, h.groupValues[2].trim()))
            i++
            continue
        }

        // table: header row + separator row of dashes/pipes
        if (trimmed.contains('|') && i + 1 < lines.size) {
            val sep = lines[i + 1].trim()
            val isSep = sep.contains('-') && sep.replace(Regex("[\\s|:\\-]"), "").isEmpty()
            if (isSep) {
                flushPara()
                val header = splitRow(trimmed)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trim().contains('|') && lines[i].isNotBlank()) {
                    rows.add(splitRow(lines[i].trim()))
                    i++
                }
                out.add(MdBlock.Table(header, rows))
                continue
            }
        }

        // block quote
        if (trimmed.startsWith(">")) {
            flushPara()
            val buf = StringBuilder()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(lines[i].trim().removePrefix(">").trim())
                i++
            }
            out.add(MdBlock.Quote(buf.toString()))
            continue
        }

        // lists (bullet and ordered, one block per contiguous run)
        val bullet = Regex("^[-*+]\\s+(.*)$").find(trimmed)
        val ordered = Regex("^\\d+[.)]\\s+(.*)$").find(trimmed)
        if (bullet != null || ordered != null) {
            flushPara()
            val isOrdered = ordered != null
            val items = mutableListOf<String>()
            while (i < lines.size) {
                val t = lines[i].trim()
                val m = (if (isOrdered) Regex("^\\d+[.)]\\s+(.*)$") else Regex("^[-*+]\\s+(.*)$")).find(t)
                if (m != null) {
                    items.add(m.groupValues[1].trim())
                    i++
                } else if (t.isNotBlank() && items.isNotEmpty() && !t.startsWith("#") &&
                    !t.startsWith("```") && !t.startsWith(">") && !t.contains('|')) {
                    // continuation line of the previous item
                    items[items.lastIndex] = items.last() + " " + t
                    i++
                } else break
            }
            out.add(MdBlock.Bullet(items, isOrdered))
            continue
        }

        if (trimmed.isEmpty()) { flushPara(); i++; continue }

        if (para.isNotEmpty()) para.append(' ')
        para.append(trimmed)
        i++
    }
    flushPara()
    return out
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

/**
 * Inline spans: **bold**, *italic* / _italic_, ~~strike~~, `code`, [text](url).
 * Underscores inside words (snake_case) are left alone.
 */
private fun inline(text: String, base: Color, codeBg: Color = Color.Transparent): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        var plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) { append(plain.toString()); plain = StringBuilder() }
        }
        while (i < text.length) {
            // code span
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flush()
                    pushStyle(SpanStyle(fontFamily = HiveFonts.Mono, background = codeBg, color = HoneyPale))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                    continue
                }
            }
            // bold
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                val mark = text.substring(i, i + 2)
                val end = text.indexOf(mark, i + 2)
                if (end > i + 2) {
                    flush()
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = base))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                    continue
                }
            }
            // strike
            if (text.startsWith("~~", i)) {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                    continue
                }
            }
            // italic (* or _), only when not part of a word
            if ((text[i] == '*' || text[i] == '_') && !text.startsWith("**", i) && !text.startsWith("__", i)) {
                val mark = text[i]
                val prevIsWord = i > 0 && text[i - 1].isLetterOrDigit()
                val end = text.indexOf(mark, i + 1)
                if (!prevIsWord && end > i + 1) {
                    flush()
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                    continue
                }
            }
            // link: show the label, keep the URL visible in parentheses
            if (text[i] == '[') {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                    val endParen = text.indexOf(')', close + 2)
                    if (endParen > close) {
                        flush()
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, endParen)
                        pushStyle(SpanStyle(color = Honey, textDecoration = TextDecoration.Underline))
                        append(label)
                        pop()
                        if (url.isNotBlank() && url != label) {
                            pushStyle(SpanStyle(color = Sub, fontSize = 12.sp))
                            append(" ($url)")
                            pop()
                        }
                        i = endParen + 1
                        continue
                    }
                }
            }
            plain.append(text[i])
            i++
        }
        flush()
        applyMathSpans()
    }

/**
 * Re-styles math runs onto [HiveFonts.Math].
 *
 * Indices must come from the builder's own text, not the source string: inline markers
 * (`**`, `` ` ``, link syntax) are stripped while appending, so source offsets would drift.
 */
private fun AnnotatedString.Builder.applyMathSpans() {
    val built = toAnnotatedString()
    val plainText = built.text
    var i = 0
    while (i < plainText.length) {
        if (HiveFonts.isMathChar(plainText[i])) {
            val start = i
            while (i < plainText.length && HiveFonts.isMathChar(plainText[i])) i++
            addStyle(SpanStyle(fontFamily = HiveFonts.Math), start, i)
        } else i++
    }
}

/** Renders a Markdown reply as themed Compose blocks. */
@Composable
fun MarkdownText(
    text: String,
    color: Color = Fg,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 24.sp
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> {
                    val size = when (b.level) {
                        1 -> 20.sp
                        2 -> 18.sp
                        3 -> 16.sp
                        else -> fontSize
                    }
                    Text(
                        inline(b.text, color),
                        color = color,
                        fontSize = size,
                        fontWeight = FontWeight.Bold,
                        lineHeight = size * 1.4f
                    )
                }
                is MdBlock.Paragraph -> Text(
                    inline(b.text, color), color = color, fontSize = fontSize, lineHeight = lineHeight
                )
                is MdBlock.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    b.items.forEachIndexed { idx, item ->
                        Row {
                            Text(
                                if (b.ordered) "${idx + 1}. " else "· ",
                                color = Honey, fontSize = fontSize, lineHeight = lineHeight,
                                modifier = Modifier.width(if (b.ordered) 24.dp else 14.dp)
                            )
                            Text(inline(item, color), color = color, fontSize = fontSize, lineHeight = lineHeight)
                        }
                    }
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(3.dp).heightIn(min = 18.dp).background(Honey.copy(alpha = .55f))) {}
                    Text(
                        inline(b.text, Sub), color = Sub, fontSize = fontSize, lineHeight = lineHeight,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
                is MdBlock.Code -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF120E06)).border(1.dp, Line, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    if (b.lang.isNotBlank()) {
                        Text(b.lang, color = Honey.copy(alpha = .8f), fontSize = 10.sp,
                            fontFamily = HiveFonts.Mono, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    // Long lines scroll horizontally instead of wrapping mid-token.
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(b.code, color = HoneyPale, fontSize = 12.sp, fontFamily = HiveFonts.Mono)
                    }
                }
                is MdBlock.Table -> MdTable(b.header, b.rows, color, fontSize)
                MdBlock.Rule -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Line)
                )
            }
        }
    }
}

/** GFM pipe table: header row emphasised, hairline separators, horizontal scroll for wide tables. */
@Composable
private fun MdTable(header: List<String>, rows: List<List<String>>, color: Color, fontSize: androidx.compose.ui.unit.TextUnit) {
    val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    // Column alignment: rows are laid out inside one shared intrinsic-size pass, otherwise each
    // Row measures independently and the columns drift out of line (the whole point of a table).
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(BeePanelRaised).border(1.dp, Line, RoundedCornerShape(10.dp))
    ) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Row(Modifier.background(Honey.copy(alpha = .14f)).width(IntrinsicSize.Max)) {
                    for (c in 0 until cols) {
                        Text(
                            inline(header.getOrElse(c) { "" }, color),
                            color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                rows.forEach { row ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = .7f))) {}
                    Row(Modifier.width(IntrinsicSize.Max)) {
                        for (c in 0 until cols) {
                            Text(
                                inline(row.getOrElse(c) { "" }, color),
                                color = color, fontSize = 12.sp,
                                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
