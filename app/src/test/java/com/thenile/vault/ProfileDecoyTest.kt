package com.thenile.vault

import com.thenile.vault.state.Profile
import com.thenile.vault.state.DummyDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDecoyTest {

    @Test
    fun testProfileDecoyPinMatching() {
        val prof1 = Profile(
            id = "p1",
            name = "Work",
            packages = listOf("com.work.app"),
            directories = listOf("/sdcard/work"),
            dummyDirectories = emptyList(),
            isActive = true,
            hideOnDecoy = true,
            decoyPin = "1234"
        )
        val prof2 = Profile(
            id = "p2",
            name = "Personal",
            packages = listOf("com.personal.app"),
            directories = listOf("/sdcard/personal"),
            dummyDirectories = emptyList(),
            isActive = true,
            hideOnDecoy = true,
            decoyPin = "5678"
        )

        assertEquals("1234", prof1.decoyPin)
        assertEquals("5678", prof2.decoyPin)
    }

    @Test
    fun testDecoyPinMatchingForSpecificCode() {
        val prof1 = Profile("p1", "Work", listOf("com.work.app"), listOf("/sdcard/work"), emptyList(), isActive = true, decoyPin = "1234")
        val prof2 = Profile("p2", "Personal", listOf("com.personal.app"), listOf("/sdcard/personal"), emptyList(), isActive = true, decoyPin = "5678")
        val profiles = listOf(prof1, prof2)
        val codeDecoy = "1234"

        // Specific PIN match for prof2 ("5678") should only match prof2
        val match5678 = profiles.filter { it.decoyPin == "5678" || ("5678" == codeDecoy && it.decoyPin.isNotBlank()) }
        assertEquals(1, match5678.size)
        assertEquals("p2", match5678[0].id)
    }

    @Test
    fun testDecoyPinMatchingForFallbackCode() {
        val prof1 = Profile("p1", "Work", listOf("com.work.app"), listOf("/sdcard/work"), emptyList(), isActive = true, decoyPin = "1234")
        val prof2 = Profile("p2", "Personal", listOf("com.personal.app"), listOf("/sdcard/personal"), emptyList(), isActive = true, decoyPin = "5678")
        val profiles = listOf(prof1, prof2)
        val codeDecoy = "1234"

        // Master decoy code "1234" matches prof1 directly and also triggers fallback for any profile with a non-blank decoyPin
        val match1234 = profiles.filter { it.decoyPin == "1234" || ("1234" == codeDecoy && it.decoyPin.isNotBlank()) }
        assertEquals(2, match1234.size)
    }
}
