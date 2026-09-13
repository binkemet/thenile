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
    private const val NS = "nsenter -t 1 -m --" // PID 1's mount namespace — see AppDataVault

    private fun tag(pkg: String) = Integer.toHexString(pkg.hashCode())
    private fun salt(context: Context) = VaultStateManager.getInstance(context).keySalt()
    private fun scratch(context: Context) = context.cacheDir.path

    // App data is per-user, so each profile gets its own snapshot — restoring one user's data into
    // another would corrupt it. Snapshot files are keyed by user: "<taghex>_u<userId>.<ext>". The
    // shared APK snapshot (uninstall mode) stays user-agnostic ("<taghex>.a").
    private fun snapPath(mp: String, pkg: String, userId: Int, ext: String) = "$mp/${tag(pkg)}_u$userId.$ext"
    private fun legacyPath(mp: String, pkg: String, ext: String) = "$mp/${tag(pkg)}.$ext" // pre-multi-user captures (user 0)

    /** All Android user IDs on the device (owner + work/secondary profiles). */
    private fun allUsers(): List<Int> {
        val out = PrivilegedShell.exec("pm list users").out
        return out.mapNotNull { "UserInfo\\{([0-9]+):".toRegex().find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .ifEmpty { listOf(USER0) }
    }

    /** Users where the package is installed AND its CE data is currently reachable. A system app
     *  (e.g. an Easter egg) exists in every profile, but a locked/unstarted secondary profile's
     *  data can't be read ("Required key not available") — snapshotting it would just fail, so it's
     *  excluded. We only hide the profiles we can actually reach right now. */
    private fun usersForPkg(pkg: String): List<Int> =
        allUsers().filter { u ->
            PrivilegedShell.exec("pm path --user $u '$pkg'").isSuccess &&
                PrivilegedShell.exec("$NS ls /data/user/$u/$pkg").isSuccess
        }.ifEmpty { listOf(USER0) }

    /** Snapshot [pkg] for every user it's installed in; returns true only if all succeeded. Mapped
     *  (not short-circuited) so one failing profile doesn't skip the rest. */
    private fun snapshotAllUsers(context: Context, pkg: String, ext: String, pin: String, s: String, mp: String, wipe: Boolean): Boolean =
        usersForPkg(pkg).map { u ->
            AppDataVault.snapshot(pkg, u, pin, s, snapPath(mp, pkg, u, ext), wipeAfter = wipe, scratch(context))
        }.all { it }

    /** Restore [pkg] into every user that has a snapshot (per-user file, or the legacy user-0 file). */
    private fun restoreAllUsers(context: Context, pkg: String, ext: String, pin: String, s: String, mp: String) {
        for (u in allUsers()) {
            val perUser = snapPath(mp, pkg, u, ext)
            val use = when {
                exists(perUser) -> perUser
                u == USER0 && exists(legacyPath(mp, pkg, ext)) -> legacyPath(mp, pkg, ext)
                else -> null
            }
            if (use != null) AppDataVault.restore(pkg, u, pin, s, use, scratch(context))
        }
    }

    // -------- capture (admin setup) --------

    /** Capture the app's CURRENT state as the anodyne decoy, into the decoy volume. No wipe. */
    fun captureDecoy(context: Context, pkg: String, decoyPin: String): Boolean {
        val s = salt(context)
        val mp = HiddenVolume.mount(HiddenVolume.Role.DECOY, decoyPin, s, formatIfNeeded = true) ?: return false
        return try {
            snapshotAllUsers(context, pkg, "d", decoyPin, s, mp, wipe = false)
        } finally { HiddenVolume.unmount(HiddenVolume.Role.DECOY) }
    }

    /** Capture the app's CURRENT state as the real data into the hidden volume, then wipe it live. */
    fun captureReal(context: Context, pkg: String, realPin: String): Boolean {
        val s = salt(context)
        val mp = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, realPin, s, formatIfNeeded = true) ?: return false
        return try {
            snapshotAllUsers(context, pkg, "r", realPin, s, mp, wipe = true)
        } finally { HiddenVolume.unmount(HiddenVolume.Role.HIDDEN) }
    }

    /** Option A: snapshot APK + real data (per user) into the hidden volume, then uninstall. */
    fun captureUninstall(context: Context, pkg: String, realPin: String): Boolean {
        val s = salt(context)
        val mp = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, realPin, s, formatIfNeeded = true) ?: return false
        val ok = try {
            val dataOk = snapshotAllUsers(context, pkg, "r", realPin, s, mp, wipe = false)
            val apkOk = AppDataVault.snapshotApks(pkg, realPin, s, "$mp/${tag(pkg)}.a", scratch(context))
            dataOk && apkOk
        } finally { HiddenVolume.unmount(HiddenVolume.Role.HIDDEN) }
        if (!ok) { Log.e(TAG, "captureUninstall: snapshot failed for $pkg"); return false }
        return AppDataVault.uninstall(pkg) // pm uninstall removes the app for every user
    }

    // -------- runtime swap --------

    /** Decoy/lock: anodyne data for data-swap apps (all profiles); uninstalled for uninstall apps. */
    fun showDecoy(context: Context, vault: Vault, decoyPin: String) {
        val s = salt(context)
        if (vault.hiddenApps.isNotEmpty()) {
            val mp = HiddenVolume.mount(HiddenVolume.Role.DECOY, decoyPin, s, formatIfNeeded = false)
            if (mp != null) try {
                for (pkg in vault.hiddenApps) restoreAllUsers(context, pkg, "d", decoyPin, s, mp)
            } finally { HiddenVolume.unmount(HiddenVolume.Role.DECOY) }
        }
        for (pkg in vault.uninstallApps) if (isInstalled(pkg)) AppDataVault.uninstall(pkg)
    }

    /** Real unlock: real data for data-swap apps (all profiles); reinstall + restore for uninstall apps. */
    fun revealReal(context: Context, vault: Vault, realPin: String) {
        if (vault.hiddenApps.isEmpty() && vault.uninstallApps.isEmpty()) return
        val s = salt(context)
        val mp = HiddenVolume.mount(HiddenVolume.Role.HIDDEN, realPin, s, formatIfNeeded = false) ?: return
        try {
            for (pkg in vault.hiddenApps) restoreAllUsers(context, pkg, "r", realPin, s, mp)
            for (pkg in vault.uninstallApps) {
                val apk = "$mp/${tag(pkg)}.a"
                if (exists(apk) && !isInstalled(pkg)) {
                    // APK reinstall is one shared operation; data is then restored per user.
                    if (AppDataVault.restoreApks(realPin, s, apk, scratch(context))) {
                        restoreAllUsers(context, pkg, "r", realPin, s, mp)
                    }
                }
            }
        } finally { HiddenVolume.unmount(HiddenVolume.Role.HIDDEN) }
    }

    private fun exists(path: String) = PrivilegedShell.exec("test -e '$path'").isSuccess
    private fun isInstalled(pkg: String) = PrivilegedShell.exec("pm path '$pkg'").isSuccess
}
