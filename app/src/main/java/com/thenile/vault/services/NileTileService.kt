package com.thenile.vault.services

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.thenile.vault.ui.AdminActivity
import com.topjohnwu.superuser.Shell

class NileTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, AdminActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            // Fallback via root launch
            Shell.cmd("am start -n com.thenile.vault/.ui.AdminActivity").exec()
        }
    }
}
