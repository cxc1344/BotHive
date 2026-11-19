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

    @Test
    fun allowsGuestWorkspacePathAndRelativeCd() {
        val root = Files.createTempDirectory("og-guard-").toFile()
        val workspace = File(root, "sandbox/bot").apply { mkdirs() }
        File(workspace, "sub").mkdirs()
        assertNull(Guard.check("rm -rf /workspace/tmp", workspace.canonicalPath))
        assertNull(Guard.check("cd sub && ls", workspace.canonicalPath))
        assertNull(Guard.check("cd /workspace/sub && ls", workspace.canonicalPath))
        assertNotNull(Guard.check("cd .. && ls", workspace.canonicalPath))
        assertNotNull(Guard.check("cd /etc && ls", workspace.canonicalPath))
        root.deleteRecursively()
    }
}
