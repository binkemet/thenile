package com.thenile.vault.root

import android.content.Context
import com.thenile.vault.state.SettingsManager

/** Removes The Nile from the device — so an inspector finds no stealth tool installed at all. Used
 *  by the in-app "Uninstall The Nile" action and by a vault's self-destruct-on-hide trigger.
 *
 *  Recovery model (see SettingsManager.restoreFromSystemConfig): the vault layout + key salt live
 *  in the encrypted /data/system config, which is OUTSIDE the app sandbox and survives `pm
 *  uninstall`. When [wipeContainer] is false we first flush that config so a later reinstall can
 *  auto-restore and re-open the kept .sysstore container. When true it's a one-way panic wipe. */
object SelfDestruct {
    private const val SELF = "com.thenile.vault"

    fun uninstallNile(context: Context, wipeContainer: Boolean) {
        if (PrivilegeManager.currentTier() != PrivilegeTier.ROOT) return
        if (wipeContainer) {
            HiddenVolume.destroy()
        } else {
            // Make sure the survive-uninstall config (vaults + key salt) is on disk before we go.
            SettingsManager.getInstance(context).syncToSystem()
        }
        // Run detached via a fresh root shell: `pm uninstall` is executed by system_server, so it
        // completes even though our own process is torn down mid-call. nohup + & so the su session
        // isn't killed with us. ponytail: fire-and-forget, no result to read — the app is leaving.
        PrivilegedShell.exec("nohup pm uninstall $SELF >/dev/null 2>&1 &")
    }
}
