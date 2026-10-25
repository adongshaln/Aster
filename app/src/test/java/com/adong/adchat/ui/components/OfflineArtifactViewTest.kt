package com.adong.adchat.ui.components

import android.app.Activity
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class OfflineArtifactViewTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private lateinit var view: OfflineArtifactView
    private lateinit var owner: Owner

    @Before fun create() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        owner = Owner().also { it.lifecycle.currentState = Lifecycle.State.RESUMED }
        view = OfflineArtifactView(activity)
        activity.setContentView(view)
        shadowOf(checkNotNull(view.engine)).performNoPageLoadClientCallbacks()
    }

    @After fun destroy() {
        view.release()
    }

    private fun render(html: String, action: ((String) -> Unit)? = null, scripts: Boolean = true) {
        view.render(html, scripts, null, action, owner.lifecycle) { fail("WebView failed to initialize") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
    }

    @Test
    fun sourceUpdateLoadsNewDocumentWithoutCreatingAnotherWebView() {
        val engine = checkNotNull(view.engine)
        render("<p>first</p>")
        assertEquals("<p>first</p>", shadowOf(engine).lastLoadDataWithBaseURL.data)
        render("<p>second</p>")
        assertSame(engine, view.engine)
        assertEquals("<p>second</p>", shadowOf(engine).lastLoadDataWithBaseURL.data)
    }

    @Test
    fun callbackReplacementDoesNotReloadDocumentAndUsesCurrentCallback() {
        val engine = checkNotNull(view.engine)
        var firstCalls = 0
        var secondCalls = 0
        render("<p>panel</p>", { firstCalls++ })
        val loaded = shadowOf(engine).lastLoadDataWithBaseURL
        render("<p>panel</p>", { secondCalls++ })
        assertSame(loaded, shadowOf(engine).lastLoadDataWithBaseURL)
        val bridge = shadowOf(engine).getJavascriptInterface("Aster") as OfflinePanelActionBridge
        bridge.onPanelAction("选择剧情")
        assertEquals(0, secondCalls)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun replacingDocumentInvalidatesQueuedAndLateActionsFromOldPage() {
        val engine = checkNotNull(view.engine)
        var oldCalls = 0
        var newCalls = 0
        render("<p>old panel</p>", { oldCalls++ })
        val oldBridge = shadowOf(engine).getJavascriptInterface("Aster") as OfflinePanelActionBridge
        oldBridge.onPanelAction("旧选项")
        view.render("<p>new panel</p>", true, null, { newCalls++ }, owner.lifecycle) { fail() }
        // The old page can still hold its bridge until the deferred document load completes.
        oldBridge.onPanelAction("另一个旧选项")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertEquals(0, oldCalls)
        assertEquals(0, newCalls)
        val newBridge = shadowOf(engine).getJavascriptInterface("Aster") as OfflinePanelActionBridge
        newBridge.onPanelAction("新选项")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, newCalls)
    }

    @Test
    fun releaseDestroysEngineAndDropsQueuedNativeActions() {
        val engine = checkNotNull(view.engine)
        var calls = 0
        render("<p>panel</p>", { calls++ })
        val bridge = shadowOf(engine).getJavascriptInterface("Aster") as OfflinePanelActionBridge
        bridge.onPanelAction("queued")
        view.release()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, calls)
        assertTrue(shadowOf(engine).wasDestroyCalled())
        assertNull(view.engine)
        assertEquals(0, view.childCount)
    }

    @Test
    fun sandboxAndLifecycleRemainEnforcedAcrossUpdates() {
        val engine = checkNotNull(view.engine)
        render("<p>panel</p>", {})
        assertTrue(engine.settings.blockNetworkLoads)
        assertFalse(engine.settings.allowFileAccess)
        assertFalse(engine.settings.allowContentAccess)
        assertFalse(engine.settings.domStorageEnabled)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertTrue(shadowOf(engine).wasOnPauseCalled())
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertTrue(shadowOf(engine).wasOnResumeCalled())
        render("<p>static</p>", {}, scripts = false)
        assertFalse(engine.settings.javaScriptEnabled)
        assertNull(shadowOf(engine).getJavascriptInterface("Aster"))
    }

    @Test
    fun bridgeBoundsInputAndDispatchesFromWorkerToMainThread() {
        val bridge = OfflinePanelActionBridge()
        val received = mutableListOf<String>()
        bridge.onAction = {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            received += it
        }
        Thread {
            bridge.onPanelAction(" ")
            bridge.onPanelAction("x".repeat(201))
            bridge.onPanelAction("有效动作")
        }.apply { start(); join() }
        assertTrue(received.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("有效动作"), received)
    }
}
