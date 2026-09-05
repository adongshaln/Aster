package com.adong.adchat.data.story

import android.content.ContentValues
import android.content.Context
import android.database.Cursor

class StoryArchiveStore(context: Context) : AutoCloseable {
    private val helper = StoryDatabase(context)

    fun listMemoryRecords(storyId: String, timelineId: String): List<StoryMemoryRecord> =
        helper.readableDatabase.query(
            StorySchema.MEMORIES,
            null,
            "story_id = ? AND timeline_id = ? AND active = 1",
            arrayOf(storyId, timelineId),
            null,
            null,
            "pinned DESC, effective_sequence DESC, updated_at DESC"
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMemory()) } }

    fun listPendingProposals(storyId: String, timelineId: String): List<StoryProposal> =
        helper.readableDatabase.query(
            StorySchema.PROPOSALS,
            null,
            "story_id = ? AND timeline_id = ? AND state = ?",
            arrayOf(storyId, timelineId, StoryProposalState.Pending.dbValue),
            null,
            null,
            "updated_at DESC"
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toProposal()) } }

    fun addConfirmedRecord(
        storyId: String,
        timelineId: String,
        kind: StoryMemoryKind,
        content: String,
        pinned: Boolean = false,
        subjectEntityId: String? = null,
        objectEntityId: String? = null,
        scope: String = "story"
    ): StoryMemoryRecord {
        val cleaned = content.trim()
        require(cleaned.isNotBlank()) { "Memory content is required" }
        val now = System.currentTimeMillis()
        val effectiveSequence = helper.readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(sequence_no), 0) FROM ${StorySchema.MESSAGES} WHERE story_id = ? AND timeline_id = ?",
            arrayOf(storyId, timelineId)
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val record = StoryMemoryRecord(
            storyId = storyId,
            timelineId = timelineId,
            kind = kind,
            content = cleaned,
            nature = StoryMemoryNature.UserConfirmed,
            subjectEntityId = subjectEntityId,
            objectEntityId = objectEntityId,
            scope = scope,
            effectiveSequence = effectiveSequence,
            sourceRevisionId = null,
            pinned = pinned,
            active = true,
            createdAt = now,
            updatedAt = now
        )
        helper.writableDatabase.insertOrThrow(
            StorySchema.MEMORIES,
            null,
            ContentValues().apply {
                put("id", record.id)
                put("story_id", record.storyId)
                put("timeline_id", record.timelineId)
                put("kind", record.kind.dbValue)
                put("content", record.content)
                put("nature", record.nature.dbValue)
                record.subjectEntityId?.let { put("subject_entity_id", it) } ?: putNull("subject_entity_id")
                record.objectEntityId?.let { put("object_entity_id", it) } ?: putNull("object_entity_id")
                put("scope", record.scope)
                put("effective_sequence", record.effectiveSequence)
                putNull("source_revision_id")
                put("pinned", if (record.pinned) 1 else 0)
                put("active", 1)
                put("created_at", record.createdAt)
                put("updated_at", record.updatedAt)
            }
        )
        touchStory(storyId, now)
        return record
    }

    fun updateConfirmedRecord(recordId: String, content: String, pinned: Boolean): Boolean {
        val cleaned = content.trim()
        if (cleaned.isBlank()) return false
        val now = System.currentTimeMillis()
        val storyId = storyIdForRecord(recordId) ?: return false
        val changed = helper.writableDatabase.update(
            StorySchema.MEMORIES,
            ContentValues().apply {
                put("content", cleaned)
                put("pinned", if (pinned) 1 else 0)
                put("updated_at", now)
            },
            "id = ? AND active = 1",
            arrayOf(recordId)
        ) == 1
        if (changed) touchStory(storyId, now)
        return changed
    }

    fun setPinned(recordId: String, pinned: Boolean): Boolean {
        val storyId = storyIdForRecord(recordId) ?: return false
        val now = System.currentTimeMillis()
        val changed = helper.writableDatabase.update(
            StorySchema.MEMORIES,
            ContentValues().apply {
                put("pinned", if (pinned) 1 else 0)
                put("updated_at", now)
            },
            "id = ? AND active = 1",
            arrayOf(recordId)
        ) == 1
        if (changed) touchStory(storyId, now)
        return changed
    }

    /** Manual removal is a soft deactivation only; full undo/replay is not implemented here. */
    fun deactivateRecord(recordId: String): Boolean {
        val storyId = storyIdForRecord(recordId) ?: return false
        val now = System.currentTimeMillis()
        val changed = helper.writableDatabase.update(
            StorySchema.MEMORIES,
            ContentValues().apply {
                put("active", 0)
                put("updated_at", now)
            },
            "id = ? AND active = 1",
            arrayOf(recordId)
        ) == 1
        if (changed) touchStory(storyId, now)
        return changed
    }

    override fun close() = helper.close()

    private fun storyIdForRecord(recordId: String): String? = helper.readableDatabase.rawQuery(
        "SELECT story_id FROM ${StorySchema.MEMORIES} WHERE id = ? LIMIT 1",
        arrayOf(recordId)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun touchStory(storyId: String, now: Long) {
        helper.writableDatabase.update(
            StorySchema.STORIES,
            ContentValues().apply { put("updated_at", now) },
            "id = ?",
            arrayOf(storyId)
        )
    }

    private fun Cursor.toMemory(): StoryMemoryRecord = StoryMemoryRecord(
        id = string("id"),
        storyId = string("story_id"),
        timelineId = string("timeline_id"),
        kind = StoryMemoryKind.fromDb(string("kind")),
        content = string("content"),
        nature = StoryMemoryNature.fromDb(string("nature")),
        subjectEntityId = nullableString("subject_entity_id"),
        objectEntityId = nullableString("object_entity_id"),
        scope = string("scope"),
        effectiveSequence = long("effective_sequence"),
        sourceRevisionId = nullableString("source_revision_id"),
        pinned = int("pinned") != 0,
        active = int("active") != 0,
        createdAt = long("created_at"),
        updatedAt = long("updated_at")
    )

    private fun Cursor.toProposal(): StoryProposal = StoryProposal(
        id = string("id"),
        storyId = string("story_id"),
        timelineId = string("timeline_id"),
        content = string("content"),
        proposalKind = string("proposal_kind"),
        sourceRevisionId = string("source_revision_id"),
        decisionSourceRevisionId = nullableString("decision_source_revision_id"),
        state = StoryProposalState.fromDb(string("state")),
        createdAt = long("created_at"),
        updatedAt = long("updated_at")
    )

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }
}
