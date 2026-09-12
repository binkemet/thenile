package com.thenile.vault

import com.thenile.vault.backup.BackupManager
import com.thenile.vault.state.DummyDir
import com.thenile.vault.state.Vault
import com.thenile.vault.state.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupTest {

    private class MockSettingsManager : SettingsManager(android.content.ContextWrapper(null)) {
        private var _vaults: MutableList<Vault>? = null

        override var vaults: List<Vault>
            get() = _vaults ?: emptyList()
            set(value) {
                _vaults = value.toMutableList()
            }

        override fun syncToSystem() {
            // No-op for unit test mock
        }
    }

    @Test
    fun testBackupExportImportRoundtrip() {
        val testVaults = listOf(
            Vault(
                id = "id1",
                name = "Work Vault",
                packages = listOf("com.app.work"),
                directories = listOf("/sdcard/doc"),
                dummyDirectories = listOf(DummyDir("/sdcard/doc", "/sdcard/dummy_doc", true)),
                isActive = true,
                hideOnDecoy = true,
                decoyPin = "1234"
            )
        )

        val out = ByteArrayOutputStream()
        val exportOk = BackupManager.exportBackup(testVaults, "SecretPass123", out)
        assertTrue(exportOk)

        val backupBytes = out.toByteArray()
        assertTrue("Backup byte array must be > 35 bytes", backupBytes.size > 35)

        val magic = String(backupBytes, 0, 7, Charsets.UTF_8)
        assertEquals("NILE_V1", magic)

        val mockSettings = MockSettingsManager()
        val inputStream = ByteArrayInputStream(backupBytes)
        val importedCount = BackupManager.importBackup(mockSettings, "SecretPass123", inputStream)

        assertEquals(1, importedCount)
        assertEquals(1, mockSettings.vaults.size)

        val imported = mockSettings.vaults[0]
        assertEquals("Work Vault", imported.name)
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
        val testVaults = listOf(
            Vault(id = "existing_id", name = "Existing", packages = listOf("com.exist"), isActive = true, hideOnDecoy = true)
        )

        val out = ByteArrayOutputStream()
        BackupManager.exportBackup(testVaults, "Pass", out)

        val mockSettings = MockSettingsManager().apply {
            vaults = listOf(
                Vault(id = "existing_id", name = "Existing", packages = listOf("com.exist"), isActive = true, hideOnDecoy = true)
            )
        }

        val inputStream = ByteArrayInputStream(out.toByteArray())
        val count = BackupManager.importBackup(mockSettings, "Pass", inputStream)

        assertEquals(1, count)
        assertEquals(2, mockSettings.vaults.size)
        assertNotEquals(mockSettings.vaults[0].id, mockSettings.vaults[1].id)
    }
}
