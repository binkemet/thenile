package com.thenile.vault.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Real-lockscreen decoy trigger for the AOSP (root, no-Xposed) edition. A ROM's Keyguard is
 *  patched to read Nile's /data/system/thenile_config.json, and when the PIN typed on the real lock
 *  screen matches a decoy PIN it broadcasts here — instead of Nile hooking system_server. The
 *  <receiver> is guarded by a signature|privileged permission so only the platform can send it.
 *  See docs/rom-lockscreen-trigger.md for the ~20-line Keyguard patch. */
class LockscreenDecoyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pin = intent.getStringExtra(EXTRA_PIN)?.takeIf { it.isNotBlank() } ?: return
        Log.i("LockscreenDecoy", "ROM Keyguard reported decoy PIN match -> dispatching")
        handleTriggerCode(context, pin)
    }

    companion object {
        const val ACTION = "com.thenile.vault.action.LOCKSCREEN_DECOY"
        const val EXTRA_PIN = "pin"
    }
}
