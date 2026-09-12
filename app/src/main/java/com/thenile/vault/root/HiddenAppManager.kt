package com.thenile.vault.root

import android.content.Context
import android.util.Log
import com.thenile.vault.state.Vault
import com.thenile.vault.state.VaultStateManager

/** Option-B/A orchestration on top of [AppDataVault] and [HiddenVolume]: for each package a vault
 *  lists, keep encrypted snapshots inside the two-key container so the app's data (or the app
 *  itself) swaps by PIN:
 *
 *    real PIN   -> restore real snapshot / reinstall   (actual data appears)
 *    decoy PIN  -> restore anodyne snapshot / stay gone (innocent app, or absent)
 *
 *  Snapshots for the DECOY state live in the decoy volume (openable with the decoy PIN); REAL
 *  snapshots + APKs live in the hidden volume (openable only with the real PIN). So entering the
 *  decoy PIN can neither reach nor decrypt the real data — it's inaccessible, not merely hidden.
 *
 *  ponytail: snapshots are still file-encrypted by AppDataVault even though the volume already
 *  encrypts them — redundant but lets us reuse snapshot()/restore() unchanged. The store lives
 *  inside HiddenVolume, validated on-device (two-key mount + wrong-key rejection; see that file). */
object HiddenAppManager {
    private const val TAG = "HiddenAppManager"
    private const val USER0 = 0

    private fun tag(pkg: String) = Integer.toHexString(pkg.hashCode())
    private fun salt(context: Context) = VaultStateManager.getInstance(context).keySalt()
    private fun scratch(context: Context) = context.cacheDir.path

    // -------- capture (admin setup) --------

    /** Capture the app's CURRENT state as the anodyne decoy, into the decoy volume. No wipe. */
    fun captureDecoy(context: Context, pkg: String, decoyPin: String): Boolean {
        val s = salt(context)
        val mp = HiddenVolume.mount(HiddenVolume.Role.DECOY, decoyPin, s, formatIfNeeded = true) ?: return false
        return try {
            AppDataVault.snapshot(pkg, USER0, decoyPin, s, "$mp/${tag(pkg)}.d", wipeAfter = false, scratch(context))
        } finally { HiddenVolume.unmount(HiddenVolume.Role.DECOY) }
    }

    /** Capture the app's CURRENT state as the real data into the hidden volume, then wipe it live. */
    fun captureReal(context: Context, pkg: String, realPin: String): Boolean {
        val s = salt(context)
        val mp = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, realPin, s, formatIfNeeded = true) ?: return false
        return try {
            AppDataVault.snapshot(pkg, USER0, realPin, s, "$mp/${tag(pkg)}.r", wipeAfter = true, scratch(context))
        } finally { HiddenVolume.unmount(HiddenVolume.Role.HIDDEN) }
    }

    /** Option A: snapshot APK + real data into the hidden volume, then uninstall. */
    fun captureUninstall(context: Context, pkg: String, realPin: String): Boolean {
        val s = salt(context)
        val mp = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, realPin, s, formatIfNeeded = true) ?: return false
        val ok = try {
            val dataOk = AppDataVault.snapshot(pkg, USER0, realPin, s, "$mp/${tag(pkg)}.r", wipeAfter = false, scratch(context))
            val apkOk = AppDataVault.snapshotApks(pkg, realPin, s, "$mp/${tag(pkg)}.a", scratch(context))
            dataOk && apkOk
        } finally { HiddenVolume.unmount(HiddenVolume.Role.HIDDEN) }
        if (!ok) { Log.e(TAG, "captureUninstall: snapshot failed for $pkg"); return false }
        return AppDataVault.uninstall(pkg)
    }

    // -------- runtime swap --------

    /** Decoy/lock: anodyne data for data-swap apps; uninstalled for uninstall apps. */
    fun showDecoy(context: Context, vault: Vault, decoyPin: String) {
        val s = salt(context)
        if (vault.hiddenApps.isNotEmpty()) {
            val mp = HiddenVolume.mount(HiddenVolume.Role.DECOY, decoyPin, s, formatIfNeeded = false)
            if (mp != null) try {
                for (pkg in vault.hiddenApps) {
                    val snap = "$mp/${tag(pkg)}.d"
                    if (exists(snap)) AppDataVault.restore(pkg, USER0, decoyPin, s, snap, scratch(context))
                }
            } finally { HiddenVolume.unmount(HiddenVolume.Role.DECOY) }
        }
        for (pkg in vault.uninstallApps) if (isInstalled(pkg)) AppDataVault.uninstall(pkg)
    }

    /** Real unlock: real data for data-swap apps; reinstall + restore for uninstall apps. */
    fun revealReal(context: Context, vault: Vault, realPin: String) {
        if (vault.hiddenApps.isEmpty() && vault.uninstallApps.isEmpty()) return
        val s = salt(context)
        val mp = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, realPin, s, formatIfNeeded = false) ?: return
        try {
            for (pkg in vault.hiddenApps) {
                val snap = "$mp/${tag(pkg)}.r"
                if (exists(snap)) AppDataVault.restore(pkg, USER0, realPin, s, snap, scratch(context))
            }
            for (pkg in vault.uninstallApps) {
                val apk = "$mp/${tag(pkg)}.a"
                val data = "$mp/${tag(pkg)}.r"
                if (exists(apk) && !isInstalled(pkg)) {
                    if (AppDataVault.restoreApks(realPin, s, apk, scratch(context)) && exists(data)) {
                        AppDataVault.restore(pkg, USER0, realPin, s, data, scratch(context))
                    }
                }
            }
        } finally { HiddenVolume.unmount(HiddenVolume.Role.HIDDEN) }
    }

    private fun exists(path: String) = PrivilegedShell.exec("test -e '$path'").isSuccess
    private fun isInstalled(pkg: String) = PrivilegedShell.exec("pm path '$pkg'").isSuccess
}
