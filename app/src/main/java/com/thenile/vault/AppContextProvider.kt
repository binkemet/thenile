package com.thenile.vault

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

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
        return true
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
