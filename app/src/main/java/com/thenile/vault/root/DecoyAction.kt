package com.thenile.vault.root

import android.content.Context
import com.thenile.vault.state.SettingsManager
import com.thenile.vault.state.VaultState
import com.thenile.vault.state.VaultStateManager

/** The decoy hide operation, used by Nile's own PromptActivity PIN pad. The real-lock-screen path
 *  (PackageManagerHook.triggerDecoyHide) does NOT call this — it runs the equivalent commands
 *  directly via su instead of going through Nile's app process (see that function's doc comment). */
object DecoyAction {
    fun run(context: Context, code: String): Boolean {
        val settings = SettingsManager.getInstance(context)
        VaultStateManager.getInstance(context).updateState(VaultState.DECOY)
        val targets = settings.getDecoyPackagesForCode(code)
        val dirs = settings.getDecoyDirectoriesForCode(code)
        val dummy = settings.getDecoyDummyDirectoriesForCode(code)
        val ok = StorageMountManager.mountDecoyDirectory(targets, dirs, dummy)
        targets.forEach { pkg -> TraceCleaner.cleanTraces(pkg) }
        return ok
    }
}
