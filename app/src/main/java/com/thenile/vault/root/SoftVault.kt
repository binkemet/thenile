package com.thenile.vault.root

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.thenile.vault.state.SettingsManager
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * No-privilege fallback for directory/file hiding — used when PrivilegeManager.currentTier() is
 * SHIZUKU (shell UID has no CAP_SYS_ADMIN, so mount/nsenter/dmcrypt are unavailable even there)
 * or NONE.
 *
 * Root's approach bind-mounts an encrypted container OVER the original path, so the file never
 * moves. Without CAP_SYS_ADMIN there's no mount syscall available at all, so this instead
 * genuinely moves the plaintext out: encrypt each file, then delete the original. By default the
 * encrypted blob goes into Nile's OWN app-private storage (context.noBackupFilesDir), which is
 * invisible to every other app and to MediaStore/gallery scanning by Android's normal sandboxing
 * alone — no root or Shizuku needed for that. The user can instead point SoftVault at any
 * directory via SettingsManager.softVaultDirectoryUri (SAF tree, picked with a warning — see
 * AdminActivity) — that directory does NOT get the same "invisible to other apps" guarantee, only
 * the app-private default does.
 *
 * Which storage a given hidden file actually lives in is recorded per-entry in the manifest at
 * hide time (not re-read from the current setting at unhide time) — otherwise changing the vault
 * location between a hide and its matching unhide would silently orphan the blob.
 *
 * ponytail: reversible == genuinely reversible only while the manifest survives — an uninstall or
 * a cleared app data wipes the default location (and its manifest) together. A custom SAF
 * location's blobs survive that, but the manifest recording where they are still lives in
 * app-private storage, so losing IT still strands them. Root mode doesn't have this failure mode
 * (the plaintext stays put in the vault image on disk). Not building manifest backup/export now,
 * nobody asked for it yet.
 */
object SoftVault {
    private const val TAG = "SoftVault"
    private const val VAULT_DIRNAME = "nile_softvault"
    private const val STAGING_DIRNAME = "nile_softvault_staging"
    private const val MANIFEST_NAME = "manifest.json"

    private fun vaultDir(context: Context): File =
        File(context.noBackupFilesDir, VAULT_DIRNAME).apply { mkdirs() }

    /** Scratch file for the native encrypt/decrypt call, which needs a real file path — a custom
     *  SAF tree only exposes streams, so a custom-location blob is encrypted here first and then
     *  copied out (or copied in and decrypted here) via SafBlobStore. */
    private fun stagingFile(context: Context, key: String): File {
        val dir = File(context.noBackupFilesDir, STAGING_DIRNAME).apply { mkdirs() }
        return File(dir, key)
    }

    private fun manifestFile(context: Context) = File(vaultDir(context), MANIFEST_NAME)

    private fun loadManifest(context: Context): JSONObject {
        val f = manifestFile(context)
        if (!f.exists()) return JSONObject()
        return try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
    }

    private fun saveManifest(context: Context, manifest: JSONObject) {
        manifestFile(context).writeText(manifest.toString())
    }

