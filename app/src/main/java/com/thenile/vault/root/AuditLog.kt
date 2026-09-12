package com.thenile.vault.root

import android.content.Context
import android.util.Log
import com.thenile.vault.state.VaultStateManager
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Append-only, encrypted record of security-relevant events (unlocks, wrong attempts, and every
 *  auto-hide trigger firing). Readable only from the Admin panel (which is itself auth-gated).
 *
 *  Encrypted at rest with a key derived from the on-device keySalt — NOT the user's PIN — because
 *  triggers fire headless with no PIN available. That means the protection is obfuscation-grade:
 *  it keeps the log from being human-readable in a file browser or a naive `adb pull`, on par with
 *  the app's existing SharedPreferences, but anyone with root and the app's data directory can
 *  recover the key. It is not a defense against a determined forensic adversary. Each line is its
 *  own AES/GCM record (own IV) so appends never need to rewrite the file. */
object AuditLog {
    private const val TAG = "AuditLog"
    private const val FILE = ".audit"
    private const val MAX_LINES = 500

    private fun key(context: Context): SecretKeySpec {
        val salt = VaultStateManager.getInstance(context).keySalt()
        val bytes = MessageDigest.getInstance("SHA-256").digest("audit:$salt".toByteArray())
        return SecretKeySpec(bytes, "AES")
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Fire-and-forget; never throws (logging must not break the thing it's logging). */
    fun record(context: Context, event: String) {
        try {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(context), GCMParameterSpec(128, iv))
            val line = "${System.currentTimeMillis()}|$event"
            val ct = cipher.doFinal(line.toByteArray(Charsets.UTF_8))
            val record = (iv + ct).joinToString("") { "%02x".format(it) }
            val f = file(context)
            f.appendText(record + "\n")
            trim(f)
        } catch (e: Exception) {
            Log.w(TAG, "audit record failed", e)
        }
    }

    /** Decrypts every readable line, newest first. A corrupt/undecryptable line is skipped. */
    fun read(context: Context): List<Entry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val k = key(context)
        return f.readLines().mapNotNull { hex ->
            try {
                val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val iv = raw.copyOfRange(0, 12)
                val ct = raw.copyOfRange(12, raw.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
                val line = String(cipher.doFinal(ct), Charsets.UTF_8)
                val sep = line.indexOf('|')
                Entry(line.substring(0, sep).toLong(), line.substring(sep + 1))
            } catch (e: Exception) {
                null
            }
        }.reversed()
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (e: Exception) { /* nothing to do */ }
    }

    /** ponytail: rewrite-on-overflow, fine at 500 lines; switch to ring buffer if this ever grows. */
    private fun trim(f: File) {
        val lines = f.readLines()
        if (lines.size > MAX_LINES) f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
    }

    data class Entry(val timestampMillis: Long, val event: String)
}
