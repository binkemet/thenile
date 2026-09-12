package com.thenile.vault

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build

/** Captures the application Context at process start — providers attach before Application.onCreate
 *  runs — so privilege-tier singletons (PrivilegeManager, ShizukuShell) that live outside any
 *  Activity/Service can reach one without every StorageMountManager/PrivilegedShell caller
 *  threading a Context through. Does nothing else; never queried. */
class AppContextProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        appContext = context!!.applicationContext
        // Must be set before ANY entry point can touch HiddenVolume/StorageMountManager — this
        // used to happen only in PromptActivity.onCreate(), so AdminActivity's own capture/hide
        // buttons (reachable straight from the launcher, no secret code needed first) silently
        // failed with "dmcrypt helper not set" on a fresh install. dm-crypt helper lives in
        // nativeLibraryDir (extracted, executable) and runs as root.
        com.thenile.vault.root.StorageMountManager.dmcryptBin =
            "${appContext.applicationInfo.nativeLibraryDir}/libdmcrypt.so"

        // ACTION_POWER_CONNECTED isn't in Android 8+'s implicit-broadcast exemption list, so a
        // manifest <receiver> for it is just silently skipped ("Background execution not allowed")
        // whenever the app isn't already running — confirmed on-device via `dumpsys activity
        // broadcasts history`. A context-registered receiver still only fires while the process is
        // alive, but that's the best a stealth app (no foreground service) can do for an instant
        // reaction; see UsbPluggedSwitch for the other half.
        register(com.thenile.vault.receivers.UsbPluggedReceiver(), IntentFilter(Intent.ACTION_POWER_CONNECTED))

        // Same restriction applies to both of these — see DeviceTamperSwitch.
        register(com.thenile.vault.receivers.DeviceTamperReceiver(), IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction("android.intent.action.SIM_STATE_CHANGED")
        })
        return true
    }

    private fun register(receiver: android.content.BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        lateinit var appContext: android.content.Context
            private set
    }
}
