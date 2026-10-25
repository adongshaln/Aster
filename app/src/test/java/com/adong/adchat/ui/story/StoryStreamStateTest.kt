package com.adong.adchat.ui.story

import com.adong.adchat.data.story.StoryMessage
import com.adong.adchat.data.story.StoryMessageRevision
import com.adong.adchat.data.story.StoryMessageWithRevision
import com.adong.adchat.data.story.StoryRevisionState
import com.adong.adchat.data.story.StoryWorkspace
import org.junit.Assert.*
import org.junit.Test

class StoryStreamStateTest {
    @Test fun streamingChangesOnlyTheActiveRevisionAndKeepsHistoryObjects() {
        val first = row("old", StoryRevisionState.Complete, "历史正文")
        val current = row("current", StoryRevisionState.Streaming, "开头")
        val rows = listOf(first, current)

        val updated = updateStreamingStoryRevision(rows, "current", "开头与新内容")

        assertSame(first, updated.first())
        assertSame(current.message, updated.last().message)
        assertEquals("开头与新内容", updated.last().revision.content)
        assertEquals("开头", rows.last().revision.content)
        assertEquals(StoryRevisionState.Streaming, updated.last().revision.state)
    }

    @Test fun lateChunksCannotOverwriteCompletedOrSwitchedRevisions() {
        val completed = listOf(row("current", StoryRevisionState.Complete, "完整内容"))
        assertSame(completed, updateStreamingStoryRevision(completed, "current", "晚到的片段"))
        val switched = listOf(row("replacement", StoryRevisionState.Streaming, "另一个版本"))
        assertSame(switched, updateStreamingStoryRevision(switched, "current", "旧版本片段"))
    }

    @Test fun repeatedSnapshotDoesNotInvalidateTheList() {
        val rows = listOf(row("current", StoryRevisionState.Streaming, "已有内容"))
        assertSame(rows, updateStreamingStoryRevision(rows, "current", "已有内容"))
    }

    private fun row(id: String, state: StoryRevisionState, content: String) = StoryMessageWithRevision(
        message = StoryMessage("message-$id", "story", "timeline", StoryWorkspace.Prose, "assistant", 1, id, 1),
        revision = StoryMessageRevision(id, "message-$id", "story", "timeline", StoryWorkspace.Prose, content, state)
    )
}
