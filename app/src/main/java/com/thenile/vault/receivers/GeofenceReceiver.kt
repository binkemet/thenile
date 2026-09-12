package com.thenile.vault.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.thenile.vault.root.GeofenceSwitch

/** Receives the proximity alert GeofenceSwitch arms, plus BOOT_COMPLETED to re-arm it (proximity
 *  alerts, like alarms, don't survive a reboot). */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            GeofenceSwitch.reschedule(context)
            return
        }
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        GeofenceSwitch.onProximityChanged(context, entering)
    }
}
