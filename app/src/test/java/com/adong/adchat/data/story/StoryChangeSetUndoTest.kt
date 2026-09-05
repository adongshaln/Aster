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
class StoryChangeSetUndoTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var archive: StoryArchiveStore
    private lateinit var memory: StoryMemoryStore
    private lateinit var story: Story
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo = StoryRepository(context); archive = StoryArchiveStore(context); memory = StoryMemoryStore(context)
        story = repo.createStory("batch undo", "profile", "model")
    }
    @After fun cleanup() { memory.close(); archive.close(); repo.close() }
    private fun organize(workspace: StoryWorkspace = StoryWorkspace.Prose): StoryMessageWithRevision {
        val source = repo.appendMessage(story.id, story.currentTimelineId, workspace, "assistant", "正文或讨论来源")
        val job = memory.markRunning(memory.enqueueForRevision(story.id, story.currentTimelineId, source.revision.id)!!)!!
        memory.applyOrganizerOutput(job, StoryOrganizerOutput(
            if (workspace == StoryWorkspace.Prose) listOf(StoryOrganizerMemoryCandidate(StoryMemoryKind.PlotEvent, "抵达城门")) else emptyList(),
            listOf(StoryOrganizerProposalCandidate("world", "存在魔法"))))
        return source
    }
    private fun latestBatch() = archive.listChanges(story.id, story.currentTimelineId).first { it.batch && it.canUndo }
    private fun undo(id: String) = archive.undoChangeSet(story.id, story.currentTimelineId, id)

    @Test fun automaticBatchUndoAndRestoreAreAtomicPersistentAndDoNotReorganize() {
        val source = organize(); val original = latestBatch()
        assertTrue(undo(original.id))
        assertTrue(archive.listMemoryRecords(story.id, story.currentTimelineId).isEmpty())
        assertTrue(archive.listPendingProposals(story.id, story.currentTimelineId).isEmpty())
        assertNull(memory.enqueueForRevision(story.id, story.currentTimelineId, source.revision.id))
        val inverse = latestBatch()
        archive.close(); archive = StoryArchiveStore(context)
        assertFalse(undo(original.id))
        assertEquals(2L, repo.getStory(story.id)!!.memoryVersion)
        assertTrue(undo(inverse.id))
        assertEquals(1, archive.listMemoryRecords(story.id, story.currentTimelineId).size)
        assertEquals(1, archive.listPendingProposals(story.id, story.currentTimelineId).size)
        assertEquals(3L, repo.getStory(story.id)!!.memoryVersion)
    }

    @Test fun adoptionUndoRestoresPendingAndRedoReusesSameRecord() {
        organize(StoryWorkspace.Discussion)
        val proposal = archive.listPendingProposals(story.id, story.currentTimelineId).single()
        archive.decideProposal(story.id, story.currentTimelineId, proposal.id, true)
        val record = archive.listMemoryRecords(story.id, story.currentTimelineId).single()
        assertTrue(undo(latestBatch().id))
        assertEquals(proposal.id, archive.listPendingProposals(story.id, story.currentTimelineId).single().id)
        assertTrue(archive.listMemoryRecords(story.id, story.currentTimelineId).isEmpty())
        assertTrue(undo(latestBatch().id))
        assertTrue(archive.listPendingProposals(story.id, story.currentTimelineId).isEmpty())
        assertEquals(record.id, archive.listMemoryRecords(story.id, story.currentTimelineId).single().id)
    }

    @Test fun rejectedCandidateCanReturnToPending() {
        organize(StoryWorkspace.Discussion)
        val proposal = archive.listPendingProposals(story.id, story.currentTimelineId).single()
        archive.decideProposal(story.id, story.currentTimelineId, proposal.id, false)
        assertTrue(undo(latestBatch().id))
        assertEquals(proposal.id, archive.listPendingProposals(story.id, story.currentTimelineId).single().id)
        assertTrue(archive.listMemoryRecords(story.id, story.currentTimelineId).isEmpty())
    }

    @Test fun laterManualChangeBlocksEntireBatchUndo() {
        organize(); val batch = latestBatch()
        archive.setPinned(archive.listMemoryRecords(story.id, story.currentTimelineId).single().id, true)
        val version = repo.getStory(story.id)!!.memoryVersion
        assertThrows(IllegalArgumentException::class.java) { undo(batch.id) }
        assertEquals(version, repo.getStory(story.id)!!.memoryVersion)
        assertTrue(archive.listMemoryRecords(story.id, story.currentTimelineId).single().pinned)
        assertEquals(1, archive.listPendingProposals(story.id, story.currentTimelineId).size)
    }

    @Test fun proposalWriteFailureRollsBackEarlierMemoryUpdateAndVersion() {
        organize(); val batch = latestBatch()
        val helper = StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_batch BEFORE UPDATE ON ${StorySchema.PROPOSALS} BEGIN SELECT RAISE(ABORT, 'failure'); END")
        helper.close()
        assertThrows(Exception::class.java) { undo(batch.id) }
        assertEquals(1L, repo.getStory(story.id)!!.memoryVersion)
        assertEquals(1, archive.listMemoryRecords(story.id, story.currentTimelineId).size)
        assertEquals(1, archive.listPendingProposals(story.id, story.currentTimelineId).size)
        assertEquals(batch.id, latestBatch().id)
    }

    @Test fun inactiveSourceAndOtherTimelineAreRejected() {
        val source = organize(); val batch = latestBatch()
        repo.replaceMessageRevision(source.message.id, "新正文")
        assertThrows(IllegalArgumentException::class.java) { undo(batch.id) }
        assertThrows(IllegalArgumentException::class.java) { archive.undoChangeSet(story.id, "other", batch.id) }
        assertFalse(archive.listChanges(story.id, story.currentTimelineId).first { it.id == batch.id }.canUndo)
    }
}
