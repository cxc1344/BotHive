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
}
