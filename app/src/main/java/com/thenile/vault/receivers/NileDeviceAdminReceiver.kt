package com.thenile.vault.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.thenile.vault.state.VaultStateManager

/** Wrong-PIN-on-the-real-device-lockscreen source for WrongPinSwitch — NOT Nile's own PIN screen.
 *  Requires the user to grant Nile device admin rights (Settings > Security > Device admin apps,
 *  or via the button in Admin > Security & Auth); onPasswordFailed/Succeeded only fire for an
 *  active admin, and only "watch-login" is requested — no lock/wipe/reset capability. */
class NileDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent) {
        VaultStateManager.getInstance(context).recordWrongDeviceLockAttempt()
        Thread { com.thenile.vault.root.WrongPinSwitch.checkAndFire(context) }.start()
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        VaultStateManager.getInstance(context).resetWrongPinAttempts()
    }
}
