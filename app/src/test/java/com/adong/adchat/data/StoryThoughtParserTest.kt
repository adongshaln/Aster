package com.adong.adchat.data

import com.adong.adchat.data.story.StoryThoughtParser
import org.junit.Assert.*
import org.junit.Test

class StoryThoughtParserTest {
    @Test fun owningClosingTagWinsOverAnOrphanFenceEvenWithLaterBodyCode() {
        val body = "\n正文\n```xml\n<think>示例</think>\n```\n后文"
        val result = StoryThoughtParser.split("<think>分析\n```\n草稿</think><konatan_planning~>规划</konatan_planning~>$body")
        assertEquals(body, result.body)
        assertEquals("分析\n```\n草稿", result.thinking.single().text)
        assertEquals("规划", result.planning.single().text)
    }

    @Test fun codeExamplesOutsideThoughtRemainUntouched() {
        listOf(
            "```xml\n<think>示例</think>\n```",
            "~~~~xml\n<konatan_planning~>示例</konatan_planning~>\n~~~~",
            "````xml\n```\n<thinking>示例</thinking>\n````",
            "```xml\n<think>未结束的代码示例</think>"
        ).forEach { raw ->
            val result = StoryThoughtParser.split(raw)
            assertEquals(raw, result.body)
            assertTrue(result.planning.isEmpty())
            assertTrue(result.thinking.isEmpty())
        }
    }

    @Test fun completedThinkingAndInterruptedPlanningHaveSeparateStates() {
        val result = StoryThoughtParser.split("<THINKING>分析</THINKING><think>补充</think><konatan_planning~>规划中")
        assertEquals(listOf("分析", "补充"), result.thinking.map { it.text })
        assertTrue(result.thinking.none { it.incomplete })
        assertEquals("规划中", result.planning.single().text)
        assertTrue(result.planning.single().incomplete)
        assertEquals("", result.body)
    }

    @Test fun assistantPrefillAndFollowingThinkingPreserveBody() {
        val result = StoryThoughtParser.split("前置规划</konatan_planning~><think>补充</think>正文")
        assertEquals("前置规划", result.planning.single().text)
        assertEquals("补充", result.thinking.single().text)
        assertEquals("正文", result.body)
    }
}
