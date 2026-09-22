package com.thenile.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thenile.vault.root.HiddenVolume
import com.thenile.vault.root.StorageMountManager
import com.thenile.vault.state.VaultStateManager
import com.topjohnwu.superuser.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** OTG off-device flow: export the container to a file, wipe the on-device one, import it back,
     *  and confirm the hidden volume still opens with the real PIN — i.e. export/import preserves the
     *  exact ciphertext. Uses a plain cache file in place of a real OTG SAF stream. */
    @Test
    fun containerExportImportRoundTrip() {
        requireRootAndHelper()
        val salt = VaultStateManager.getInstance(ctx).keySalt()
        val backup = java.io.File(ctx.cacheDir, "sysstore.bak")
        try {
            // Create + write a secret into the hidden volume.
            val mp = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, "8888", salt, formatIfNeeded = true)
            assertNotNull(mp)
            Shell.cmd("echo OTG-SECRET > $mp/t.txt", "sync").exec()
            HiddenVolume.unmount(HiddenVolume.Role.HIDDEN)

            // Export off-device, then destroy the on-device container entirely.
            assertTrue("export should succeed", backup.outputStream().use { HiddenVolume.exportContainer(it) })
            assertTrue("backup file should be non-empty", backup.length() > 0)
            assertTrue("destroy should remove the container", HiddenVolume.destroy())
            assertFalse("container should be gone after destroy", HiddenVolume.containerExists())

            // Import it back and reopen with the real PIN.
            assertTrue("import should succeed", backup.inputStream().use { HiddenVolume.importContainer(it) })
            val mp2 = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, "8888", salt, formatIfNeeded = false)
            assertNotNull("restored container should reopen with the real PIN", mp2)
            assertEquals("OTG-SECRET", Shell.cmd("cat $mp2/t.txt").exec().out.firstOrNull())
        } finally {
            HiddenVolume.unmount(HiddenVolume.Role.HIDDEN)
            HiddenVolume.unmount(HiddenVolume.Role.DECOY)
            backup.delete()
            Shell.cmd("rm -f /data/system/.sysstore").exec()
        }
    }

    /** Deniability: creating the container random-fills it, so the raw file has no field of zeros for
     *  a hidden volume to stand out against. A zero/sparse (pre-fill) container fails this. */
    @Test
    fun containerIsRandomFilledNotZeros() {
        requireRootAndHelper()
        val salt = VaultStateManager.getInstance(ctx).keySalt()
        val path = "/data/system/.sysstore"
        try {
            // First mount creates + random-fills the container.
            assertNotNull(HiddenVolume.mount(HiddenVolume.Role.HIDDEN, "8888", salt, formatIfNeeded = true))
            HiddenVolume.unmount(HiddenVolume.Role.HIDDEN)
            val size = Shell.cmd("stat -c %s $path").exec().out.firstOrNull()?.trim()?.toLong() ?: 0L
            assertTrue("container should exist with size", size > 0)
            // Sample raw sectors (bypassing dm-crypt) at three offsets, including a mid region the
            // decoy fs never writes — each must contain a non-zero byte.
            listOf(size * 10 / 100, size * 50 / 100, size * 90 / 100).forEach { off ->
                val sector = off - (off % 512)
                val hex = Shell.cmd("dd if=$path bs=512 count=1 skip=${sector / 512} 2>/dev/null | od -An -tx1 | tr -d ' \\n'")
                    .exec().out.joinToString("")
                assertTrue("raw sector at $sector must be random, not zeros", hex.any { it != '0' })
            }
        } finally {
            HiddenVolume.unmount(HiddenVolume.Role.HIDDEN)
            HiddenVolume.unmount(HiddenVolume.Role.DECOY)
            Shell.cmd("rm -f $path").exec()
        }
    }
}
