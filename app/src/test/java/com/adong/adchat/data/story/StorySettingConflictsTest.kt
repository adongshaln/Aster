package com.adong.adchat.data.story

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StorySettingConflictsTest {
    @Test fun semanticFlagIsBoundToExactLocalRecordAndRequiresExplicitChoice() {
        val context=RuntimeEnvironment.getApplication();context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        val repo=StoryRepository(context);val archive=StoryArchiveStore(context);val memory=StoryMemoryStore(context)
        try {
            val story=repo.createStory("设定冲突","p","m");val route=story.currentTimelineId
            val rule=archive.addConfirmedRecord(story.id,route,StoryMemoryKind.WorldFact,"王国禁止魔法",true)
            val prose=repo.appendMessage(story.id,route,StoryWorkspace.Prose,"assistant","王国向所有公民开放魔法。")
            val job=memory.markRunning(memory.enqueueForRevision(story.id,route,prose.revision.id)!!)!!
            val output=StoryMemoryOrganizer.parse("""{"memories":[{"kind":"world_fact","content":"王国允许魔法","contradicts":"王国禁止魔法"}],"proposals":[]}""")
            memory.applyOrganizerOutput(job,output)
            val entry=archive.listStateConflicts(story.id,route).single()
            assertEquals(rule.id,entry.conflict.earlier.id)
            assertEquals(prose.revision.id,entry.conflict.latest.sourceRevisionId)
            assertTrue(entry.conflict.description.contains("设定疑似矛盾"))
            val snapshot=archive.contextMemorySnapshot(story.id,route)
            assertThrows(StoryStateConflictException::class.java) {
                StoryContextComposer.compose(StoryWorkspace.Prose,"规则",snapshot.records,emptyList(),emptyList(),emptyList())
            }
            assertTrue(archive.resolveStateConflict(story.id,route,entry.id,entry.memoryVersion,true))
            assertTrue(archive.listStateConflicts(story.id,route).isEmpty())
            assertTrue(archive.listMemoryRecords(story.id,route).single().pinned)
            val undo=archive.listChanges(story.id,route).first { it.batch && it.canUndo }
            archive.undoChangeSet(story.id,route,undo.id)
            assertEquals(1,archive.listStateConflicts(story.id,route).size)
            val target=repo.appendMessage(story.id,route,StoryWorkspace.Prose,"assistant","下一章")
            val branch=repo.forkProseRevision(target.message.id,target.revision.id,"新路线")
            assertEquals(1,archive.listStateConflicts(story.id,branch).size)
            assertNotEquals(rule.id,archive.listStateConflicts(story.id,branch).single().conflict.earlier.id)
        } finally { memory.close();archive.close();repo.close() }
    }

    @Test fun missingAmbiguousAndSubjectiveConflictTargetsCannotWriteFacts() {
        val context=RuntimeEnvironment.getApplication();context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        val repo=StoryRepository(context);val archive=StoryArchiveStore(context);val memory=StoryMemoryStore(context)
        try {
            val story=repo.createStory("校验","p","m");val route=story.currentTimelineId
            val prose=repo.appendMessage(story.id,route,StoryWorkspace.Prose,"assistant","新说法")
            val job=memory.markRunning(memory.enqueueForRevision(story.id,route,prose.revision.id)!!)!!
            val candidate=StoryOrganizerMemoryCandidate(StoryMemoryKind.WorldFact,"新说法",contradicts="不存在")
            assertThrows(IllegalArgumentException::class.java) { memory.applyOrganizerOutput(job,StoryOrganizerOutput(listOf(candidate),emptyList())) }
            assertTrue(archive.listMemoryRecords(story.id,route).isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                candidate.copy(kind=StoryMemoryKind.CharacterKnowledge,nature=StoryMemoryNature.CharacterBelief,subject="守卫").validate()
            }
            repeat(2) { archive.addConfirmedRecord(story.id,route,StoryMemoryKind.WorldFact,"不存在") }
            // New version creates a fresh organizer job; duplicate text is never treated as an ID.
            memory.applyOrganizerOutput(job,StoryOrganizerOutput(emptyList(),emptyList()))
            val retry=memory.markRunning(memory.nextPendingJob(story.id,route)!!)!!
            assertThrows(IllegalArgumentException::class.java) { memory.applyOrganizerOutput(retry,StoryOrganizerOutput(listOf(candidate),emptyList())) }
            assertEquals(2,archive.listMemoryRecords(story.id,route).size)
        } finally { memory.close();archive.close();repo.close() }
    }
}
