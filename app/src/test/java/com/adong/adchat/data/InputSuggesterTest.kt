package com.adong.adchat.data

import org.junit.Assert.*
import org.junit.Test

class InputSuggesterTest {
    @Test fun keepsTheLatestIntentAndReplyEndingWithoutLeakingIncompleteOrNonTextContext() {
        val history = listOf(
            ChatMessage(role = "user", content = "更早的问题"),
            ChatMessage(role = "assistant", content = "更早的回复"),
            ChatMessage(role = "user", content = "如何选择缓存策略？"),
            ChatMessage(role = "assistant", content = "可以按读写比例选择。"),
            ChatMessage(role = "user", content = "请设计缓存。" + "背景信息".repeat(200) + "最终要求是支持离线。"),
            ChatMessage(role = "assistant", content = "建议先分层。" + "方案细节".repeat(600) + "下一步可以检查离线冲突。",
                reasoning = "不应提供给建议器的推理", attachments = listOf(ChatImageAttachment(uri = "file:///private.jpg", name = "private.jpg")))
        )

        val context = InputSuggester.buildSuggestHistory(history)

        assertEquals(listOf("user", "assistant", "user", "assistant"), context.map { it.role })
        assertTrue(context[2].content.startsWith("请设计缓存。"))
        assertTrue(context[2].content.endsWith("最终要求是支持离线。"))
        assertTrue(context.last().content.startsWith("建议先分层。"))
        assertTrue(context.last().content.endsWith("下一步可以检查离线冲突。"))
        assertTrue(context.last().reasoning.isEmpty())
        assertTrue(context.last().attachments.isEmpty())
        assertTrue(context.sumOf { it.content.length } < 3100)
        assertTrue(InputSuggester.buildSuggestHistory(history.dropLast(1) + history.last().copy(isStopped = true)).isEmpty())
    }

    @Test fun rejectsGenericRepeatedInventedAndMalformedSuggestionsWithoutTruncatingThem() {
        val history = listOf(
            ChatMessage(role = "user", content = "如何设计缓存？"),
            ChatMessage(role = "assistant", content = "可以先将内存缓存和磁盘缓存分层。")
        )
        val suggestion = "请给一个带注释的缓存示例"
        assertEquals(suggestion, InputSuggester.cleanSuggestion("  “$suggestion”  ", history))
        listOf(
            "结束。", "还有什么需要注意的吗？", "你想让我进一步解释缓存吗？", "我的预算是两万元",
            "如何设计缓存!", "可以先将内存缓存和磁盘缓存分层。", "1. 给我一个缓存示例", "建议：给我一个缓存示例",
            "给我一个缓存示例\n再写一份测试", "请给一个缓存示例…", "请".repeat(61)
        ).forEach { assertNull(it, InputSuggester.cleanSuggestion(it, history)) }
    }
}