    /** Original absolute path -> vault blob filename. Hashed so the blob name never leaks the
     *  original path if the vault dir is ever inspected, and so nested paths can't collide. */
    private fun blobKey(originalPath: String): String =
        MessageDigest.getInstance("SHA-256").digest(originalPath.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun currentCustomTreeUri(context: Context): Uri? {
        val raw = SettingsManager.getInstance(context).softVaultDirectoryUri
        if (raw.isNullOrBlank()) return null
        return try { Uri.parse(raw) } catch (e: Exception) { null }
    }

    /** Best-effort MediaStore row removal so a hidden photo/video also drops out of the gallery.
     *  An entry this app didn't create can throw RecoverableSecurityException on API 29+ without
     *  MANAGE_EXTERNAL_STORAGE — the plaintext file is still deleted regardless (see caller), just
     *  the gallery may show a stale thumbnail until its next scan. Root's path doesn't have this
     *  ceiling (it deletes MediaStore rows directly via sqlite3 as root). */
    private fun tryDeleteFromMediaStore(context: Context, path: String) {
        try {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DATA}=?"
            for (uri in listOf(
                MediaStore.Files.getContentUri("external"),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )) {
                resolver.query(uri, projection, selection, arrayOf(path), null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        try {
                            resolver.delete(Uri.withAppendedPath(uri, id.toString()), null, null)
                        } catch (e: Exception) {
                            Log.w(TAG, "MediaStore delete needs user consent for $path: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryDeleteFromMediaStore failed for $path: ${e.message}")
        }
    }

    /** Encrypts when there's a real pin to derive a key from; otherwise falls back to a plain copy
     *  — same asymmetry the root path already has (unmountAndLock calls with pin="" from
     *  AdminActivity's "Hide Vault" button, which doesn't prompt for the vault PIN, and falls
     *  back to `cp` there too). Without this, encryptFileNative("", ...) just fails the whole hide
     *  silently (confirmed on-device: no crash, no log, file simply never touched). */
    private fun encryptToFile(pin: String, salt: String, src: String, dst: String): Boolean {
        if (pin.isEmpty()) {
            return try {
                File(src).copyTo(File(dst), overwrite = true)
                true
            } catch (e: Exception) {
                Log.e(TAG, "plain copy failed for $src: ${e.message}")
                false
            }
        }
        return try {
            StorageMountManager.encryptFileNative(pin, salt, src, dst)
        } catch (e: Throwable) {
            Log.e(TAG, "encryptFileNative error for $src: ${e.message}")
            false
        }
    }

    /** Mirror of encryptToFile for the restore side, plus root's own fallback: a blob that fails
     *  to decrypt (because it was only ever plain-copied, not actually encrypted) is copied as-is. */
    private fun decryptToFile(pin: String, salt: String, src: String, dst: String): Boolean {
        if (pin.isNotEmpty()) {
            val ok = try {
                StorageMountManager.decryptFileNative(pin, salt, src, dst)
            } catch (e: Throwable) {
                Log.e(TAG, "decryptFileNative error for $src: ${e.message}")
                false
            }
            if (ok) return true
        }
        return try {
            File(src).copyTo(File(dst), overwrite = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "plain copy fallback failed for $src: ${e.message}")
            false
        }
    }

    private fun encryptOneFile(context: Context, pin: String, salt: String, path: String, manifest: JSONObject): Boolean {
        // path is always the /sdcard/... (or /storage/emulated/0/...) form the vault stores —
        // that's the only path form a regular app process may touch. The raw /data/media/0/...
        // path root's shell-based code uses is off-limits here: SELinux denies untrusted_app from
        // it even with MANAGE_EXTERNAL_STORAGE granted (confirmed on-device — an unguarded copyTo
        // against it crashed the whole process with EACCES). /sdcard/... is the FUSE-remapped view
        // of the exact same file, so there's no need for a second path at all.
        val src = File(path).takeIf { it.exists() }
        if (src == null) return true // nothing there to hide, not a failure
        val key = blobKey(path)
        val tree = currentCustomTreeUri(context)

        val ok = if (tree != null) {
            val staging = stagingFile(context, key)
            val encrypted = encryptToFile(pin, salt, src.absolutePath, staging.absolutePath)
            val written = encrypted && SafBlobStore.write(context, tree, key, staging)
            staging.delete()
            written
        } else {
            val blob = File(vaultDir(context), key)
            encryptToFile(pin, salt, src.absolutePath, blob.absolutePath)
        }
        if (!ok) return false

        manifest.put(key, JSONObject().apply {
            put("path", path)
            // omit "treeUri" entirely for the default location — JSONObject.NULL.toString() is the
            // 4-char string "null", which optString("treeUri", "") would return verbatim instead of
            // the fallback, and Uri.parse("null") then returns a bogus non-null Uri.
            if (tree != null) put("treeUri", tree.toString())
        })
        src.delete()
        tryDeleteFromMediaStore(context, path)
        return true
    }

    private fun decryptOneFile(context: Context, pin: String, salt: String, path: String, key: String, treeUri: Uri?): Boolean {
        val dest = File(path)
        dest.parentFile?.mkdirs()

        val ok = if (treeUri != null) {
            val staging = stagingFile(context, key)
            val fetched = SafBlobStore.read(context, treeUri, key, staging)
            val decrypted = fetched && decryptToFile(pin, salt, staging.absolutePath, dest.absolutePath)
            staging.delete()
            if (decrypted) SafBlobStore.delete(context, treeUri, key)
            decrypted
        } else {
            val blob = File(vaultDir(context), key)
            if (!blob.exists()) {
                false
            } else {
                val decrypted = decryptToFile(pin, salt, blob.absolutePath, dest.absolutePath)
                if (decrypted) blob.delete()
                decrypted
            }
        }

        return ok
    }

    /** Encrypts every file under each directory (recursively) and each individual file, then
     *  deletes the plaintext originals. Directories are left behind as empty shells rather than
     *  removed outright, same intent as the root path's DummyDir: "still there, just empty". */
    fun hide(context: Context, pin: String, salt: String, directories: List<String>, files: List<String>): Boolean {
        val manifest = loadManifest(context)
        var allOk = true
        for (dir in directories) {
            val root = File(dir)
            if (!root.exists()) continue
            root.walkTopDown().filter { it.isFile }.toList().forEach { f ->
                if (!encryptOneFile(context, pin, salt, f.absolutePath, manifest)) allOk = false
            }
            root.walkBottomUp().filter { it.isDirectory && it != root }.forEach { it.delete() }
        }
        for (file in files) {
            if (!encryptOneFile(context, pin, salt, file, manifest)) allOk = false
        }
        saveManifest(context, manifest)
        return allOk
    }

    /** A hidden file's original path is in scope for this unhide call if it's listed directly, or
     *  sits inside one of the requested directories. trimEnd+"/" so "/sdcard/work" doesn't also
     *  match a sibling like "/sdcard/work2" — extracted standalone so it's testable without a
     *  Context (see SoftVaultTest). */
    internal fun matchesUnhideScope(path: String, directories: List<String>, files: List<String>): Boolean =
        files.contains(path) || directories.any { path.startsWith(it.trimEnd('/') + "/") }

    /** Restores every blob whose recorded original path falls under one of `directories`, or is
     *  listed in `files`, decrypting it back in place and forgetting it from the manifest. Uses
     *  each entry's own recorded location, not the current softVaultDirectoryUri setting. */
    fun unhide(context: Context, pin: String, salt: String, directories: List<String>, files: List<String>): Boolean {
        val manifest = loadManifest(context)
        var allOk = true
        for (key in manifest.keys().asSequence().toList()) {
            val entry = manifest.optJSONObject(key) ?: continue
            val path = entry.optString("path")
            if (!matchesUnhideScope(path, directories, files)) continue
            val treeUriStr = entry.optString("treeUri", "")
            val treeUri = if (treeUriStr.isBlank()) null else try { Uri.parse(treeUriStr) } catch (e: Exception) { null }
            if (decryptOneFile(context, pin, salt, path, key, treeUri)) {
                manifest.remove(key)
            } else {
                allOk = false
            }
        }
        saveManifest(context, manifest)
        return allOk
    }
}
