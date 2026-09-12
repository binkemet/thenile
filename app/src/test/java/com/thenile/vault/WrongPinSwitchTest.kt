package com.thenile.vault

import com.thenile.vault.root.WrongPinSwitch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WrongPinSwitchTest {

    @Test
    fun belowLimit_notExpired() {
        assertFalse(WrongPinSwitch.isExpired(attempts = 4, limit = 5))
    }

    @Test
    fun atLimit_isExpired() {
        assertTrue(WrongPinSwitch.isExpired(attempts = 5, limit = 5))
    }

    @Test
    fun pastLimit_isExpired() {
        assertTrue(WrongPinSwitch.isExpired(attempts = 9, limit = 5))
    }

    @Test
    fun zeroLimit_disabled_neverExpires() {
        assertFalse(WrongPinSwitch.isExpired(attempts = 100, limit = 0))
    }
}
