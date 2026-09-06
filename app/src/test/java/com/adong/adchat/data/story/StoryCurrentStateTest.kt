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
class StoryCurrentStateTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var archive: StoryArchiveStore
    private lateinit var memory: StoryMemoryStore
    private lateinit var story: Story
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo = StoryRepository(context); archive = StoryArchiveStore(context); memory = StoryMemoryStore(context)
        story = repo.createStory("状态", "profile", "model")
    }
    @After fun cleanup() { memory.close(); archive.close(); repo.close() }
    private fun source(text: String) = repo.appendMessage(story.id, repo.getStory(story.id)!!.currentTimelineId,
        StoryWorkspace.Prose, "assistant", text)
    private fun output(value: String, key: String = "location") = StoryMemoryOrganizer.parse("""{"memories":[
        {"kind":"current_state","subject":"林遥","state_key":"$key","content":"$value"}
    ],"proposals":[]}""")
    private fun organize(row: StoryMessageWithRevision, value: String, key: String = "location") {
        val job = memory.markRunning(memory.enqueueForRevision(story.id, row.message.timelineId, row.revision.id)!!)!!
        assertTrue(memory.applyOrganizerOutput(job, output(value, key)) is StoryMemoryApplyResult.Committed)
    }
    private fun records() = archive.listMemoryRecords(story.id, repo.getStory(story.id)!!.currentTimelineId)
    private fun current() = StoryStateProjection.project(records())

    @Test fun returningToAnEarlierLocationIsNotDeduplicatedAway() {
        organize(source("北门"), "北门")
        organize(source("港口"), "港口")
        val last = source("返回北门"); organize(last, "北门")
        assertEquals(3, records().size)
        assertEquals("北门", current().records.single().content)
        assertEquals(last.revision.id, current().records.single().sourceRevisionId)
        val result = StoryContextComposer.compose(StoryWorkspace.Prose, "继续", records(), emptyList(), emptyList(), emptyList())
        assertFalse(result.systemPrompt.contains("港口"))
        assertEquals(setOf(current().records.single().id), result.includedMemoryIds)
    }

    @Test fun injuryRecoveryUndoRewriteAndRestoreRecomputeTheEffectiveState() {
        organize(source("受伤"), "擦伤", "injury:left_hand")
        val healed = source("伤愈"); organize(healed, "痊愈", "injury:left_hand")
        val batch = archive.listChanges(story.id, story.currentTimelineId).first { it.batch && it.canUndo }
        assertTrue(archive.undoChangeSet(story.id, story.currentTimelineId, batch.id))
        assertEquals("擦伤", current().records.single().content)
        val inverse = archive.listChanges(story.id, story.currentTimelineId).first { it.batch && it.canUndo }
        assertTrue(archive.undoChangeSet(story.id, story.currentTimelineId, inverse.id))
        assertEquals("痊愈", current().records.single().content)
        repo.replaceMessageRevision(healed.message.id, "尚未治疗")
        assertEquals("擦伤", current().records.single().content)
        repo.restoreMessageRevision(healed.message.id, healed.revision.id,
            repo.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Prose).last().revision.id)
        archive.close(); archive = StoryArchiveStore(context)
        assertEquals("痊愈", current().records.single().content)
    }

    @Test fun delayedOlderOrganizerCannotOverrideNewerSourceState() {
        val first = source("北门")
        val oldJob = memory.markRunning(memory.enqueueForRevision(story.id, story.currentTimelineId, first.revision.id)!!)!!
        organize(source("港口"), "港口")
        assertTrue(memory.applyOrganizerOutput(oldJob, output("北门")) is StoryMemoryApplyResult.Requeued)
        val retried = memory.markRunning(memory.nextPendingJob(story.id, story.currentTimelineId)!!)!!
        memory.applyOrganizerOutput(retried, output("北门"))
        assertEquals("港口", current().records.single().content)
    }

    @Test fun fixedConflictBlocksProseButDiscussionAndArchiveStayAvailable() {
        organize(source("北门"), "北门")
        val fixed = records().single(); archive.setPinned(fixed.id, true)
        organize(source("港口"), "港口")
        assertEquals(1, current().conflicts.size)
        assertEquals(fixed.id, current().conflicts.single().earlier.id)
        assertThrows(StoryStateConflictException::class.java) {
            StoryContextComposer.compose(StoryWorkspace.Prose, "继续", records(), emptyList(), emptyList(), emptyList())
        }
        val discussion = StoryContextComposer.compose(StoryWorkspace.Discussion, "讨论", records(), emptyList(), emptyList(), emptyList())
        assertTrue(discussion.systemPrompt.contains("以下状态尚有冲突"))
        archive.setPinned(fixed.id, false)
        assertTrue(current().conflicts.isEmpty())
        assertEquals("港口", current().records.single().content)
        val log = archive.listManualChanges(story.id, story.currentTimelineId).first()
        assertTrue(archive.undoManualChange(story.id, story.currentTimelineId, log.id))
        assertEquals(1, current().conflicts.size)
    }

    @Test fun forkRetainsStateKeyAndHistoricalValueWithoutAffectingOriginalRoute() {
        organize(source("北门"), "北门")
        val target = source("港口"); organize(target, "港口")
        val branch = repo.forkProseRevision(target.message.id, target.revision.id, "留在北门")
        assertEquals("location", current().records.single().stateKey)
        assertEquals("北门", current().records.single().content)
        repo.switchTimeline(story.id, story.currentTimelineId, branch)
        assertEquals("港口", current().records.single().content)
    }

    @Test fun malformedOrAmbiguousStateCannotPartiallyWrite() {
        listOf(
            """{"kind":"current_state","content":"北门"}""",
            """{"kind":"current_state","subject":"林遥","state_key":"位置的长描述","content":"北门"}""",
            """{"kind":"plot_event","state_key":"location","content":"北门"}"""
        ).forEach { item -> assertThrows(IllegalArgumentException::class.java) {
            StoryMemoryOrganizer.parse("""{"memories":[$item],"proposals":[]}""")
        } }
        val row = source("正文")
        val job = memory.markRunning(memory.enqueueForRevision(story.id, story.currentTimelineId, row.revision.id)!!)!!
        assertThrows(IllegalArgumentException::class.java) {
            memory.applyOrganizerOutput(job, StoryOrganizerOutput(output("北门").memories + output("港口").memories, emptyList()))
        }
        assertTrue(records().isEmpty()); assertEquals(0L, repo.getStory(story.id)!!.memoryVersion)
    }
}
