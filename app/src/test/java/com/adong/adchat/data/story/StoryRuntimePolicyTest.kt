package com.adong.adchat.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryRuntimePolicyTest {
    @Test
    fun emptyStoppedReplyIsRemovedInsteadOfPersistedAsPlaceholder() {
        assertEquals(StoryStopCleanup.RemoveAssistant, storyStopCleanupFor("   \n"))
    }

    @Test
    fun stoppedPartialReplyIsKeptAsStoppedContent() {
        assertEquals(StoryStopCleanup.KeepStoppedPartial, storyStopCleanupFor("已经生成的正文"))
    }

    @Test
    fun workspaceUpdatedAtIsStrictlyMonotonicWithinSameWallClockTick() {
        assertEquals(101L, nextStoryWorkspaceUpdatedAt(previous = 100L, wallClock = 100L))
        assertEquals(150L, nextStoryWorkspaceUpdatedAt(previous = 101L, wallClock = 150L))
    }

    @Test
    fun staleWorkspaceSaveCannotOverwriteNewerDraftOrScrollState() {
        assertFalse(shouldPersistStoryWorkspaceState(existingUpdatedAt = 200L, incomingUpdatedAt = 199L))
        assertFalse(shouldPersistStoryWorkspaceState(existingUpdatedAt = 200L, incomingUpdatedAt = 200L))
        assertTrue(shouldPersistStoryWorkspaceState(existingUpdatedAt = 200L, incomingUpdatedAt = 201L))
        assertTrue(shouldPersistStoryWorkspaceState(existingUpdatedAt = null, incomingUpdatedAt = 1L))
    }
}
