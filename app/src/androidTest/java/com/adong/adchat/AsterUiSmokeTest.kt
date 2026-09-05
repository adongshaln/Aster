package com.adong.adchat

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real native layouts and interactions on a small phone. No service requests or real API keys. */
@RunWith(AndroidJUnit4::class)
class AsterUiSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun primaryPagesAndKeyboardRemainUsable() {
        lateinit var vm: MainViewModel
        rule.runOnIdle {
            vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
            vm.newConversation()
            vm.selectChatModel(vm.chatProfile.id, "gpt-5.6")
        }
        rule.onNodeWithText("理清思路").assertIsDisplayed()
        screenshot("01-chat-home")
        val compactHeight = rule.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.height
        rule.onNodeWithText("理清思路").performClick()
        rule.onNodeWithTag("chat-input").assertIsFocused()
        rule.waitUntil(10_000) { imeVisible() }
        rule.runOnIdle {
            assertTrue("Suggestions must remain editable drafts", vm.messages.isEmpty())
            assertFalse(vm.isChatLoading)
        }
        val expandedHeight = rule.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Composer should expand with the keyboard", expandedHeight > compactHeight + 30)
        screenshot("02-chat-keyboard")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(10_000) { !imeVisible() }
        rule.onNodeWithTag("chat-input").assertIsNotFocused()
        rule.waitForIdle()
        assertTrue("Composer must collapse when the keyboard closes",
            rule.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.height <= compactHeight + 8)
        rule.runOnIdle {
            vm.updateChatInput("")
            vm.messages.addAll(listOf(
                ChatMessage(role = "user", content = "怎样开始一个小而美的个人项目？"),
                ChatMessage(role = "assistant", content = "## 从一个值得解决的问题开始\n\n先把目标缩小到：**一周内，做出自己愿意每天使用的东西。**\n\n1. 找到日常生活里一个反复出现的小麻烦。\n2. 只保留解决它所需的核心操作。\n3. 做出第一版，在真实使用中慢慢打磨。\n\n> 质感，来自每一个被认真对待的细节。", model = "gpt-5.6")
            ))
        }
        rule.onNodeWithText("复制").assertExists()
        screenshot("03-chat-reading")
        rule.onNodeWithContentDescription("打开侧栏").performClick()
        screenshot("04-navigation")
        rule.onNodeWithText("创作").performClick()
        rule.onNodeWithText("图像创作").assertIsDisplayed()
        rule.onNodeWithText("开始生成").assertIsDisplayed()
        screenshot("05-studio")
        rule.onNodeWithText("画面设置").performScrollTo().performClick()
        rule.onNodeWithText("画布比例").performScrollTo().assertIsDisplayed()
        screenshot("06-studio-options")
        rule.onNodeWithContentDescription("打开侧栏").performClick()
        rule.onNodeWithText("下载").performClick()
        rule.onNodeWithText("解析链接").assertIsDisplayed().assertIsNotEnabled()
        screenshot("07-downloads")
        rule.onNodeWithContentDescription("打开侧栏").performClick()
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("我的服务").assertIsDisplayed()
        screenshot("08-model-settings")
        rule.onNodeWithText("默认模型").performScrollTo().performClick()
        screenshot("09-service-settings")
        rule.onNodeWithText("默认模型").performScrollTo().performClick()
        rule.onNodeWithText("助手偏好").performScrollTo().performClick()
        rule.onNodeWithText("修改自动保存，仅用于对话。").performScrollTo().assertIsDisplayed()
        screenshot("10-assistant-settings")
    }

    @Test fun settingsEditorKeepsAdvancedOptionsCollapsed() {
        rule.onNodeWithContentDescription("打开侧栏").performClick()
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("我的服务").assertIsDisplayed()
        screenshot("11-settings-home")
        rule.onAllNodesWithText("编辑")[0].performClick()
        rule.onNodeWithText("连接信息").assertIsDisplayed()
        rule.onNodeWithText("GPT-5.6 Sol 优化").assertDoesNotExist()
        rule.onNodeWithText("额外请求头").assertDoesNotExist()
        screenshot("12-settings-editor")
        rule.onNodeWithText("高级设置").performScrollTo().performClick()
        rule.onNodeWithText("额外请求头").performScrollTo().assertIsDisplayed()
        screenshot("13-settings-advanced")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("我的服务").assertIsDisplayed()
    }

    private fun imeVisible(): Boolean = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun screenshot(name: String) {
        rule.waitForIdle()
        // Allow the Android window compositor to finish the last frame, including IME animation.
        Thread.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("Android screenshot unavailable", bitmap)
        val directory = File(rule.activity.getExternalFilesDir(null), "ui-preview").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap!!.recycle()
    }
}
