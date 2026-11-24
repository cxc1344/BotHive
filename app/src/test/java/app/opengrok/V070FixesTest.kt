package app.opengrok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the v0.7.0-alpha1 fixes that live in pure (Android-free) code. */
class V070FixesTest {

    // ---- model API retry policy (single key used to skip all retries) ----

    @Test fun singleKeyRateLimitBacksOffInsteadOfFailing() {
        assertEquals(RetryPolicy.Action.BACKOFF,
            RetryPolicy.decide("HTTP 429: slow down", isIo = true, hasMoreKeys = false, retriesSoFar = 0, hasImages = false))
    }

    @Test fun rateLimitGivesUpAfterMaxRetries() {
        assertEquals(RetryPolicy.Action.FAIL,
            RetryPolicy.decide("HTTP 429: slow down", true, false, RetryPolicy.MAX_RETRIES, false))
    }

    @Test fun rateLimitRotatesWhenAnotherKeyIsLeft() {
        assertEquals(RetryPolicy.Action.ROTATE_KEY, RetryPolicy.decide("HTTP 429: x", true, true, 0, false))
    }

    @Test fun gatewayAndNetworkErrorsRetry() {
        assertEquals(RetryPolicy.Action.BACKOFF, RetryPolicy.decide("HTTP 502: bad gateway", true, false, 0, false))
        assertEquals(RetryPolicy.Action.BACKOFF, RetryPolicy.decide("timeout", true, false, 2, false))
    }

    @Test fun clientErrorsAndParseErrorsFailFast() {
        assertEquals(RetryPolicy.Action.FAIL, RetryPolicy.decide("HTTP 400: bad request", true, false, 0, false))
        assertEquals(RetryPolicy.Action.FAIL, RetryPolicy.decide("JSONObject text must begin with '{'", false, false, 0, false))
    }

    @Test fun imageRejectionStripsImagesOnlyWhenImagesArePresent() {
        val msg = "HTTP 404: No endpoints found that support image input"
        assertEquals(RetryPolicy.Action.STRIP_IMAGES, RetryPolicy.decide(msg, true, false, 0, hasImages = true))
        assertEquals(RetryPolicy.Action.FAIL, RetryPolicy.decide(msg, true, false, 0, hasImages = false))
    }

    @Test fun revokedKeyRotatesToTheNextOne() {
        assertEquals(RetryPolicy.Action.ROTATE_KEY, RetryPolicy.decide("HTTP 401: invalid key", true, true, 0, false))
        assertEquals(RetryPolicy.Action.FAIL, RetryPolicy.decide("HTTP 401: invalid key", true, false, 0, false))
    }

    @Test fun backoffIsCapped() {
        assertEquals(1_200L, RetryPolicy.backoffMs(1, rateLimited = true))
        assertEquals(5_000L, RetryPolicy.backoffMs(9, rateLimited = false))
    }

    // ---- key pool (multi-line keys were sent as one Authorization header) ----

    @Test fun keyPoolSplitsLinesAndNeverReturnsEmpty() {
        assertEquals(listOf("sk-a", "sk-b"), RetryPolicy.parseKeyPool("  sk-a \n\n sk-b \n"))
        assertEquals(listOf(""), RetryPolicy.parseKeyPool(""))
        assertEquals("sk-a", RetryPolicy.firstKey("sk-a\nsk-b"))
    }

    // ---- Store: state read by load() must be initialised before `init` runs ----

    @Test fun storeStateIsDeclaredBeforeInit() {
        // Kotlin runs property initialisers and init blocks in source order. A field that load()
        // touches but that is declared below `init` is still null during load() (v0.6.x `pinned`).
        val src = java.io.File("src/main/java/app/opengrok/Store.kt").readText()
        val initAt = src.indexOf("\n    init {")
        assertTrue("init block not found in Store.kt", initAt > 0)
        for (field in listOf(
            "val pinned", "private val jobs", "private val undecryptable", "val loadNotice", "private val lists",
            // v0.7.0: also read by load() / used by save() during init.
            "val chiefId", "private var lastAppOpenMs", "private val msgJsonCache", "private val chatJsonCache"
        )) {
            val at = src.indexOf(field)
            assertTrue("$field must be declared before init in Store.kt", at in 0 until initAt)
        }
    }

    // ---- MCP transport policy ----

    @Test fun mcpAcceptsHttpsAndLocalHttpOnly() {
        Mcp.checkUrl("https://mcp.example.com/mcp")
        Mcp.checkUrl("http://localhost:3000/mcp")
        Mcp.checkUrl("http://127.0.0.1:8080/mcp")
        Mcp.checkUrl("http://192.168.1.20:8080/mcp")
        Mcp.checkUrl("http://172.20.0.5/mcp")
        assertThrows(IllegalArgumentException::class.java) { Mcp.checkUrl("http://mcp.example.com/mcp") }
        assertThrows(IllegalArgumentException::class.java) { Mcp.checkUrl("http://172.32.0.1/mcp") }
        assertThrows(IllegalArgumentException::class.java) { Mcp.checkUrl("https://user:pw@mcp.example.com/") }
    }

    @Test fun mcpBackoffGrowsAndCaps() {
        assertEquals(30_000L, Mcp.backoffMs(1))
        assertEquals(60_000L, Mcp.backoffMs(2))
        assertEquals(15 * 60_000L, Mcp.backoffMs(20))
    }
}
