package com.adong.adchat.ui.story

import com.adong.adchat.data.story.StoryMessageWithRevision
import com.adong.adchat.data.story.StoryRevisionState

/** Publish only the active revision; a late chunk must not replace a completed or switched revision. */
internal fun updateStreamingStoryRevision(
    rows: List<StoryMessageWithRevision>,
    revisionId: String,
    content: String
): List<StoryMessageWithRevision> {
    val index = rows.indexOfFirst { it.revision.id == revisionId && it.revision.state == StoryRevisionState.Streaming }
    if (index < 0 || rows[index].revision.content == content) return rows
    return rows.toMutableList().apply {
        val current = this[index]
        this[index] = current.copy(revision = current.revision.copy(content = content))
    }
}
