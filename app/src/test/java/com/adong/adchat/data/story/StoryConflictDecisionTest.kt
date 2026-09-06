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
class StoryConflictDecisionTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var archive: StoryArchiveStore
    private lateinit var memory: StoryMemoryStore
    private lateinit var story: Story
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo = StoryRepository(context); archive = StoryArchiveStore(context); memory = StoryMemoryStore(context)
        story = repo.createStory("冲突决定", "profile", "model")
    }
    @After fun cleanup() { memory.close(); archive.close(); repo.close() }
    private fun source(text: String) = repo.appendMessage(story.id, repo.getStory(story.id)!!.currentTimelineId,
        StoryWorkspace.Prose, "assistant", text)
    private fun organize(value: String): StoryMessageWithRevision {
        val row = source(value)
        val job = memory.markRunning(memory.enqueueForRevision(story.id, row.message.timelineId, row.revision.id)!!)!!
        memory.applyOrganizerOutput(job, StoryOrganizerOutput(listOf(StoryOrganizerMemoryCandidate(
            StoryMemoryKind.CurrentState, value, subject = "林遥", stateKey = "location")), emptyList()))
        return row
    }
    private fun records() = archive.listMemoryRecords(story.id, repo.getStory(story.id)!!.currentTimelineId)
    private fun conflict(): StoryConflictEntry {
        organize("北门"); archive.setPinned(records().single().id, true); organize("港口")
        return archive.listStateConflicts(story.id, story.currentTimelineId).single()
    }
    private fun decide(entry: StoryConflictEntry, accept: Boolean) = archive.resolveStateConflict(
        story.id, story.currentTimelineId, entry.id, entry.memoryVersion, accept)
    private fun latestBatch() = archive.listChanges(story.id, story.currentTimelineId).first { it.batch && it.canUndo }
    private fun undoLatest() = archive.undoChangeSet(story.id, story.currentTimelineId, latestBatch().id)
    private fun state(id: String): String {
        val helper = StoryDatabase(context)
        try { return helper.readableDatabase.rawQuery("SELECT state FROM state_conflicts WHERE id = ?", arrayOf(id)).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        } } finally { helper.close() }
    }

    @Test fun acceptingTransfersFixedConstraintAndWholeUndoRedoSurviveRestart() {
        val entry = conflict()
        assertEquals("pending", state(entry.id))
        assertTrue(decide(entry, true))
        assertEquals("accepted", state(entry.id))
        assertTrue(archive.listStateConflicts(story.id, story.currentTimelineId).isEmpty())
        assertEquals("港口", records().single().content); assertTrue(records().single().pinned)
        assertEquals(entry.memoryVersion + 1, repo.getStory(story.id)!!.memoryVersion)
        assertFalse(decide(entry, true)); assertEquals(entry.memoryVersion + 1, repo.getStory(story.id)!!.memoryVersion)
        archive.close(); archive = StoryArchiveStore(context)
        assertTrue(undoLatest())
        val restored = archive.listStateConflicts(story.id, story.currentTimelineId).single()
        assertEquals(entry.id, restored.id)
        assertTrue(restored.conflict.earlier.pinned); assertFalse(restored.conflict.latest.pinned)
        assertTrue(undoLatest())
        assertEquals("accepted", state(entry.id))
        assertEquals("港口", records().single().content); assertTrue(records().single().pinned)
    }

    @Test fun rejectingKeepsOriginalAndCanBeUndoneAsOneDecision() {
        val entry = conflict(); assertTrue(decide(entry, false))
        assertEquals("rejected", state(entry.id)); assertEquals("北门", records().single().content)
        assertTrue(undoLatest()); assertEquals("pending", state(entry.id))
        assertEquals(2, records().size)
    }

    @Test fun laterMutationAndChangedSourceCannotBeOverwrittenByStaleButtons() {
        val entry = conflict()
        archive.addConfirmedRecord(story.id, story.currentTimelineId, StoryMemoryKind.WorldFact, "独立新设定")
        assertThrows(IllegalArgumentException::class.java) { decide(entry, true) }
        assertEquals("pending", state(entry.id))
        val last = repo.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Prose).last()
        repo.replaceMessageRevision(last.message.id, "未抵达港口")
        assertEquals("superseded", state(entry.id)); assertFalse(decide(entry, true))
        repo.restoreMessageRevision(last.message.id, last.revision.id,
            repo.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Prose).last().revision.id)
        assertEquals(entry.id, archive.listStateConflicts(story.id, story.currentTimelineId).single().id)
    }

    @Test fun conflictWriteFailureRollsBackBothMemoryChangesAndVersion() {
        val entry = conflict()
        val helper = StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_conflict BEFORE UPDATE ON state_conflicts BEGIN SELECT RAISE(ABORT, 'failure'); END")
        helper.close()
        assertThrows(Exception::class.java) { decide(entry, true) }
        assertEquals(entry.memoryVersion, repo.getStory(story.id)!!.memoryVersion)
        assertEquals(2, records().size); assertTrue(records().first { it.content == "北门" }.pinned)
        assertFalse(records().first { it.content == "港口" }.pinned); assertEquals("pending", state(entry.id))
    }

    @Test fun decisionAuditFailureAndUndoFailureAreAtomic() {
        val entry = conflict()
        val helper = StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_decision BEFORE INSERT ON memory_change_sets BEGIN SELECT RAISE(ABORT, 'failure'); END")
        assertThrows(Exception::class.java) { decide(entry, true) }
        assertEquals("pending", state(entry.id)); assertEquals(entry.memoryVersion, repo.getStory(story.id)!!.memoryVersion)
        helper.writableDatabase.execSQL("DROP TRIGGER fail_decision")
        assertTrue(decide(entry, true))
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_undo BEFORE UPDATE ON state_conflicts BEGIN SELECT RAISE(ABORT, 'failure'); END")
        assertThrows(Exception::class.java) { undoLatest() }
        assertEquals("accepted", state(entry.id)); assertEquals("港口", records().single().content)
        assertTrue(records().single().pinned); assertEquals(entry.memoryVersion + 1, repo.getStory(story.id)!!.memoryVersion)
        helper.close()
    }

    @Test fun historicalForkHasIndependentConflictsAndLeavesOriginalDecisionIntact() {
        val entry = conflict()
        val target = source("下一章") // checkpoint before the decision
        assertTrue(decide(entry, true))
        val branch = repo.forkProseRevision(target.message.id, target.revision.id, "另写")
        val forkConflict = archive.listStateConflicts(story.id, branch).single()
        assertNotEquals(entry.id, forkConflict.id)
        assertNotEquals(entry.conflict.earlier.id, forkConflict.conflict.earlier.id)
        assertThrows(IllegalArgumentException::class.java) { decide(entry, false) }
        assertTrue(archive.resolveStateConflict(story.id, branch, forkConflict.id, forkConflict.memoryVersion, false))
        assertEquals("北门", records().single().content)
        repo.switchTimeline(story.id, story.currentTimelineId, branch)
        assertEquals("港口", records().single().content); assertEquals("accepted", state(entry.id))
    }

    @Test fun registeringConflictFailureRollsBackTheNewStateAndMemoryVersion() {
        organize("北门"); archive.setPinned(records().single().id, true)
        val version = repo.getStory(story.id)!!.memoryVersion
        val helper = StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_registration BEFORE INSERT ON state_conflicts BEGIN SELECT RAISE(ABORT, 'failure'); END")
        assertThrows(Exception::class.java) { organize("港口") }
        assertEquals(version, repo.getStory(story.id)!!.memoryVersion)
        assertEquals("北门", records().single().content)
        helper.close()
    }

    @Test fun pendingOrganizerBecomesStaleAfterDecisionAndLaterEditBlocksUndo() {
        val entry = conflict()
        val row = source("下一轮")
        val job = memory.markRunning(memory.enqueueForRevision(story.id, story.currentTimelineId, row.revision.id)!!)!!
        assertTrue(decide(entry, true))
        val decision = latestBatch()
        assertTrue(memory.applyOrganizerOutput(job, StoryOrganizerOutput(emptyList(), emptyList())) is StoryMemoryApplyResult.Requeued)
        archive.updateConfirmedRecord(records().single().id, "东港", true)
        assertThrows(IllegalArgumentException::class.java) {
            archive.undoChangeSet(story.id, story.currentTimelineId, decision.id)
        }
        assertEquals("东港", records().single().content)
    }
}
