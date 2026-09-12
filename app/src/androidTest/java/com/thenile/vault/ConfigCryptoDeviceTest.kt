package com.thenile.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thenile.vault.root.ConfigCrypto
import com.thenile.vault.state.SettingsManager
import com.thenile.vault.state.Vault
import com.topjohnwu.superuser.Shell
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the real syncToSystem() write path produces an ENCRYPTED /data/system/thenile_config.json
 *  (not plaintext decoy PINs) and that it decrypts back to valid JSON with the hook's shared key.
 *  Needs root (the write goes through a root shell). Restores the original vaults afterward. */
@RunWith(AndroidJUnit4::class)
class ConfigCryptoDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val path = "/data/system/thenile_config.json"

    @Test
    fun syncWritesEncryptedConfig() {
        assumeTrue("needs root", Shell.getShell().isRoot)
        val settings = SettingsManager.getInstance(ctx)
        val original = settings.vaults
        try {
            settings.vaults = listOf(
                Vault(id = "cfgtest", name = "CfgProbeVault", decoyPin = "9998", decoyDialerCode = "7777")
            )
            settings.syncToSystem()

            // syncToSystem writes on a background thread — poll for the file to appear/update.
            var raw: String? = null
            for (i in 0 until 50) {
                val r = Shell.cmd("cat $path").exec()
                if (r.isSuccess && r.out.isNotEmpty()) { raw = r.out.joinToString("\n"); if (raw!!.startsWith("NILEC1:")) break }
                Thread.sleep(100)
            }
            assertTrue("config should exist after sync", raw != null)
            assertTrue("config must be encrypted (NILEC1 magic)", raw!!.startsWith("NILEC1:"))
            assertFalse("decoy pin must not appear in plaintext on disk", raw!!.contains("9998"))
            assertFalse("vault name must not appear in plaintext on disk", raw!!.contains("CfgProbeVault"))

            // The hook's shared key must decrypt it back to the real config.
            val decoded = ConfigCrypto.decrypt(raw!!)
            assertTrue("decrypted config must contain the decoy pin", decoded.contains("9998"))
            assertTrue("decrypted config must be JSON", decoded.trimStart().startsWith("{"))
        } finally {
            settings.vaults = original
            settings.syncToSystem()
        }
    }
}
