package com.thenile.vault

import com.thenile.vault.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    @Test
    fun testTargetPackages() {
        assertTrue(Config.TARGET_PACKAGES.isEmpty())
    }

    @Test
    fun testHiddenPackagesIncludesSelfAndTargets() {
        assertTrue(Config.HIDDEN_PACKAGES.contains("com.thenile.vault"))
    }

    @Test
    fun testSecretCodes() {
        assertEquals("1111", Config.CODE_LOCK)
        assertEquals("1234", Config.CODE_DECOY)
        assertEquals("9876", Config.CODE_UNLOCK)
        assertEquals("0000", Config.CODE_DURESS)
        assertEquals(4, Config.KNOWN_CODES.size)
    }
}
