package com.secretdialer.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class CallRecordingAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No action needed - this service serves as an OS-level permission bypass hook
    }

    override fun onInterrupt() {
        // No action needed
    }
}
