package com.thenile.vault.root

import android.content.Context
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import com.thenile.vault.state.SettingsManager
import com.thenile.vault.state.VaultState
import com.thenile.vault.state.VaultStateManager

/** Hides the selected vaults if airplane mode gets turned on, or the SIM card is swapped/removed —
 *  both common signs the device has been seized or handed off. Same effect as manually tapping
 *  that vault's "Hide Vault" quick action. Triggered by AIRPLANE_MODE_CHANGED and SIM_STATE_CHANGED,
 *  registered dynamically in AppContextProvider — see UsbPluggedReceiver for why (neither action is
 *  in Android 8+'s implicit-broadcast exemption list for manifest receivers). */
object DeviceTamperSwitch {
    private const val TAG = "DeviceTamperSwitch"

    fun isAirplaneModeOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    /** Null if the SIM serial can't be read (no SIM, permission denied, or restricted on this
     *  Android version) — that absence is itself treated as a change from a previously-known SIM. */
    fun currentSimSerial(context: Context): String? = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (tm.simState != TelephonyManager.SIM_STATE_READY) null else tm.simSerialNumber
    } catch (e: SecurityException) {
        null
    }

    /** Pure/testable. baseline == "" means no baseline captured yet (never treated as a change —
     *  the caller should capture one instead of firing on it). */
    fun hasSimChanged(baseline: String, current: String?): Boolean {
        if (baseline.isEmpty()) return false
        return current != baseline
    }

    fun checkAirplaneMode(context: Context) {
        if (isAirplaneModeOn(context)) fire(context, "airplane mode enabled")
    }

    fun checkSimState(context: Context) {
        val settings = SettingsManager.getInstance(context)
        val current = currentSimSerial(context)
        if (settings.tamperSimSerialBaseline.isEmpty()) {
            if (current != null) settings.tamperSimSerialBaseline = current
            return
        }
        if (hasSimChanged(settings.tamperSimSerialBaseline, current)) fire(context, "SIM changed or removed")
    }

    private fun fire(context: Context, reason: String) {
        val settings = SettingsManager.getInstance(context)
        if (!settings.tamperSwitchEnabled) return
        val targetIds = settings.tamperSwitchVaultIds
        if (targetIds.isEmpty()) return
        SecureLog.w(TAG, "device tamper switch fired ($reason): hiding $targetIds")
        AuditLog.record(context, "Device tamper: $reason — hid ${targetIds.size} vault(s)")
        for (vault in settings.vaults.filter { it.id in targetIds }) {
            StorageMountManager.unmountAndLock(vault.packages, vault.directories, vault.dummyDirectories, vault.files, context = context)
        }
        VaultStateManager.getInstance(context).updateState(VaultState.LOCKED)
    }
}
