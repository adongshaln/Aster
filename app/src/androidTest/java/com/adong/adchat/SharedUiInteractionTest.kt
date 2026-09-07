package com.adong.adchat

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.ui.components.*
import com.adong.adchat.ui.theme.AsterTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise the same controls used by both modes, without an API or story database fixture. */
@RunWith(AndroidJUnit4::class)
class SharedUiInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun content(block: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { AsterTheme { block() } } }
    }

    @Test fun ordinaryComposerCollapsesWithoutLosingDraft() = checkKeyboard("chat")
    @Test fun storyComposerCollapsesWithoutLosingDraft() = checkKeyboard("story")

    private fun checkKeyboard(mode: String) {
        var draft by mutableStateOf("第一行\n第二行\n第三行")
        content {
            Box(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
                ConversationComposer(draft, emptyList(), false, false, { draft = it }, {}, {}, {}, {}, {},
                    modifier = Modifier.align(Alignment.BottomCenter), testTag = mode)
            }
        }
        val compact = rule.onNodeWithTag("$mode-composer").fetchSemanticsNode().boundsInRoot.height
        rule.onNodeWithTag("$mode-input").performClick()
        rule.waitUntil(10_000) { imeVisible() }
        rule.waitUntil(5_000) {
            rule.onNodeWithTag("$mode-composer").fetchSemanticsNode().boundsInRoot.height > compact + 20
        }
        screenshot("$mode-expanded")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(10_000) { !imeVisible() }
        rule.onNodeWithTag("$mode-input").assertIsNotFocused()
        rule.waitUntil(5_000) {
            kotlin.math.abs(rule.onNodeWithTag("$mode-composer").fetchSemanticsNode().boundsInRoot.height - compact) < 3
        }
        rule.runOnIdle { assertEquals("第一行\n第二行\n第三行", draft) }
        screenshot("$mode-collapsed")
    }

    @Test fun importingBlocksSendAndRemovalButNeverBlocksStop() {
        var importing by mutableStateOf(true)
        var generating by mutableStateOf(false)
        var sends = 0
        var stops = 0
        var removals = 0
        val attachments = listOf(ChatImageAttachment(id = "image", uri = "file:///missing-preview.jpg", name = "参考图"))
        content {
            ConversationComposer("草稿", attachments, generating, importing, {}, {}, { removals++ },
                { sends++ }, { stops++ }, {})
        }
        rule.onNodeWithContentDescription("发送").assertIsNotEnabled()
        rule.onNodeWithContentDescription("移除 参考图").assertIsNotEnabled()
        rule.runOnIdle { generating = true }
        rule.onNodeWithContentDescription("停止生成").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, stops); assertEquals(0, sends); generating = false; importing = false }
        rule.onNodeWithContentDescription("移除 参考图").performClick()
        rule.onNodeWithContentDescription("发送").performClick()
        rule.runOnIdle { assertEquals(1, removals); assertEquals(1, sends) }
    }

    @Test fun selectionsRevealCurrentChoiceAndResetAfterSearch() {
        content {
            AdSelectionSheet("选择模型", "当前使用的模型", (0..59).map { AdChoiceOption("$it", "模型 $it") },
                "54", {}, {})
        }
        rule.onNodeWithText("模型 54", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("搜索").performTextInput("模型 2")
        rule.onNode(hasText("模型 2") and hasClickAction() and !hasSetTextAction()).assertIsDisplayed()
        rule.onNodeWithText("模型 54", useUnmergedTree = true).assertDoesNotExist()
        screenshot("model-search")
    }

    @Test fun longOptionsRemainReachableAtLargeFontSize() {
        content {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                AsterOptionsSheet("输入选项", "添加参考资料与选择工具", {}) {
                    repeat(12) { index ->
                        ConversationSheetAction(Icons.Rounded.AttachFile, "操作 $index", true, {}, Modifier.fillMaxWidth(), "详细说明")
                    }
                }
            }
        }
        rule.onNodeWithText("操作 11").performScrollTo().assertIsDisplayed().performClick()
        screenshot("large-font-options")
    }

    @Test fun copyConfirmsAndSelectedTabDoesNotRetrigger() {
        var switches = 0
        content {
            Column {
                ConversationCopyAction("需要复制的内容")
                AsterSegmentedControl(listOf("讨论", "正文"), 0, { switches++ })
            }
        }
        rule.onNodeWithText("复制").performClick()
        rule.onNodeWithText("已复制").assertIsDisplayed()
        rule.onNodeWithText("讨论").performClick()
        rule.runOnIdle { assertEquals(0, switches) }
        rule.onNodeWithText("正文").performClick()
        rule.runOnIdle { assertEquals(1, switches) }
    }

    private fun imeVisible() = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val directory = File(rule.activity.getExternalFilesDir(null), "ui-preview").apply { mkdirs() }
        val file = File(directory, "shared-$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // UTP can uninstall the app after tests. Preserve screenshots outside its data directory.
        val command = "sh -c 'mkdir -p /sdcard/Download/aster-ui-preview; " +
            "run-as com.adong.adchat cat ${file.absolutePath} > /sdcard/Download/aster-ui-preview/shared-$name.png'"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).use { it.readBytes() }
    }
}
