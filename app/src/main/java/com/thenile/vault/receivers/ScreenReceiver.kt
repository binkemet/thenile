package com.thenile.vault.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thenile.vault.root.ScreenOffSwitch

/** Dynamically registered in AppContextProvider — ACTION_SCREEN_OFF/ACTION_SCREEN_ON are
 *  registered-only broadcasts that a manifest <receiver> can never get. Starts the screen-off
 *  countdown, and cancels it as soon as the screen comes back on. */
class ScreenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> ScreenOffSwitch.onScreenOff(context)
            Intent.ACTION_SCREEN_ON -> ScreenOffSwitch.onScreenOn(context)
        }
    }
}
