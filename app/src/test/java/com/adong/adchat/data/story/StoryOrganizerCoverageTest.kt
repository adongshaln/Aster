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
class StoryOrganizerCoverageTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var archive: StoryArchiveStore
    private lateinit var memory: StoryMemoryStore
    private lateinit var story: Story
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo = StoryRepository(context); archive = StoryArchiveStore(context); memory = StoryMemoryStore(context)
        story = repo.createStory("整理覆盖", "profile", "model")
    }
    @After fun cleanup() { memory.close(); archive.close(); repo.close() }
    private fun source(workspace: StoryWorkspace = StoryWorkspace.Prose) = repo.appendMessage(story.id,
        repo.getStory(story.id)!!.currentTimelineId, workspace, "assistant", "完整正文")
    private fun job(row: StoryMessageWithRevision) = memory.enqueueForRevision(story.id, row.message.timelineId, row.revision.id)!!
    private fun facts() = StoryOrganizerOutput(listOf(StoryOrganizerMemoryCandidate(StoryMemoryKind.PlotEvent, "抵达北门")), emptyList())
    private fun snapshot() = archive.contextMemorySnapshot(story.id, repo.getStory(story.id)!!.currentTimelineId)

    @Test fun onlyCommittedCompleteProseCountsAsOrganizedAndMemoryIsReadWithIt() {
        val row = source(); val pending = job(row)
        assertTrue(snapshot().organizedProseRevisionIds.isEmpty())
        val running = memory.markRunning(pending)!!
        assertTrue(snapshot().organizedProseRevisionIds.isEmpty())
        memory.markFailed(running.id, "模拟失败")
        assertTrue(snapshot().organizedProseRevisionIds.isEmpty())
        val next = source(); val nextJob = memory.markRunning(job(next))!!
        memory.applyOrganizerOutput(nextJob, facts())
        val view = snapshot()
        assertEquals(setOf(next.revision.id), view.organizedProseRevisionIds)
        assertEquals(next.revision.id, view.records.single().sourceRevisionId)
        val discussion = source(StoryWorkspace.Discussion)
        memory.applyOrganizerOutput(memory.markRunning(job(discussion))!!, StoryOrganizerOutput(emptyList(), emptyList()))
        assertEquals(setOf(next.revision.id), snapshot().organizedProseRevisionIds)
    }

    @Test fun sourceRewriteInvalidatesCoverageAndRestoringItRestoresCoverage() {
        val row = source(); memory.applyOrganizerOutput(memory.markRunning(job(row))!!, facts())
        repo.replaceMessageRevision(row.message.id, "改写后的正文")
        assertTrue(snapshot().organizedProseRevisionIds.isEmpty()); assertTrue(snapshot().records.isEmpty())
        val replacement = repo.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Prose).single()
        repo.restoreMessageRevision(row.message.id, row.revision.id, replacement.revision.id)
        assertEquals(setOf(row.revision.id), snapshot().organizedProseRevisionIds)
    }

    @Test fun failedAtomicCommitNeverAdvertisesCoverageWithoutMemory() {
        val row = source(); val running = memory.markRunning(job(row))!!
        val helper = StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_coverage BEFORE INSERT ON memory_change_sets BEGIN SELECT RAISE(ABORT, 'failure'); END")
        helper.close()
        assertThrows(Exception::class.java) { memory.applyOrganizerOutput(running, facts()) }
        val view = snapshot()
        assertTrue(view.organizedProseRevisionIds.isEmpty()); assertTrue(view.records.isEmpty())
    }

    @Test fun checkpointSurvivesRestartButDoesNotReleaseCoverageBeforeWholeCommit() {
        val text = "甲".repeat(30_000)
        val row = repo.appendMessage(story.id, story.currentTimelineId, StoryWorkspace.Prose, "assistant", text)
        val running = memory.markRunning(job(row))!!
        val chunks = StoryOrganizerChunks.plan(text, "")
        val raw = """{"memories":[{"kind":"current_state","subject":"林遥","state_key":"location","content":"北门"}],"proposals":[]}"""
        assertTrue(memory.saveOrganizerChunk(running, chunks[0], chunks[0].fingerprint(""), raw))
        assertTrue(snapshot().organizedProseRevisionIds.isEmpty()); assertTrue(snapshot().records.isEmpty())
        memory.close(); memory = StoryMemoryStore(context)
        assertEquals(raw, memory.loadOrganizerChunk(running, 0, chunks[0].fingerprint("")))
        assertNull(memory.loadOrganizerChunk(running, 0, "different"))
        val second = raw.replace("北门", "港口")
        val output = StoryOrganizerChunks.combine(chunks, listOf(StoryMemoryOrganizer.parse(raw), StoryMemoryOrganizer.parse(second)))
        assertThrows(IllegalArgumentException::class.java) { memory.applyOrganizerOutput(running, output) }
        assertTrue(snapshot().organizedProseRevisionIds.isEmpty())
        assertTrue(memory.saveOrganizerChunk(running, chunks[1], chunks[1].fingerprint(""), second))
        assertTrue(memory.applyOrganizerOutput(running, output) is StoryMemoryApplyResult.Committed)
        assertEquals(setOf(row.revision.id), snapshot().organizedProseRevisionIds)
        assertEquals("港口", snapshot().records.single().content)
    }

    @Test fun inheritedCoverageUsesNewRouteRevisionIdsAndSurvivesRestart() {
        val first = source(); memory.applyOrganizerOutput(memory.markRunning(job(first))!!, facts())
        val target = source()
        val branch = repo.forkProseRevision(target.message.id, target.revision.id, "新后续")
        val rows = repo.loadMessages(story.id, branch, StoryWorkspace.Prose)
        archive.close(); archive = StoryArchiveStore(context)
        val view = snapshot()
        assertEquals(setOf(rows.first().revision.id), view.organizedProseRevisionIds)
        assertFalse(first.revision.id in view.organizedProseRevisionIds)
        assertEquals(rows.first().revision.id, view.records.single().sourceRevisionId)
    }
}
