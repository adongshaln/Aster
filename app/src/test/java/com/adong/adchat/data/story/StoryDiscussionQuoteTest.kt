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
class StoryDiscussionQuoteTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var story: Story
    private lateinit var source: StoryMessageWithRevision
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo=StoryRepository(context);story=repo.createStory("引用","p","m")
        source=repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"assistant","她推开门。\n月亮🌙照进房间。")
    }
    @After fun close() { repo.close() }
    private fun draft(text: String="原有想法",version: Long=10)=StoryWorkspaceState(
        story.id,StoryWorkspace.Discussion,draft=text,updatedAt=story.updatedAt+version,timelineId=story.currentTimelineId)

    @Test fun selectionAppendsToDraftDurablyWithoutSendingOrChangingMemory() {
        val before=draft();assertTrue(repo.saveWorkspaceState(before))
        val version=repo.getStory(story.id)!!.memoryVersion
        val saved=repo.appendDiscussionQuote(source.message.id,source.revision.id,5,0,before)
        assertTrue(saved.draft.startsWith("原有想法\n\n"));assertTrue(saved.draft.contains("> 她推开门。"))
        assertFalse(saved.draft.contains("照进房间"));assertTrue(saved.updatedAt > before.updatedAt)
        assertTrue(repo.loadMessages(story.id,story.currentTimelineId,StoryWorkspace.Discussion).isEmpty())
        assertEquals(source.revision.id,repo.loadMessages(story.id,story.currentTimelineId,StoryWorkspace.Prose).single().revision.id)
        assertEquals(version,repo.getStory(story.id)!!.memoryVersion)
        assertFalse(repo.saveWorkspaceState(before))
        repo.close();repo=StoryRepository(context)
        assertEquals(saved.draft,repo.loadWorkspaceState(story.id,StoryWorkspace.Discussion).draft)
    }

    @Test fun staleDraftSourceAndRouteAreRejectedWithoutOverwrite() {
        val old=draft();assertTrue(repo.saveWorkspaceState(old))
        val newer=draft("刚改的草稿",11);assertTrue(repo.saveWorkspaceState(newer))
        assertThrows(Exception::class.java) { repo.appendDiscussionQuote(source.message.id,source.revision.id,0,5,old) }
        assertEquals(newer.draft,repo.loadWorkspaceState(story.id,StoryWorkspace.Discussion).draft)
        val updated=repo.replaceMessageRevision(source.message.id,"新版正文",expectedRevisionId=source.revision.id)!!
        assertThrows(Exception::class.java) { repo.appendDiscussionQuote(source.message.id,source.revision.id,0,5,newer) }
        repo.forkProseRevision(updated.message.id,updated.revision.id,"新路线")
        assertThrows(Exception::class.java) { repo.appendDiscussionQuote(updated.message.id,updated.revision.id,0,4,newer) }
    }

    @Test fun unsavedNewerDraftIsPreservedButOversizeQuoteIsNeverTruncated() {
        assertTrue(repo.saveWorkspaceState(draft("旧草稿")))
        val current=draft("尚在写入的新草稿",20)
        val saved=repo.appendDiscussionQuote(source.message.id,source.revision.id,0,5,current)
        assertTrue(saved.draft.startsWith(current.draft))
        val huge=saved.copy(draft="字".repeat(40000),updatedAt=saved.updatedAt+1)
        assertThrows(IllegalArgumentException::class.java) { repo.appendDiscussionQuote(source.message.id,source.revision.id,0,5,huge) }
        assertEquals(saved.draft,repo.loadWorkspaceState(story.id,StoryWorkspace.Discussion).draft)
    }

    @Test fun invalidUnicodeRangesAndNonCanonicalSourcesAreRejected() {
        val emoji=source.revision.content.indexOf("🌙")
        assertThrows(IllegalArgumentException::class.java) { StoryDiscussionQuote.append("",source,emoji+1,emoji+2) }
        assertTrue(StoryDiscussionQuote.append("",source,emoji,emoji+2).contains("> 🌙"))
        for(state in listOf(StoryRevisionState.Stopped,StoryRevisionState.Interrupted,StoryRevisionState.Streaming)) {
            assertThrows(IllegalArgumentException::class.java) { StoryDiscussionQuote.append("",source.copy(revision=source.revision.copy(state=state)),0,5) }
        }
        assertThrows(IllegalArgumentException::class.java) { StoryDiscussionQuote.append("",source,0,0) }
    }
}
