package app.opengrok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V062FixesTest {

    @Test
    fun duplicateNameNeverLoopsOnRepeatedCopies() {
        val existing = mutableSetOf("程序员")
        val first = nextDuplicateName("程序员", existing)
        assertEquals("程序员 副本", first)
        existing.add(first)

        val second = nextDuplicateName("程序员", existing)
        assertEquals("程序员 副本 2", second)
        existing.add(second)

        val third = nextDuplicateName("程序员 副本", existing)
        assertEquals("程序员 副本 3", third)
    }

    @Test
    fun mcpExposedToolNameRespects64CharLimit() {
        val shortName = Mcp.exposedToolName("srv1", "search")
        assertEquals("mcp_srv1_search", shortName)

        val longServer = "a".repeat(40)
        val longTool = "b".repeat(40)
        val capped = Mcp.exposedToolName(longServer, longTool)
        assertTrue("Expected <= 64 chars, got ${capped.length}", capped.length <= 64)
        assertEquals(capped, Mcp.exposedToolName(longServer, longTool))
    }

    @Test
    fun phoneOperatorParsesCoreActions() {
        val tap = PhoneOperator.parse("""do(action="Tap", element=[512, 820])""")
        assertNotNull(tap)
        assertEquals("tap", tap?.kind)
        assertEquals(512, tap?.x)
        assertEquals(820, tap?.y)

        val fin = PhoneOperator.parse("""finish(message="已发送消息")""")
        assertEquals("finish", fin?.kind)
        assertEquals("已发送消息", fin?.message)

        assertNull(PhoneOperator.parse("无法解析的自由文本"))
    }

    @Test
    fun immersiveThicknessScalesMonotonically() {
        val tiers = ImmersiveThickness.values()
        assertEquals(5, tiers.size)
        for (i in 0 until tiers.lastIndex) {
            assertTrue(tiers[i].blurRadius < tiers[i + 1].blurRadius)
            assertTrue(tiers[i].veilAlpha < tiers[i + 1].veilAlpha)
        }
    }
}
