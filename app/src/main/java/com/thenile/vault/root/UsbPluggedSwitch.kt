package com.thenile.vault.root

import android.content.Context
import android.util.Log
import com.thenile.vault.state.SettingsManager
import com.thenile.vault.state.VaultState
import com.thenile.vault.state.VaultStateManager

/** Plugging the phone into USB power hides the selected vaults — same effect as manually tapping
 *  that vault's "Hide Vault" quick action. Checked right when ACTION_POWER_CONNECTED fires (a USB
 *  plug-in), not on a timer — see UsbPluggedReceiver. */
object UsbPluggedSwitch {
    private const val TAG = "UsbPluggedSwitch"

    fun checkAndFire(context: Context) {
        val settings = SettingsManager.getInstance(context)
        if (!settings.usbSwitchEnabled) return

        val targetIds = settings.usbSwitchVaultIds
        if (targetIds.isEmpty()) return
        SecureLog.w(TAG, "USB-plugged switch fired: hiding $targetIds")
        AuditLog.record(context, "USB plugged in — hid ${targetIds.size} vault(s)")
        for (vault in settings.vaults.filter { it.id in targetIds }) {
            StorageMountManager.unmountAndLock(vault.packages, vault.directories, vault.dummyDirectories, vault.files, context = context)
        }
        VaultStateManager.getInstance(context).updateState(VaultState.LOCKED)
    }
}
