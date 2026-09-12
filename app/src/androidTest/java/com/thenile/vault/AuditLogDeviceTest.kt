package com.thenile.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thenile.vault.root.AuditLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The audit log is a crypto path (AES/GCM, key derived from keySalt) — this verifies the
 *  encrypt-append then decrypt-read round trip on a real device, and that what lands on disk is
 *  ciphertext, not the plaintext event. Runs without root. */
@RunWith(AndroidJUnit4::class)
class AuditLogDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun recordThenReadRoundTrips() {
        AuditLog.clear(ctx)
        try {
            AuditLog.record(ctx, "first event")
            AuditLog.record(ctx, "second event")

            val entries = AuditLog.read(ctx)
            assertEquals(2, entries.size)
            // newest first
            assertEquals("second event", entries[0].event)
            assertEquals("first event", entries[1].event)
            assertTrue("timestamp should be recent", entries[0].timestampMillis > 0)

            val onDisk = java.io.File(ctx.filesDir, ".audit").readText()
            assertTrue("plaintext must not appear in the encrypted log",
                !onDisk.contains("first event") && !onDisk.contains("second event"))
        } finally {
            AuditLog.clear(ctx)
        }
    }
}
