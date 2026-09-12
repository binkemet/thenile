package com.thenile.vault

import com.thenile.vault.root.DeadManSwitch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadManSwitchTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun neverUnlocked_notExpired() {
        assertFalse(DeadManSwitch.isExpired(lastUnlockMillis = 0L, nowMillis = 100 * hour, thresholdHours = 1))
    }

    @Test
    fun withinWindow_notExpired() {
        assertFalse(DeadManSwitch.isExpired(lastUnlockMillis = 0L + hour, nowMillis = hour + 71 * hour, thresholdHours = 72))
    }

    @Test
    fun pastWindow_isExpired() {
        assertTrue(DeadManSwitch.isExpired(lastUnlockMillis = hour, nowMillis = hour + 73 * hour, thresholdHours = 72))
    }

    @Test
    fun exactlyAtThreshold_isExpired() {
        assertTrue(DeadManSwitch.isExpired(lastUnlockMillis = hour, nowMillis = hour + 72 * hour, thresholdHours = 72))
    }
}
