package app.opengrok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotArgsTest {
    private val args = ProotArgs.build("/n/libproot.so", "/r", "/w", "echo hello; uname -a")

    @Test
    fun hasNoDoubleDashSeparator() {
        assertFalse(args.contains("--"))
    }

    @Test
    fun guestCommandComesAfterAllProotOptions() {
        val sh = args.indexOf("/bin/sh")
        assertTrue(sh > args.indexOf("-w"))
        assertEquals(listOf("/bin/sh", "-lc", "echo hello; uname -a"), args.subList(sh, args.size))
    }

    @Test
    fun bindsWorkspaceIntoGuest() {
        assertTrue(args.contains("/w:/workspace"))
        assertEquals("/r", args[args.indexOf("-r") + 1])
    }

    @Test
    fun extraBindsAreAddedBeforeTheGuestCommand() {
        val a = ProotArgs.build("/n/libproot.so", "/r", "/w", "ls", listOf("/data/x/exchange/p1" to "/exchange/"))
        val i = a.indexOf("/data/x/exchange/p1:/exchange")
        assertTrue(i > 0)
        assertEquals("-b", a[i - 1])
        assertTrue(i < a.indexOf("/bin/sh"))
    }

    @Test
    fun unsafeExtraBindsAreDropped() {
        val unsafe = listOf(
            "/h" to "/workspace", "/h" to "/", "/h" to "/etc", "/h" to "/usr/",
            "rel" to "/exchange", "/h" to "exchange", "/a:b" to "/exchange", "/h" to "/ex:change",
            "/h/../x" to "/exchange", "/h" to "/exchange/../etc", "" to "/exchange"
        )
        for ((host, guest) in unsafe) {
            assertFalse("$host -> $guest", ProotArgs.isSafeBind(host, guest))
        }
        val a = ProotArgs.build("/n/libproot.so", "/r", "/w", "ls", unsafe)
        assertEquals(ProotArgs.build("/n/libproot.so", "/r", "/w", "ls"), a)
        assertTrue(ProotArgs.isSafeBind("/data/x/exchange/p1", "/exchange"))
    }
}
