package com.thenile.vault

import com.thenile.vault.root.ConfigCrypto
import com.thenile.vault.root.ContainerBundle
import com.thenile.vault.state.SettingsManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/** The OTG bundle wire format: readHeader must stop exactly at the header/body boundary so the
 *  binary container survives byte-for-byte, and the header must decrypt to the recovery payload. */
class ContainerBundleTest {

    @Test
    fun headerParsesAndBodySurvivesExactly() {
        val header = ConfigCrypto.encrypt("""{"vaultsFull":"[{\"id\":\"a\"}]","keySalt":"cafe"}""")
        // A body that deliberately contains newline (0x0A) and zero bytes — must pass through intact.
        val body = byteArrayOf(1, 10, 0, 10, 42, -7, 0, 10)
        val stream = ByteArrayInputStream("NILEOTG1\n$header\n".toByteArray(Charsets.UTF_8) + body)

        val readHeader = ContainerBundle.readHeader(stream)
        assertEquals(header, readHeader)
        assertArrayEquals("container body must be left intact after the header", body, stream.readBytes())

        val rec = SettingsManager.parseRecoveryConfig(readHeader!!)!!
        assertEquals("cafe", rec.keySalt)
    }

    @Test
    fun wrongMagicRejected() {
        val stream = ByteArrayInputStream("NOTNILE\nxxx\n".toByteArray(Charsets.UTF_8))
        assertNull(ContainerBundle.readHeader(stream))
    }
}
