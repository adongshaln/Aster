package com.adong.adchat.data

import com.adong.adchat.data.story.StoryProsePresenter
import org.junit.Assert.*
import org.junit.Test

class StoryProsePresentationTest {
    private fun present(raw: String, streaming: Boolean = false) =
        StoryProsePresenter.present(raw, null, "assistant", 0, false, streaming)

    @Test fun retainsPlanningBeforeBodyAndSummary() {
        val result = present("<konatan_planning~>检查人物关系</konatan_planning~>\n正文开始\n<details><summary>摘要</summary>角色已抵达</details>")
        assertEquals("检查人物关系", result.planning)
        assertFalse(result.planningIncomplete)
        assertEquals("正文开始", result.blocks.first().text)
        assertEquals("摘要", result.blocks.last().title)
        assertEquals("角色已抵达", result.blocks.last().text)
    }

    @Test fun partialPlanningIsNeverMisrepresentedAsCompletedBody() {
        val result = present("<think>正在整理", true)
        assertEquals("正在整理", result.thinking)
        assertTrue(result.thinkingIncomplete)
        assertTrue(result.planning.isEmpty())
        assertTrue(result.blocks.isEmpty())
        assertTrue(present("<think>已中断").thinkingIncomplete)
        assertTrue(present("<konatan_planning~>未完规划", true).planningIncomplete)
        assertEquals("前置规划", present("前置规划</konatan_planning~>正文").planning)
        assertTrue(present("直接返回正文").planning.isEmpty())
    }

    @Test fun orphanFenceInThinkingDoesNotSwallowPlanningBodyOrSummary() {
        val raw = "<think>分析\n```text\n<konatan_planning~>试写规划</konatan_planning~>\n```\n```\n试写正文\n</think>" +
            "<konatan_planning~>最终规划</konatan_planning~>\n最终正文\n<details><summary>摘要</summary>最终摘要</details>"
        val result = present(raw)
        assertTrue(result.thinking.contains("试写规划"))
        assertTrue(result.thinking.contains("试写正文"))
        assertFalse(result.thinking.contains("最终"))
        assertEquals("最终规划", result.planning)
        assertFalse(result.thinkingIncomplete)
        assertFalse(result.planningIncomplete)
        assertEquals("最终正文", result.blocks.first().text)
        assertEquals("最终摘要", result.blocks.last().text)
    }

    @Test fun preservesCodeExamplesAndPlainProseAroundMultipleHtmlFragments() {
        val result = present("```xml\n<think>这是示例</think>\n```\n前文\n```html\n<style>body{display:none}</style><details><summary>事件</summary>已发生</details><script>BAD()</script>\n```\n后文")
        assertTrue(result.planning.isEmpty())
        assertTrue(result.thinking.isEmpty())
        assertTrue(result.blocks.any { "<think>这是示例</think>" in it.text })
        assertTrue(result.blocks.any { it.text == "前文" })
        assertTrue(result.blocks.any { it.title == "事件" && it.text == "已发生" })
        assertTrue(result.blocks.any { it.text == "后文" })
        assertFalse(result.blocks.any { "BAD()" in it.text || "display:none" in it.text })
    }
}
