package com.adong.adchat

import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.ui.components.ComposerToolToggle
import com.adong.adchat.ui.components.ConversationSheetAction
import com.adong.adchat.ui.theme.AsterTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualUpgradeInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun starterOnlyFillsAnEditableDraft() {
        lateinit var vm: MainViewModel
        rule.runOnIdle {
            vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
            vm.skipUserNamePrompt()
            vm.newConversation()
            vm.selectChatModel(vm.chatProfile.id, "gpt-5.6")
            vm.dismissNotice()
        }
        // Inspect the input contract at a fixed animation time; decorative motion is independent
        // of whether the draft and focus were correctly handed to the editor.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("一起想想").performClick()
        rule.mainClock.advanceTimeBy(700)
        rule.onNodeWithTag("chat-input").assertIsFocused()
        rule.runOnIdle {
            assertEquals("帮我梳理一下这个想法：", vm.chatInput)
            assertTrue(vm.messages.isEmpty())
            assertFalse(vm.isChatLoading)
        }
    }

    @Test fun customToolSwitchRetainsStateAndOneToggleAction() {
        var calls = 0
        val checked = androidx.compose.runtime.mutableStateOf(false)
        rule.runOnUiThread {
            rule.activity.setContent {
                AsterTheme {
                    ComposerToolToggle(Icons.Rounded.Search, "联网搜索", "查找资料", checked.value,
                        { checked.value = it; calls++ })
                }
            }
        }
        rule.onNode(isToggleable()).assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performClick()
        rule.onNode(isToggleable()).assertIsOn()
        rule.runOnIdle { assertEquals(1, calls) }
        rule.onNodeWithText("联网搜索").performTouchInput { click() }
        rule.onNode(isToggleable()).assertIsOff()
        rule.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun focusingTheComposerKeepsTheReadersHistoryPosition() {
        rule.runOnIdle {
            val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
            vm.skipUserNamePrompt()
            vm.newConversation()
            vm.dismissNotice()
            repeat(20) { i ->
                vm.messages.add(ChatMessage(role = "user", content = "第 ${i + 1} 个问题"))
                vm.messages.add(ChatMessage(role = "assistant", content = "这是一段用于核对阅读位置的本地文本。".repeat(12)))
            }
        }
        // First let the new-conversation tail alignment finish, then use a real reading gesture
        // to opt out of following. A semantic scroll alone is not a user drag.
        rule.waitForIdle()
        rule.onNodeWithTag("chat-messages").performTouchInput { swipeDown() }
        rule.onNodeWithTag("chat-messages").performScrollToIndex(0)
        rule.onNodeWithText("第 1 个问题").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("chat-input").performClick()
        rule.mainClock.advanceTimeBy(700)
        Thread.sleep(400)
        rule.onNodeWithTag("chat-input").assertIsFocused()
        rule.onNodeWithText("第 1 个问题").assertIsDisplayed()
    }

    @Test fun scrollingToolsDoesNotRequestClickHaptics() {
        var haptics = 0
        var clicks = 0
        rule.runOnUiThread {
            val recorder = object : FrameLayout(rule.activity) {
                override fun performHapticFeedback(feedbackConstant: Int): Boolean { haptics++; return true }
            }
            val compose = ComposeView(rule.activity)
            recorder.addView(compose, FrameLayout.LayoutParams(-1, -1))
            rule.activity.setContentView(recorder)
            compose.setContent {
                AsterTheme {
                    CompositionLocalProvider(LocalView provides recorder) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            repeat(20) { i ->
                                ConversationSheetAction(Icons.Rounded.Search, "选项 $i", true, { clicks++ })
                            }
                        }
                    }
                }
            }
        }
        repeat(10) { rule.onRoot().performTouchInput { swipeUp(durationMillis = 120) } }
        rule.runOnIdle { assertEquals(0, haptics); assertEquals(0, clicks) }
        rule.onNodeWithText("选项 12").performScrollTo().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, haptics); assertEquals(1, clicks) }
    }
}
