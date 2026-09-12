package com.thenile.vault.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thenile.vault.root.DeviceTamperSwitch

/** Dynamically registered in AppContextProvider for ACTION_AIRPLANE_MODE_CHANGED and
 *  ACTION_SIM_STATE_CHANGED — see DeviceTamperSwitch for why manifest registration doesn't work
 *  for either. Only reacts to the SIM turning un-ready (removed/locked/swapped), not every state
 *  transition (e.g. a boot-time READY doesn't count as a change on its own). */
class DeviceTamperReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> DeviceTamperSwitch.checkAirplaneMode(context)
            "android.intent.action.SIM_STATE_CHANGED" -> DeviceTamperSwitch.checkSimState(context)
        }
    }
}
