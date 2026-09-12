package com.thenile.vault.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thenile.vault.root.ScheduledLockSwitch

/** Fires on the periodic alarm ScheduledLockSwitch schedules, and on boot (alarms don't survive a
 *  reboot, so this re-arms the repeating check and also runs one immediately). */
class ScheduledLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ScheduledLockSwitch.reschedule(context)
        }
        ScheduledLockSwitch.checkAndFire(context)
    }
}
