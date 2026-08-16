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
        prefs.edit().putString("state", newState.name).commit()
        _currentState.value = newState
        com.topjohnwu.superuser.Shell.cmd("setprop ${com.thenile.vault.Config.STATE_PROP} ${newState.name}").exec()
    }

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
        if (saved != VaultState.LOCKED && liveStateProp() != saved.name) {
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
