package com.thenile.vault

import com.thenile.vault.backup.BackupManager
import com.thenile.vault.state.DummyDir
import com.thenile.vault.state.Profile
import com.thenile.vault.state.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupTest {

    private class MockSettingsManager : SettingsManager(android.content.ContextWrapper(null)) {
        private var _profiles: MutableList<Profile>? = null

        override var profiles: List<Profile>
            get() = _profiles ?: emptyList()
            set(value) {
                _profiles = value.toMutableList()
            }
    }

    @Test
    fun testBackupExportImportRoundtrip() {
        val testProfiles = listOf(
            Profile(
                id = "id1",
                name = "Work Profile",
                packages = listOf("com.app.work"),
                directories = listOf("/sdcard/doc"),
                dummyDirectories = listOf(DummyDir("/sdcard/doc", "/sdcard/dummy_doc", true)),
                isActive = true,
                hideOnDecoy = true,
                decoyPin = "1234"
            )
        )

        val out = ByteArrayOutputStream()
        val exportOk = BackupManager.exportBackup(testProfiles, "SecretPass123", out)
        assertTrue(exportOk)

        val backupBytes = out.toByteArray()
        assertTrue("Backup byte array must be > 35 bytes", backupBytes.size > 35)

        val magic = String(backupBytes, 0, 7, Charsets.UTF_8)
        assertEquals("NILE_V1", magic)

        val mockSettings = MockSettingsManager()
        val inputStream = ByteArrayInputStream(backupBytes)
        val importedCount = BackupManager.importBackup(mockSettings, "SecretPass123", inputStream)

        assertEquals(1, importedCount)
        assertEquals(1, mockSettings.profiles.size)

        val imported = mockSettings.profiles[0]
        assertEquals("Work Profile", imported.name)
        assertEquals(listOf("com.app.work"), imported.packages)
        assertEquals(listOf("/sdcard/doc"), imported.directories)
        assertEquals(1, imported.dummyDirectories.size)
        assertEquals("/sdcard/doc", imported.dummyDirectories[0].target)
        assertEquals("/sdcard/dummy_doc", imported.dummyDirectories[0].dummy)
        assertTrue(imported.dummyDirectories[0].encrypt)
        assertTrue(imported.isActive)
        assertTrue(imported.hideOnDecoy)
        assertEquals("1234", imported.decoyPin)
    }

    @Test
    fun testImportDuplicateIdGeneratesNewUuid() {
        val testProfiles = listOf(
            Profile("existing_id", "Existing", listOf("com.exist"), emptyList(), emptyList(), true, true, "")
        )

        val out = ByteArrayOutputStream()
        BackupManager.exportBackup(testProfiles, "Pass", out)

        val mockSettings = MockSettingsManager().apply {
            profiles = listOf(
                Profile("existing_id", "Existing", listOf("com.exist"), emptyList(), emptyList(), true, true, "")
            )
        }

        val inputStream = ByteArrayInputStream(out.toByteArray())
        val count = BackupManager.importBackup(mockSettings, "Pass", inputStream)

        assertEquals(1, count)
        assertEquals(2, mockSettings.profiles.size)
        assertNotEquals(mockSettings.profiles[0].id, mockSettings.profiles[1].id)
    }
}
