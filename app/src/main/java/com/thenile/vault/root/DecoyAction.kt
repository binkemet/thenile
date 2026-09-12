package com.thenile.vault.root

import android.content.Context
import com.thenile.vault.state.Vault
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
        val files = settings.getDecoyFilesForCode(code)
        val ok = StorageMountManager.mountDecoyDirectory(targets, dirs, dummy, files, context)
        TraceCleaner.cleanAllTraces(targets, dirs, files)
        return ok
    }

    /** Hide a single vault's own hide targets (its packages/dirs/files) using the vault's fields
     *  directly, rather than looking them up by code. Used by the switch_user path so a decoy that
     *  switches user ALSO triggers its vault first — the mount runs via su regardless of which user
     *  is foreground, so we hide before switching (Nile's process may be frozen after the switch).
     *  No-op (returns true) when the vault has nothing to hide. */
    fun runForVault(context: Context, vault: Vault): Boolean {
        if (!vault.hideOnDecoy) return true
        // hiddenApps use copy-based snapshot swapping (restore the anodyne data), separate from the
        // bind-mount hide of packages/dirs/files below.
        if (vault.hiddenApps.isNotEmpty() || vault.uninstallApps.isNotEmpty())
            HiddenAppManager.showDecoy(context, vault, vault.decoyPin)
        if (vault.packages.isEmpty() && vault.directories.isEmpty() &&
            vault.dummyDirectories.isEmpty() && vault.files.isEmpty()) return true
        VaultStateManager.getInstance(context).updateState(VaultState.DECOY)
        val ok = StorageMountManager.mountDecoyDirectory(
            vault.packages, vault.directories, vault.dummyDirectories, vault.files, context
        )
        TraceCleaner.cleanAllTraces(vault.packages, vault.directories, vault.files)
        return ok
    }
}
