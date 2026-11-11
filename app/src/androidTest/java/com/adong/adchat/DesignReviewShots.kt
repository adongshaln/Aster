package com.adong.adchat

import android.graphics.Bitmap
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adong.adchat.data.Conversation
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.THEME_MODE_ASTER
import com.adong.adchat.data.THEME_MODE_DARK
import com.adong.adchat.data.TokenUsage
import com.adong.adchat.data.rp.RpConfig
import com.adong.adchat.data.story.StoryCharacterCard
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.rp.RpViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks the main screens with local fixtures and saves one screenshot per stop, for same-content
 * before/after review. It sends no requests. A stop that cannot be reached is logged and skipped so
 * one renamed label does not hide every later screen.
 *
 * adb shell am instrument -w -e class com.adong.adchat.DesignReviewShots \
 *   com.adong.adchat.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/com.adong.adchat/files/design-review
 */
@RunWith(AndroidJUnit4::class)
class DesignReviewShots {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val reply = """
        ## 从一个值得解决的问题开始

        先把目标缩小到：**一周内，做出自己愿意每天使用的东西。** 范围小，才看得见终点，也才有余力把细节做好。

        1. 找到日常里一个反复出现的小麻烦。
        2. 只保留解决它所需的核心操作。
        3. 做出第一版，在真实使用中慢慢打磨。

        > 质感，来自每一个被认真对待的细节。

        下面是一个最小的起点，`main` 里只做一件事：

        ```kotlin
        fun main() {
            println("今天只解决一个问题")
        }
        ```

        | 阶段 | 目标 | 用时 |
        | :--- | :--- | ---: |
        | 第一版 | 能用 | 2 天 |
        | 打磨 | 顺手 | 3 天 |
        | 分享 | 给三个人试用 | 2 天 |

        做完之后再决定要不要继续。很多项目停在第一版，也已经足够有用。
    """.trimIndent()

    private val opening = """
        *雨声敲在老宅的瓦上，像谁在屋顶轻轻数着时间。*

        林夕把油灯往桌角推了推，抬眼看向门口。灯芯跳了一下，墙上的影子跟着晃了晃，又慢慢稳住。

        「你来得比信上写的早了一天。」她的声音不高，却让满屋的潮气都静了下来。

        "路上没遇到麻烦吧？" 她顿了顿，又补了一句，（如果有，也不必现在说。）

        <details><summary>状态栏</summary><div style="padding:10px 12px;font-size:14px;line-height:1.7">时间：戌时三刻<br>地点：林家老宅 · 西厢<br>天气：夜雨</div></details>

        窗外的雨更密了。她起身去关那扇总也合不严的窗，木轴发出一声很轻的叹息。
    """.trimIndent()

