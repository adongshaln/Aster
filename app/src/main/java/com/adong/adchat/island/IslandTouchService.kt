package com.adong.adchat.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Supplies an accessibility-overlay window token, without observing other apps or input. */
class IslandTouchService : AccessibilityService() {
    companion object {
        private val activeHost = MutableStateFlow<IslandTouchService?>(null)
        internal val connectedHost = activeHost.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeHost.value = this
        // The user may have enabled compatibility while the ordinary island service was stopped.
        // Starting it still respects the island's master switch and notification permission.
        IslandService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        detachHost()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detachHost()
        super.onDestroy()
    }

    private fun detachHost() {
        if (activeHost.value === this) activeHost.value = null
    }
}
