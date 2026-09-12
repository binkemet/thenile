package com.thenile.vault.root

import android.content.Context
import android.util.Log
import com.thenile.vault.state.SettingsManager
import com.thenile.vault.state.VaultState
import com.thenile.vault.state.VaultStateManager

/** N consecutive wrong real-vault PIN entries hides the selected vaults — same effect as manually
 *  tapping that vault's "Hide Vault" quick action. A correct PIN clears the counter (see
 *  VaultStateManager.verifyPin). Checked right after a failed attempt, not on a timer — unlike
 *  DeadManSwitch, there's no reason to wait for the next periodic check. */
object WrongPinSwitch {
    private const val TAG = "WrongPinSwitch"

    /** Pure/testable. */
    fun isExpired(attempts: Int, limit: Int): Boolean = limit > 0 && attempts >= limit

    /** Call off the main thread (root shell) right after a failed PIN verification. */
    fun checkAndFire(context: Context) {
        val settings = SettingsManager.getInstance(context)
        if (!settings.wrongPinSwitchEnabled) return
        val attempts = VaultStateManager.getInstance(context).wrongPinAttempts()
        if (!isExpired(attempts, settings.wrongPinSwitchLimit)) return

        val targetIds = settings.wrongPinSwitchVaultIds
        if (targetIds.isEmpty()) return
        Log.w(TAG, "wrong-PIN switch fired: $attempts attempts, hiding $targetIds")
        AuditLog.record(context, "Wrong PIN lockdown fired ($attempts wrong device unlocks) — hid ${targetIds.size} vault(s)")
        for (vault in settings.vaults.filter { it.id in targetIds }) {
            StorageMountManager.unmountAndLock(vault.packages, vault.directories, vault.dummyDirectories, vault.files, context = context)
        }
        VaultStateManager.getInstance(context).updateState(VaultState.LOCKED)
    }
}
