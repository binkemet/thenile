package com.thenile.vault

import com.thenile.vault.root.ConfigCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCryptoTest {

    @Test
    fun roundTrips() {
        val json = """{"decoyCodes":["1234","4321"],"vaults":[{"name":"Work","decoyPin":"9999"}]}"""
        val blob = ConfigCrypto.encrypt(json)
        assertEquals(json, ConfigCrypto.decrypt(blob))
    }

    @Test
    fun encryptedBlobIsNotPlaintext() {
        val json = """{"decoyPin":"9999","name":"Secret Work"}"""
        val blob = ConfigCrypto.encrypt(json)
        assertNotEquals(json, blob)
        assertTrue("must be tagged with the format magic", blob.startsWith("NILEC1:"))
        assertTrue("decoy pin must not appear in the blob", !blob.contains("9999"))
        assertTrue("vault name must not appear in the blob", !blob.contains("Secret Work"))
    }

    @Test
    fun eachEncryptionUsesFreshIv() {
        val json = """{"a":1}"""
        assertNotEquals("random IV per call means identical plaintext -> different blobs",
            ConfigCrypto.encrypt(json), ConfigCrypto.encrypt(json))
    }

    /** Reads of an older, un-encrypted config must still work (backward compatibility). */
    @Test
    fun legacyPlaintextPassesThroughUnchanged() {
        val json = """{"decoyLockScreenMode":"off"}"""
        assertEquals(json, ConfigCrypto.decrypt(json))
    }
}
