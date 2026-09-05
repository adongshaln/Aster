package com.adong.adchat.data.story

/** Read-only presentation of persisted audit entries. */
data class StoryChangeEntry(
    val id: String,
    val version: Long,
    val title: String,
    val before: String = "",
    val after: String = "",
    val source: String = "",
    val note: String = "",
    val canUndo: Boolean = false,
    val batch: Boolean = false
)
