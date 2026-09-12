package com.thenile.vault

import com.thenile.vault.root.SoftVault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftVaultTest {

    @Test
    fun fileListedDirectly() {
        assertTrue(SoftVault.matchesUnhideScope("/sdcard/notes.txt", emptyList(), listOf("/sdcard/notes.txt")))
    }

    @Test
    fun fileInsideRequestedDirectory() {
        assertTrue(SoftVault.matchesUnhideScope("/sdcard/work/report.pdf", listOf("/sdcard/work"), emptyList()))
    }

    @Test
    fun doesNotMatchSiblingDirectoryWithSharedPrefix() {
        // "/sdcard/work2" must not be treated as inside "/sdcard/work".
        assertFalse(SoftVault.matchesUnhideScope("/sdcard/work2/file.txt", listOf("/sdcard/work"), emptyList()))
    }

    @Test
    fun toleratesTrailingSlashOnRequestedDirectory() {
        assertTrue(SoftVault.matchesUnhideScope("/sdcard/work/report.pdf", listOf("/sdcard/work/"), emptyList()))
    }

    @Test
    fun unrelatedPathDoesNotMatch() {
        assertFalse(SoftVault.matchesUnhideScope("/sdcard/personal/photo.jpg", listOf("/sdcard/work"), listOf("/sdcard/notes.txt")))
    }
}
