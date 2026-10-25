package com.adong.adchat

import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adong.adchat.island.IslandTypefaces
import com.adong.adchat.ui.components.ExpandableUserMessage
import com.adong.adchat.ui.theme.AsterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OptimizedChatUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun islandTitleLoadsBundledVariableFontAtTheRequestedWeight() {
        // Run on the instrumentation thread: loading this large font must stay off the UI thread.
        val typeface = IslandTypefaces.title(rule.activity)
        assertNotEquals(Typeface.SERIF, typeface)
        assertNotEquals(Typeface.DEFAULT, typeface)
        // A distinct face proves that API 26/27 also applied the variable font axis.
        assertNotEquals(rule.activity.resources.getFont(R.font.island_title_serif), typeface)
        if (Build.VERSION.SDK_INT >= 28) assertEquals(560, typeface.weight)
    }

    @Test
    fun longMessageRemainsReadableAndCanExpandWithLargeFonts() {
        val text = (1..30).joinToString("\n") { "第 $it 行消息" }
        rule.runOnUiThread {
            rule.activity.setContent {
                AsterTheme {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Column(Modifier.width(220.dp)) {
                                ExpandableUserMessage(100L, text)
                            }
                        }
                    }
                }
            }
        }
        val collapsed = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(collapsed) }
        assertEquals(12, collapsed.single().lineCount)
        assertTrue(collapsed.single().isLineEllipsized(11))
        rule.onNodeWithText("展开").performScrollTo().assertHeightIsAtLeast(48.dp)
        screenshot("large-font-collapsed")
        rule.onNodeWithText("展开").performClick()

        val expanded = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(expanded) }
        assertTrue(expanded.single().lineCount >= 30)
        rule.onNodeWithText("收起").performScrollTo().assertHeightIsAtLeast(48.dp)
        screenshot("large-font-expanded")
        rule.onNodeWithText("收起").performClick()
        rule.onNodeWithText("展开").performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Allow SurfaceFlinger to present the frame after Compose semantics have settled.
        android.os.SystemClock.sleep(350)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val filename = "optimized-$name.png"
            val directory = File(rule.activity.getExternalFilesDir(null), "ui-preview").apply { mkdirs() }
            File(directory, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // UTP may uninstall the app after tests, so also preserve captures in Downloads.
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/aster-ui-preview")
            }
            val resolver = rule.activity.contentResolver
            val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
