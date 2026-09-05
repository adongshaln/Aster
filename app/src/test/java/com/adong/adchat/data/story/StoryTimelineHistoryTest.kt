package com.adong.adchat.data.story

import android.content.Context
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StoryTimelineHistoryTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var archive: StoryArchiveStore
    private lateinit var memory: StoryMemoryStore
    private lateinit var story: Story
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo = StoryRepository(context); archive = StoryArchiveStore(context); memory = StoryMemoryStore(context)
        story = repo.createStory("branch test", "profile", "model")
    }
    @After fun cleanup() { memory.close(); archive.close(); repo.close() }
    private fun prose(text: String) = repo.appendMessage(story.id, repo.getStory(story.id)!!.currentTimelineId,
        StoryWorkspace.Prose, "assistant", text)
    private fun fact(source: StoryMessageWithRevision, text: String): StoryMemoryJob {
        val job = memory.markRunning(memory.enqueueForRevision(story.id, source.message.timelineId, source.revision.id)!!)!!
        memory.applyOrganizerOutput(job, StoryOrganizerOutput(listOf(StoryOrganizerMemoryCandidate(StoryMemoryKind.PlotEvent, text)), emptyList()))
        return job
    }

    @Test fun earlierRewriteUsesHistoricalMemoryAndPreservesOriginalFuture() {
        val setting = archive.addConfirmedRecord(story.id, story.currentTimelineId, StoryMemoryKind.WorldFact, "门仍关闭", pinned = true)
        val first = prose("抵达城门")
        fact(first, "抵达城门")
        val second = prose("进入城中")
        archive.updateConfirmedRecord(setting.id, "门永远敞开", true)
        archive.addConfirmedRecord(story.id, story.currentTimelineId, StoryMemoryKind.WorldFact, "未来才揭示的秘密")
        val third = prose("旧后续：成为国王")
        val oldJob = memory.markRunning(memory.enqueueForRevision(story.id, story.currentTimelineId, third.revision.id)!!)!!
        val branch = repo.forkProseRevision(second.message.id, second.revision.id, "决定离开城门")
        val messages = repo.loadMessages(story.id, branch, StoryWorkspace.Prose)
        assertEquals(listOf("抵达城门", "决定离开城门"), messages.map { it.revision.content })
        val records = archive.listMemoryRecords(story.id, branch)
        assertEquals(setOf("门仍关闭", "抵达城门"), records.map { it.content }.toSet())
        assertTrue(records.first { it.content == "门仍关闭" }.pinned)
        assertNotEquals(first.revision.id, messages.first().revision.id)
        assertNull(memory.enqueueForRevision(story.id, branch, messages.first().revision.id))
        assertEquals(StoryMemoryApplyResult.StaleSource, memory.applyOrganizerOutput(oldJob,
            StoryOrganizerOutput(listOf(StoryOrganizerMemoryCandidate(StoryMemoryKind.PlotEvent, "成为国王")), emptyList())))
        repo.switchTimeline(story.id, story.currentTimelineId, branch)
        assertEquals(3, repo.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Prose).size)
        assertTrue(archive.listMemoryRecords(story.id, story.currentTimelineId).any { it.content == "未来才揭示的秘密" })
    }

    @Test fun workspaceRestoresAcrossRestartAndRejectsLateOldRouteWrites() {
        val target = prose("原文")
        val original = repo.loadWorkspaceState(story.id, StoryWorkspace.Prose)
        val draft = original.copy(draft = "旧路线草稿", firstVisibleIndex = 2, updatedAt = original.updatedAt + 1)
        assertTrue(repo.saveWorkspaceState(draft))
        val branch = repo.forkProseRevision(target.message.id, target.revision.id, "新文")
        assertEquals("", repo.loadWorkspaceState(story.id, StoryWorkspace.Prose).draft)
        assertFalse(repo.saveWorkspaceState(draft.copy(updatedAt = Long.MAX_VALUE)))
        val newState = repo.loadWorkspaceState(story.id, StoryWorkspace.Prose)
        repo.saveWorkspaceState(newState.copy(draft = "新路线草稿", updatedAt = newState.updatedAt + 1))
        repo.close(); repo = StoryRepository(context)
        repo.switchTimeline(story.id, story.currentTimelineId, branch)
        val restored = repo.loadWorkspaceState(story.id, StoryWorkspace.Prose)
        assertEquals("旧路线草稿", restored.draft)
        assertEquals(2, restored.firstVisibleIndex)
        repo.switchTimeline(story.id, branch, story.currentTimelineId)
        assertEquals("新路线草稿", repo.loadWorkspaceState(story.id, StoryWorkspace.Prose).draft)
    }

    @Test fun snapshotFailureRollsBackForkAndRoute() {
        val target = prose("原文")
        val base = repo.getStory(story.id)!!.memoryVersion
        val helper = StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_snapshot BEFORE INSERT ON ${StorySchema.SNAPSHOTS} BEGIN SELECT RAISE(ABORT, 'fail'); END")
        helper.close()
        assertThrows(Exception::class.java) { repo.forkProseRevision(target.message.id, target.revision.id, "新文") }
        assertEquals(1, repo.listTimelines(story.id).size)
        assertEquals(story.currentTimelineId, repo.getStory(story.id)!!.currentTimelineId)
        assertEquals(base, repo.getStory(story.id)!!.memoryVersion)
    }

    @Test fun missingCheckpointAndStaleRouteAreRejected() {
        val target = prose("原文")
        val helper = StoryDatabase(context)
        helper.writableDatabase.delete(StorySchema.SNAPSHOTS, null, null)
        helper.close()
        assertThrows(IllegalArgumentException::class.java) { repo.forkProseRevision(target.message.id, target.revision.id, "新文") }
        val next = prose("新快照")
        val branch = repo.forkProseRevision(next.message.id, next.revision.id, "新路线")
        assertThrows(IllegalArgumentException::class.java) { proseOnOldRoute() }
        assertThrows(IllegalArgumentException::class.java) { repo.switchTimeline(story.id, story.currentTimelineId, story.currentTimelineId) }
        assertEquals(branch, repo.getStory(story.id)!!.currentTimelineId)
    }
    private fun proseOnOldRoute() = repo.appendMessage(story.id, story.currentTimelineId, StoryWorkspace.Prose, "user", "迟到的旧输入")

    @Test fun processRecoveryUnblocksRoutesWithoutPromotingPartialTextToFact() {
        val target = prose("原文")
        val branch = repo.forkProseRevision(target.message.id, target.revision.id, "新文")
        val partial = repo.appendMessage(story.id, branch, StoryWorkspace.Prose, "assistant", "未完成",
            StoryRevisionState.Streaming)
        repo.close(); repo = StoryRepository(context)
        assertEquals(1, repo.recoverInterruptedGenerations())
        assertEquals(0, repo.recoverInterruptedGenerations())
        assertEquals(StoryRevisionState.Interrupted, repo.getActiveRevision(partial.revision.id)!!.state)
        assertNull(memory.enqueueForRevision(story.id, branch, partial.revision.id))
        assertTrue(repo.switchTimeline(story.id, story.currentTimelineId, branch))
    }

    @Test fun concurrentGenerationPreventsTimelineSwitch() {
        val target = prose("原文")
        val branch = repo.forkProseRevision(target.message.id, target.revision.id, "新文")
        repo.appendMessage(story.id, branch, StoryWorkspace.Discussion, "assistant", "", StoryRevisionState.Streaming)
        assertThrows(IllegalArgumentException::class.java) { repo.switchTimeline(story.id, story.currentTimelineId, branch) }
        assertEquals(branch, repo.getStory(story.id)!!.currentTimelineId)
    }
}
