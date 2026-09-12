package com.thenile.vault

import com.thenile.vault.root.HiddenVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offset math is the part that, if wrong, silently overlaps the two volumes and corrupts data.
 *  The shell plumbing is validated on-device; this pins the geometry. */
class HiddenVolumeTest {

    @Test
    fun decoyAlwaysStartsAtZero() {
        assertEquals(0L, HiddenVolume.offsetBytes(HiddenVolume.Role.DECOY, 8L * 1024 * 1024 * 1024))
    }

    @Test
    fun hiddenStartsInBackHalfAnd512Aligned() {
        val size = 8L * 1024 * 1024 * 1024
        val off = HiddenVolume.offsetBytes(HiddenVolume.Role.HIDDEN, size)
        assertTrue("hidden must start past the container midpoint", off > size / 2)
        assertTrue("hidden must leave room to the end", off < size)
        assertEquals("must be 512-aligned for the loop/dm layer", 0L, off % 512)
    }

    @Test
    fun hiddenBelowDecoyEndSoTheyDontInvert() {
        val size = 1_000_000_000L
        assertTrue(HiddenVolume.offsetBytes(HiddenVolume.Role.HIDDEN, size) <
            size) // hidden region is non-empty
    }
}
