package com.thenile.vault.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thenile.vault.root.ScreenOffSwitch

/** Fires when the screen-off delay elapses. Manifest-registered (unlike ScreenReceiver) so the
 *  alarm still lands if the process died while the screen was off — the custom action is explicit
 *  via setPackage, so the implicit-broadcast restrictions don't apply. */
class ScreenOffCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScreenOffSwitch.checkAndFire(context)
    }
}
