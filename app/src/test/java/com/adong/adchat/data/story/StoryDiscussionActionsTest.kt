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
@Config(sdk=[28],manifest=Config.NONE)
class StoryDiscussionActionsTest {
    private lateinit var context:Context
    private lateinit var repo:StoryRepository
    private lateinit var archive:StoryArchiveStore
    private lateinit var story:Story
    private lateinit var prose:StoryMessageWithRevision
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo=StoryRepository(context);archive=StoryArchiveStore(context);story=repo.createStory("应用","p","m")
        prose=repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"assistant","她发现了旧钥匙。")
    }
    @After fun close() {archive.close();repo.close()}
    private fun discussion():StoryMessageWithRevision {
        val state=repo.loadWorkspaceState(story.id,StoryWorkspace.Discussion).copy(timelineId=story.currentTimelineId)
        val draft=repo.appendDiscussionQuote(prose.message.id,prose.revision.id,0,prose.revision.content.length,state)
        repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Discussion,"user",draft.draft+"讨论钥匙")
        repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Discussion,"assistant","钥匙可能来自家族")
        repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Discussion,"user","再具体一些")
        return repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Discussion,"assistant","钥匙属于她的家族")
    }
    @Test fun linkedDiscussionDecisionsBecomeReviewItemsWhenProseChangesAndRecoverWithIt() {
        val reply=discussion()
        assertEquals(setOf(prose.revision.id),repo.discussionSources(reply.revision.id))
        val fact=archive.addDiscussionRecord(story.id,story.currentTimelineId,reply.revision.id,"钥匙属于家族",StoryMemoryKind.WorldFact)
        assertTrue(archive.listMemoryRecords(story.id,story.currentTimelineId).any {it.id==fact.id})
        val revised=repo.replaceMessageRevision(prose.message.id,"她没有发现钥匙。",expectedRevisionId=prose.revision.id,allowLaterDiscussion=true)!!
        assertTrue(archive.listMemoryRecords(story.id,story.currentTimelineId).isEmpty())
        assertEquals(fact.id,archive.listReviewRecords(story.id,story.currentTimelineId).single().id)
        repo.restoreMessageRevision(prose.message.id,prose.revision.id,revised.revision.id)
        assertEquals(fact.id,archive.listMemoryRecords(story.id,story.currentTimelineId).single().id)
        assertTrue(archive.listReviewRecords(story.id,story.currentTimelineId).isEmpty())
    }
    @Test fun editedProposalAdoptsOnlyChosenTextAndKeepsQuoteDependency() {
        val reply=discussion();val memory=StoryMemoryStore(context)
        try {
            val job=memory.markRunning(memory.enqueueForRevision(story.id,story.currentTimelineId,reply.revision.id)!!)!!
            memory.applyOrganizerOutput(job,StoryOrganizerOutput(emptyList(),listOf(StoryOrganizerProposalCandidate("world","钥匙由公主赠予"))))
            val proposal=archive.listPendingProposals(story.id,story.currentTimelineId).single()
            assertTrue(archive.decideProposal(story.id,story.currentTimelineId,proposal.id,true,"钥匙由祖母赠予"))
            assertEquals("钥匙由祖母赠予",archive.listMemoryRecords(story.id,story.currentTimelineId).single().content)
            assertFalse(archive.decideProposal(story.id,story.currentTimelineId,proposal.id,true,"别的值"))
        } finally {memory.close()}
    }
    @Test fun explicitConfirmationRequiresExactUniqueCandidate() {
        val proposal=StoryProposal(storyId=story.id,timelineId=story.currentTimelineId,content="钥匙来自家族",proposalKind="world",sourceRevisionId=prose.revision.id)
        assertEquals(proposal,StoryExplicitDecision.match("采用：钥匙来自家族",listOf(proposal)))
        assertNull(StoryExplicitDecision.match("这个不错",listOf(proposal)))
        assertTrue(StoryExplicitDecision.needsClarification("这个不错！",listOf(proposal)))
        assertFalse(StoryExplicitDecision.needsClarification("这个不错",emptyList()))
        assertNull(StoryExplicitDecision.match("采用：钥匙来自家族",listOf(proposal,proposal.copy(id="duplicate"))))
        assertNull(StoryExplicitDecision.match("采用：钥匙",listOf(proposal)))
    }
    @Test fun reviewReconfirmationIsAtomicAndDoesNotDependOnOldProse() {
        val reply=discussion()
        val fact=archive.addDiscussionRecord(story.id,story.currentTimelineId,reply.revision.id,"钥匙来自家族",StoryMemoryKind.WorldFact)
        val revised=repo.replaceMessageRevision(prose.message.id,"没有钥匙",expectedRevisionId=prose.revision.id,allowLaterDiscussion=true)!!
        val helper=StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_review BEFORE INSERT ON memory_records BEGIN SELECT RAISE(ABORT,'fail'); END")
        val version=repo.getStory(story.id)!!.memoryVersion
        assertThrows(Exception::class.java) { archive.reconfirmReviewedRecord(story.id,story.currentTimelineId,fact.id,"家族保管钥匙") }
        assertEquals(version,repo.getStory(story.id)!!.memoryVersion)
        assertEquals(fact.id,archive.listReviewRecords(story.id,story.currentTimelineId).single().id)
        helper.writableDatabase.execSQL("DROP TRIGGER fail_review");helper.close()
        val fresh=archive.reconfirmReviewedRecord(story.id,story.currentTimelineId,fact.id,"家族保管钥匙")
        assertNull(fresh.sourceRevisionId);assertTrue(archive.listReviewRecords(story.id,story.currentTimelineId).isEmpty())
        repo.restoreMessageRevision(prose.message.id,prose.revision.id,revised.revision.id)
        assertEquals(fresh.id,archive.listMemoryRecords(story.id,story.currentTimelineId).single().id)
    }

    @Test fun forkOffersIndependentSettingsAndLinkedDecisionsWithoutChangingOldRoute() {
        val reply=discussion()
        val linked=archive.addDiscussionRecord(story.id,story.currentTimelineId,reply.revision.id,"钥匙属于家族",StoryMemoryKind.WorldFact)
        val independent=archive.addConfirmedRecord(story.id,story.currentTimelineId,StoryMemoryKind.WorldFact,"北方终年积雪",true)
        val route=repo.forkProseRevision(prose.message.id,prose.revision.id,"她没有发现钥匙")
        assertTrue(archive.listMemoryRecords(story.id,route).isEmpty())
        assertEquals(independent.id,archive.listReapplicableSettings(story.id,route).single().id)
        assertEquals(linked.id,archive.listReviewRecords(story.id,route).single().id)
        val copy=archive.reapplySetting(story.id,route,independent.id,independent.content)
        assertTrue(copy.pinned);assertNull(copy.sourceRevisionId)
        assertTrue(archive.listReapplicableSettings(story.id,route).isEmpty())
        assertThrows(IllegalStateException::class.java) {archive.reapplySetting(story.id,route,independent.id,independent.content)}
        archive.reconfirmReviewedRecord(story.id,route,linked.id,"家族仍保管钥匙")
        assertTrue(archive.listReviewRecords(story.id,route).isEmpty())
        assertEquals(setOf(linked.id,independent.id),archive.listMemoryRecords(story.id,story.currentTimelineId).map { it.id }.toSet())
        assertEquals(2,archive.listMemoryRecords(story.id,route).size)
    }

}
