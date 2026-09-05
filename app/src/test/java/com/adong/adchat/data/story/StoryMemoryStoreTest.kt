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
class StoryMemoryStoreTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var memory: StoryMemoryStore
    private lateinit var archive: StoryArchiveStore
    private lateinit var story: Story

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo = StoryRepository(context)
        memory = StoryMemoryStore(context)
        archive = StoryArchiveStore(context)
        story = repo.createStory("test", "profile", "model")
    }
    @After fun cleanup() { archive.close(); memory.close(); repo.close() }

    private fun source(workspace: StoryWorkspace = StoryWorkspace.Prose) = repo.appendMessage(
        story.id, story.currentTimelineId, workspace, "assistant", "人物抵达城门", StoryRevisionState.Complete)
    private fun running(source: StoryMessageWithRevision): StoryMemoryJob = memory.markRunning(
        memory.enqueueForRevision(story.id, story.currentTimelineId, source.revision.id)!!)!!
    private fun facts() = StoryOrganizerOutput(listOf(StoryOrganizerMemoryCandidate(StoryMemoryKind.PlotEvent, "抵达城门")), emptyList())
    private fun count(table: String): Int {
        val helper = StoryDatabase(context)
        val cursor = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null)
        val value = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        helper.close()
        return value
    }

    @Test fun missingConfigurationPreservesPendingJobUntilRouteIsRestored() {
        val job = memory.enqueueForRevision(story.id, story.currentTimelineId, source().revision.id)!!
        repeat(8) {
            assertNull(memory.markRunning(job, configurationAvailable = false))
            val pending = memory.nextPendingJob(story.id, story.currentTimelineId)!!
            assertEquals(job.id, pending.id)
            assertEquals(0, pending.attempts)
            assertEquals(StoryJobState.Pending, pending.state)
        }
        memory.close()
        memory = StoryMemoryStore(context)
        val restored = memory.nextPendingJob(story.id, story.currentTimelineId)!!
        val running = memory.markRunning(restored, configurationAvailable = true)!!
        assertEquals(1, running.attempts)
        assertTrue(memory.applyOrganizerOutput(running, facts()) is StoryMemoryApplyResult.Committed)
    }

    @Test fun commitIsIdempotentAcrossLaterManualVersions() {
        val source = source(); val job = running(source)
        assertTrue(memory.applyOrganizerOutput(job, facts()) is StoryMemoryApplyResult.Committed)
        assertEquals(1, count(StorySchema.MEMORIES))
        assertEquals(1L, repo.getStory(story.id)!!.memoryVersion)
        assertEquals(StoryMemoryApplyResult.StaleSource, memory.applyOrganizerOutput(job, facts()))
        archive.addConfirmedRecord(story.id, story.currentTimelineId, StoryMemoryKind.WorldFact, "固定规则")
        assertNull(memory.enqueueForRevision(story.id, story.currentTimelineId, source.revision.id))
        assertEquals(1, count(StorySchema.CHANGE_SETS))
    }

    @Test fun replacedSourceCannotCommitAfterAtomicVersionChange() {
        val source = source(); val job = running(source)
        repo.replaceMessageRevision(source.message.id, "新的正文")
        assertEquals(StoryMemoryApplyResult.StaleSource, memory.applyOrganizerOutput(job, facts()))
        assertEquals(0, count(StorySchema.MEMORIES))
        assertEquals(1L, repo.getStory(story.id)!!.memoryVersion)
    }

    @Test fun manualEditDuringRequestRequeuesWithoutWriting() {
        val job = running(source())
        archive.addConfirmedRecord(story.id, story.currentTimelineId, StoryMemoryKind.WorldFact, "不能复活")
        assertTrue(memory.applyOrganizerOutput(job, facts()) is StoryMemoryApplyResult.Requeued)
        assertEquals(1, count(StorySchema.MEMORIES))
        assertEquals(1L, memory.nextPendingJob(story.id, story.currentTimelineId)!!.baseMemoryVersion)
    }

    @Test fun sqliteFailureRollsBackRecordVersionAndChangeSet() {
        val job = running(source())
        val triggerHelper = StoryDatabase(context)
        triggerHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_changes BEFORE INSERT ON ${StorySchema.CHANGE_SETS} BEGIN SELECT RAISE(ABORT, 'test failure'); END"
        )
        triggerHelper.close()
        var failed = false
        try { memory.applyOrganizerOutput(job, facts()) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(0, count(StorySchema.MEMORIES))
        assertEquals(0L, repo.getStory(story.id)!!.memoryVersion)
        assertEquals(1, memory.recoverRunningJobs())
    }

    @Test fun discussionNeedsExplicitDecisionAndDoubleClickDoesNotDuplicate() {
        val job = running(source(StoryWorkspace.Discussion))
        val output = StoryOrganizerOutput(emptyList(), listOf(StoryOrganizerProposalCandidate("world", "存在魔法")))
        memory.applyOrganizerOutput(job, output)
        assertEquals(0, count(StorySchema.MEMORIES))
        val proposal = archive.listPendingProposals(story.id, story.currentTimelineId).single()
        assertTrue(archive.decideProposal(story.id, story.currentTimelineId, proposal.id, true))
        assertFalse(archive.decideProposal(story.id, story.currentTimelineId, proposal.id, true))
        assertEquals(1, count(StorySchema.MEMORIES))
        assertEquals(1, count(StorySchema.MANUAL_MEMORY_CHANGES))
        assertEquals(2L, repo.getStory(story.id)!!.memoryVersion)
    }

    @Test fun discussionCannotBypassParserAtStoreBoundary() {
        val job = running(source(StoryWorkspace.Discussion))
        var rejected = false
        try { memory.applyOrganizerOutput(job, facts()) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, count(StorySchema.MEMORIES))
    }

    @Test fun staleRetriesAreBoundedAcrossVersions() {
        val source = source()
        var job = running(source)
        repeat(4) { index ->
            archive.addConfirmedRecord(story.id, story.currentTimelineId, StoryMemoryKind.WorldFact, "规则$index")
            memory.applyOrganizerOutput(job, facts())
            val pending = memory.nextPendingJob(story.id, story.currentTimelineId)
            if (index < 3) job = memory.markRunning(pending!!)!! else assertNull(pending)
        }
        assertNull(memory.enqueueForRevision(story.id, story.currentTimelineId, source.revision.id))
    }

    @Test fun switchingRevisionsRestoresSourceMemoryWithoutResurrectingManualDeactivation() {
        val original = source()
        memory.applyOrganizerOutput(running(original), facts())
        val oldRecord = archive.listMemoryRecords(story.id, story.currentTimelineId).single()
        archive.setPinned(oldRecord.id, true)
        val revised = repo.replaceMessageRevision(original.message.id, "人物仍在家中",
            expectedRevisionId = original.revision.id)!!
        assertEquals(StoryRevisionState.Complete, repo.listRevisions(original.message.id).first().state)
        assertTrue(archive.listMemoryRecords(story.id, story.currentTimelineId).isEmpty())
        // An obsolete source must not suppress identical extraction for a new source.
        memory.applyOrganizerOutput(running(revised), facts())
        val newRecord = archive.listMemoryRecords(story.id, story.currentTimelineId).single()
        assertNotEquals(oldRecord.id, newRecord.id)
        assertTrue(repo.restoreMessageRevision(original.message.id, original.revision.id, revised.revision.id))
        val restored = archive.listMemoryRecords(story.id, story.currentTimelineId).single()
        assertEquals(oldRecord.id, restored.id)
        assertTrue(restored.pinned)
        assertNull(memory.enqueueForRevision(story.id, story.currentTimelineId, original.revision.id))
        archive.deactivateRecord(oldRecord.id)
        repo.restoreMessageRevision(original.message.id, revised.revision.id, original.revision.id)
        repo.restoreMessageRevision(original.message.id, original.revision.id, revised.revision.id)
        assertTrue(archive.listMemoryRecords(story.id, story.currentTimelineId).isEmpty())
    }

    @Test fun revisionChangeRollsBackPointerVersionAndNewRevisionOnAuditFailure() {
        val original = source()
        val helper = StoryDatabase(context)
        helper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_revision_audit BEFORE INSERT ON ${StorySchema.CHANGE_SETS} BEGIN SELECT RAISE(ABORT, 'failure'); END")
        helper.close()
        assertThrows(Exception::class.java) { repo.replaceMessageRevision(original.message.id, "修订") }
        assertTrue(repo.isRevisionActive(original.revision.id))
        assertEquals(1, repo.listRevisions(original.message.id).size)
        assertEquals(0L, repo.getStory(story.id)!!.memoryVersion)
    }

    @Test fun staleEditorAndEarlierMessageCannotChangeStory() {
        val original = source()
        val revised = repo.replaceMessageRevision(original.message.id, "修订")!!
        assertThrows(IllegalArgumentException::class.java) {
            repo.replaceMessageRevision(original.message.id, "过期编辑", expectedRevisionId = original.revision.id)
        }
        repo.appendMessage(story.id, story.currentTimelineId, StoryWorkspace.Discussion, "user", "讨论后续")
        assertThrows(IllegalArgumentException::class.java) {
            repo.restoreMessageRevision(original.message.id, original.revision.id, revised.revision.id)
        }
        assertTrue(repo.isRevisionActive(revised.revision.id))
    }

    @Test fun finalizedRevisionCannotBeMutatedOrDeletedAndStoppedRevisionCannotBeRestoredAsComplete() {
        val original = source()
        assertFalse(repo.updateActiveRevision(original.revision.id, "覆盖", StoryRevisionState.Complete))
        assertFalse(repo.deleteMessage(original.message.id))
        val other = repo.createStory("stopped", "profile", "model")
        val stopped = repo.appendMessage(other.id, other.currentTimelineId, StoryWorkspace.Prose,
            "assistant", "未完成", StoryRevisionState.Stopped)
        val revised = repo.replaceMessageRevision(stopped.message.id, "用户补写完成")!!
        assertThrows(IllegalArgumentException::class.java) {
            repo.restoreMessageRevision(stopped.message.id, stopped.revision.id, revised.revision.id)
        }
        assertEquals(StoryRevisionState.Stopped, repo.listRevisions(stopped.message.id).first().state)
    }

    @Test fun recoveryFindsCompletedReplyWithoutJobAndResumesRunning() {
        source()
        memory.enqueueMissingSources(story.id, story.currentTimelineId)
        val job = memory.nextPendingJob(story.id, story.currentTimelineId)!!
        memory.markRunning(job)
        memory.close(); memory = StoryMemoryStore(context)
        assertEquals(1, memory.recoverRunningJobs())
        memory.enqueueMissingSources(story.id, story.currentTimelineId)
        assertEquals(1, count(StorySchema.JOBS))
        assertEquals(job.id, memory.nextPendingJob(story.id, story.currentTimelineId)!!.id)
    }
}
