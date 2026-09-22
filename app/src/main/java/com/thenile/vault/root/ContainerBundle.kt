package com.thenile.vault.root

import android.content.Context
import com.thenile.vault.state.SettingsManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Self-contained OTG bundle = a recovery header (key salt + vaults + codes) followed by the raw
 *  encrypted container, so a single picked file restores everything on a clean device — no
 *  dependency on the on-device /data/system config surviving.
 *
 *  Wire format (header is ASCII, no embedded newlines, so it can't collide with the binary body):
 *      line 1:  "NILEOTG1"
 *      line 2:  ConfigCrypto.encrypt(recovery JSON)   // obfuscation-grade, same as the on-device
 *                                                      // config — hides decoy PINs/vault layout on
 *                                                      // the drive; the salt alone is useless w/o
 *                                                      // the real PIN.
 *      then:    raw container ciphertext bytes
 */
object ContainerBundle {
    private const val MAGIC = "NILEOTG1"

    /** Write header + container to [out]. Returns false if there's no container to export. */
    fun export(context: Context, out: OutputStream): Boolean {
        val header = ConfigCrypto.encrypt(SettingsManager.getInstance(context).buildRecoveryJson())
        out.write("$MAGIC\n$header\n".toByteArray(Charsets.UTF_8))
        return HiddenVolume.exportContainer(out)  // streams the container bytes after the header
    }

    /** Read a bundle from [input]: restore the recovery config, then the container. Returns false if
     *  the stream isn't a bundle (wrong/absent magic) or the container write fails. */
    fun import(context: Context, input: InputStream): Boolean {
        val header = readHeader(input) ?: return false
        // Restore vaults + salt if the header carries them (edge bundles may not); either way still
        // restore the container so the salt from a surviving on-device config can open it.
        SettingsManager.parseRecoveryConfig(header)?.let {
            SettingsManager.getInstance(context).applyRecoveryConfig(it)
        }
        return HiddenVolume.importContainer(input)  // input is now positioned at the container bytes
    }

    /** Read + validate the two header lines, leaving [input] positioned exactly at the first
     *  container byte. Returns the encrypted header line, or null if the magic is wrong/absent.
     *  Pure (no root/Context) so the header/body boundary is unit-testable. */
    fun readHeader(input: InputStream): String? {
        if (readLine(input) != MAGIC) return null
        return readLine(input)
    }

    /** Read one '\n'-terminated ASCII line, consuming exactly up to and including the newline so the
     *  stream is left at the next byte. null at EOF with nothing read. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8")
            buf.write(b)
        }
    }
}
