package com.thenile.vault.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.thenile.vault.root.UsbPluggedSwitch

/** ACTION_POWER_CONNECTED fires for ANY power source (USB, AC, wireless) and doesn't carry which
 *  one — so it's re-checked here via a sticky ACTION_BATTERY_CHANGED query, firing only on
 *  BATTERY_PLUGGED_USB. */
class UsbPluggedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        if (plugged != BatteryManager.BATTERY_PLUGGED_USB) return
        UsbPluggedSwitch.checkAndFire(context)
    }
}
