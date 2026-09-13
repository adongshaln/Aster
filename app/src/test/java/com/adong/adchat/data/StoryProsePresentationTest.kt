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
        assertEquals("正在整理", result.planning)
        assertTrue(result.planningIncomplete)
        assertTrue(result.blocks.isEmpty())
        assertTrue(present("<think>已中断").planningIncomplete)
        assertEquals("前置规划", present("前置规划</konatan_planning~>正文").planning)
        assertTrue(present("直接返回正文").planning.isEmpty())
    }

    @Test fun preservesCodeExamplesAndPlainProseAroundMultipleHtmlFragments() {
        val result = present("```xml\n<think>这是示例</think>\n```\n前文\n```html\n<style>body{display:none}</style><details><summary>事件</summary>已发生</details><script>BAD()</script>\n```\n后文")
        assertTrue(result.planning.isEmpty())
        assertTrue(result.blocks.any { "<think>这是示例</think>" in it.text })
        assertTrue(result.blocks.any { it.text == "前文" })
        assertTrue(result.blocks.any { it.title == "事件" && it.text == "已发生" })
        assertTrue(result.blocks.any { it.text == "后文" })
        assertFalse(result.blocks.any { "BAD()" in it.text || "display:none" in it.text })
    }
}
