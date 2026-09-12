package com.thenile.vault.root

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.thenile.vault.state.SettingsManager
import com.thenile.vault.state.VaultState
import com.thenile.vault.state.VaultStateManager

/** Hides the selected vaults once the screen has stayed off for the configured timeout — so an
 *  unlocked vault doesn't stay open after the phone is set down. The screen-off/on events come
 *  from ScreenReceiver (dynamically registered; SCREEN_OFF/SCREEN_ON can't be declared in a
 *  manifest at all), and the delay itself is a one-shot alarm cancelled the moment the screen
 *  comes back on. No boot re-arm needed: the screen is on during boot, so there's never a pending
 *  check to restore. */
object ScreenOffSwitch {
    private const val TAG = "ScreenOffSwitch"
    const val ACTION_CHECK = "com.thenile.vault.action.SCREEN_OFF_CHECK"

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_CHECK).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Pure/testable: a timeout of 0 (or less) disables the switch rather than firing instantly. */
    fun timeoutMillis(minutes: Int): Long? =
        if (minutes <= 0) null else minutes * 60 * 1000L

    fun onScreenOff(context: Context) {
        val settings = SettingsManager.getInstance(context)
        if (!settings.screenOffSwitchEnabled) return
        val delay = timeoutMillis(settings.screenOffTimeoutMinutes) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + delay, pendingIntent(context))
    }

    fun onScreenOn(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    /** Runs when the delay elapses. Re-checks the screen is still off, so a screen-on that raced
     *  the alarm cancellation can't hide the vault out from under the user. */
    fun checkAndFire(context: Context) {
        val settings = SettingsManager.getInstance(context)
        if (!settings.screenOffSwitchEnabled) return

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return

        val targetIds = settings.screenOffVaultIds
        if (targetIds.isEmpty()) return
        SecureLog.w(TAG, "screen-off switch fired after ${settings.screenOffTimeoutMinutes}min: hiding $targetIds")
        AuditLog.record(context, "Screen off ${settings.screenOffTimeoutMinutes}min — hid ${targetIds.size} vault(s)")
        for (vault in settings.vaults.filter { it.id in targetIds }) {
            StorageMountManager.unmountAndLock(vault.packages, vault.directories, vault.dummyDirectories, vault.files, context = context)
        }
        VaultStateManager.getInstance(context).updateState(VaultState.LOCKED)
    }
}
