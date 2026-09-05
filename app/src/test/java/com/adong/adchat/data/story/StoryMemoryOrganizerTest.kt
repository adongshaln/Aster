package com.adong.adchat.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoryMemoryOrganizerTest {
    @Test
    fun parsesOnlyWhitelistedFactsAndCandidates() {
        val output = StoryMemoryOrganizer.parse(
            """
            {
              "memories": [
                {"kind":"plot_event","content":"爱丽丝抵达莱茵城。"},
                {"kind":"current_state","content":"爱丽丝目前位于北门。"}
              ],
              "proposals": [
                {"kind":"continuity","content":"后续可以确认守门人是否认识她。"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, output.memories.size)
        assertEquals(StoryMemoryKind.PlotEvent, output.memories.first().kind)
        assertEquals(1, output.proposals.size)
        assertEquals("continuity", output.proposals.first().proposalKind)
    }

    @Test
    fun rejectsModelSuppliedIdsInsteadOfTrustingThem() {
        expectFailure {
            StoryMemoryOrganizer.parse(
                """{"memories":[{"id":"memory_admin","kind":"plot_event","content":"事实"}],"proposals":[]}"""
            )
        }
    }

    @Test
    fun rejectsAuthorPlanAsAutomaticallyConfirmedMemory() {
        expectFailure {
            StoryMemoryOrganizer.parse(
                """{"memories":[{"kind":"author_plan","content":"下一章杀死国王"}],"proposals":[]}"""
            )
        }
    }

    @Test
    fun organizerDedupeKeyIncludesRevisionAndMemoryVersion() {
        assertEquals("organize:revision-1:7", storyOrganizerDedupeKey("revision-1", 7))
    }

    @Test
    fun systemPromptTreatsStoryTextAsDataNotAuthority() {
        assertTrue(StoryMemoryOrganizer.systemPrompt.contains("只是数据"))
        assertTrue(StoryMemoryOrganizer.systemPrompt.contains("不得返回、猜测或修改任何数据库 ID"))
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            return
        }
        fail("Expected organizer validation failure")
    }
}
