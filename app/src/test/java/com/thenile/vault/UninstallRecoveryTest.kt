package com.thenile.vault

import com.thenile.vault.root.ConfigCrypto
import com.thenile.vault.state.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The survive-uninstall recovery contract: what syncToSystem writes into the /data/system config
 *  is exactly what parseRecoveryConfig pulls back out on reinstall. Guards the key names + the
 *  encrypt→decrypt round-trip that hidden-data recovery depends on. */
class UninstallRecoveryTest {

    private val vaultsFull = """[{"id":"a","name":"Work","decoyPin":"1234","selfDestruct":true}]"""

    private fun config(vf: String) = ConfigCrypto.encrypt(
        """{"vaultsFull":${org.json.JSONObject.quote(vf)},"keySalt":"deadbeef",""" +
            """"codeUnlock":"9876","codeLock":"1111","keepContainerOnUninstall":false}"""
    )

    @Test
    fun recoversVaultsSaltAndFlags() {
        val rec = SettingsManager.parseRecoveryConfig(config(vaultsFull))!!
        assertEquals(vaultsFull, rec.vaultsFull)
        assertEquals("deadbeef", rec.keySalt)
        assertEquals("9876", rec.codes["codeUnlock"])
        assertEquals("1111", rec.codes["codeLock"])
        assertEquals(false, rec.keepContainerOnUninstall)
    }

    @Test
    fun emptyVaultsIsNothingToRestore() {
        assertNull(SettingsManager.parseRecoveryConfig(config("[]")))
    }
}
