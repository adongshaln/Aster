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
class StorySummariesTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var archive: StoryArchiveStore
    private lateinit var memory: StoryMemoryStore
    private lateinit var story: Story
    private val raw = """{"summary":"林遥从北门抵达港口；守卫曾怀疑她，怀疑不是偷窃事实。"}"""
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo=StoryRepository(context);archive=StoryArchiveStore(context);memory=StoryMemoryStore(context)
        story=repo.createStory("摘要","profile","model")
    }
    @After fun cleanup() { memory.close();archive.close();repo.close() }
    private fun seed(count: Int = 8): List<StoryMessageWithRevision> = (1..count).map { index ->
        repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"user","推进第${index}轮")
        val row=repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"assistant","第${index}轮正文"+"甲".repeat(1200))
        val job=memory.markRunning(memory.enqueueForRevision(story.id,story.currentTimelineId,row.revision.id)!!)!!
        memory.applyOrganizerOutput(job,StoryOrganizerOutput(emptyList(),emptyList()))
        row
    }
    private fun running(): StoryMemoryJob {
        memory.enqueueSummary(story.id,story.currentTimelineId)
        return memory.markRunning(memory.nextPendingJob(story.id,story.currentTimelineId)!!)!!
    }
    private fun view() = archive.contextMemorySnapshot(story.id,repo.getStory(story.id)!!.currentTimelineId)

    @Test fun thresholdUsesOlderCompleteProseAndReplacesOnlyCoveredHistory() {
        val rows=seed();val job=running()
        val input=memory.summaryRequest(job)!!
        assertTrue(input.contains("第1轮正文"));assertTrue(input.contains("第6轮正文"));assertFalse(input.contains("第7轮正文"))
        assertTrue(view().summarySources.isEmpty())
        assertTrue(memory.applySummary(job,raw));assertFalse(memory.applySummary(job,raw))
        val snapshot=view();val summary=snapshot.records.single()
        assertEquals(rows.take(6).map { it.revision.id }.toSet(),snapshot.summarySources[summary.id])
        repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"user","继续")
        val result=StoryContextComposer.compose(StoryWorkspace.Prose,"规则",snapshot.records,snapshot.proposals,
            repo.loadMessages(story.id,story.currentTimelineId,StoryWorkspace.Prose),emptyList(),
            budget=StoryContextBudget(recentHistoryChars=0,confirmedMemoryChars=0),
            organizedProseRevisionIds=snapshot.organizedProseRevisionIds,summarySources=snapshot.summarySources)
        assertTrue(summary.id in result.includedMemoryIds)
        assertFalse(result.history.any { it.content.contains("第1轮正文") })
        assertTrue(result.history.any { it.content.contains("第7轮正文") })
    }

    @Test fun invalidatingAnInteriorSourceHidesSummaryAndRestoringItRevivesSummary() {
        val rows=seed();memory.applySummary(running(),raw)
        val helper=StoryDatabase(context)
        helper.writableDatabase.execSQL("UPDATE message_revisions SET state='superseded' WHERE id=?",arrayOf(rows[1].revision.id))
        assertTrue(view().records.isEmpty());assertTrue(view().summarySources.isEmpty())
        helper.writableDatabase.execSQL("UPDATE message_revisions SET state='complete' WHERE id=?",arrayOf(rows[1].revision.id))
        assertEquals(1,view().records.size)
        helper.close()
    }

    @Test fun staleVersionAndInvalidOutputNeverCommitSummary() {
        seed();val job=running()
        listOf("", "{\"summary\":12}", "{\"summary\":\"正文\",\"sources\":[]}", "{\"summary\":\"正文\"} trailing").forEach {
            assertThrows(Exception::class.java) { memory.applySummary(job,it) }
        }
        archive.addConfirmedRecord(story.id,story.currentTimelineId,StoryMemoryKind.WorldFact,"用户新设定")
        assertFalse(memory.applySummary(job,raw));assertTrue(view().summarySources.isEmpty())
        val retry=memory.nextPendingJob(story.id,story.currentTimelineId)!!
        assertEquals(StorySummaries.KIND,retry.kind)
        assertTrue(memory.applySummary(memory.markRunning(retry)!!,raw))
    }

    @Test fun failedTransactionRollsBackDependenciesJobCompletionAndVersion() {
        seed();val job=running();val version=repo.getStory(story.id)!!.memoryVersion
        val helper=StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_summary BEFORE INSERT ON summary_sources BEGIN SELECT RAISE(ABORT, 'failure'); END")
        assertThrows(Exception::class.java) { memory.applySummary(job,raw) }
        assertEquals(version,repo.getStory(story.id)!!.memoryVersion);assertTrue(view().records.isEmpty())
        assertNotNull(memory.summaryRequest(job));helper.close()
    }

    @Test fun undoDoesNotAutomaticallyRegenerateAndForkRemapsAllDependencies() {
        val rows=seed();memory.applySummary(running(),raw)
        val batch=archive.listChanges(story.id,story.currentTimelineId).first { it.batch && it.canUndo }
        archive.undoChangeSet(story.id,story.currentTimelineId,batch.id)
        assertTrue(view().summarySources.isEmpty());memory.enqueueSummary(story.id,story.currentTimelineId)
        assertNull(memory.nextPendingJob(story.id,story.currentTimelineId))
        val inverse=archive.listChanges(story.id,story.currentTimelineId).first { it.batch && it.canUndo }
        archive.undoChangeSet(story.id,story.currentTimelineId,inverse.id)
        val target=repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"assistant","下一章")
        val branch=repo.forkProseRevision(target.message.id,target.revision.id,"另写")
        archive.close();archive=StoryArchiveStore(context)
        val deps=view().summarySources.values.single()
        assertEquals(6,deps.size);assertTrue(deps.intersect(rows.map { it.revision.id }.toSet()).isEmpty())
        val newRows=repo.loadMessages(story.id,branch,StoryWorkspace.Prose).filter { it.message.role=="assistant" }
        assertEquals(newRows.take(6).map { it.revision.id }.toSet(),deps)
    }

    @Test fun summaryRecoveryAndRetryDoNotSpendProseOrganizerAttemptBudget() {
        val rows=seed();val job=running()
        assertEquals(1,memory.recoverRunningJobs())
        val resumed=memory.markRunning(memory.nextPendingJob(story.id,story.currentTimelineId)!!)!!
        memory.markFailed(resumed.id,"模拟失败")
        memory.retryFailed(story.id,story.currentTimelineId)
        val retried=memory.markRunning(memory.nextPendingJob(story.id,story.currentTimelineId)!!)!!
        assertEquals(StorySummaries.KIND,retried.kind);assertTrue(memory.applySummary(retried,raw))
        assertNull(memory.enqueueForRevision(story.id,story.currentTimelineId,rows[5].revision.id))
    }
    private fun fourLeaves(): List<StoryMemoryRecord> {
        seed(26)
        repeat(4) { assertTrue(memory.applySummary(running(),raw)) }
        return view().records
    }

    @Test fun hierarchyReplacesFourLeavesWithoutDeletingThemAndUndoFallsBack() {
        val leaves=fourLeaves();assertEquals(4,leaves.size)
        val job=running();val request=memory.summaryRequest(job)!!
        assertTrue(request.contains("历史摘要 4"));assertFalse(request.contains("第1轮正文"))
        assertTrue(memory.applySummary(job,raw))
        val parent=view().records.single()
        assertEquals(24,parent.summarySourceRevisionIds.size)
        assertEquals(5,archive.listMemoryRecords(story.id,story.currentTimelineId).size)
        val batch=archive.listChanges(story.id,story.currentTimelineId).first { it.batch && it.canUndo }
        archive.undoChangeSet(story.id,story.currentTimelineId,batch.id)
        assertEquals(leaves.map { it.id }.toSet(),view().records.map { it.id }.toSet())
        memory.enqueueSummary(story.id,story.currentTimelineId)
        assertNull(memory.nextPendingJob(story.id,story.currentTimelineId))
    }

    @Test fun changingOrPinningAChildInvalidatesParentAndRestoringRevivesIt() {
        val child=fourLeaves().first();memory.applySummary(running(),raw)
        assertTrue(archive.updateConfirmedRecord(child.id,"用户修正过的历史",false))
        assertEquals(4,view().records.size)
        assertTrue(archive.updateConfirmedRecord(child.id,child.content,false))
        assertEquals(1,view().records.size)
        assertTrue(archive.updateConfirmedRecord(child.id,child.content,true))
        assertEquals(4,view().records.size)
        assertTrue(view().records.any { it.id == child.id && it.pinned })
    }

    @Test fun hierarchyInputsRollbackAndStaleJobCannotOverwriteAnEdit() {
        val child=fourLeaves().first();val job=running();val version=repo.getStory(story.id)!!.memoryVersion
        val helper=StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_hierarchy BEFORE INSERT ON summary_inputs BEGIN SELECT RAISE(ABORT, 'failure'); END")
        assertThrows(Exception::class.java) { memory.applySummary(job,raw) }
        assertEquals(version,repo.getStory(story.id)!!.memoryVersion)
        assertEquals(4,view().records.size)
        helper.writableDatabase.execSQL("DROP TRIGGER fail_hierarchy");helper.close()
        archive.updateConfirmedRecord(child.id,"新历史",false)
        assertFalse(memory.applySummary(job,raw))
        assertEquals(4,view().records.size)
        assertTrue(memory.applySummary(memory.markRunning(memory.nextPendingJob(story.id,story.currentTimelineId)!!)!!,raw))
    }

    @Test fun hierarchyForkRemapsInputsAndStillInvalidatesInNewRoute() {
        fourLeaves();memory.applySummary(running(),raw)
        val target=repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"assistant","下一章")
        val branch=repo.forkProseRevision(target.message.id,target.revision.id,"新路线")
        archive.close();archive=StoryArchiveStore(context)
        assertEquals(1,view().records.size)
        val child=archive.listMemoryRecords(story.id,branch).first { it.scope == "summary:v1" }
        assertTrue(archive.updateConfirmedRecord(child.id,"新路线修正",false))
        assertEquals(4,view().records.size)
        assertEquals(5,archive.listMemoryRecords(story.id,story.currentTimelineId).size)
    }

    @Test fun secondLevelRetainsAllAncestorsAndInvalidatesOnLeafEdit() {
        seed(98)
        repeat(16) { assertTrue(memory.applySummary(running(),raw)) }
        val leaf=view().records.first()
        repeat(4) { assertTrue(memory.applySummary(running(),raw)) }
        assertEquals(4,view().records.size)
        assertTrue(memory.applySummary(running(),raw))
        assertEquals(96,view().records.single().summarySourceRevisionIds.size)
        archive.updateConfirmedRecord(leaf.id,"最底层修正",false)
        // One first-level parent and the second-level parent are invalid; the other three remain usable.
        assertEquals(7,view().records.size)
    }

}
