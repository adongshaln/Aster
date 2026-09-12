package com.adong.adchat.data.story

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StoryInterruptedRegenerationTest {
    private lateinit var context: Context
    private lateinit var repository: StoryRepository
    private lateinit var story: Story

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repository = StoryRepository(context)
        story = repository.createStory("retry", "profile", "model")
    }

    @After
    fun tearDown() = repository.close()

    @Test
    fun retryReusesUserTurnAndKeepsFailedRevision() {
        repository.appendMessage(
            story.id, story.currentTimelineId, StoryWorkspace.Prose,
            role = "user", content = "推开门"
        )
        val failed = repository.appendMessage(
            story.id, story.currentTimelineId, StoryWorkspace.Prose,
            role = "assistant", content = "门后传来",
            state = StoryRevisionState.Interrupted,
            profileName = "old", model = "old-model"
        )

        val retry = repository.restartInterruptedRevision(
            failed.message.id, failed.revision.id, "new", "new-model"
        )

        assertEquals(failed.message.id, retry.message.id)
        assertNotEquals(failed.revision.id, retry.revision.id)
        assertEquals(StoryRevisionState.Streaming, retry.revision.state)
        assertEquals("", retry.revision.content)
        assertEquals("new", retry.revision.profileName)
        assertEquals("new-model", retry.revision.model)
        assertEquals(2, repository.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Prose).size)
        val revisions = repository.listRevisions(failed.message.id)
        assertEquals(2, revisions.size)
        assertTrue(revisions.any { it.id == failed.revision.id && it.content == "门后传来" })
    }

    @Test
    fun retryRejectsCompletedStaleAndNonLatestReplies() {
        repository.appendMessage(story.id, story.currentTimelineId, StoryWorkspace.Discussion, "user", "讨论")
        val complete = repository.appendMessage(story.id, story.currentTimelineId, StoryWorkspace.Discussion, "assistant", "完成")
        assertThrows(IllegalArgumentException::class.java) {
            repository.restartInterruptedRevision(complete.message.id, complete.revision.id, "p", "m")
        }

        repository.appendMessage(story.id, story.currentTimelineId, StoryWorkspace.Prose, "user", "开始")
        val failed = repository.appendMessage(
            story.id, story.currentTimelineId, StoryWorkspace.Prose, "assistant", "失败",
            StoryRevisionState.Interrupted
        )
        repository.appendMessage(story.id, story.currentTimelineId, StoryWorkspace.Prose, "user", "后续")
        assertThrows(IllegalArgumentException::class.java) {
            repository.restartInterruptedRevision(failed.message.id, failed.revision.id, "p", "m")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.restartInterruptedRevision(failed.message.id, "stale", "p", "m")
        }
    }
}
