package com.adong.adchat.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hosts the island and captures one frame only after the user requests screen selection. */
class IslandTouchService : AccessibilityService() {
    companion object {
        private val activeHost = MutableStateFlow<IslandTouchService?>(null)
        internal val connectedHost = activeHost.asStateFlow()
        private val activeWindowFullScreen = MutableStateFlow(false)
        internal val windowFullScreen = activeWindowFullScreen.asStateFlow()
    }

    private var fullScreenPackage: String? = null
    private var fullScreenWindowId: Int? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        fullScreenPackage = null
        fullScreenWindowId = null
        activeWindowFullScreen.value = false
        activeHost.value = this
        // The user may have enabled compatibility while the ordinary island service was stopped.
        // Starting it still respects the island's master switch and notification permission.
        IslandService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // The accessibility overlay itself is full-screen by geometry; only follow other apps'
        // window metadata. No node tree, text, or other app content is retrieved.
        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage == packageName || eventPackage == "com.android.systemui") return
        val inputMethodPackage = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
        )?.substringBefore('/')
        if (eventPackage == inputMethodPackage) return
        if (event.isFullScreen) {
            fullScreenPackage = eventPackage
            fullScreenWindowId = event.windowId
            activeWindowFullScreen.value = true
            return
        }
        val previousPackage = fullScreenPackage ?: run {
            activeWindowFullScreen.value = false
            return
        }
        // Ignore transient non-fullscreen child windows, IME and system chrome; exit only when
        // the same app/window leaves fullscreen or a different foreground app takes over.
        if (eventPackage != previousPackage || event.windowId == fullScreenWindowId) {
            fullScreenPackage = null
            fullScreenWindowId = null
            activeWindowFullScreen.value = false
        }
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
            fullScreenPackage = null
            fullScreenWindowId = null
            activeWindowFullScreen.value = false
            activeHost.value = null
        }
    }
}
