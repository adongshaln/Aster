package com.adong.adchat.data.story

import java.util.UUID

enum class StoryWorkspace(val dbValue: String) {
    Discussion("discussion"),
    Prose("prose");

    companion object {
        fun fromDb(value: String): StoryWorkspace = entries.firstOrNull { it.dbValue == value } ?: Discussion
    }
}

enum class StoryRevisionState(val dbValue: String) {
    Complete("complete"),
    Streaming("streaming"),
    Interrupted("interrupted"),
    Stopped("stopped"),
    Superseded("superseded");

    companion object {
        fun fromDb(value: String): StoryRevisionState = entries.firstOrNull { it.dbValue == value } ?: Complete
    }
}

enum class StoryMemoryKind(val dbValue: String) {
    WorldFact("world_fact"),
    CharacterProfile("character_profile"),
    CurrentState("current_state"),
    DirectedRelationship("directed_relationship"),
    CharacterKnowledge("character_knowledge"),
    PlotEvent("plot_event"),
    OpenThread("open_thread"),
    AuthorPlan("author_plan"),
    Summary("summary");

    companion object {
        fun fromDb(value: String): StoryMemoryKind = entries.firstOrNull { it.dbValue == value } ?: WorldFact
    }
}

enum class StoryMemoryNature(val dbValue: String) {
    UserConfirmed("user_confirmed"),
    ProseOccurred("prose_occurred"),
    CharacterBelief("character_belief"),
    Inference("inference");

    companion object {
        fun fromDb(value: String): StoryMemoryNature = entries.firstOrNull { it.dbValue == value } ?: Inference
    }
}

enum class StoryProposalState(val dbValue: String) {
    Pending("pending"),
    Accepted("accepted"),
    Rejected("rejected"),
    Superseded("superseded");

    companion object {
        fun fromDb(value: String): StoryProposalState = entries.firstOrNull { it.dbValue == value } ?: Pending
    }
}

enum class StoryJobState(val dbValue: String) {
    Pending("pending"),
    Running("running"),
    Completed("completed"),
    Failed("failed"),
    Stale("stale");

    companion object {
        fun fromDb(value: String): StoryJobState = entries.firstOrNull { it.dbValue == value } ?: Pending
    }
}

enum class StoryEntityKind(val dbValue: String) {
    Character("character"),
    Place("place"),
    Organization("organization"),
    Other("other");

    companion object {
        fun fromDb(value: String): StoryEntityKind = entries.firstOrNull { it.dbValue == value } ?: Other
    }
}

data class Story(
    val id: String = newStoryId(),
    val title: String,
    val profileId: String,
    val model: String,
    val currentTimelineId: String,
    val memoryVersion: Long = 0,
    val automaticMemoryEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class StoryTimeline(
    val id: String = newTimelineId(),
    val storyId: String,
    val parentTimelineId: String? = null,
    val forkRevisionId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class StoryMessage(
    val id: String,
    val storyId: String,
    val timelineId: String,
    val workspace: StoryWorkspace,
    val role: String,
    val sequence: Long,
    val activeRevisionId: String,
    val createdAt: Long
)

data class StoryMessageRevision(
    val id: String = newRevisionId(),
    val messageId: String,
    val storyId: String,
    val timelineId: String,
    val workspace: StoryWorkspace,
    val content: String,
    val state: StoryRevisionState,
    val profileName: String = "",
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    val eligibleForMemory: Boolean
        get() = workspace == StoryWorkspace.Prose && state == StoryRevisionState.Complete && content.isNotBlank()
}

data class StoryMessageWithRevision(
    val message: StoryMessage,
    val revision: StoryMessageRevision
)

data class StoryEntity(
    val id: String = newEntityId(),
    val storyId: String,
    val kind: StoryEntityKind,
    val canonicalName: String,
    val aliases: List<String> = emptyList(),
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class StoryMemoryRecord(
    val id: String = newMemoryId(),
    val storyId: String,
    val timelineId: String,
    val kind: StoryMemoryKind,
    val content: String,
    val nature: StoryMemoryNature,
    val subjectEntityId: String? = null,
    val objectEntityId: String? = null,
    val scope: String = "story",
    val effectiveSequence: Long = 0,
    val sourceRevisionId: String? = null,
    val pinned: Boolean = false,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Runtime-only canonical names/aliases used by context relevance; not persisted in memory_records. */
    val subjectEntityNames: List<String> = emptyList(),
    val objectEntityNames: List<String> = emptyList()
)

data class StoryProposal(
    val id: String = newProposalId(),
    val storyId: String,
    val timelineId: String,
    val content: String,
    val proposalKind: String,
    val sourceRevisionId: String,
    val decisionSourceRevisionId: String? = null,
    val state: StoryProposalState = StoryProposalState.Pending,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class StoryMemoryJob(
    val id: String = newJobId(),
    val storyId: String,
    val timelineId: String,
    val sourceRevisionId: String,
    val kind: String,
    val dedupeKey: String,
    val baseMemoryVersion: Long,
    val state: StoryJobState = StoryJobState.Pending,
    val attempts: Int = 0,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class StoryWorkspaceState(
    val storyId: String,
    val workspace: StoryWorkspace,
    val draft: String = "",
    val firstVisibleIndex: Int = 0,
    val firstVisibleOffset: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

fun newStoryId(): String = "story_${UUID.randomUUID()}"
fun newTimelineId(): String = "timeline_${UUID.randomUUID()}"
fun newMessageId(): String = "message_${UUID.randomUUID()}"
fun newRevisionId(): String = "revision_${UUID.randomUUID()}"
fun newEntityId(): String = "entity_${UUID.randomUUID()}"
fun newMemoryId(): String = "memory_${UUID.randomUUID()}"
fun newProposalId(): String = "proposal_${UUID.randomUUID()}"
fun newChangeSetId(): String = "change_${UUID.randomUUID()}"
fun newJobId(): String = "job_${UUID.randomUUID()}"
