package com.adong.adchat.data.story

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryModelsTest {
    @Test
    fun onlyCompleteProseRevisionIsEligibleForMemory() {
        val proseComplete = revision(StoryWorkspace.Prose, StoryRevisionState.Complete, "发生了一件事")
        val discussionComplete = revision(StoryWorkspace.Discussion, StoryRevisionState.Complete, "也许可以这样")
        val interruptedProse = revision(StoryWorkspace.Prose, StoryRevisionState.Interrupted, "未完成")
        val blankProse = revision(StoryWorkspace.Prose, StoryRevisionState.Complete, "")

        assertTrue(proseComplete.eligibleForMemory)
        assertFalse(discussionComplete.eligibleForMemory)
        assertFalse(interruptedProse.eligibleForMemory)
        assertFalse(blankProse.eligibleForMemory)
    }

    @Test
    fun generatedIdsKeepDomainPrefix() {
        assertTrue(newStoryId().startsWith("story_"))
        assertTrue(newTimelineId().startsWith("timeline_"))
        assertTrue(newMessageId().startsWith("message_"))
        assertTrue(newRevisionId().startsWith("revision_"))
        assertTrue(newMemoryId().startsWith("memory_"))
        assertTrue(newJobId().startsWith("job_"))
    }

    private fun revision(
        workspace: StoryWorkspace,
        state: StoryRevisionState,
        content: String
    ): StoryMessageRevision = StoryMessageRevision(
        messageId = "message_test",
        storyId = "story_test",
        timelineId = "timeline_test",
        workspace = workspace,
        content = content,
        state = state
    )
}
