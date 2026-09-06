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
class StoryKnowledgeTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var archive: StoryArchiveStore
    private lateinit var memory: StoryMemoryStore
    private lateinit var story: Story
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo = StoryRepository(context); archive = StoryArchiveStore(context); memory = StoryMemoryStore(context)
        story = repo.createStory("认知边界", "profile", "model")
    }
    @After fun cleanup() { memory.close(); archive.close(); repo.close() }
    private fun source() = repo.appendMessage(story.id, repo.getStory(story.id)!!.currentTimelineId,
        StoryWorkspace.Prose, "assistant", "守卫怀疑林遥偷了钥匙，但钥匙实际在抽屉里。两人互相信任。")
    private fun running(row: StoryMessageWithRevision) = memory.markRunning(
        memory.enqueueForRevision(story.id, row.message.timelineId, row.revision.id)!!)!!
    private fun output() = StoryMemoryOrganizer.parse("""{"memories":[
        {"kind":"character_knowledge","nature":"character_belief","subject":"守卫","content":"怀疑林遥偷了钥匙"},
        {"kind":"character_knowledge","nature":"character_belief","subject":"林遥","content":"怀疑林遥偷了钥匙"},
        {"kind":"directed_relationship","subject":"守卫","object":"林遥","content":"信任"},
        {"kind":"directed_relationship","subject":"林遥","object":"守卫","content":"信任"},
        {"kind":"world_fact","content":"钥匙在抽屉里"}
    ],"proposals":[]}""")
    private fun records() = archive.listMemoryRecords(story.id, repo.getStory(story.id)!!.currentTimelineId)
    private fun count(table: String): Int {
        val helper = StoryDatabase(context)
        try { return helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } }
        finally { helper.close() }
    }

    @Test fun ownersDirectionsAndNatureSurviveRestartUndoAndRestore() {
        val row = source(); memory.applyOrganizerOutput(running(row), output())
        assertEquals(2, count(StorySchema.ENTITIES))
        archive.close(); archive = StoryArchiveStore(context)
        val original = records()
        assertEquals(5, original.size)
        val beliefs = original.filter { it.nature == StoryMemoryNature.CharacterBelief }
        assertEquals(setOf("守卫", "林遥"), beliefs.map { it.subjectEntityNames.single() }.toSet())
        assertEquals(2, beliefs.map { it.subjectEntityId }.toSet().size)
        val relations = original.filter { it.kind == StoryMemoryKind.DirectedRelationship }
        assertEquals(relations[0].subjectEntityId, relations[1].objectEntityId)
        assertEquals(relations[0].objectEntityId, relations[1].subjectEntityId)
        val input = StoryMemoryOrganizer.buildInput(row.revision, original)
        assertTrue(input.contains("认知主体：守卫")); assertTrue(input.contains("不等于事实"))
        val batch = archive.listChanges(story.id, story.currentTimelineId).first { it.batch && it.canUndo }
        assertTrue(archive.undoChangeSet(story.id, story.currentTimelineId, batch.id))
        assertTrue(records().isEmpty())
        val inverse = archive.listChanges(story.id, story.currentTimelineId).first { it.batch && it.canUndo }
        assertTrue(archive.undoChangeSet(story.id, story.currentTimelineId, inverse.id))
        assertEquals(original.map { it.id }.toSet(), records().map { it.id }.toSet())
        assertEquals(beliefs.map { it.subjectEntityId }.toSet(), records().filter {
            it.nature == StoryMemoryNature.CharacterBelief }.map { it.subjectEntityId }.toSet())
        memory.applyOrganizerOutput(running(source()), output())
        assertEquals(5, records().size) // Identity-aware dedupe reuses the existing route's names.
        assertEquals(2, count(StorySchema.ENTITIES))
    }

    @Test fun historicalForkRemapsEntitiesWithoutLinkingBackToOldRoute() {
        memory.applyOrganizerOutput(running(source()), output())
        val original = records()
        val target = source()
        val branch = repo.forkProseRevision(target.message.id, target.revision.id, "新的后续")
        val inherited = records()
        assertEquals(5, inherited.size)
        assertEquals(original.map { it.content }.toSet(), inherited.map { it.content }.toSet())
        assertTrue(original.mapNotNull { it.subjectEntityId }.toSet()
            .intersect(inherited.mapNotNull { it.subjectEntityId }.toSet()).isEmpty())
        memory.applyOrganizerOutput(running(source()), output())
        assertEquals(5, records().size)
        repo.switchTimeline(story.id, story.currentTimelineId, branch)
        assertEquals(original.map { it.id }.toSet(), records().map { it.id }.toSet())
    }

    @Test fun replacingSourceHidesItsKnowledgeAndDoesNotReuseObsoleteIdentity() {
        val row = source(); memory.applyOrganizerOutput(running(row), output())
        val oldIds = records().mapNotNull { it.subjectEntityId }.toSet()
        repo.replaceMessageRevision(row.message.id, "纠正后的正文")
        assertTrue(records().isEmpty())
        val replacement = repo.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Prose).single()
        memory.applyOrganizerOutput(running(replacement), output())
        assertEquals(5, records().size)
        assertTrue(oldIds.intersect(records().mapNotNull { it.subjectEntityId }.toSet()).isEmpty())
    }

    @Test fun failedCommitRollsBackNewEntitiesAlongWithMemoryAndVersion() {
        val job = running(source())
        val helper = StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_knowledge BEFORE INSERT ON ${StorySchema.CHANGE_SETS} BEGIN SELECT RAISE(ABORT, 'failure'); END")
        helper.close()
        assertThrows(Exception::class.java) { memory.applyOrganizerOutput(job, output()) }
        assertEquals(0, count(StorySchema.ENTITIES)); assertTrue(records().isEmpty())
        assertEquals(0L, repo.getStory(story.id)!!.memoryVersion)
    }

    @Test fun knownAliasResolvesButAmbiguousAliasRollsBackWholeBatch() {
        memory.applyOrganizerOutput(running(source()), output())
        val guard = records().first { it.subjectEntityNames == listOf("守卫") }.subjectEntityId!!
        val helper = StoryDatabase(context)
        helper.writableDatabase.execSQL("UPDATE ${StorySchema.ENTITIES} SET aliases_json = ? WHERE id = ?",
            arrayOf("[\"门卫\"]", guard))
        val aliasOutput = StoryOrganizerOutput(listOf(StoryOrganizerMemoryCandidate(
            StoryMemoryKind.CharacterKnowledge, "看见钥匙", StoryMemoryNature.ProseOccurred, "门卫")), emptyList())
        memory.applyOrganizerOutput(running(source()), aliasOutput)
        assertEquals(guard, records().first { it.content == "看见钥匙" }.subjectEntityId)
        helper.writableDatabase.execSQL("UPDATE ${StorySchema.ENTITIES} SET aliases_json = '[\"门卫\"]'")
        helper.close()
        val version = repo.getStory(story.id)!!.memoryVersion
        val countBefore = records().size
        assertThrows(IllegalArgumentException::class.java) {
            memory.applyOrganizerOutput(running(source()), aliasOutput)
        }
        assertEquals(version, repo.getStory(story.id)!!.memoryVersion)
        assertEquals(countBefore, records().size)
        assertEquals(2, count(StorySchema.ENTITIES))
    }

    @Test fun storeRejectsUnownedBeliefEvenWithoutParser() {
        val job = running(source())
        assertThrows(IllegalArgumentException::class.java) {
            memory.applyOrganizerOutput(job, StoryOrganizerOutput(listOf(StoryOrganizerMemoryCandidate(
                StoryMemoryKind.CharacterKnowledge, "怀疑", StoryMemoryNature.CharacterBelief)), emptyList()))
        }
        assertEquals(0, count(StorySchema.ENTITIES)); assertTrue(records().isEmpty())
    }
}
