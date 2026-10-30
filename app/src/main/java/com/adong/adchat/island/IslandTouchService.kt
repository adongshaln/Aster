package com.adong.adchat.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Hosts the island and captures one frame only after the user requests screen selection. */
class IslandTouchService : AccessibilityService() {
    companion object {
        private val activeHost = MutableStateFlow<IslandTouchService?>(null)
        internal val connectedHost = activeHost.asStateFlow()
        private val activeWindowImmersive = MutableStateFlow(false)
        /**
         * True only when the status bar around the camera cutout is genuinely hidden by an
         * immersive full-screen app (status-bar window absent from the top edge). Never relies on
         * `AccessibilityEvent.isFullScreen`, which edge-to-edge portrait apps and launchers also set.
         */
        internal val windowFullScreen = activeWindowImmersive.asStateFlow()

        internal fun computeImmersiveStatusBarHidden(
            hasVisibleTopStatusBar: Boolean,
            hasFocusedApplicationWindow: Boolean,
        ): Boolean = hasFocusedApplicationWindow && !hasVisibleTopStatusBar
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeHost.value = this
        refreshImmersiveState()
        // The user may have enabled compatibility while the ordinary island service was stopped.
        // Starting it still respects the island's master switch and notification permission.
        IslandService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            refreshImmersiveState()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshImmersiveState()
    }

    /**
     * Inspect window geometry metadata only (`canRetrieveWindowContent=false` remains enforced).
     * When the system status bar is visible along the top edge, normal edge-to-edge apps and the
     * launcher are NOT immersive and must never hide the island ring.
     */
    internal fun refreshImmersiveState() {
        val windowList = runCatching { windows }.getOrNull()
        if (windowList.isNullOrEmpty()) {
            activeWindowImmersive.value = false
            return
        }
        val statusBarMaxHeightPx = (96f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val screenWidthPx = resources.displayMetrics.widthPixels
        var hasTopStatusBar = false
        var hasApplicationWindow = false
        val bounds = Rect()
        try {
            for (window in windowList) {
                window.getBoundsInScreen(bounds)
                if (bounds.isEmpty) continue
                when (window.type) {
                    AccessibilityWindowInfo.TYPE_SYSTEM -> {
                        // StatusBar spans the top of the screen with a compact bar height; full-screen
                        // notification shade / lock-screen system windows are much taller.
                        val spansWidth = screenWidthPx <= 0 || bounds.width() >= screenWidthPx / 2
                        if (bounds.top <= 2 && bounds.height() in 1..statusBarMaxHeightPx && spansWidth) {
                            hasTopStatusBar = true
                        }
                    }
                    AccessibilityWindowInfo.TYPE_APPLICATION -> {
                        if (window.isActive || window.isFocused || bounds.top <= 2) {
                            hasApplicationWindow = true
                        }
                    }
                }
            }
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                windowList.forEach { runCatching { it.recycle() } }
            }
        }
        activeWindowImmersive.value = computeImmersiveStatusBarHidden(
            hasVisibleTopStatusBar = hasTopStatusBar,
            hasFocusedApplicationWindow = hasApplicationWindow,
        )
    }

    override fun onInterrupt() = Unit

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureScreen(): Bitmap = suspendCancellableCoroutine { continuation ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                // The hardware buffer must be closed even if capture was cancelled or copying fails.
                val result = runCatching {
                    screenshot.hardwareBuffer.use { buffer ->
                        val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace))
                        try {
                            checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                        } finally {
                            hardware.recycle()
                        }
                    }
                }
                if (!continuation.isActive) {
                    result.getOrNull()?.recycle()
                    return
                }
                result.fold({ continuation.resume(it) }, {
                    continuation.resumeWithException(IllegalStateException("无法准备屏幕画面，请重试"))
                })
            }

            override fun onFailure(errorCode: Int) {
                if (!continuation.isActive) return
                val message = when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "请在系统设置中重新开启星岛无障碍服务，允许主动截屏"
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截屏操作太快，请稍等片刻再试"
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前页面禁止截屏，请切换页面后再试"
                    else -> "当前屏幕暂时无法截取，请重试"
                }
                continuation.resumeWithException(IllegalStateException(message))
            }
        })
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detachHost()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detachHost()
        super.onDestroy()
    }

    private fun detachHost() {
        if (activeHost.value === this) {
            activeWindowImmersive.value = false
            activeHost.value = null
        }
    }
}
