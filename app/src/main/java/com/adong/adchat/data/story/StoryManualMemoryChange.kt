package com.adong.adchat.data.story

import java.util.UUID

enum class StoryManualMemoryOperation(val dbValue: String) {
    Add("add"),
    Update("update"),
    Pin("pin"),
    Deactivate("deactivate");

    companion object {
        fun fromDb(value: String): StoryManualMemoryOperation =
            entries.firstOrNull { it.dbValue == value } ?: error("Unknown manual memory operation: $value")
    }
}

data class StoryManualMemoryChange(
    val id: String = newManualMemoryChangeId(),
    val storyId: String,
    val timelineId: String,
    val recordId: String,
    val operation: StoryManualMemoryOperation,
    val baseMemoryVersion: Long,
    val committedVersion: Long,
    val beforeJson: String?,
    val afterJson: String?,
    val createdAt: Long = System.currentTimeMillis()
)

fun newManualMemoryChangeId(): String = "manual_change_${UUID.randomUUID()}"
