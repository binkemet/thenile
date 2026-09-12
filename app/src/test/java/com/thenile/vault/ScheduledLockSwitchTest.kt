package com.thenile.vault

import com.thenile.vault.root.ScheduledLockSwitch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledLockSwitchTest {

    @Test
    fun sameDayWindow_insideAndOutside() {
        // 09:00-17:00
        assertTrue(ScheduledLockSwitch.isInWindow(nowMinute = 12 * 60, startMinute = 9 * 60, endMinute = 17 * 60))
        assertFalse(ScheduledLockSwitch.isInWindow(nowMinute = 8 * 60, startMinute = 9 * 60, endMinute = 17 * 60))
        assertFalse(ScheduledLockSwitch.isInWindow(nowMinute = 17 * 60, startMinute = 9 * 60, endMinute = 17 * 60))
    }

    @Test
    fun overnightWindow_wrapsPastMidnight() {
        // 23:00-06:00
        assertTrue(ScheduledLockSwitch.isInWindow(nowMinute = 23 * 60 + 30, startMinute = 23 * 60, endMinute = 6 * 60))
        assertTrue(ScheduledLockSwitch.isInWindow(nowMinute = 3 * 60, startMinute = 23 * 60, endMinute = 6 * 60))
        assertFalse(ScheduledLockSwitch.isInWindow(nowMinute = 12 * 60, startMinute = 23 * 60, endMinute = 6 * 60))
    }

    @Test
    fun equalStartAndEnd_neverInWindow() {
        assertFalse(ScheduledLockSwitch.isInWindow(nowMinute = 0, startMinute = 12 * 60, endMinute = 12 * 60))
        assertFalse(ScheduledLockSwitch.isInWindow(nowMinute = 12 * 60, startMinute = 12 * 60, endMinute = 12 * 60))
    }
}
