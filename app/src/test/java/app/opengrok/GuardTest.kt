package app.opengrok

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GuardTest {
    @Test
    fun allowsCleanupInsideBotWorkspace() {
        val root = Files.createTempDirectory("og-guard-").toFile()
        val workspace = File(root, "sandbox/bot").apply { mkdirs() }
        assertNull(Guard.check("rm -rf build", workspace.canonicalPath))
        root.deleteRecursively()
    }

    @Test
    fun blocksRelativeTraversalOutsideWorkspace() {
        val root = Files.createTempDirectory("og-guard-").toFile()
        val workspace = File(root, "sandbox/bot").apply { mkdirs() }
        assertNotNull(Guard.check("rm -rf ../state.json", workspace.canonicalPath))
        root.deleteRecursively()
    }

    @Test
    fun blocksAbsolutePathsOutsideWorkspace() {
        val root = Files.createTempDirectory("og-guard-").toFile()
        val workspace = File(root, "sandbox/bot").apply { mkdirs() }
        val outside = File(root, "private/state.json")
        assertNotNull(Guard.check("rm -rf ${outside.absolutePath}", workspace.canonicalPath))
        root.deleteRecursively()
    }

    @Test
    fun blocksSymlinkEscapeFromWorkspace() {
        val root = Files.createTempDirectory("og-guard-").toFile()
        val workspace = File(root, "sandbox/bot").apply { mkdirs() }
        val outside = File(root, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(workspace, "linked").toPath(), outside.toPath())
        assertNotNull(Guard.check("rm -rf linked", workspace.canonicalPath))
        root.deleteRecursively()
    }
}
