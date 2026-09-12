package com.thenile.vault.root

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.thenile.vault.receivers.DeadManSwitchReceiver
import com.thenile.vault.state.SettingsManager
import com.thenile.vault.state.VaultState
import com.thenile.vault.state.VaultStateManager

/** If the REAL vault hasn't been unlocked in `deadManSwitchHours`, hide every vault the user
 *  selected — same effect as manually tapping that vault's "Hide Vault" quick action. Checked
 *  periodically via an inexact repeating alarm (re-armed on boot, since alarms don't survive one)
 *  rather than only when the app happens to be opened — the whole point of a dead man's switch is
 *  that it fires without the user doing anything. */
object DeadManSwitch {
    private const val TAG = "DeadManSwitch"
    private const val ACTION_CHECK = "com.thenile.vault.action.DEAD_MAN_SWITCH_CHECK"
    // Inexact is fine: the threshold is hours-to-days, a check every ~2h has ample margin and lets
    // the system batch it with other wakeups (Doze-friendly, no special permission needed).
    private const val CHECK_INTERVAL_MS = 2 * 60 * 60 * 1000L

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_CHECK).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Call whenever the setting is enabled, its hour threshold changes, or on boot. */
    fun reschedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        am.cancel(pi)
        if (SettingsManager.getInstance(context).deadManSwitchEnabled) {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    /** Pure/testable: has the inactivity window elapsed? `lastUnlockMillis` = 0 means never
     *  unlocked since install — treated as "not yet timed out" (nothing to measure from). */
    fun isExpired(lastUnlockMillis: Long, nowMillis: Long, thresholdHours: Int): Boolean {
        if (lastUnlockMillis == 0L) return false
        val elapsedHours = (nowMillis - lastUnlockMillis) / (60 * 60 * 1000L)
        return elapsedHours >= thresholdHours
    }

    /** Runs the actual check; called by [DeadManSwitchReceiver] off the main thread (needs root). */
    fun checkAndFire(context: Context) {
        val settings = SettingsManager.getInstance(context)
        if (!settings.deadManSwitchEnabled) return
        val lastUnlock = VaultStateManager.getInstance(context).lastRealUnlockAt()
        if (!isExpired(lastUnlock, System.currentTimeMillis(), settings.deadManSwitchHours)) return

        val targetIds = settings.deadManSwitchVaultIds
        if (targetIds.isEmpty()) return
        Log.w(TAG, "dead man's switch fired: hiding $targetIds")
        for (vault in settings.vaults.filter { it.id in targetIds }) {
            StorageMountManager.unmountAndLock(vault.packages, vault.directories, vault.dummyDirectories, vault.files, context = context)
        }
        VaultStateManager.getInstance(context).updateState(VaultState.LOCKED)
    }
}
