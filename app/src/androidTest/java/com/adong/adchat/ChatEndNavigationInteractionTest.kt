package com.adong.adchat

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adong.adchat.data.*
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.chat.ConversationEndPull
import com.adong.adchat.ui.components.ConversationNewPullIndicator
import com.adong.adchat.ui.components.conversationEndPull
import com.adong.adchat.ui.screens.ChatScreen
import com.adong.adchat.ui.theme.AsterTheme
import com.adong.adchat.ui.theme.LocalAsterMotionEnabled
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ChatEndNavigationInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val stores = mutableListOf<ViewModelStore>()

    @After fun closeModels() { rule.runOnUiThread { stores.forEach(ViewModelStore::clear) } }

    private fun model(): MainViewModel {
        lateinit var vm: MainViewModel
        rule.runOnUiThread {
            val app = rule.activity.application as Application
            val profile = ApiProfile(id = "pull-fixture", chatModel = "mock-chat")
            ConfigStore(app).save(AppConfig(listOf(profile), profile.id, profile.id, userNamePromptDone = true))
            val messages = (1..3).flatMap { i -> listOf(
                ChatMessage(id = i * 2L, role = "user", content = "提问 $i"),
                ChatMessage(id = i * 2L + 1, role = "assistant", content = "回答 $i\n\n" + "用于检查阅读、导航和滚动的本地内容。".repeat(70))) }
            ConversationStore(app).save(listOf(Conversation(id = "kept-conversation", title = "原会话",
                messages = messages, profileId = profile.id, model = profile.chatModel)))
            ChatSessionStore(app).save(emptyMap(), "kept-conversation")
            vm = MainViewModel(app)
            stores += ViewModelStore().apply { put("fixture", vm) }
        }
        return vm
    }

    private fun pullFixture(vm: MainViewModel, enabled: State<Boolean> = mutableStateOf(true)) {
        rule.runOnUiThread {
            rule.activity.setContent {
                CompositionLocalProvider(LocalAsterMotionEnabled provides false) {
                    AsterTheme(themeMode = vm.appConfig.themeMode) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            val list = rememberLazyListState()
                            val threshold = with(LocalDensity.current) { 96.dp.toPx() }
                            val pull = remember { ConversationEndPull(threshold) }
                            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                                if (vm.messages.isEmpty()) Text("新的会话", Modifier.align(Alignment.Center))
                                else LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("pull-list")
                                    .conversationEndPull(pull, enabled.value, { !list.canScrollForward }) { vm.newConversation() },
                                    contentPadding = PaddingValues(24.dp)) {
                                    items(vm.messages, key = { it.id }) { Text(it.content, Modifier.padding(vertical = 24.dp)) }
                                    item { Spacer(Modifier.height(120.dp)) }
                                }
                                ConversationNewPullIndicator(pull, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("pull-list").performScrollToIndex(vm.messages.size)
    }

    @Test fun pullFillsThenReleasesOnceAndKeepsTheOldConversation() {
        val vm = model()
        rule.runOnIdle { vm.updateChatInput("保留的草稿") }
        pullFixture(vm)
        val density = rule.activity.resources.displayMetrics.density
        val list = rule.onNodeWithTag("pull-list")
        list.performTouchInput {
            val start = Offset(centerX, height * .75f)
            down(start)
            moveTo(start - Offset(0f, 80f * density), delayMillis = 200)
        }
        rule.onNodeWithTag("new-conversation-pull").assertIsDisplayed()
        rule.onNodeWithText("继续滑动，新建会话").assertIsDisplayed()
        screenshot("pull-partial")
        list.performTouchInput { moveTo(Offset(centerX, height * .75f - 220f * density), delayMillis = 250) }
        rule.onNodeWithText("松开，新建会话").assertIsDisplayed()
        rule.runOnIdle { assertEquals(6, vm.messages.size) }
        screenshot("pull-ready")
        rule.runOnIdle { vm.setThemeMode(THEME_MODE_DARK) }
        screenshot("pull-ready-dark")
        list.performTouchInput { up() }
        rule.onNodeWithText("新的会话").assertIsDisplayed()
        rule.runOnIdle {
            assertNull(vm.activeConversationId)
            assertEquals(6, vm.conversations.first { it.id == "kept-conversation" }.messages.size)
        }
        rule.waitUntil(5_000) {
            ChatSessionStore(rule.activity).load().drafts["kept-conversation"] == "保留的草稿"
        }
    }

    @Test fun shortPullReverseAndCancellationDoNotCreateAConversation() {
        val vm = model()
        pullFixture(vm)
        val density = rule.activity.resources.displayMetrics.density
        val list = rule.onNodeWithTag("pull-list")
        list.performTouchInput {
            val start = Offset(centerX, height * .75f)
            down(start); moveTo(start - Offset(0f, 65f * density), delayMillis = 200); up()
        }
        rule.runOnIdle { assertEquals(6, vm.messages.size) }
        list.performScrollToIndex(vm.messages.size)
        list.performTouchInput {
            val start = Offset(centerX, height * .75f)
            down(start); moveTo(start - Offset(0f, 220f * density), delayMillis = 300)
            moveTo(start, delayMillis = 300); up()
        }
        rule.runOnIdle { assertEquals(6, vm.messages.size) }
        list.performScrollToIndex(vm.messages.size)
        list.performTouchInput {
            val start = Offset(centerX, height * .75f)
            down(start); moveTo(start - Offset(0f, 220f * density), delayMillis = 300); cancel()
        }
        rule.onNodeWithTag("new-conversation-pull").assertDoesNotExist()
        rule.runOnIdle { assertEquals("kept-conversation", vm.activeConversationId) }
    }

    @Test fun disabledGesturesAndFastScrollingCannotOpenANewConversation() {
        val vm = model()
        val enabled = mutableStateOf(false)
        pullFixture(vm, enabled)
        rule.onNodeWithTag("pull-list").performTouchInput { swipeUp(durationMillis = 450) }
        rule.onNodeWithTag("new-conversation-pull").assertDoesNotExist()
        rule.runOnIdle { assertEquals(6, vm.messages.size); enabled.value = true }
        rule.onNodeWithTag("pull-list").performScrollToIndex(0).performTouchInput { swipeUp(durationMillis = 60) }
        rule.runOnIdle { assertEquals(6, vm.messages.size) }
        rule.onNodeWithTag("new-conversation-pull").assertDoesNotExist()
    }

    @Test fun headerExportsMarkdownAndCapsuleNavigatesAnswers() {
        val vm = model()
        val fontScale = mutableStateOf(1f)
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            rule.activity.setContent {
                CompositionLocalProvider(LocalAsterMotionEnabled provides false,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                    AsterTheme(themeMode = vm.appConfig.themeMode) { ChatScreen(vm, {}, {}) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_200)
        rule.onNodeWithContentDescription("新建对话").assertDoesNotExist()
        rule.onNodeWithContentDescription("对话操作").assertDoesNotExist()
        val exported = AtomicReference<Intent>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                exported.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            rule.onNodeWithTag("conversation-export-markdown").assertIsEnabled().performClick()
            rule.mainClock.advanceTimeBy(500)
            rule.runOnIdle {
                assertEquals("text/markdown", exported.get()?.type)
                assertTrue(exported.get()?.getStringExtra(Intent.EXTRA_TITLE)?.endsWith(".md") == true)
            }
        } finally { instrumentation.removeMonitor(monitor) }
        // Begin at the natural reading tail; using the capsule itself opts out of auto-follow.
        rule.onNodeWithTag("answer-navigation").assertIsDisplayed()
        rule.onNodeWithContentDescription("上一个回答").assertWidthIsAtLeast(48.dp).performClick()
        settleNavigation("after-previous-to-two")
        screenshot("after-previous-to-two")
        rule.onNodeWithText("回答 2").assertIsDisplayed()
        rule.onNodeWithContentDescription("上一个回答").performClick()
        settleNavigation("after-previous-to-one")
        screenshot("after-previous-to-one")
        rule.onNodeWithText("回答 1").assertIsDisplayed()
        rule.onNodeWithContentDescription("下一个回答").performClick()
        settleNavigation("after-next-to-two")
        rule.onNodeWithText("回答 2").assertIsDisplayed()
        screenshot("navigation-light")
        rule.runOnIdle { vm.setThemeMode(THEME_MODE_DARK); fontScale.value = 1.4f }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("answer-navigation").assertIsDisplayed()
        screenshot("navigation-dark-large-text")
    }

    private fun settleNavigation(stage: String) {
        repeat(10) {
            rule.mainClock.advanceTimeBy(300)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            android.os.SystemClock.sleep(30)
        }
        val axis = rule.onNodeWithTag("chat-messages").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        Log.i("ChatEndNav", "$stage: scroll=${axis.value()}")
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "chat-end-$name.png")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/aster-ui-preview")
            }
            val resolver = rule.activity.contentResolver
            val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
