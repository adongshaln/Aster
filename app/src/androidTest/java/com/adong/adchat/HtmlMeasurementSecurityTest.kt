package com.adong.adchat

import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adong.adchat.ui.components.HtmlPreviewDocument
import com.adong.adchat.ui.components.OfflineArtifactView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/** Real Chromium positive/negative checks; no Compose clock and no external service. */
@RunWith(AndroidJUnit4::class)
class HtmlMeasurementSecurityTest {
    @Test fun deniedCardScriptsStillMeasureAndCollapseWithoutNativeActions() {
        val heights = CopyOnWriteArrayList<Int>()
        val failures = AtomicInteger()
        val capability = AtomicReference<String?>()
        val painted = AtomicBoolean()
        lateinit var view: OfflineArtifactView
        var density = 1f
        val scenario = ActivityScenario.launch(SharedUiTestActivity::class.java)
        try {
            scenario.onActivity { activity ->
                density = activity.resources.displayMetrics.density
                view = OfflineArtifactView(activity)
                val root = FrameLayout(activity).apply {
                    setPadding(0, (48 * density).toInt(), 0, 0)
                    addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (200 * density).toInt()))
                }
                activity.setContentView(root)
                val source = """
                    <style>body{font:16px/24px sans-serif}summary{padding:8px}p{margin:0;padding:8px}a{display:block;height:24px}</style>
                    <details><summary>状态栏</summary><p>第一行<br>第二行<br>第三行</p></details>
                    <a href="javascript:window.Aster.onHeight(4321)">测试链接</a>
                    <script>window.Aster.onHeight(4321);parent.postMessage({asterHeight:4321},'*')</script>
                    <img style="display:none" src="https://aster.local/missing.png" onerror="window.Aster.onHeight(4321)">
                    <script src="https://aster.local/untrusted.js"></script>
                """.trimIndent()
                view.render(HtmlPreviewDocument.wrap(source, allowScripts = false, reportHeight = true),
                    false, HtmlPreviewDocument.ORIGIN + "/", { failures.incrementAndGet() },
                    { h ->
                        heights += h
                        view.layoutParams = view.layoutParams.apply { height = (h * density).toInt() }
                    }, null, activity.lifecycle, onReady = { painted.set(true) }) { failures.incrementAndGet() }
            }
            await("initial trusted measurement: $heights") { heights.isNotEmpty() }
            await("first painted frame") { painted.get() }
            val collapsed = heights.last()
            assertTrue("fixture collapsed height: $collapsed", collapsed in 40..160)
            scenario.onActivity {
                view.engine!!.evaluateJavascript(
                    "typeof Aster.rpc+'|'+typeof Aster.rpcAsync+'|'+typeof Aster.broadcast+'|'+typeof Aster.onPanelAction+'|'+typeof Aster.onHeight"
                ) { result -> capability.set(result) }
            }
            await("native capability inspection") { capability.get() != null }
            assertEquals("\"undefined|undefined|undefined|undefined|function\"", capability.get())

            tap(view, 28f * density, 20f * density)
            await("expanded height: $heights") { heights.last() > collapsed + 40 }
            tap(view, 28f * density, 20f * density)
            await("collapsed again: $heights") { heights.last() <= collapsed + 2 }
            tap(view, 28f * density, (collapsed - 12f) * density)
            SystemClock.sleep(250)
            assertFalse("untrusted scripts must not forge measurements: $heights", heights.contains(4321))
            assertEquals(0, failures.get())
            scenario.onActivity { activity ->
                val folder = File(activity.getExternalFilesDir(null), "design-review").apply { mkdirs() }
                File(folder, "html-measurement.json").writeText(org.json.JSONObject()
                    .put("heights", org.json.JSONArray(heights))
                    .put("capabilities", capability.get()).put("actionCalls", failures.get()).toString(2))
            }
        } finally {
            scenario.onActivity { view.release() }
            scenario.close()
        }
    }

    private fun tap(view: OfflineArtifactView, x: Float, y: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        var up: MotionEvent? = null
        try {
            instrumentation.runOnMainSync { assertTrue(view.engine!!.dispatchTouchEvent(down)) }
            SystemClock.sleep(60)
            up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.runOnMainSync { assertTrue(view.engine!!.dispatchTouchEvent(up)) }
        } finally { down.recycle(); up?.recycle() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8000
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(25)
        assertTrue(message, condition())
    }
}
