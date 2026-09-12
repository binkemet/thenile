package com.thenile.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thenile.vault.root.SoftVault
import com.thenile.vault.state.VaultStateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** On-device round trip for the no-root fallback path: real Context, real noBackupFilesDir,
 *  real encryptFileNative/decryptFileNative. Needs no root/Shizuku. */
@RunWith(AndroidJUnit4::class)
class SoftVaultDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun hideThenUnhideRestoresFileExactly() {
        val salt = VaultStateManager.getInstance(ctx).keySalt()
        val src = File(ctx.cacheDir, "softvault_src.txt")
        src.writeText("plaintext-secret")

        val hidOk = SoftVault.hide(ctx, "1234", salt, directories = emptyList(), files = listOf(src.absolutePath))
        assertTrue("hide should succeed", hidOk)
        assertFalse("plaintext must be gone after hide", src.exists())

        val unhidOk = SoftVault.unhide(ctx, "1234", salt, directories = emptyList(), files = listOf(src.absolutePath))
        assertTrue("unhide should succeed", unhidOk)
        assertTrue("file should be restored", src.exists())
        assertEquals("plaintext-secret", src.readText())

        src.delete()
    }
}