    @Test fun captureMainScreens() {
        lateinit var vm: MainViewModel
        lateinit var rpVm: RpViewModel
        rule.runOnIdle {
            vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
            rpVm = ViewModelProvider(rule.activity)[RpViewModel::class.java]
            vm.setThemeMode(THEME_MODE_ASTER)
            vm.skipUserNamePrompt()
            vm.newConversation()
            vm.selectChatModel(vm.chatProfile.id, "gpt-5.6")
            vm.dismissNotice()
        }
        stop("01-chat-home") {}
        stop("02-composer-open") {
            // A non-empty focused draft keeps Clawd's compose clip alive. Do not ask Espresso
            // to fast-forward an intentionally continuous animation until it becomes idle.
            rule.mainClock.autoAdvance = false
            rule.onNodeWithTag("chat-input").performClick()
            rule.onNodeWithTag("chat-input").performTextInput("我有一个想法，想和你一起展开。")
            rule.mainClock.advanceTimeBy(900)
            Thread.sleep(900)
        }
        back()
        rule.runOnUiThread { vm.updateChatInput("") }
        rule.mainClock.advanceTimeBy(700)
        rule.mainClock.autoAdvance = true
        rule.runOnIdle {
            vm.messages.addAll(listOf(
                ChatMessage(role = "user", content = "怎样开始一个小而美的个人项目？我总是想得太大，最后什么也没做完。"),
                ChatMessage(
                    role = "assistant", content = reply, model = "gpt-5.6", profileName = "默认服务",
                    reasoning = "用户的困难是范围过大。先给出收窄目标的办法，再给一个可以立刻动手的最小步骤。",
                    usage = TokenUsage(inputTokens = 1280, outputTokens = 412, totalTokens = 1692)
                )
            ))
        }
        stop("03-chat-reading-end") {}
        stop("04-chat-reading-top") {
            rule.onNodeWithTag("chat-messages").performScrollToIndex(0)
        }
        stop("05-model-sheet") { rule.onNodeWithTag("conversation-model-selector").performClick() }
        back()
        stop("06-chat-tools") { rule.onNodeWithContentDescription("输入选项").performClick() }
        back()
        rule.runOnIdle {
            vm.conversations.removeAll { it.id.startsWith("design-fixture-") }
            listOf("写给未来的一封信", "把想法整理成一个小项目", "周末的阅读清单").forEachIndexed { index, title ->
                vm.conversations.add(Conversation(id = "design-fixture-$index", title = title,
                    messages = listOf(ChatMessage(role = "user", content = "从日常的一点灵感开始。")),
                    profileId = vm.chatProfile.id, model = "gpt-5.6", pinned = index == 0))
            }
        }
        stop("07-drawer") { rule.onNodeWithContentDescription("打开侧栏").performClick() }
        stop("08-rp-empty") { rule.onNodeWithText("剧情").performClick() }
        rule.runOnIdle {
            rpVm.createSession(
                RpConfig(
                    card = StoryCharacterCard(
                        name = "林夕", description = "林家老宅的守宅人，寡言，记性极好。", personality = "克制、细心",
                        scenario = "连日夜雨，你按一封旧信的约定来到林家老宅。"
                    ),
                    opening = opening
                ),
                vm.chatProfile.id, "gpt-5.6"
            )
        }
        stop("09-rp-reading") { settlePreview() }
        stop("10-rp-tools") { rule.onNodeWithContentDescription("输入选项").performClick() }
        back()
        stop("11-rp-drawer") { rule.onNodeWithContentDescription("打开侧栏").performClick() }
        stop("12-create") { rule.onNodeWithText("创作").performClick() }
        stop("13-download") {
            rule.onNodeWithContentDescription("打开侧栏").performClick()
            rule.onNodeWithText("下载").performClick()
        }
        stop("14-settings") {
            rule.onNodeWithContentDescription("打开侧栏").performClick()
            rule.onNodeWithText("设置").performClick()
        }
        rule.runOnIdle { vm.setThemeMode(THEME_MODE_DARK) }
        stop("15-dark-settings") { Thread.sleep(600) }
        stop("16-dark-drawer") { rule.onNodeWithContentDescription("打开侧栏").performClick() }
        stop("17-dark-rp") { rule.onNodeWithText("剧情").performClick(); settlePreview() }
        back()
        stop("18-dark-chat") { Thread.sleep(400) }
        stop("19-dark-model-sheet") { rule.onNodeWithTag("conversation-model-selector").performClick() }
        back()
        rule.runOnIdle { vm.setThemeMode(THEME_MODE_ASTER) }
    }

    private fun back() {
        runCatching {
            rule.waitForIdle()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle(); Thread.sleep(350)
        }
    }

    private fun settlePreview() {
        // HTML preparation and Chromium run on real threads, while the Compose mount queue and
        // reveal use the test frame clock. Pump both instead of only sleeping on the test thread.
        repeat(6) { rule.mainClock.advanceTimeBy(160); Thread.sleep(200) }
    }

    private fun stop(name: String, reach: () -> Unit) {
        try {
            reach()
            rule.waitForIdle()
            Thread.sleep(450)
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val directory = File(rule.activity.getExternalFilesDir(null), "design-review").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            Log.i("DesignReview", "saved $name")
        } catch (problem: Throwable) {
            Log.w("DesignReview", "skipped $name: ${problem.message?.lineSequence()?.firstOrNull()}")
        }
    }
}
