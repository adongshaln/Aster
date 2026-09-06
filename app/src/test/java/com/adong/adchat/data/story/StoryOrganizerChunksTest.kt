package com.adong.adchat.data.story

import org.junit.Assert.*
import org.junit.Test

class StoryOrganizerChunksTest {
    @Test fun chunksCoverExactTextAndRespectInputBudgetWithoutBreakingEmoji() {
        val text = "甲".repeat(23_999) + "😀" + "乙".repeat(30_000) + "\n尾声"
        val parts = StoryOrganizerChunks.plan(text, "要求")
        assertTrue(parts.size > 1)
        assertEquals(text, parts.joinToString("") { it.text })
        assertEquals(text.length, parts.last().end)
        parts.forEach {
            assertFalse(it.text.first().isLowSurrogate()); assertFalse(it.text.last().isHighSurrogate())
            assertTrue(it.text.length + it.precedingContext.length + 2 <= 36_000)
        }
    }
    @Test fun incompletePartResultsCannotBeCombined() {
        val parts = StoryOrganizerChunks.plan("甲".repeat(50_000), "")
        assertThrows(IllegalArgumentException::class.java) {
            StoryOrganizerChunks.combine(parts, listOf(StoryOrganizerOutput(emptyList(), emptyList())))
        }
    }
    @Test fun tooManyPartsAndOversizedUserInputFailBeforeAnyRequests() {
        assertThrows(IllegalArgumentException::class.java) { StoryOrganizerChunks.plan("甲".repeat(400_000), "") }
        assertThrows(IllegalArgumentException::class.java) { StoryOrganizerChunks.plan("正文", "甲".repeat(35_000)) }
    }
}
