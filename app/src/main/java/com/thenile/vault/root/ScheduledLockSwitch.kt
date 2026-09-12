package com.thenile.vault.root

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.thenile.vault.state.SettingsManager
import com.thenile.vault.state.VaultState
import com.thenile.vault.state.VaultStateManager
import java.util.Calendar

/** Hides the selected vaults during a daily clock-time window (e.g. 23:00-06:00) — same effect as
 *  manually tapping that vault's "Hide Vault" quick action. Checked periodically via an inexact
 *  repeating alarm (re-armed on boot, like DeadManSwitch) since exact-alarm permission isn't worth
 *  requiring for a "lock overnight" feature — a 15-minute check interval is close enough. */
object ScheduledLockSwitch {
    private const val TAG = "ScheduledLockSwitch"
    private const val ACTION_CHECK = "com.thenile.vault.action.SCHEDULED_LOCK_CHECK"
    private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_CHECK).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Call whenever the setting is enabled, its window changes, or on boot. */
    fun reschedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        am.cancel(pi)
        if (SettingsManager.getInstance(context).scheduledLockEnabled) {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    /** Pure/testable: is `nowMinute` (0-1439, minutes since midnight) inside [startMinute,
     *  endMinute)? Handles a window that wraps past midnight (start > end, e.g. 23:00-06:00).
     *  start == end is treated as "no window" (false) rather than "always on" — a degenerate
     *  config shouldn't silently lock the vault permanently. */
    fun isInWindow(nowMinute: Int, startMinute: Int, endMinute: Int): Boolean {
        if (startMinute == endMinute) return false
        return if (startMinute < endMinute) nowMinute in startMinute until endMinute
        else nowMinute >= startMinute || nowMinute < endMinute
    }

    /** Runs the actual check; called by [com.thenile.vault.receivers.ScheduledLockReceiver] off
     *  the main thread (needs root). */
    fun checkAndFire(context: Context) {
        val settings = SettingsManager.getInstance(context)
        if (!settings.scheduledLockEnabled) return

        val cal = Calendar.getInstance()
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (!isInWindow(nowMinute, settings.scheduledLockStartMinute, settings.scheduledLockEndMinute)) return

        val targetIds = settings.scheduledLockVaultIds
        if (targetIds.isEmpty()) return
        SecureLog.w(TAG, "scheduled lock fired: hiding $targetIds")
        AuditLog.record(context, "Scheduled lockdown fired (in daily window) — hid ${targetIds.size} vault(s)")
        for (vault in settings.vaults.filter { it.id in targetIds }) {
            StorageMountManager.unmountAndLock(vault.packages, vault.directories, vault.dummyDirectories, vault.files, context = context)
        }
        VaultStateManager.getInstance(context).updateState(VaultState.LOCKED)
    }
}
