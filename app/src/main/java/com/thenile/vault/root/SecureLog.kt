package com.thenile.vault.root

import android.util.Log
import com.thenile.vault.BuildConfig

/** Logging that vanishes in release builds. The auto-hide switches describe exactly what they're
 *  hiding and when — useful while developing, but in a shipped build those lines in logcat are a
 *  plausible-deniability leak (anyone with `adb logcat` or a log-reader app sees the vault
 *  machinery operating). Debug builds still log normally so on-device testing is unaffected. */
object SecureLog {
    fun w(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.w(tag, msg) }
    fun i(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.i(tag, msg) }
    fun d(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.d(tag, msg) }
}
