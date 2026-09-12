package com.thenile.vault.root

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts the /data/system/thenile_config.json hook-config blob.
 *
 *  This is the one config channel that CANNOT use the app's Keystore: it's written by the app but
 *  read by the lock-screen hook running in system_server (Xposed) or a ROM Keyguard patch, neither
 *  of which can reach the app's per-uid Keystore key. So the key here is derived from a secret
 *  baked into the shipped code, shared by writer and reader.
 *
 *  That makes this OBFUSCATION-GRADE, and deliberately so: it stops the config's decoy PINs and
 *  vault layout from being read by file inspection, `adb pull`, a backup extraction, or forensic
 *  triage — the realistic plausible-deniability threats. It does NOT stop someone who reverse-
 *  engineers the APK to recover this key. That's the ceiling of any writer-and-hook-shared scheme;
 *  the strong per-user encryption lives in HiddenVolume and the Keystore-backed settings.
 *
 *  Output is `NILEC1:` + hex(iv‖ciphertext‖tag), so it stays a quote-free text blob a root shell
 *  can `echo` without escaping trouble. Reads accept a legacy plaintext JSON blob unchanged, so an
 *  older file (or an external reader mid-migration) still works. */
object ConfigCrypto {
    private const val MAGIC = "NILEC1:"

    private fun key(): SecretKeySpec {
        // ponytail: baked-in secret — obfuscation ceiling is documented above; upgrade path is a
        // per-device secret both app and hook can derive, which the current hook architecture has no
        // channel for.
        val secret = "th3n1l3::cfg::" + "v1::" + "keyguard-hook-shared-secret"
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)), "AES")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return MAGIC + (iv + ct).joinToString("") { "%02x".format(it) }
    }

    /** Decrypts a blob produced by [encrypt]; returns a non-encrypted (legacy plaintext) blob
     *  unchanged. Throwing on a corrupt encrypted blob is intentional — callers already wrap config
     *  reads in try/catch and treat failure as "no config". */
    fun decrypt(blob: String): String {
        if (!blob.startsWith(MAGIC)) return blob
        val raw = blob.substring(MAGIC.length).trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
