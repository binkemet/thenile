package com.thenile.vault.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.thenile.vault.ui.PromptActivity

class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            val uri = intent.data
            val host = uri?.host ?: return
            Log.d("SecretCodeReceiver", "Received secret code: $host")

            val settings = com.thenile.vault.state.SettingsManager.getInstance(context)
            val isKnown = settings.codeLock == host || settings.codeDecoy == host ||
                          settings.codeUnlock == host || settings.codeAdmin == host

            if (isKnown) {
                val promptIntent = Intent(context, PromptActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("SECRET_CODE", host)
                }
                context.startActivity(promptIntent)
            }
        }
    }
}
