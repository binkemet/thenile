package com.thenile.vault.root

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFileOutputStream
import java.io.InputStream

/** Lets the user drop real, ordinary files into the decoy HiddenVolume so that if the container is
 *  ever extracted and mounted with the decoy password, it looks like genuinely used personal
 *  storage instead of empty free space or opaque app-snapshot blobs — the "I have nothing to hide"
 *  story needs something to actually show. Files keep their real names (unlike HiddenAppManager's
 *  hash-named snapshots) so a listing reads like an ordinary folder. */
object DecoyFileStore {
    private const val TAG = "DecoyFileStore"
    private const val DIR = "user_files"

    private fun salt(context: Context) = com.thenile.vault.state.VaultStateManager.getInstance(context).keySalt()

    /** Copy each (filename, stream) pair into the decoy volume's user_files dir. Returns how many
     *  succeeded. Mounts with formatIfNeeded=true so this also works as first-use setup. */
    fun importFiles(context: Context, decoyPassword: String, files: List<Pair<String, InputStream>>): Int {
        val s = salt(context)
        val mp = HiddenVolume.mount(HiddenVolume.Role.DECOY, decoyPassword, s, formatIfNeeded = true) ?: return 0
        var ok = 0
        try {
            Shell.cmd("mkdir -p '$mp/$DIR'").exec()
            for ((name, stream) in files) {
                val safeName = name.replace("'", "_")
                try {
                    SuFileOutputStream.open("$mp/$DIR/$safeName").use { stream.copyTo(it) }
                    ok++
                } catch (e: Throwable) {
                    Log.e(TAG, "importFiles: failed to write $safeName", e)
                }
            }
        } finally { HiddenVolume.unmount(HiddenVolume.Role.DECOY) }
        return ok
    }

    /** List (name, size in bytes) of everything currently in the decoy file store. Uses `stat -c`
     *  (not `ls -la` column-splitting) so filenames containing spaces still parse correctly. */
    fun listFiles(context: Context, decoyPassword: String): List<Pair<String, Long>> {
        val s = salt(context)
        val mp = HiddenVolume.mount(HiddenVolume.Role.DECOY, decoyPassword, s, formatIfNeeded = false) ?: return emptyList()
        return try {
            Shell.cmd("stat -c '%s %n' '$mp/$DIR'/* 2>/dev/null").exec().out.mapNotNull { line ->
                val parts = line.trim().split(" ", limit = 2)
                val size = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val path = parts.getOrNull(1) ?: return@mapNotNull null
                path.substringAfterLast('/') to size
            }
        } finally { HiddenVolume.unmount(HiddenVolume.Role.DECOY) }
    }

    /** Delete one file by name from the decoy file store. */
    fun deleteFile(context: Context, decoyPassword: String, name: String): Boolean {
        val s = salt(context)
        val mp = HiddenVolume.mount(HiddenVolume.Role.DECOY, decoyPassword, s, formatIfNeeded = false) ?: return false
        return try {
            Shell.cmd("rm -f '$mp/$DIR/${name.replace("'", "_")}'").exec().isSuccess
        } finally { HiddenVolume.unmount(HiddenVolume.Role.DECOY) }
    }
}
