package com.thenile.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thenile.vault.root.DecoyFileStore
import com.thenile.vault.root.HiddenVolume
import com.thenile.vault.root.StorageMountManager
import com.topjohnwu.superuser.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device round-trip for the decoy file store: import, list, delete — same rooted-device
 *  requirement as HiddenVolumeDeviceTest, which this mirrors. */
@RunWith(AndroidJUnit4::class)
class DecoyFileStoreDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val pw = "test-decoy-password"

    private fun requireRootAndHelper() {
        assumeTrue("needs root", Shell.getShell().isRoot)
        StorageMountManager.dmcryptBin = "${ctx.applicationInfo.nativeLibraryDir}/libdmcrypt.so"
        assumeTrue("dm-crypt helper must ship",
            Shell.cmd("test -x ${StorageMountManager.dmcryptBin}").exec().isSuccess)
    }

    @Test
    fun importListDeleteRoundTrip() {
        requireRootAndHelper()
        try {
            val count = DecoyFileStore.importFiles(ctx, pw, listOf("hello.txt" to "hello world".byteInputStream()))
            assertEquals(1, count)

            val listed = DecoyFileStore.listFiles(ctx, pw)
            assertTrue("imported file should be listed", listed.any { it.first == "hello.txt" })
            assertEquals(11L, listed.first { it.first == "hello.txt" }.second)

            assertTrue("delete should succeed", DecoyFileStore.deleteFile(ctx, pw, "hello.txt"))
            assertFalse("deleted file should no longer be listed", DecoyFileStore.listFiles(ctx, pw).any { it.first == "hello.txt" })
        } finally {
            HiddenVolume.unmount(HiddenVolume.Role.DECOY)
            HiddenVolume.unmount(HiddenVolume.Role.HIDDEN)
            Shell.cmd("rm -f /data/system/.sysstore").exec()
        }
    }
}
