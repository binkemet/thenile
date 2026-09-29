package com.thenile.vault.root

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Optional, opt-in progress notification for hide/unlock (see SettingsManager.showProgressNotifications).
 *  Never call this from the decoy path — showing anything during a decoy trigger defeats the point.
 *  LOW importance: shows silently in the shade, no sound or heads-up popup. */
object ProgressNotifier {
    private const val CHANNEL_ID = "nile_progress"
    private const val NOTIF_ID = 4242

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Vault Progress", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** Show/update an indeterminate-progress notification with [title]. Silently does nothing if
     *  notification permission isn't granted (Android 13+) — best-effort, never crashes the caller. */
    fun show(context: Context, title: String) {
        ensureChannel(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notif) }
    }

    fun dismiss(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }
}
