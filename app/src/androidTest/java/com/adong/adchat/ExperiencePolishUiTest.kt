package com.adong.adchat

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.Conversation
import com.adong.adchat.data.THEME_MODE_ASTER
import com.adong.adchat.data.THEME_MODE_DARK
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.components.ConversationComposer
import com.adong.adchat.ui.components.ConversationHeader
import com.adong.adchat.ui.screens.SettingsScreen
import com.adong.adchat.ui.screens.StructuredMessageText
import com.adong.adchat.ui.theme.AsterTheme
import com.adong.adchat.ui.theme.Canvas
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native layout and interaction checks using local fixtures, with no model requests. */
@RunWith(AndroidJUnit4::class)
class ExperiencePolishUiTest {
    @get:Rule val rule = createAndroidComposeRule<SharedUiTestActivity>()

    private fun content(dark: Boolean = false, fontScale: Float = 1f, block: @Composable () -> Unit) {
        rule.runOnUiThread {
            rule.activity.setContent {
                AsterTheme(themeMode = if (dark) THEME_MODE_DARK else THEME_MODE_ASTER) {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        Box(Modifier.fillMaxSize().background(Canvas)) { block() }
                    }
                }
            }
        }
    }

    private fun model(): MainViewModel = rule.runOnIdle {
        ViewModelProvider(rule.activity)[MainViewModel::class.java].also { vm ->
            vm.conversations.clear()
            vm.conversations.addAll(listOf(
                Conversation(id = "walk", title = "周末散步计划", favorite = true,
                    messages = listOf(ChatMessage(role = "user", content = "沿着河边走走，拍一些照片。"))),
                Conversation(id = "writing", title = "把一个想法写清楚",
                    messages = listOf(ChatMessage(role = "assistant", content = "先找到最想表达的一句话。"))),
                Conversation(id = "archived", title = "已经收好的资料", archived = true,
                    messages = listOf(ChatMessage(role = "assistant", content = "这段资料已经整理完毕。")))
            ))
        }
    }

    @Test fun drawerKeepsOnlyActiveWorkspacesAndReadableFilters() {
        val vm = model()
        content {
            AppDrawer(vm, AppPage.Chat, {}, {}, {}, {}, {})
        }
        listOf("对话", "创作", "下载").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("故事").assertDoesNotExist()
        rule.onNodeWithText("全部").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("归档").performClick()
        rule.onNodeWithText("已经收好的资料").assertIsDisplayed()
        rule.onNodeWithText("全部").performClick()
        rule.onNodeWithText("周末散步计划").assertIsDisplayed()
        screenshot("drawer-light")
    }

    @Test fun drawerSearchCanRecoverFromEmptyResultsWithLargeText() {
        val vm = model()
        content(fontScale = 1.6f) {
            AppDrawer(vm, AppPage.Chat, {}, {}, {}, {}, {})
        }
        rule.onNodeWithText("创作").assertIsDisplayed()
        rule.onNodeWithText("收藏").assertHeightIsAtLeast(48.dp)
        rule.onNode(hasSetTextAction()).performTextInput("没有这条记录")
        rule.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        rule.onNodeWithText("没有找到对话").assertIsDisplayed()
        rule.onNodeWithText("收藏").performClick()
        rule.onNodeWithText("查看全部对话").performScrollTo().performClick()
        rule.onNodeWithText("清除搜索").performScrollTo().assertIsDisplayed()
        screenshot("drawer-large-text-search")
        rule.onNodeWithText("清除搜索").performClick()
        rule.onNodeWithText("周末散步计划").assertIsDisplayed()
        rule.onNodeWithText("下载").assertIsDisplayed()
    }

    @Test fun settingsSearchRemainsReachableAfterScrolling() {
        val vm = model()
        content { SettingsScreen(vm, {}) }
        screenshot("settings-light")
        rule.onNodeWithTag("settings-list").performScrollToNode(hasText("应用管理"))
        rule.onNodeWithTag("settings-search").assertIsDisplayed()
        rule.onNodeWithTag("settings-search").performTextInput("外观")
        rule.onNodeWithText("服务与连接").assertDoesNotExist()
        rule.onNodeWithContentDescription("清除搜索").performClick()
        rule.onNodeWithText("模型与连接").assertIsDisplayed()
    }

    @Test fun groupedSettingsRemainReadableInDarkModeAndLargeText() {
        val vm = model()
        content(dark = true, fontScale = 1.4f) { SettingsScreen(vm, {}) }
        rule.onNodeWithText("服务与连接").assertIsDisplayed()
        rule.onNodeWithTag("settings-search").assertIsDisplayed()
        screenshot("settings-dark-large-text")
    }

    @Test fun headerUsesTheWholeTitleAsModelSelector() {
        var modelClicks = 0
        content { ReadingFixture(onModelClick = { modelClicks++ }) }
        rule.onNodeWithTag("conversation-model-selector").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("今天的灵感").performClick()
        rule.runOnIdle { assertEquals(1, modelClicks) }
        screenshot("chat-light")
        rule.onNodeWithTag("review-input").performTextInput("把这个想法留到明天。")
        rule.onNodeWithTag("review-input").assertTextContains("把这个想法留到明天。")
    }

    @Test fun readingAndComposerRemainLegibleInDarkMode() {
        content(dark = true) { ReadingFixture({}) }
        rule.onNodeWithText("一个可以慢慢展开的想法").assertExists()
        screenshot("chat-dark")
    }

    @Composable private fun ReadingFixture(onModelClick: () -> Unit) {
        var draft by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ConversationHeader("今天的灵感", "gemini-test", {}, onModelClick) {}
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp)) {
                StructuredMessageText(
                    "## 一个可以慢慢展开的想法\n\n先把最想表达的一句话写下来，再给它留一点生长的空间。\n\n- 留意那些反复出现的小问题\n- 只保留眼下最重要的一步\n- 让第一版真正进入日常生活\n\n> 好的工具，让注意力回到你正在做的事情上。",
                    streaming = false, error = false
                )
            }
            ConversationComposer(
                value = draft, attachments = emptyList(), loading = false, attachmentLoading = false,
                onValueChange = { draft = it }, onOptionsClick = {}, onRemoveImage = {},
                onSend = {}, onStop = {}, onFocusChange = {}, testTag = "review"
            )
        }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(rule.activity.getExternalFilesDir(null), "ui-preview").apply { mkdirs() }
        try {
            File(directory, "experience-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "experience-$name.png")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/aster-ui-preview")
                }
                val resolver = rule.activity.contentResolver
                val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { bitmap.recycle() }
    }
}
