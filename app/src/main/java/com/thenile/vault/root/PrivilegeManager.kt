package com.thenile.vault.root

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

enum class PrivilegeTier {
    /** su available — full StorageMountManager mount/dm-crypt path, PackageManagerHook (Xposed). */
    ROOT,
    /** Shell UID (2000) via Shizuku/ADB — am switch-user and am force-stop work (verified
     *  on-device: an actual user switch via plain `adb shell am switch-user`). pm hide/unhide do
     *  NOT work on Android 14+ (confirmed on API 35): setApplicationHiddenSettingAsUser now
     *  requires MANAGE_USERS, which shell doesn't hold — SecurityException, not a bug in
     *  PrivilegedShell. mount/nsenter/dm-crypt don't work either (no CAP_SYS_ADMIN), so
     *  directory/file hiding falls back to SoftVault (confirmed working). No Xposed — the
     *  real-lockscreen decoy trick is unavailable. */
    SHIZUKU,
    /** Neither. SoftVault only; no package hiding, no vault switching. */
    NONE
}

/** Picks and caches which privilege tier this device/session actually has. Root is checked once
 *  per process (su prompts/binds a shell) and then trusted; Shizuku's binder can come and go (the
 *  Shizuku app/service can be killed independently of Nile) so that check is re-done each call. */
object PrivilegeManager {
    private const val TAG = "PrivilegeManager"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 8341

    @Volatile private var rootChecked = false
    @Volatile private var rootAvailable = false

    private fun isRootAvailable(): Boolean {
        if (rootChecked) return rootAvailable
        rootAvailable = try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
        rootChecked = true
        return rootAvailable
    }

    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        // Shizuku not installed/running at all throws rather than returning false.
        false
    }

    fun isShizukuPermissionGranted(): Boolean = try {
        isShizukuAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** Only meaningful to call when isShizukuAvailable() and permission isn't already granted;
     *  the result arrives via Shizuku.OnRequestPermissionResultListener, not a return value. */
    fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            Log.w(TAG, "requestShizukuPermission failed: ${e.message}")
        }
    }

    fun currentTier(): PrivilegeTier = when {
        isRootAvailable() -> PrivilegeTier.ROOT
        isShizukuPermissionGranted() -> PrivilegeTier.SHIZUKU
        else -> PrivilegeTier.NONE
    }

    /** Root bypasses scoped storage entirely (everything runs via a root shell). SoftVault
     *  (Shizuku/none) runs plain java.io.File calls inside Nile's own process, which scoped
     *  storage silently blocks for directories/files the app didn't create — confirmed on-device:
     *  without this, a vault's hidden directories/files just don't get touched, no error. Not
     *  needed at all under ROOT. */
    fun isManageStorageGranted(): Boolean =
        isRootAvailable() || Environment.isExternalStorageManager()

    /** Opens the system's per-app "All files access" screen. There's no ActivityResultContract
     *  for this (it's not a runtime permission dialog) — the caller finds out the grant happened
     *  via isManageStorageGranted() next time the UI recomposes (e.g. on resume), same pattern as
     *  Shizuku's permission listener refreshing privilegeTick in AdminScreen. */
    fun requestManageStoragePermission(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                Log.w(TAG, "requestManageStoragePermission failed: ${e2.message}")
            }
        }
    }
}
