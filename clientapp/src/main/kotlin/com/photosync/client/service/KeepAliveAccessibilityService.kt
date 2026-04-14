package com.photosync.client.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * A minimal accessibility service whose only purpose is to keep the PhotoSync process
 * alive on OEM devices (Samsung, Xiaomi, Huawei, etc.) that aggressively kill foreground
 * services. Accessibility services run at system service priority and are rarely killed.
 *
 * The user must enable this once in Settings → Accessibility → PhotoSync Client.
 */
class KeepAliveAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
