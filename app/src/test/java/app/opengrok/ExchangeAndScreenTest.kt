package app.opengrok

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v0.7.0 project exchange digest and screen-change hashing (pure parts). */
class ExchangeAndScreenTest {

    @Test fun exchangeDigestListsFilesAndPreviewsText() {
        val dir = Files.createTempDirectory("exch").toFile()
        try {
            File(dir, "report.md").writeText("# 报告\n结论:完成")
            File(dir, "big.txt").writeText("a".repeat(5_000))
            File(dir, "blob.bin").writeBytes(byteArrayOf(1, 0, 2))
            File(dir, "sub").mkdirs()
            File(dir, "sub/notes.txt").writeText("key sk-abcdefghijklmnop1234")
            val d = Pipeline.exchangeDigest(dir)
            assertTrue(d.contains("/exchange/report.md"))
            assertTrue(d.contains("/exchange/blob.bin"))
            assertTrue(d.contains("/exchange/sub/notes.txt"))
            assertTrue(d.contains("结论:完成"))
            assertFalse("binary files are not previewed", d.contains("预览 /exchange/blob.bin"))
            assertFalse("previews are capped at 2 KB", d.contains("a".repeat(2_100)))
            assertFalse("previews pass the leak guard", d.contains("sk-abcdefghijklmnop1234"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun exchangeDigestSkipsSymlinksOutOfTheFolder() {
        val dir = Files.createTempDirectory("exch").toFile()
        val outside = Files.createTempFile("secret", ".txt").toFile()
        try {
            outside.writeText("TOP-SECRET-CONTENT")
            val linked = try {
                Files.createSymbolicLink(File(dir, "link.txt").toPath(), outside.toPath()); true
            } catch (_: Exception) { false }
            val d = Pipeline.exchangeDigest(dir)
            if (linked) assertFalse(d.contains("TOP-SECRET-CONTENT"))
        } finally {
            dir.deleteRecursively()
            outside.delete()
        }
    }

    @Test fun exchangeDigestOfMissingOrEmptyFolder() {
        assertTrue(Pipeline.exchangeDigest(File("/nonexistent/bothive-test")).contains("空"))
        val dir = Files.createTempDirectory("exch").toFile()
        try {
            assertTrue(Pipeline.exchangeDigest(dir).contains("空"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun previewCountIsLimited() {
        val dir = Files.createTempDirectory("exch").toFile()
        try {
            for (i in 1..12) File(dir, "f$i.txt").writeText("content $i")
            val d = Pipeline.exchangeDigest(dir)
            assertEquals(8, Regex("--- 预览 ").findAll(d).count())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun centerCropIsTheMiddleRegion() {
        assertArrayEquals(intArrayOf(10, 15, 80, 70), ScreenHash.centerCrop(100, 100))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), ScreenHash.centerCrop(0, 100))
        val r = ScreenHash.centerCrop(1080, 2400)
        assertTrue(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= 1080 && r[1] + r[3] <= 2400)
    }

    @Test fun dHashSeesChangesAndToleratesNone() {
        val a = IntArray(72) { (it * 37) % 255 }
        val b = a.copyOf()
        assertEquals(0, ScreenHash.distance(ScreenHash.dHash(a), ScreenHash.dHash(b)))
        val c = IntArray(72) { 255 - a[it] }
        assertTrue(ScreenHash.distance(ScreenHash.dHash(a), ScreenHash.dHash(c)) > ScreenHash.SAME_THRESHOLD)
    }
}
