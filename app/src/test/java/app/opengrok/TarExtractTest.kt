package app.opengrok

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TarExtractTest {
    private class TarBuilder {
        private val out = ByteArrayOutputStream()

        private fun put(h: ByteArray, off: Int, s: String, len: Int) {
            val b = s.toByteArray(Charsets.UTF_8)
            System.arraycopy(b, 0, h, off, minOf(b.size, len))
        }

        private fun header(name: String, type: Char, size: Long, mode: Int, link: String): ByteArray {
            val h = ByteArray(512)
            put(h, 0, name, 100)
            put(h, 100, "%07o".format(mode), 8)
            put(h, 124, "%011o".format(size), 12)
            h[156] = type.code.toByte()
            put(h, 157, link, 100)
            put(h, 257, "ustar", 6)
            h[263] = '0'.code.toByte()
            h[264] = '0'.code.toByte()
            return h
        }

        private fun data(bytes: ByteArray) {
            out.write(bytes)
            out.write(ByteArray((512 - bytes.size % 512) % 512))
        }

        fun dir(name: String) = apply { out.write(header(name, '5', 0, 493, "")) }

        fun file(name: String, content: ByteArray, mode: Int = 420) = apply {
            out.write(header(name, '0', content.size.toLong(), mode, ""))
            data(content)
        }

        fun symlink(name: String, target: String) = apply { out.write(header(name, '2', 0, 511, target)) }

        fun longName(name: String, content: ByteArray) = apply {
            val nb = (name + "\u0000").toByteArray(Charsets.UTF_8)
            out.write(header("././@LongLink", 'L', nb.size.toLong(), 420, ""))
            data(nb)
            out.write(header(name.take(100), '0', content.size.toLong(), 420, ""))
            data(content)
        }

        fun bytes(): ByteArray = out.toByteArray() + ByteArray(1024)
    }

    private fun tmp(): File = Files.createTempDirectory("og-tar-").toFile()

    @Test
    fun absoluteSymlinkInsideRootfsResolvesInGuest() {
        val tar = TarBuilder()
            .dir("bin/")
            .file("bin/busybox", "BB".repeat(300).toByteArray(), 493)
            .symlink("bin/sh", "/bin/busybox")
            .bytes()
        val gz = ByteArrayOutputStream().also { bos -> GZIPOutputStream(bos).use { it.write(tar) } }.toByteArray()
        val dir = tmp()
        val archive = File(dir, "a.tar.gz")
        archive.writeBytes(gz)
        val root = File(dir, "root")
        val res = TarExtract.extractTarGz(archive, root)
        assertEquals(1, res.symlinks)
        assertTrue(Files.isSymbolicLink(File(root, "bin/sh").toPath()))
        val resolved = TarExtract.resolveInGuest(root, "bin/sh")
        assertNotNull(resolved)
        assertEquals(File(root, "bin/busybox").canonicalFile, resolved!!.canonicalFile)
        assertTrue(resolved.isFile)
        TarExtract.deleteTree(dir)
    }

    @Test
    fun rejectsPathTraversal() {
        val dir = tmp()
        try {
            TarExtract.extract(ByteArrayInputStream(TarBuilder().file("../evil", "x".toByteArray()).bytes()), File(dir, "r"))
            fail("expected TarException")
        } catch (_: TarException) {
        }
        assertFalse(File(dir, "evil").exists())
        TarExtract.deleteTree(dir)
    }

    @Test
    fun skipsWriteThroughEscapingSymlink() {
        val dir = tmp()
        val outside = File(dir, "outside").apply { mkdirs() }
        val tar = TarBuilder()
            .symlink("evil", outside.absolutePath)
            .file("evil/pwned.txt", "x".toByteArray())
            .bytes()
        val res = TarExtract.extract(ByteArrayInputStream(tar), File(dir, "root"))
        assertFalse(File(outside, "pwned.txt").exists())
        assertEquals(1, res.skipped.size)
        TarExtract.deleteTree(dir)
    }

    @Test
    fun handlesGnuLongNames() {
        val name = "d/" + "x".repeat(150) + "/f.txt"
        val dir = tmp()
        val root = File(dir, "r")
        TarExtract.extract(ByteArrayInputStream(TarBuilder().longName(name, "hi".toByteArray()).bytes()), root)
        assertEquals("hi", File(root, name).readText())
        TarExtract.deleteTree(dir)
    }

    @Test
    fun detectsTruncatedArchive() {
        val full = TarBuilder().file("a.bin", ByteArray(5000)).bytes()
        val cut = full.copyOf(512 + 700)
        val dir = tmp()
        try {
            TarExtract.extract(ByteArrayInputStream(cut), File(dir, "r"))
            fail("expected TarException")
        } catch (_: TarException) {
        }
        TarExtract.deleteTree(dir)
    }

    @Test
    fun danglingLinkDoesNotResolve() {
        val dir = tmp()
        File(dir, "bin").mkdirs()
        Files.createSymbolicLink(File(dir, "bin/sh").toPath(), Paths.get("/bin/definitely-missing"))
        assertNull(TarExtract.resolveInGuest(dir, "bin/sh"))
        TarExtract.deleteTree(dir)
    }

    @Test
    fun deleteTreeDoesNotFollowSymlinks() {
        val dir = tmp()
        val outside = File(dir, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("k")
        val victim = File(dir, "victim").apply { mkdirs() }
        Files.createSymbolicLink(File(victim, "link").toPath(), outside.toPath())
        TarExtract.deleteTree(victim)
        assertFalse(victim.exists())
        assertTrue(File(outside, "keep.txt").exists())
        TarExtract.deleteTree(dir)
    }
}
