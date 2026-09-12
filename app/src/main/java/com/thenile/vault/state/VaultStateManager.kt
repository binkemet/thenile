package com.thenile.vault.state

import android.content.Context
import android.content.SharedPreferences
import com.thenile.vault.root.StorageMountManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VaultState {
    LOCKED,
    DECOY,
    UNLOCKED
}

class VaultStateManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("vault_state", Context.MODE_PRIVATE)

    private val _currentState = MutableStateFlow(getSavedState())
    val currentState: StateFlow<VaultState> = _currentState.asStateFlow()

    fun updateState(newState: VaultState) {
        val editor = prefs.edit().putString("state", newState.name)
        if (newState == VaultState.UNLOCKED) editor.putLong("lastRealUnlockAt", System.currentTimeMillis())
        editor.commit()
        _currentState.value = newState
        if (newState == VaultState.UNLOCKED) com.thenile.vault.root.AuditLog.record(context, "Real vault unlocked")
        // debug.* is shell-writable by design (no root needed) — works under Shizuku too. Under
        // PrivilegeTier.NONE this just fails silently, same as everything else that needs it
        // (PackageManagerHook can't run without root/Zygisk at all, so nothing reads this prop then).
        com.thenile.vault.root.PrivilegedShell.exec("setprop ${com.thenile.vault.Config.STATE_PROP} ${newState.name}")
    }

    /** Millis since epoch of the last time the REAL vault was unlocked (not decoy) — the dead man's
     *  switch clock. 0 if never unlocked since install. */
    fun lastRealUnlockAt(): Long = prefs.getLong("lastRealUnlockAt", 0L)

    fun isUnlocked(): Boolean {
        return _currentState.value == VaultState.UNLOCKED
    }

    private fun getSavedState(): VaultState {
        val saved = try {
            VaultState.valueOf(prefs.getString("state", VaultState.LOCKED.name) ?: VaultState.LOCKED.name)
        } catch (e: Exception) {
            VaultState.LOCKED
        }
        // Cold boot must be LOCKED (spec). The runtime prop is volatile (cleared on reboot) and the
        // container mount lives in init's namespace — both survive app-process death but NOT a reboot,
        // exactly like an active session. So a persisted UNLOCKED/DECOY is only real if the prop still
        // agrees; after a reboot it won't, so fall back to LOCKED and heal the persisted value.
        //
        // This check only applies to the root tier's bind-mounted vault. SoftVault (Shizuku/no-root)
        // has no mount to lose on reboot — hide/unhide are real file moves that stay exactly as they
        // were left — so cross-checking the prop there would incorrectly force a persisted UNLOCKED
        // back to LOCKED after every reboot while the files themselves are still sitting unhidden,
        // desyncing Nile's own idea of its state from what's actually on disk.
        if (com.thenile.vault.root.PrivilegeManager.currentTier() == com.thenile.vault.root.PrivilegeTier.ROOT &&
            saved != VaultState.LOCKED && liveStateProp() != saved.name) {
            prefs.edit().putString("state", VaultState.LOCKED.name).commit()
            return VaultState.LOCKED
        }
        return saved
    }

    private fun liveStateProp(): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java, String::class.java)
            .invoke(null, com.thenile.vault.Config.STATE_PROP, VaultState.LOCKED.name) as String
    } catch (e: Exception) {
        VaultState.LOCKED.name
    }
    
    // --- PIN (Argon2 via StorageMountManager JNI) -------------------------------------------

    fun isPinEnrolled(): Boolean = prefs.contains("pin_hash")

    /** First-run enrollment: the first PIN the user types becomes the real PIN. */
    fun enrollPin(pin: String) {
        prefs.edit().putString("pin_hash", StorageMountManager.hashPin(pin)).commit()
    }

    fun verifyPin(pin: String): Boolean {
        val stored = prefs.getString("pin_hash", null) ?: return false
        return StorageMountManager.verifyPin(pin, stored)
    }

    /** WrongPinSwitch's counter — driven by NileDeviceAdminReceiver watching the REAL Android
     *  lock screen (onPasswordFailed/onPasswordSucceeded), not Nile's own PIN screen above. */
    fun recordWrongDeviceLockAttempt() {
        val n = wrongPinAttempts() + 1
        prefs.edit().putInt("wrongPinAttempts", n).commit()
        com.thenile.vault.root.AuditLog.record(context, "Wrong device lock-screen attempt (#$n)")
    }

    fun resetWrongPinAttempts() {
        prefs.edit().putInt("wrongPinAttempts", 0).commit()
    }

    fun wrongPinAttempts(): Int = prefs.getInt("wrongPinAttempts", 0)

    /** Stable per-install salt so the derived LUKS key is reproducible across mounts. */
    fun keySalt(): String {
        prefs.getString("key_salt", null)?.let { return it }
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val salt = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("key_salt", salt).commit()
        return salt
    }

    companion object {
        @Volatile
        private var instance: VaultStateManager? = null

        fun getInstance(context: Context): VaultStateManager {
            return instance ?: synchronized(this) {
                instance ?: VaultStateManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
