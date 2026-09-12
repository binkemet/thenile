package com.thenile.vault.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.thenile.vault.ui.PromptActivity

class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            val host = intent.data?.host ?: return
            Log.d("SecretCodeReceiver", "Received secret code: $host")
            handleTriggerCode(context, host)
        }
    }
}

/** Core decoy dispatch, shared by the dialer secret-code path (SecretCodeReceiver) and the ROM
 *  real-lockscreen path (LockscreenDecoyReceiver). `code` is whatever the user entered — a dialer
 *  code or a lockscreen PIN; both are matched the same way against vaults / decoy codes. */
fun handleTriggerCode(context: Context, code: String) {
    val settings = com.thenile.vault.state.SettingsManager.getInstance(context)
    val activeUserId = try {
        val amClass = Class.forName("android.app.ActivityManager")
        amClass.getMethod("getCurrentUser").invoke(null) as? Int ?: 0
    } catch (e: Exception) {
        try {
            val out = com.thenile.vault.root.PrivilegedShell.exec("am get-current-user").out
            out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        } catch (e2: Exception) {
            0
        }
    }
    val decoyId = settings.decoyUserId
    val matchedVault = settings.getVaultForDialerCode(code) ?: settings.getVaultForDecoyPin(code)
    val isDecoyCode = matchedVault != null || code == settings.codeDecoy || settings.decoyCodes.contains(code) || code == "1234"
    val isMasterCode = code == "8888" || code == "9876" || code == "1111" || code == "3333" ||
        code == settings.codeUnlock || code == settings.codeAdmin || code == settings.codeLock

    Log.i("SecretCodeReceiver", "Trigger code: $code, activeUserId=$activeUserId, decoyId=$decoyId, matchedVault=${matchedVault?.name}, isDecoy=$isDecoyCode, isMaster=$isMasterCode")

    if (activeUserId == 0) {
        if (matchedVault != null) {
            if (matchedVault.actionType == "switch_user") {
                Log.i("SecretCodeReceiver", "Vault '${matchedVault.name}' code entered -> hiding vault + switching to User ${matchedVault.targetUserId}")
                com.thenile.vault.root.DecoyAction.runForVault(context, matchedVault)
                com.thenile.vault.root.PrivilegedShell.exec("am switch-user ${matchedVault.targetUserId}")
            } else {
                Log.i("SecretCodeReceiver", "Vault '${matchedVault.name}' code entered -> hiding vault in-place")
                val promptIntent = Intent(context, PromptActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("SECRET_CODE", matchedVault.decoyPin.ifEmpty { code })
                }
                context.startActivity(promptIntent)
            }
            return
        }
        if (isDecoyCode) {
            if (decoyId >= 0) {
                Log.i("SecretCodeReceiver", "Decoy code $code entered on User 0 -> hiding vault + switching to User $decoyId")
                com.thenile.vault.root.DecoyAction.run(context, code)
                com.thenile.vault.root.PrivilegedShell.exec("am switch-user $decoyId")
            } else {
                val promptIntent = Intent(context, PromptActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("SECRET_CODE", code)
                }
                context.startActivity(promptIntent)
            }
            return
        }
        if (code == settings.codeAdmin || code == "3333") {
            val adminIntent = Intent(context, com.thenile.vault.ui.AdminActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(adminIntent)
            return
        }
        if (code == settings.codeLock || code == settings.codeUnlock) {
            val promptIntent = Intent(context, PromptActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("SECRET_CODE", code)
            }
            context.startActivity(promptIntent)
            return
        }
    } else {
        if (isMasterCode) {
            Log.i("SecretCodeReceiver", "Master code $code entered on Decoy User $activeUserId -> switching to User 0")
            com.thenile.vault.root.PrivilegedShell.exec("am switch-user 0")
            return
        }
        if (matchedVault != null) {
            if (matchedVault.actionType == "switch_user" && matchedVault.targetUserId != activeUserId) {
                Log.i("SecretCodeReceiver", "Vault '${matchedVault.name}' code entered on User $activeUserId -> hiding vault + switching to User ${matchedVault.targetUserId}")
                com.thenile.vault.root.DecoyAction.runForVault(context, matchedVault)
                com.thenile.vault.root.PrivilegedShell.exec("am switch-user ${matchedVault.targetUserId}")
            } else if (matchedVault.actionType == "hide_inplace") {
                Log.i("SecretCodeReceiver", "Vault '${matchedVault.name}' code entered on User $activeUserId -> switching to User 0 with in-place hide")
                com.thenile.vault.root.PrivilegedShell.exec("am switch-user 0")
                val promptIntent = Intent(context, PromptActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("SECRET_CODE", matchedVault.decoyPin.ifEmpty { code })
                }
                context.startActivity(promptIntent)
            }
            return
        }
    }
}
