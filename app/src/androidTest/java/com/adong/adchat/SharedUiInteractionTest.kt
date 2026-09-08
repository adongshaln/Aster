package com.adong.adchat

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.ui.components.*
import com.adong.adchat.ui.theme.AsterTheme
import com.adong.adchat.ui.theme.Canvas
import com.adong.adchat.ui.MainViewModel
import androidx.lifecycle.ViewModelProvider
import com.adong.adchat.data.Conversation
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.story.StoryWorkspace
import com.adong.adchat.ui.screens.EmptyChat
import com.adong.adchat.ui.screens.StoryWorkspaceEmpty
import com.adong.adchat.ui.screens.StructuredMessageText
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
        // Dismiss only the known cold-boot launcher ANR, never an Aster error dialog.
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        if (root?.packageName?.toString() == "android" &&
            root.findAccessibilityNodeInfosByText("Pixel Launcher isn't responding").isNotEmpty()) {
            root.findAccessibilityNodeInfosByText("Close app").firstOrNull()
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        rule.waitUntil(10_000) { rule.activity.hasWindowFocus() }
        rule.runOnUiThread { rule.activity.setContent { AsterTheme { block() } } }
    }

    @Test fun ordinaryComposerCollapsesWithoutLosingDraft() = checkKeyboard("chat")
    @Test fun storyComposerCollapsesWithoutLosingDraft() = checkKeyboard("story")

    @OptIn(ExperimentalTestApi::class)
    @Test fun expandedDraftPreservesTextAndSelection() {
        var draft by mutableStateOf("第一行\n第二行")
        var sent = ""
        content {
            Box(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
                ConversationComposer(draft, emptyList(), false, false, { draft = it }, {}, {},
                    { sent = draft }, {}, {}, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
        rule.onNodeWithTag("chat-input").performClick()
        rule.onNodeWithContentDescription("展开草稿").performClick()
        val revised = "第一行：补充世界观\n第二行：安排人物出场\n第三行：暂时保留悬念"
        rule.onNodeWithTag("chat-expanded-input").performTextReplacement(revised)
        rule.onNodeWithTag("chat-expanded-input").performTextInputSelection(TextRange(3, 8))
        rule.runOnIdle { assertEquals(revised, draft); assertEquals("", sent) }
        screenshot("long-draft-editor")
        rule.onNodeWithContentDescription("收起草稿").performClick()
        rule.onNodeWithTag("chat-expanded-input").assertDoesNotExist()
        rule.onNodeWithTag("chat-input").assertTextEquals(revised)
        rule.onNodeWithTag("chat-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3, 8)))
        rule.onNodeWithTag("chat-input").performClick()
        rule.onNodeWithContentDescription("展开草稿").performClick()
        rule.onNodeWithText("发送").performClick()
        rule.runOnIdle { assertEquals(revised, sent) }
        rule.onNodeWithTag("chat-expanded-input").assertDoesNotExist()
    }

    @Test fun expandedDraftHonorsImportAndStopState() {
        var draft by mutableStateOf("草稿")
        var importing by mutableStateOf(false)
        var loading by mutableStateOf(false)
        var stops = 0
        var sends = 0
        content {
            ConversationComposer(draft, emptyList(), loading, importing, { draft = it }, {}, {},
                { sends++ }, { stops++ }, {})
        }
        rule.onNodeWithTag("chat-input").performClick()
        rule.onNodeWithContentDescription("展开草稿").performClick()
        rule.runOnIdle { importing = true }
        rule.onNodeWithText("发送").assertIsNotEnabled()
        rule.runOnIdle { loading = true }
        rule.onNodeWithText("停止生成").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, stops); assertEquals(0, sends); loading = false; importing = false }
        rule.onNodeWithText("发送").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, sends) }
    }

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
        rule.onNodeWithTag("$mode-input").assertIsFocused()
        // Request the software IME explicitly: CI emulators also have a hardware keyboard.
        rule.runOnUiThread {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
                .show(WindowInsetsCompat.Type.ime())
        }
        try { rule.waitUntil(10_000) { imeVisible() } }
        catch (error: Throwable) { throw AssertionError("$mode: software keyboard did not open", error) }
        rule.waitUntil(5_000) {
            rule.onNodeWithTag("$mode-composer").fetchSemanticsNode().boundsInRoot.height > compact + 20
        }
        screenshot("$mode-expanded")
        rule.runOnUiThread {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        try { rule.waitUntil(10_000) { !imeVisible() } }
        catch (error: Throwable) { throw AssertionError("$mode: software keyboard did not close", error) }
        // Window visibility changes before the final IME / Compose animation frames.
        rule.waitUntil(5_000) {
            kotlin.math.abs(rule.onNodeWithTag("$mode-composer").fetchSemanticsNode().boundsInRoot.height - compact) < 3
        }
        rule.onNodeWithTag("$mode-input").assertIsNotFocused()
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

    @Test fun welcomeCardsAndSharedReadingPreview() {
        var screen by mutableStateOf(0)
        var chosen = ""
        content {
            Column(Modifier.fillMaxSize().background(Canvas).statusBarsPadding().navigationBarsPadding()) {
                ConversationHeader(if (screen == 2) "林间回声" else "Aster", "gemini · 创作伙伴", {}, {}) {}
                if (screen == 2) AsterSegmentedControl(listOf("讨论", "正文"), 0, {})
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        0 -> BaselineEmptyChat("gemini", {}, {}, Modifier.fillMaxSize().padding(bottom = 82.dp))
                        1 -> EmptyChat("gemini", { chosen = it }, {}, Modifier.fillMaxSize().padding(bottom = 82.dp))
                        2 -> StoryWorkspaceEmpty(StoryWorkspace.Discussion, Modifier.fillMaxSize().padding(bottom = 82.dp)) { chosen = it }
                        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp, vertical = 20.dp).padding(bottom = 82.dp)) {
                            ConversationAuthor()
                            StructuredMessageText("# 雨后的森林\n\n晨光穿过树梢，在湿润的石阶上留下细碎的光。远处传来鸟鸣，溪水沿着林间的小路缓缓流过。\n\n## 留给这一幕的细节\n\n- 树叶上尚未落下的雨滴\n- 旧木门轻轻开启的声音\n- 一封还没有拆开的信\n\n> 她停在门前，没有立刻推门。那些想说的话，似乎都被清晨的风留在了身后。\n\n故事可以在这里放慢一点，让人物的犹豫通过动作显露出来。", false, false)
                        }
                    }
                    ConversationComposer("", emptyList(), false, false, {}, {}, {}, {}, {}, {},
                        modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        screenshot("welcome-before")
        rule.runOnIdle { screen = 1 }
        rule.onNodeWithText("把想法，写在这里。").assertIsDisplayed()
        screenshot("welcome-after")
        rule.onNodeWithText("一起创作").performClick()
        rule.runOnIdle { assertTrue(chosen.contains("打磨想法")); screen = 2 }
        rule.onNodeWithText("故事，从想象开始。").assertIsDisplayed()
        screenshot("story-welcome-after")
        rule.onNodeWithText("梳理走向").performClick()
        rule.runOnIdle { assertTrue(chosen.contains("不要把讨论当作已发生的剧情")); screen = 3 }
        screenshot("reading-after")
    }

    @Test fun drawerKeepsSearchNavigationAndActionsReachable() {
        lateinit var vm: MainViewModel
        val now = System.currentTimeMillis()
        var redesigned by mutableStateOf(false)
        var destination: AppPage? = null
        var selected = ""
        rule.runOnUiThread {
            vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
            vm.conversations.clear()
            vm.conversations.addAll(listOf(
                Conversation(id = "forest", title = "林间回声 · 第一幕", messages = listOf(ChatMessage(role = "assistant", content = "晨光穿过树梢，她在旧木门前停下。")), updatedAt = now),
                Conversation(id = "notes", title = "晨间笔记", messages = listOf(ChatMessage(role = "user", content = "把今天最重要的三件事整理一下。")), updatedAt = now - 3600000),
                Conversation(id = "ideas", title = "一个新故事的想法", messages = listOf(ChatMessage(role = "assistant", content = "我们可以先从人物的动机开始。")), updatedAt = now - 86400000),
                Conversation(id = "reading", title = "最近读到的一句话", messages = listOf(ChatMessage(role = "user", content = "帮我理解这段文字中的比喻。")), updatedAt = now - 172800000)
            ))
            vm.selectConversation("forest")
        }
        content {
            if (redesigned) AppDrawer(vm, AppPage.Chat, {}, {}, { selected = it }, { destination = it }, {})
            else BaselineAppDrawer(vm, AppPage.Chat, {}, {}, {}, {}, {})
        }
        screenshot("drawer-before")
        rule.runOnIdle { redesigned = true }
        rule.onNodeWithText("对话记录").assertIsDisplayed()
        screenshot("drawer-after")
        rule.onNodeWithText("故事").performClick()
        rule.runOnIdle { assertEquals(AppPage.Story, destination) }
        rule.onNode(hasSetTextAction()).performTextReplacement("林间")
        rule.onNodeWithText("晨间笔记").assertDoesNotExist()
        rule.onNodeWithText("林间回声 · 第一幕").performClick()
        rule.runOnIdle { assertEquals("forest", selected) }
        rule.onNodeWithContentDescription("更多：林间回声 · 第一幕").performClick()
        rule.onNodeWithText("重命名").assertIsDisplayed()
        rule.onNodeWithText("删除对话").assertIsDisplayed()
    }

    @Test fun modelContextEditorValidatesAppliesAndResets() {
        var applied: com.adong.adchat.data.ModelContextLimits? = null
        var changes = 0
        content {
            com.adong.adchat.ui.screens.ModelContextDialog("gemini-custom",null,{}, { applied=it;changes++ })
        }
        rule.onNodeWithText("应用").assertIsNotEnabled()
        rule.onNodeWithText("128K").performClick()
        rule.onNodeWithText("应用").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(131072,applied?.windowTokens);assertEquals(8192,applied?.outputTokens) }
        rule.onNodeWithText("上下文窗口 · Token").performTextReplacement("4096")
        rule.onNodeWithText("应用").assertIsNotEnabled()
        rule.onNodeWithText("最大输出 · Token").performTextReplacement("512")
        rule.onNodeWithText("应用").assertIsEnabled()
        screenshot("model-context")
        rule.onNodeWithText("恢复默认").performClick()
        rule.runOnIdle { assertNull(applied);assertEquals(2,changes) }
    }

    @Test fun modelPickerPresetsSaveWithoutChangingRoutesOrClosingSheet() {
        lateinit var vm: MainViewModel
        val profile=com.adong.adchat.data.ApiProfile(id="context-picker-test", name="创作 API",baseUrl="https://example.com",
            chatModel="gemini-a",cachedModels=listOf(com.adong.adchat.data.ApiModel("gemini-a"),com.adong.adchat.data.ApiModel("gemini-b")),
            modelContexts=mapOf("gemini-b" to com.adong.adchat.data.ModelContextLimits(131072,16384)))
        var picked=""
        var dismissed=false
        rule.runOnUiThread {
            vm=ViewModelProvider(rule.activity)[MainViewModel::class.java]
            vm.saveProfile(profile)
        }
        content {
            QuickModelSwitcher(RouteKind.Chat,vm,{dismissed=true},{},routeProfileId=profile.id,routeModel="gemini-b",
                onSelectChatModel={ id,model -> picked="$id/$model" })
        }
        rule.onNodeWithContentDescription("设置 gemini-b 的上下文").performScrollTo().performClick()
        rule.onNodeWithText("512K").performScrollTo().performClick()
        rule.onNode(hasText("512K") and SemanticsMatcher.expectValue(SemanticsProperties.Selected,true)).assertExists()
        rule.runOnIdle {
            assertFalse(dismissed);assertEquals("",picked)
            val saved=com.adong.adchat.data.ConfigStore(rule.activity).load().profiles.first { it.id==profile.id }
            assertEquals(524288,saved.modelContexts["gemini-b"]?.windowTokens)
            assertEquals(16384,saved.modelContexts["gemini-b"]?.outputTokens)
            assertNull(saved.modelContexts["gemini-a"])
            assertEquals("gemini-a",saved.chatModel)
        }
        screenshot("quick-context-presets")
        rule.onNodeWithText("gemini-a").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("context-picker-test/gemini-a",picked);assertTrue(dismissed) }
    }

    private fun imeVisible() = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun screenshot(name: String) {
        rule.waitForIdle()
        // Compose semantics can settle before SurfaceFlinger presents the new frame.
        // Finish transient ripples and allow the native window to draw before capture.
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val directory = File(rule.activity.getExternalFilesDir(null), "ui-preview").apply { mkdirs() }
        val file = File(directory, "shared-$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // UTP can uninstall the app after tests. Preserve screenshots outside its data directory.
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "shared-$name.png")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/aster-ui-preview")
        }
        val resolver = rule.activity.contentResolver
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
