package com.thenile.vault

import com.thenile.vault.root.ScreenOffSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenOffSwitchTest {

    @Test
    fun positiveTimeout_convertsToMillis() {
        assertEquals(5 * 60 * 1000L, ScreenOffSwitch.timeoutMillis(5))
        assertEquals(60 * 1000L, ScreenOffSwitch.timeoutMillis(1))
    }

    /** A 0/negative timeout must disable the switch, not fire the instant the screen goes off. */
    @Test
    fun zeroOrNegativeTimeout_disables() {
        assertNull(ScreenOffSwitch.timeoutMillis(0))
        assertNull(ScreenOffSwitch.timeoutMillis(-1))
    }
}
