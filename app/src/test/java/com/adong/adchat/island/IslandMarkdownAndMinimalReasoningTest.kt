package com.adong.adchat.island

import android.view.ViewGroup
import android.widget.TextView
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.ReasoningPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IslandMarkdownAndMinimalReasoningTest {

    @Test fun starIslandAlwaysSelectsLowestReasoningModeForEveryModelFamily() {
        fun profileFor(model: String) = StarIslandTaskRunner.starIslandRequestProfile(
            ApiProfile(baseUrl = "https://api.example.com", chatModel = model, reasoningEffort = "max")
        )

        // DeepSeek: off/none ("thinking": {"type": "disabled"})
        val dsProfile = profileFor("deepseek-reasoner")
        assertEquals("none", dsProfile.reasoningEffort)
        val dsChatBody = JSONObject()
        ReasoningPolicy.write(dsChatBody, dsProfile.chatModel, dsProfile.reasoningEffort, responsesApi = false, outputTokenLimit = 8192)
        assertEquals("disabled", dsChatBody.getJSONObject("thinking").getString("type"))
        assertFalse(dsChatBody.has("reasoning_effort"))

        val dsResponsesBody = JSONObject()
        ReasoningPolicy.write(dsResponsesBody, dsProfile.chatModel, dsProfile.reasoningEffort, responsesApi = true, outputTokenLimit = 8192)
        assertEquals("disabled", dsResponsesBody.getJSONObject("thinking").getString("type"))
        assertEquals("none", dsResponsesBody.getJSONObject("reasoning").getString("effort"))

        // GLM: none ("thinking": {"type": "disabled"})
        val glmProfile = profileFor("glm-4.6")
        assertEquals("none", glmProfile.reasoningEffort)
        val glmBody = JSONObject()
        ReasoningPolicy.write(glmBody, glmProfile.chatModel, glmProfile.reasoningEffort, responsesApi = false, outputTokenLimit = 8192)
        assertEquals("disabled", glmBody.getJSONObject("thinking").getString("type"))

        // Kimi K2.x toggle: off ("thinking": {"type": "disabled"})
        val kimiToggle = profileFor("kimi-k2.6")
        assertEquals("off", kimiToggle.reasoningEffort)
        val kimiToggleBody = JSONObject()
        ReasoningPolicy.write(kimiToggleBody, kimiToggle.chatModel, kimiToggle.reasoningEffort, responsesApi = false, outputTokenLimit = 8192)
        assertEquals("disabled", kimiToggleBody.getJSONObject("thinking").getString("type"))

        // GPT: minimal
        val gptProfile = profileFor("gpt-5.6-sol")
        assertEquals("minimal", gptProfile.reasoningEffort)

        // Grok / Claude / Kimi K3: low
        assertEquals("low", profileFor("grok-4.6").reasoningEffort)
        assertEquals("low", profileFor("claude-sonnet-4.5").reasoningEffort)
        assertEquals("low", profileFor("kimi-k3").reasoningEffort)

        // All side-effects remain disabled in Star Island
        assertFalse(dsProfile.autoResumeStream)
        assertFalse(dsProfile.promptCacheEnabled)
        assertFalse(dsProfile.webSearchEnabled)
        assertFalse(dsProfile.fileCreationEnabled)
    }

    @Test fun markdownParserBuildsHeadingsListsQuotesCodeAndTables() {
        val raw = """
            # 核心结论
            这是一段包含 **重点加粗** 与 `inline_code` 的说明。

            - 第一条要点
            - [x] 已完成检查

            > 引用原文的关键句子

            ```kotlin
            val answer = 42
            ```

            | 字段 | 含义 |
            | :--- | ---: |
            | mode | minimal |
        """.trimIndent()

        val blocks = IslandMarkdown.parseBlocks(raw)
        assertTrue(blocks.any { it is IslandMarkdownBlock.Heading && it.text == "核心结论" && it.level == 1 })
        assertTrue(blocks.any { it is IslandMarkdownBlock.Paragraph && it.text.contains("**重点加粗**") })
        assertTrue(blocks.any { it is IslandMarkdownBlock.ListItem && it.marker == "•" && it.text == "第一条要点" })
        assertTrue(blocks.any { it is IslandMarkdownBlock.ListItem && it.marker == "✓" && it.text == "已完成检查" })
        assertTrue(blocks.any { it is IslandMarkdownBlock.Quote && it.text == "引用原文的关键句子" })
        assertTrue(blocks.any { it is IslandMarkdownBlock.CodeBlock && it.language == "kotlin" && it.code == "val answer = 42" })
        assertTrue(blocks.any { it is IslandMarkdownBlock.Table && it.table.header == listOf("字段", "含义") })
    }

    @Test fun markdownViewRendersFormattedSpansInBothClipboardAndScreenPanels() {
        val context = RuntimeEnvironment.getApplication()
        val mdView = IslandMarkdownView(context, IslandPalette.Light)
        mdView.setMarkdown("## 标题\n\n- **加粗项** 与 `code`")
        assertTrue("Markdown view should create block child views", mdView.childCount >= 2)

        fun collectTexts(group: ViewGroup, out: MutableList<String> = mutableListOf()): List<String> {
            for (i in 0 until group.childCount) {
                when (val child = group.getChildAt(i)) {
                    is TextView -> out += child.text.toString()
                    is ViewGroup -> collectTexts(child, out)
                }
            }
            return out
        }
        val renderedTexts = collectTexts(mdView)
        assertTrue(renderedTexts.any { it.contains("标题") })
        assertTrue(renderedTexts.any { it.contains("加粗项") && !it.contains("**") })
    }
}
