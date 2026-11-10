package com.adong.adchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adong.adchat.data.THEME_MODE_ASTER
import com.adong.adchat.data.THEME_MODE_DARK
import com.adong.adchat.island.IslandPalette
import com.adong.adchat.island.IslandPermissions
import com.adong.adchat.island.IslandService
import com.adong.adchat.island.IslandSurfaceView
import com.adong.adchat.island.IslandView
import com.adong.adchat.island.prepareScreenQuestion
import com.adong.adchat.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Dedicated emulator only: clipboard, real overlay window and app-to-service theme updates. */
@RunWith(AndroidJUnit4::class)
class StarIslandVisualTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun paperOverlayOpensChangesThemeAndReturnsToItsAnchor() {
        val activity = rule.activity
        assertTrue("Grant overlay and notification access on the dedicated emulator", IslandPermissions.isReady(activity))
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previousClip = clipboard.primaryClip
        val enabled = IslandPermissions.isEnabled(activity)
        lateinit var vm: MainViewModel
        var configured = false
        lateinit var island: IslandView
        try {
            rule.runOnIdle {
                vm = ViewModelProvider(activity)[MainViewModel::class.java]
                configured = true
                vm.skipUserNamePrompt()
                vm.newConversation()
                vm.dismissNotice()
                vm.setThemeMode(THEME_MODE_ASTER)
                clipboard.setPrimaryClip(ClipData.newPlainText("本地设计样例", "今天的灵感：留一点空白，让想法慢慢展开。"))
                IslandPermissions.setEnabled(activity, true)
                IslandService.start(activity, false)
            }
            rule.waitUntil(8000) {
                rule.runOnUiThread {
                    WindowInspector.getGlobalWindowViews().filterIsInstance<IslandView>().firstOrNull()?.let { island = it }
                }
                runCatching { island.isAttachedToWindow }.getOrDefault(false)
            }
            // Let Android's clipboard confirmation disappear before recording the app surface.
            SystemClock.sleep(3200)
            rule.runOnUiThread {
                val ring = island.getChildAt(1)
                val now = SystemClock.uptimeMillis()
                val x = ring.left + ring.width / 2f
                val y = ring.top + ring.height / 2f
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
                island.dispatchTouchEvent(down); island.dispatchTouchEvent(up)
                down.recycle(); up.recycle()
            }
            rule.waitUntil(8000) {
                rule.runOnUiThread { }
                island.getChildAt(0).width > island.getChildAt(1).width * 2
            }
            // Opening reads the clipboard again and can show Android's own clipboard overlay.
            SystemClock.sleep(3600)
            screenshot("20-island-light")
            rule.runOnIdle { vm.setThemeMode(THEME_MODE_DARK) }
            rule.waitForIdle()
            rule.waitUntil(8000) { (island.getChildAt(0) as IslandSurfaceView).currentPalette == IslandPalette.Dark }
            SystemClock.sleep(400)
            screenshot("21-island-dark")
            rule.runOnUiThread {
                val close = findText(island, "收起")
                assertNotNull("Clipboard card keeps its collapse action", close)
                close!!.performClick()
            }
            SystemClock.sleep(1000)
            assertTrue("Overlay returns to its compact anchor", island.height <= island.getChildAt(1).height + 8)

            // Seed only the confirmed selection, without invoking capture or a model request.
            val selection = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(selection).apply {
                drawColor(0xFFF5F3EF.toInt())
                val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF22221F.toInt(); textSize = 28f
                }
                drawText("把一个大想法，变成今天的一小步。", 20f, 95f, ink)
                ink.textSize = 22f
                drawText("先写下最想解决的一个问题。", 20f, 145f, ink)
            }
            val session = prepareScreenQuestion(selection, android.graphics.Rect(0, 0, 480, 240))
            rule.runOnIdle { vm.setThemeMode(THEME_MODE_ASTER) }
            rule.waitForIdle()
            rule.waitUntil(8000) { (island.getChildAt(0) as IslandSurfaceView).currentPalette == IslandPalette.Light }
            rule.runOnUiThread { island.showScreenQuestion(session.copy(draft = "帮我梳理这段话的重点")) }
            SystemClock.sleep(1200)
            screenshot("22-island-question-light")
            rule.runOnIdle { vm.setThemeMode(THEME_MODE_DARK) }
            rule.waitForIdle()
            rule.waitUntil(8000) { (island.getChildAt(0) as IslandSurfaceView).currentPalette == IslandPalette.Dark }
            SystemClock.sleep(400)
            screenshot("23-island-question-dark")
            rule.runOnUiThread { island.collapse() }
            SystemClock.sleep(1000)
            selection.recycle()
            if (session.preview !== selection) session.preview.recycle()
        } finally {
            rule.runOnUiThread {
                if (configured) vm.setThemeMode(THEME_MODE_ASTER)
                IslandPermissions.setEnabled(activity, enabled)
                if (!enabled) IslandService.stop(activity)
                if (previousClip != null) clipboard.setPrimaryClip(previousClip) else clipboard.clearPrimaryClip()
            }
        }
    }

    private fun findText(view: View, value: String): TextView? {
        if (view is TextView && view.text.toString() == value) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), value)?.let { return it }
        return null
    }

    private fun screenshot(name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val folder = File(rule.activity.getExternalFilesDir(null), "design-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
