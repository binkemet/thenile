package com.thenile.vault

import com.thenile.vault.root.GeofenceSwitch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceSwitchTest {

    // Safe zone centre (arbitrary point).
    private val cLat = 48.8566
    private val cLon = 2.3522

    @Test
    fun sameSpot_isInside() {
        assertFalse(GeofenceSwitch.isOutsideRadius(cLat, cLon, cLat, cLon, 200))
    }

    @Test
    fun pointWellOutside_isOutside() {
        // ~1.1km east — clearly outside a 200m radius.
        assertTrue(GeofenceSwitch.isOutsideRadius(cLat, cLon + 0.015, cLat, cLon, 200))
    }

    @Test
    fun pointJustInside_isInside() {
        // ~11m north (0.0001 deg lat ~= 11m) — inside a 200m radius.
        assertFalse(GeofenceSwitch.isOutsideRadius(cLat + 0.0001, cLon, cLat, cLon, 200))
    }

    @Test
    fun largerRadiusContainsFartherPoint() {
        // ~1.1km point is outside 200m but inside 2km.
        assertTrue(GeofenceSwitch.isOutsideRadius(cLat, cLon + 0.015, cLat, cLon, 200))
        assertFalse(GeofenceSwitch.isOutsideRadius(cLat, cLon + 0.015, cLat, cLon, 2000))
    }
}
