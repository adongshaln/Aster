package com.adong.adchat.data.story

import com.adong.adchat.data.ChatImageAttachment
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class StoryImagesTest {
    @Test fun imageDraftAndMessagePersistButDiscussionImagesNeverEnterProse() {
        val context=RuntimeEnvironment.getApplication();context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        var repo=StoryRepository(context)
        try {
            val story=repo.createStory("图片","p","m")
            val image=ChatImageAttachment(uri="file:///test.jpg",name="设定.jpg")
            val draft=repo.loadWorkspaceState(story.id,StoryWorkspace.Discussion)
            assertTrue(repo.saveWorkspaceState(draft.copy(attachments=listOf(image),updatedAt=draft.updatedAt+1,timelineId=story.currentTimelineId)))
            repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Discussion,"user","看看参考图",attachments=listOf(image))
            repo.close();repo=StoryRepository(context)
            assertEquals(image,repo.loadWorkspaceState(story.id,StoryWorkspace.Discussion).attachments.single())
            assertTrue(repo.loadWorkspaceState(story.id,StoryWorkspace.Prose).attachments.isEmpty())
            val discussion=repo.loadMessages(story.id,story.currentTimelineId,StoryWorkspace.Discussion)
            assertEquals(image,discussion.single().revision.attachments.single())
            val included=StoryContextComposer.compose(StoryWorkspace.Discussion,"讨论",emptyList(),emptyList(),emptyList(),discussion)
            assertEquals(image,included.history.single().attachments.single())
            val excluded=StoryContextComposer.compose(StoryWorkspace.Prose,"正文",emptyList(),emptyList(),emptyList(),discussion)
            assertTrue(excluded.history.isEmpty())
            assertFalse(repo.saveWorkspaceState(draft.copy(updatedAt=draft.updatedAt+1)))
        } finally {repo.close()}
    }
    @Test fun imageReferencesFollowHistoricalForkAndWorkspaceSwitch() {
        val context=RuntimeEnvironment.getApplication();context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        val repo=StoryRepository(context)
        try {
            val story=repo.createStory("分支图片","p","m")
            val image=ChatImageAttachment(uri="file:///test.jpg",name="参考.jpg")
            repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"user","写这个场景",attachments=listOf(image))
            val prose=repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"assistant","正文")
            val draft=repo.loadWorkspaceState(story.id,StoryWorkspace.Prose)
            repo.saveWorkspaceState(draft.copy(attachments=listOf(image),updatedAt=draft.updatedAt+1))
            val fork=repo.forkProseRevision(prose.message.id,prose.revision.id,"新正文")
            assertEquals(image,repo.loadMessages(story.id,fork,StoryWorkspace.Prose).first().revision.attachments.single())
            assertTrue(repo.loadWorkspaceState(story.id,StoryWorkspace.Prose).attachments.isEmpty())
            repo.switchTimeline(story.id,story.currentTimelineId,fork)
            assertEquals(image,repo.loadWorkspaceState(story.id,StoryWorkspace.Prose).attachments.single())
        } finally {repo.close()}
    }
}
