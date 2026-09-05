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
class StoryMemoryUndoTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var archive: StoryArchiveStore
    private lateinit var memory: StoryMemoryStore
    private lateinit var story: Story
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo = StoryRepository(context); archive = StoryArchiveStore(context); memory = StoryMemoryStore(context)
        story = repo.createStory("undo", "profile", "model")
    }
    @After fun cleanup() { memory.close(); archive.close(); repo.close() }
    private fun add() = archive.addConfirmedRecord(story.id, story.currentTimelineId, StoryMemoryKind.WorldFact, "原设定")
    private fun latest() = archive.listManualChanges(story.id, story.currentTimelineId).first()
    private fun undo(id: String) = archive.undoManualChange(story.id, story.currentTimelineId, id)

    @Test fun undoAddIsPersistentIdempotentAndInverseCanRestore() {
        val record = add(); val original = latest()
        assertTrue(undo(original.id)); val inverse = latest()
        assertTrue(archive.listMemoryRecords(story.id, story.currentTimelineId).isEmpty())
        assertEquals(2L, repo.getStory(story.id)!!.memoryVersion)
        archive.close(); archive = StoryArchiveStore(context)
        assertFalse(undo(original.id))
        assertEquals(2L, repo.getStory(story.id)!!.memoryVersion)
        assertTrue(undo(inverse.id))
        assertEquals(record.id, archive.listMemoryRecords(story.id, story.currentTimelineId).single().id)
        assertEquals(3, archive.listManualChanges(story.id, story.currentTimelineId).size)
    }

    @Test fun undoUpdatePinAndDeactivateRestoresPreviousValues() {
        val record = add()
        archive.updateConfirmedRecord(record.id, "新设定", true)
        assertTrue(undo(latest().id))
        var restored = archive.listMemoryRecords(story.id, story.currentTimelineId).single()
        assertEquals("原设定", restored.content); assertFalse(restored.pinned)
        archive.setPinned(record.id, true); assertTrue(undo(latest().id))
        assertFalse(archive.listMemoryRecords(story.id, story.currentTimelineId).single().pinned)
        archive.deactivateRecord(record.id); assertTrue(undo(latest().id))
        restored = archive.listMemoryRecords(story.id, story.currentTimelineId).single()
        assertTrue(restored.active)
    }

    @Test fun laterEditAndWrongRouteRejectUndoWithoutChangingVersion() {
        val record = add(); val original = latest()
        archive.updateConfirmedRecord(record.id, "后续改动", false)
        val before = repo.getStory(story.id)!!.memoryVersion
        assertThrows(IllegalArgumentException::class.java) { undo(original.id) }
        assertThrows(IllegalArgumentException::class.java) { archive.undoManualChange(story.id, "wrong", latest().id) }
        assertEquals(before, repo.getStory(story.id)!!.memoryVersion)
        assertEquals("后续改动", archive.listMemoryRecords(story.id, story.currentTimelineId).single().content)
    }

    @Test fun auditFailureRollsBackUndoRecordAndVersion() {
        val record = add(); val original = latest()
        val helper = StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_undo BEFORE INSERT ON ${StorySchema.SNAPSHOTS} BEGIN SELECT RAISE(ABORT, 'failure'); END")
        helper.close()
        assertThrows(Exception::class.java) { undo(original.id) }
        assertEquals(record.id, archive.listMemoryRecords(story.id, story.currentTimelineId).single().id)
        assertEquals(1L, repo.getStory(story.id)!!.memoryVersion)
        assertEquals(1, archive.listManualChanges(story.id, story.currentTimelineId).size)
    }

    @Test fun undoInvalidatesAnInFlightOrganizerSnapshot() {
        add(); val change = latest()
        val source = repo.appendMessage(story.id, story.currentTimelineId, StoryWorkspace.Prose, "assistant", "正式剧情")
        val job = memory.markRunning(memory.enqueueForRevision(story.id, story.currentTimelineId, source.revision.id)!!)!!
        assertTrue(undo(change.id))
        assertTrue(memory.applyOrganizerOutput(job, StoryOrganizerOutput(
            listOf(StoryOrganizerMemoryCandidate(StoryMemoryKind.PlotEvent, "过期资料结果")), emptyList())) is StoryMemoryApplyResult.Requeued)
        assertTrue(archive.listMemoryRecords(story.id, story.currentTimelineId).isEmpty())
    }

    @Test fun proposalAdoptionCannotBePartiallyUndoneAndAuditIncludesSource() {
        val source = repo.appendMessage(story.id, story.currentTimelineId, StoryWorkspace.Discussion, "assistant", "候选世界观")
        val job = memory.markRunning(memory.enqueueForRevision(story.id, story.currentTimelineId, source.revision.id)!!)!!
        memory.applyOrganizerOutput(job, StoryOrganizerOutput(emptyList(), listOf(StoryOrganizerProposalCandidate("world", "存在魔法"))))
        val proposal = archive.listPendingProposals(story.id, story.currentTimelineId).single()
        archive.decideProposal(story.id, story.currentTimelineId, proposal.id, true)
        assertThrows(IllegalArgumentException::class.java) { undo(latest().id) }
        val entries = archive.listChanges(story.id, story.currentTimelineId)
        assertTrue(entries.any { it.title == "采用候选" && it.source == "候选世界观" })
        assertFalse(entries.first { it.id == latest().id }.canUndo)
    }
}
