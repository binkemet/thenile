package com.thenile.vault.backup

import com.thenile.vault.state.DummyDir
import com.thenile.vault.state.Profile
import com.thenile.vault.state.SettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object BackupManager {
    var isNativeLoaded = false
        private set

    init {
        try {
            System.loadLibrary("rust_crypto")
            isNativeLoaded = true
        } catch (e: Throwable) {
            isNativeLoaded = false
        }
    }

    @JvmStatic
    private external fun encryptPayloadNative(pass: String, salt: String, iv: ByteArray, plaintext: String): String

    @JvmStatic
    private external fun decryptPayloadNative(pass: String, salt: String, iv: ByteArray, ciphertextHex: String): String

    private fun encryptPayload(pass: String, saltHex: String, iv: ByteArray, plaintext: String): String {
        if (isNativeLoaded) {
            return encryptPayloadNative(pass, saltHex, iv, plaintext)
        }
        val keyBytes = MessageDigest.getInstance("SHA-256").digest("$pass:$saltHex".toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return ciphertext.joinToString("") { "%02x".format(it) }
    }

    private fun decryptPayload(pass: String, saltHex: String, iv: ByteArray, ciphertextHex: String): String {
        if (isNativeLoaded) {
            return decryptPayloadNative(pass, saltHex, iv, ciphertextHex)
        }
        val keyBytes = MessageDigest.getInstance("SHA-256").digest("$pass:$saltHex".toByteArray(Charsets.UTF_8))
        val ciphertextBytes = ciphertextHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        val plaintextBytes = cipher.doFinal(ciphertextBytes)
        return String(plaintextBytes, Charsets.UTF_8)
    }

    fun exportBackup(profiles: List<Profile>, password: String, outputStream: OutputStream): Boolean {
        val jsonArray = JSONArray()
        profiles.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            val pkgs = JSONArray()
            p.packages.forEach { pkgs.put(it) }
            obj.put("packages", pkgs)
            val dirs = JSONArray()
            p.directories.forEach { dirs.put(it) }
            obj.put("directories", dirs)
            val dummyArr = JSONArray()
            p.dummyDirectories.forEach { d ->
                val dObj = JSONObject()
                dObj.put("target", d.target)
                dObj.put("dummy", d.dummy)
                dObj.put("encrypt", d.encrypt)
                dummyArr.put(dObj)
            }
            obj.put("dummyDirectories", dummyArr)
            obj.put("isActive", p.isActive)
            obj.put("hideOnDecoy", p.hideOnDecoy)
            obj.put("decoyPin", p.decoyPin)
            jsonArray.put(obj)
        }
        val payload = JSONObject().apply {
            put("version", 1)
            put("profiles", jsonArray)
        }.toString()

        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val ivBytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val saltHex = saltBytes.joinToString("") { "%02x".format(it) }

        val ciphertextHex = encryptPayload(password, saltHex, ivBytes, payload)
        val ciphertextBytes = ciphertextHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        outputStream.write("NILE_V1".toByteArray(Charsets.UTF_8))
        outputStream.write(saltBytes)
        outputStream.write(ivBytes)
        outputStream.write(ciphertextBytes)
        outputStream.flush()
        return true
    }

    fun importBackup(settings: SettingsManager, password: String, inputStream: InputStream): Int {
        val bytes = inputStream.readBytes()
        if (bytes.size < 35) {
            throw IllegalArgumentException("Invalid backup file: header missing or incomplete")
        }

        val magic = String(bytes, 0, 7, Charsets.UTF_8)
        if (magic != "NILE_V1") {
            throw IllegalArgumentException("Invalid backup file header: $magic")
        }

        val saltBytes = bytes.copyOfRange(7, 23)
        val ivBytes = bytes.copyOfRange(23, 35)
        val ciphertextBytes = bytes.copyOfRange(35, bytes.size)

        val saltHex = saltBytes.joinToString("") { "%02x".format(it) }
        val ciphertextHex = ciphertextBytes.joinToString("") { "%02x".format(it) }

        val jsonPayloadStr = decryptPayload(password, saltHex, ivBytes, ciphertextHex)

        val payloadObj = JSONObject(jsonPayloadStr)
        val jsonProfiles = payloadObj.getJSONArray("profiles")
        val currentProfiles = settings.profiles.toMutableList()
        val existingIds = currentProfiles.map { it.id }.toSet()
        var importedCount = 0

        for (i in 0 until jsonProfiles.length()) {
            val pObj = jsonProfiles.getJSONObject(i)
            var profileId = pObj.optString("id", UUID.randomUUID().toString())
            if (profileId.isBlank() || existingIds.contains(profileId)) {
                profileId = UUID.randomUUID().toString()
            }

            val pkgs = mutableListOf<String>()
            val pkgsArr = pObj.optJSONArray("packages")
            if (pkgsArr != null) {
                for (j in 0 until pkgsArr.length()) pkgs.add(pkgsArr.getString(j))
            }

            val dirs = mutableListOf<String>()
            val dirsArr = pObj.optJSONArray("directories")
            if (dirsArr != null) {
                for (j in 0 until dirsArr.length()) dirs.add(dirsArr.getString(j))
            }

            val dummyDirs = mutableListOf<DummyDir>()
            val dummyArr = pObj.optJSONArray("dummyDirectories")
            if (dummyArr != null) {
                for (j in 0 until dummyArr.length()) {
                    val dObj = dummyArr.getJSONObject(j)
                    dummyDirs.add(
                        DummyDir(
                            target = dObj.getString("target"),
                            dummy = dObj.getString("dummy"),
                            encrypt = dObj.optBoolean("encrypt", false)
                        )
                    )
                }
            }

            val importedProfile = Profile(
                id = profileId,
                name = pObj.optString("name", "Imported Profile"),
                packages = pkgs,
                directories = dirs,
                dummyDirectories = dummyDirs,
                isActive = pObj.optBoolean("isActive", false),
                hideOnDecoy = pObj.optBoolean("hideOnDecoy", true),
                decoyPin = pObj.optString("decoyPin", "")
            )
            currentProfiles.add(importedProfile)
            importedCount++
        }

        settings.profiles = currentProfiles
        return importedCount
    }
}
