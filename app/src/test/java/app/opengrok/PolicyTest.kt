package app.opengrok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.7.0 policy rules (pure part of Policy.kt; no Context / org.json needed). */
class PolicyTest {
    private fun cls(tool: String, vararg kv: Pair<String, String>): ActionClass {
        val m = kv.toMap()
        return PolicyRules.classify(tool) { k -> m[k].orEmpty() }
    }

    @Test fun classifiesEveryKindOfTool() {
        assertEquals(ActionClass.SANDBOX, cls("shell"))
        assertEquals(ActionClass.FILE_WRITE, cls("fs_write"))
        assertEquals(ActionClass.DELETE, cls("fs_delete"))
        assertEquals(ActionClass.PRIVILEGED, cls("shizuku_shell"))
        assertEquals(ActionClass.SCREEN, cls("tap_at"))
        assertEquals(ActionClass.SCREEN, cls("phone_task"))
        assertEquals(ActionClass.WEB, cls("browser_open"))
        assertEquals(ActionClass.WEB, cls("open_url"))
        assertEquals(ActionClass.EXTERNAL, cls("mcp_weather_get"))
        assertEquals(ActionClass.SELF, cls("remember"))
        assertEquals(ActionClass.SELF, cls("schedule_task"))
        assertEquals(ActionClass.SENSITIVE_DATA, cls("browser_fill_secret"))
        assertEquals(ActionClass.READ, cls("recall"))
        assertEquals(ActionClass.READ, cls("screen_shot"))
        assertEquals(ActionClass.READ, cls("team_status"))
    }

    @Test fun browserNavSnapshotIsReadOtherwiseWeb() {
        assertEquals(ActionClass.READ, cls("browser_nav", "action" to "snapshot"))
        assertEquals(ActionClass.READ, cls("browser_nav"))
        assertEquals(ActionClass.WEB, cls("browser_nav", "action" to "back"))
    }

    @Test fun unknownToolsAreTreatedAsOutbound() {
        assertEquals(ActionClass.EXTERNAL, cls("definitely_not_a_tool"))
    }

    @Test fun floorsCannotBeLoosened() {
        for (lvl in PolicyLevel.values()) {
            assertTrue(PolicyRules.effectiveLevel(ActionClass.SENSITIVE_DATA, lvl, false).ordinal >= PolicyLevel.ASK.ordinal)
            assertEquals(PolicyLevel.HANDOFF, PolicyRules.effectiveLevel(ActionClass.PAYMENT, lvl, false))
            // READ is not configurable: always its default.
            assertEquals(PolicyLevel.AUTO, PolicyRules.effectiveLevel(ActionClass.READ, lvl, false))
        }
    }

    @Test fun configurableClassesFollowTheUser() {
        assertEquals(PolicyLevel.HANDOFF, PolicyRules.effectiveLevel(ActionClass.WEB, PolicyLevel.HANDOFF, false))
        assertEquals(PolicyLevel.AUTO, PolicyRules.effectiveLevel(ActionClass.DELETE, PolicyLevel.AUTO, false))
    }

    @Test fun shellApprovalForcesAskForSandboxPrivilegedAndDelete() {
        assertEquals(PolicyLevel.ASK, PolicyRules.effectiveLevel(ActionClass.SANDBOX, PolicyLevel.AUTO, true))
        assertEquals(PolicyLevel.ASK, PolicyRules.effectiveLevel(ActionClass.PRIVILEGED, PolicyLevel.AUTO, true))
        assertEquals(PolicyLevel.ASK, PolicyRules.effectiveLevel(ActionClass.DELETE, PolicyLevel.AUTO, true))
        assertEquals(PolicyLevel.HANDOFF, PolicyRules.effectiveLevel(ActionClass.SANDBOX, PolicyLevel.HANDOFF, true))
        assertEquals(PolicyLevel.AUTO, PolicyRules.effectiveLevel(ActionClass.WEB, PolicyLevel.AUTO, true))
    }

    @Test fun stricterPicksTheHigherLevel() {
        assertEquals(PolicyLevel.ASK, PolicyRules.stricter(PolicyLevel.AUTO, PolicyLevel.ASK))
        assertEquals(PolicyLevel.HANDOFF, PolicyRules.stricter(PolicyLevel.HANDOFF, PolicyLevel.EXPLICIT))
    }

    @Test fun explicitNeedsTheKeyArgumentInTheHumanMessage() {
        assertTrue(PolicyRules.explicitlyRequested("browser_open", "https://www.example.com/a?b=1", "帮我打开 example.com 看看"))
        assertTrue(PolicyRules.explicitlyRequested("shell", "ls -la", "运行 ls -la 看一下"))
        assertFalse(PolicyRules.explicitlyRequested("shell", "rm -rf build", "帮我看看目录"))
        assertFalse(PolicyRules.explicitlyRequested("shell", "x", "x"))           // too short
        assertFalse(PolicyRules.explicitlyRequested("shell", "ls -la", ""))
    }

    @Test fun keyArgumentPerTool() {
        val args = mapOf("command" to " ls ", "path" to "/sdcard/a", "url" to "https://a.com", "text" to "hi")
        val get: (String) -> String = { k -> args[k].orEmpty() }
        assertEquals("ls", PolicyRules.keyArg("shell", get))
        assertEquals("/sdcard/a", PolicyRules.keyArg("fs_delete", get))
        assertEquals("https://a.com", PolicyRules.keyArg("open_url", get))
        assertEquals("hi", PolicyRules.keyArg("input_text", get))
        assertEquals("", PolicyRules.keyArg("press_key", get))
    }

    @Test fun hostOfStripsSchemeUserPortAndWww() {
        assertEquals("example.com", PolicyRules.hostOf("https://user@www.Example.com:8443/path?q#f"))
        assertEquals("a.b.c", PolicyRules.hostOf("a.b.c/x"))
    }

    @Test fun sensitivePages() {
        assertTrue(PolicyRules.isSensitivePage("https://www.alipay.com/x", ""))
        assertTrue(PolicyRules.isSensitivePage("https://m.alipay.com/x", ""))
        assertTrue(PolicyRules.isSensitivePage("https://shop.example.com/checkout?id=1", ""))
        assertTrue(PolicyRules.isSensitivePage("https://example.com/account/security", ""))
        assertTrue(PolicyRules.isSensitivePage("https://example.com/x", "收银台"))
        assertFalse(PolicyRules.isSensitivePage("https://example.com/blog/post", "博客"))
    }

    @Test fun paymentTextAndProtectedPackages() {
        assertTrue(PolicyRules.isPaymentText("请输入支付密码"))
        assertFalse(PolicyRules.isPaymentText("发送消息"))
        val list = listOf("com.eg.android.AlipayGphone", " ")
        assertTrue(PolicyRules.isProtectedPackage("com.eg.android.AlipayGphone", list))
        assertFalse(PolicyRules.isProtectedPackage("com.eg.android", list))
        assertFalse(PolicyRules.isProtectedPackage("", list))
    }
}
