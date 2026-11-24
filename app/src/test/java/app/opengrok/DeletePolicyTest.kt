package app.opengrok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** v0.7.0 fs_delete protection (FsTools.kt, pure). */
class DeletePolicyTest {
    @Test fun normalizeResolvesDotsAndSlashes() {
        assertEquals("/sdcard/DCIM", DeletePolicy.normalize("//sdcard/./x/../DCIM/"))
        assertEquals("/", DeletePolicy.normalize("/../.."))
    }

    @Test fun refusesRootsSystemTreesStorageRootsAndTheirChildren() {
        for (p in listOf("/", "/system", "/system/app/x.apk", "/data", "/data/data", "/data/data/com.x",
                "/sdcard", "/sdcard/", "/storage/emulated/0", "/sdcard/DCIM", "/storage/emulated/0/Download",
                "/sdcard/a/../DCIM", "relative/path", "")) {
            assertNotNull(p, DeletePolicy.refusal(p))
        }
    }

    @Test fun allowsConcreteFilesAndSubfolders() {
        assertNull(DeletePolicy.refusal("/sdcard/Download/old.zip"))
        assertNull(DeletePolicy.refusal("/sdcard/DCIM/Camera/IMG_1.jpg"))
        assertNull(DeletePolicy.refusal("/storage/emulated/0/Documents/notes"))
    }
}
