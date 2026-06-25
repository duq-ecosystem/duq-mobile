package com.duq.android.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class DuqAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DuqAccessibility"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected - listener starts when app opens")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.d(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }
}
