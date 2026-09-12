package com.thenile.vault

import com.thenile.vault.root.DeviceTamperSwitch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTamperSwitchTest {

    @Test
    fun noBaseline_neverCountsAsChange() {
        assertFalse(DeviceTamperSwitch.hasSimChanged(baseline = "", current = "8933150319"))
        assertFalse(DeviceTamperSwitch.hasSimChanged(baseline = "", current = null))
    }

    @Test
    fun sameSim_notChanged() {
        assertFalse(DeviceTamperSwitch.hasSimChanged(baseline = "8933150319", current = "8933150319"))
    }

    @Test
    fun swappedSim_isChanged() {
        assertTrue(DeviceTamperSwitch.hasSimChanged(baseline = "8933150319", current = "8944200077"))
    }

    /** Unreadable/absent SIM after a known one was recorded is the removal case — must fire. */
    @Test
    fun removedSim_isChanged() {
        assertTrue(DeviceTamperSwitch.hasSimChanged(baseline = "8933150319", current = null))
    }
}
