package com.adong.adchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import com.adong.adchat.ui.components.composerEnterKey
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adong.adchat.data.*
import com.adong.adchat.ui.components.ConversationComposer
import com.adong.adchat.ui.components.QuoteSelectionScope
import com.adong.adchat.ui.theme.AsterTheme
import com.adong.adchat.ui.theme.LocalAsterMotionEnabled
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ComposerContextInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun enterSettingHandlesImeHardwareAndShiftEnter() {
        val input = mutableStateOf("草稿")
        val sendOnEnter = mutableStateOf(false)
        var sends = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                CompositionLocalProvider(LocalAsterMotionEnabled provides false) {
                    AsterTheme {
                        ConversationComposer(input.value, emptyList(), false, false,
                            { input.value = it }, {}, {}, { sends++ }, {}, {}, sendOnEnter = sendOnEnter.value)
                    }
                }
            }
        }
        rule.onNodeWithTag("chat-input").performClick().performTextInputSelection(androidx.compose.ui.text.TextRange(2))
        rule.onNodeWithTag("chat-input").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(0, sends); assertEquals("草稿\n", input.value); sendOnEnter.value = true }
        rule.onNodeWithTag("chat-input").performClick().assertIsFocused().performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
        rule.runOnIdle { assertEquals(0, sends); assertEquals("草稿\n\n", input.value) }
        rule.onNodeWithTag("chat-input").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, sends) }
        rule.onNodeWithTag("chat-input").performClick().performImeAction()
        rule.runOnIdle { assertEquals(2, sends) }
    }

    @Test fun committingCompositionOnEnterDoesNotSendOnKeyRelease() {
        val composing = mutableStateOf(true)
        val input = mutableStateOf("候选文字")
        var sends = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                AsterTheme {
                    BasicTextField(input.value, { input.value = it }, Modifier.testTag("composition-input")
                        .composerEnterKey(true, composing.value) { sends++ })
                }
            }
        }
        rule.onNodeWithTag("composition-input").performClick().performKeyInput { keyDown(Key.Enter) }
        rule.runOnIdle { composing.value = false }
        rule.onNodeWithTag("composition-input").performKeyInput { keyUp(Key.Enter) }
        rule.runOnIdle { assertEquals(0, sends) }
        rule.onNodeWithTag("composition-input").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, sends) }
    }

    @Test fun filesAndQuotesRemainCardsAndImageRemoveTargetStaysReachable() {
        val theme = mutableStateOf(THEME_MODE_ASTER)
        val fontScale = mutableStateOf(1f)
        val documents = mutableStateOf(listOf(ChatDocumentAttachment(id = "file", name = "notes.txt",
            uri = "file:///notes.txt", mimeType = "text/plain", size = 12, text = "HIDDEN_DOCUMENT_TEXT")))
        val quotes = mutableStateOf(listOf(ChatQuote(id = "quote", messageId = 1, role = "assistant", text = "选中的准确片段", partial = true)))
        val preview = java.io.File(rule.activity.cacheDir, "composer-preview.png")
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(217, 119, 87))
        preview.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val images = mutableStateOf(listOf(ChatImageAttachment(id = "image", uri = android.net.Uri.fromFile(preview).toString(), name = "preview.jpg")))
        rule.runOnUiThread {
            rule.activity.setContent {
                CompositionLocalProvider(LocalAsterMotionEnabled provides false,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                    AsterTheme(themeMode = theme.value) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                        ConversationComposer("自己的问题", images.value, false, false, {}, {}, { images.value = emptyList() }, {}, {}, {},
                            documents = documents.value, quotes = quotes.value,
                            onRemoveDocument = { documents.value = emptyList() }, onRemoveQuote = { quotes.value = emptyList() })
                        }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("chat-input").assertTextEquals("自己的问题")
        rule.onNodeWithText("HIDDEN_DOCUMENT_TEXT").assertDoesNotExist()
        screenshot("cards-light")
        rule.onNodeWithTag("document-file", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("quote-quote").assertIsDisplayed()
        rule.runOnIdle { theme.value = THEME_MODE_DARK; fontScale.value = 1.4f }
        rule.onNodeWithTag("document-file", useUnmergedTree = true).assertIsDisplayed()
        screenshot("cards-dark-large-text")
        rule.onNodeWithContentDescription("移除 preview.jpg").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription("移除 notes.txt").performClick()
        rule.onNodeWithContentDescription("移除引用回复 · 片段").performClick()
        rule.onNodeWithTag("document-file", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("quote-quote").assertDoesNotExist()
        rule.onNodeWithTag("chat-input").assertTextEquals("自己的问题")
    }

    @Test fun selectedWordBecomesAnExactQuoteWithoutOverwritingClipboard() {
        var quoted = ""
        val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        rule.runOnUiThread {
            clipboard.setPrimaryClip(ClipData.newPlainText("existing", "保留剪贴板"))
            rule.activity.setContent {
                CompositionLocalProvider(LocalAsterMotionEnabled provides false) {
                    AsterTheme {
                        Box(Modifier.fillMaxSize().padding(24.dp)) {
                            QuoteSelectionScope({ quoted = it }) {
                                SelectionContainer { Text("Selected words stay exact", Modifier.testTag("selectable")) }
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("selectable").performTouchInput { longClick(Offset(10f, center.y)) }
        rule.onNodeWithText("引用").assertIsDisplayed()
        screenshot("selection-menu")
        rule.onNodeWithText("引用").performClick()
        rule.runOnIdle {
            assertEquals("Selected", quoted)
            assertEquals("保留剪贴板", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = java.io.File(rule.activity.getExternalFilesDir(null), "ui-preview").apply { mkdirs() }
        try {
            java.io.File(directory, "composer-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "composer-$name.png")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/aster-ui-preview")
            }
            val resolver = rule.activity.contentResolver
            val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            requireNotNull(resolver.openOutputStream(uri)).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
}
