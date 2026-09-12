package com.thenile.vault.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import com.thenile.vault.state.SettingsManager

/** Applies (or clears) FLAG_SECURE on Nile's own windows per the user's `blockScreenshots`
 *  setting — blocks screenshots, screen recording, and the recents-screen thumbnail. Safe to call
 *  on a live window, so the setting takes effect the moment it's saved. */
fun applySecureFlag(context: Context) {
    val activity = context.findActivity() ?: return
    if (SettingsManager.getInstance(context).blockScreenshots) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
