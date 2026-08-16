package com.thenile.vault.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.thenile.vault.state.SettingsManager
import com.thenile.vault.ui.AdminActivity
import com.topjohnwu.superuser.Shell

class NileAccessibilityService : AccessibilityService() {

    private var lastVolDownTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Belt-and-suspenders: the res/xml config already requests this, but some OEM skins
        // only honor the flag when it's also set here at runtime.
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!SettingsManager.getInstance(this).enableVolumeKeys) return super.onKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolDownTime in 100..1000) {
                // Double-tap Volume Down detected!
                lastVolDownTime = 0L
                val intent = Intent(this, AdminActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Shell.cmd("am start -n com.thenile.vault/.ui.AdminActivity").exec()
                }
                return true
            }
            lastVolDownTime = now
        }
        return super.onKeyEvent(event)
    }
}
