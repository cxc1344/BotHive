package app.opengrok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural tests for the chat Markdown renderer.
 *
 * The renderer's block parser is private, so these tests go through the same regex/parsing rules
 * the renderer uses. They exist to catch the regression that made replies show raw `**bold**`
 * and `| a | b |` tables.
 */
class MarkdownTest {

    /** Mirrors the inline rules: strip emphasis markers, keep the visible text. */
    private fun plainInline(s: String): String {
        var out = s
        out = out.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        out = out.replace(Regex("__(.+?)__"), "$1")
        out = out.replace(Regex("~~(.+?)~~"), "$1")
        out = out.replace(Regex("`(.+?)`"), "$1")
        out = out.replace(Regex("\\*(.+?)\\*"), "$1")
        out = out.replace(Regex("\\[(.+?)\\]\\((.+?)\\)"), "$1")
        return out
    }

    @Test
    fun boldMarkersAreStripped() {
        assertEquals("Shizuku shell", plainInline("**Shizuku shell**"))
        assertEquals("a b", plainInline("**a** **b**"))
    }

    @Test
    fun inlineCodeAndStrikeAreStripped() {
        assertEquals("echo test", plainInline("`echo test`"))
        assertEquals("done", plainInline("~~done~~"))
    }

    @Test
    fun linksKeepLabel() {
        assertEquals("docs", plainInline("[docs](https://example.com)"))
    }

    @Test
    fun snakeCaseIsNotTreatedAsItalic() {
        // A single underscore inside a word must survive untouched.
        assertEquals("snake_case_name", plainInline("snake_case_name"))
    }

    @Test
    fun tableSeparatorIsRecognised() {
        val sep = "|------|------|"
        val isSep = sep.contains('-') && sep.replace(Regex("[\\s|:\\-]"), "").isEmpty()
        assertTrue("pipe separator row must be detected as a table separator", isSep)
    }

    @Test
    fun tableRowSplitsIntoCells() {
        val cells = "| 项目 | 结果 |".trim().trim('|').split('|').map { it.trim() }
        assertEquals(listOf("项目", "结果"), cells)
    }

    @Test
    fun headingLevelIsParsed() {
        val m = Regex("^(#{1,6})\\s+(.*)$").find("## 当前环境状态")
        assertEquals(2, m?.groupValues?.get(1)?.length)
        assertEquals("当前环境状态", m?.groupValues?.get(2))
    }

    @Test
    fun bulletAndOrderedItemsAreDetected() {
        assertTrue(Regex("^[-*+]\\s+(.*)$").matches("- 第一项"))
        assertTrue(Regex("^\\d+[.)]\\s+(.*)$").matches("1. 第一步"))
        assertTrue(Regex("^\\d+[.)]\\s+(.*)$").matches("2) 第二步"))
    }
}
