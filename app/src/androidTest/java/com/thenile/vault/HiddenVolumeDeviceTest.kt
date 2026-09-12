package com.thenile.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thenile.vault.root.HiddenVolume
import com.thenile.vault.root.StorageMountManager
import com.thenile.vault.state.VaultStateManager
import com.topjohnwu.superuser.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On-device end-to-end for slice 3: runs the REAL HiddenVolume + shipped dm-crypt helper + JNI key
 *  derivation inside the app process. Needs a rooted device with root granted to the app. */
@RunWith(AndroidJUnit4::class)
class HiddenVolumeDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requireRootAndHelper() {
        assumeTrue("needs root", Shell.getShell().isRoot)
        StorageMountManager.dmcryptBin = "${ctx.applicationInfo.nativeLibraryDir}/libdmcrypt.so"
        assumeTrue("dm-crypt helper must ship",
            Shell.cmd("test -x ${StorageMountManager.dmcryptBin}").exec().isSuccess)
    }

    @Test
    fun jniEncryptDecryptRoundTrip() {
        assumeTrue("needs root", Shell.getShell().isRoot)
        val salt = VaultStateManager.getInstance(ctx).keySalt()
        // JNI runs as the app uid, so use app-owned paths it can actually read/write.
        val plain = java.io.File(ctx.cacheDir, "adv_plain")
        val enc = java.io.File(ctx.cacheDir, "adv_enc")
        val dec = java.io.File(ctx.cacheDir, "adv_dec")
        plain.writeText("the-nile-secret")
        assertEquals(true, StorageMountManager.encryptFileNative("8888", salt, plain.path, enc.path))
        assertEquals(true, StorageMountManager.decryptFileNative("8888", salt, enc.path, dec.path))
        assertEquals("the-nile-secret", dec.readText())
        plain.delete(); enc.delete(); dec.delete()
    }

    @Test
    fun hiddenVolumeTwoKeyRoundTripAndWrongKeyRejected() {
        requireRootAndHelper()
        val salt = VaultStateManager.getInstance(ctx).keySalt()
        try {
            // Capture side: mount+format the hidden volume with the real PIN, write a secret.
            val mp = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, "8888", salt, formatIfNeeded = true)
            assertNotNull("hidden mount+format should succeed", mp)
            Shell.cmd("echo REAL-SECRET > $mp/t.txt", "sync").exec()
            HiddenVolume.unmount(HiddenVolume.Role.HIDDEN)

            // Real PIN reopens it and reads the secret back.
            val mp2 = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, "8888", salt, formatIfNeeded = false)
            assertNotNull("correct key should reopen", mp2)
            assertEquals("REAL-SECRET", Shell.cmd("cat $mp2/t.txt").exec().out.firstOrNull())
            HiddenVolume.unmount(HiddenVolume.Role.HIDDEN)

            // Wrong PIN must NOT mount (never format on the open path) — the deniability property.
            val mp3 = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, "0000", salt, formatIfNeeded = false)
            assertNull("wrong key must be rejected", mp3)
        } finally {
            HiddenVolume.unmount(HiddenVolume.Role.HIDDEN)
            HiddenVolume.unmount(HiddenVolume.Role.DECOY)
            Shell.cmd("rm -f /data/system/.sysstore").exec()
        }
    }
}
