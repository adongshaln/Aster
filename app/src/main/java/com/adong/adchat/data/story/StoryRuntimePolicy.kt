package com.adong.adchat.data.story

internal enum class StoryStopCleanup {
    RemoveAssistant,
    KeepStoppedPartial
}

internal fun storyStopCleanupFor(partial: String): StoryStopCleanup =
    if (partial.isBlank()) StoryStopCleanup.RemoveAssistant else StoryStopCleanup.KeepStoppedPartial

internal fun nextStoryWorkspaceUpdatedAt(previous: Long, wallClock: Long): Long = when {
    previous == Long.MAX_VALUE -> Long.MAX_VALUE
    else -> maxOf(wallClock, previous + 1)
}

internal fun shouldPersistStoryWorkspaceState(existingUpdatedAt: Long?, incomingUpdatedAt: Long): Boolean =
    existingUpdatedAt == null || incomingUpdatedAt > existingUpdatedAt
