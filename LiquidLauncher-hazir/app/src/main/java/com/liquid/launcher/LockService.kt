package com.liquid.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class LockService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }
    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
    }
    override fun onUnbind(intent: Intent?): Boolean {
        inst = null
        return super.onUnbind(intent)
    }
    companion object {
        var inst: LockService? = null
    }
}
