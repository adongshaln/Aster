package com.adong.adchat.data.story

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

class StoryArchiveStore(context: Context) : AutoCloseable {
    private val helper = StoryDatabase(context)

    fun listMemoryRecords(storyId: String, timelineId: String): List<StoryMemoryRecord> {
        val records = helper.readableDatabase.query(
            StorySchema.MEMORIES,
            null,
            """story_id = ? AND timeline_id = ? AND active = 1 AND
               (source_revision_id IS NULL OR EXISTS (SELECT 1 FROM ${StorySchema.MESSAGES} m
                JOIN ${StorySchema.REVISIONS} r ON r.id = m.active_revision_id
                WHERE r.id = ${StorySchema.MEMORIES}.source_revision_id AND r.state = 'complete'))""",
            arrayOf(storyId, timelineId),
            null,
            null,
            "pinned DESC, effective_sequence DESC, updated_at DESC"
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMemory()) } }
        if (records.none { it.subjectEntityId != null || it.objectEntityId != null }) return records

        val namesByEntityId = activeCharacterAndPlaceNames(storyId)
        return records.map { record ->
            record.copy(
                subjectEntityNames = record.subjectEntityId?.let(namesByEntityId::get).orEmpty(),
                objectEntityNames = record.objectEntityId?.let(namesByEntityId::get).orEmpty()
            )
        }
    }

    fun listPendingProposals(storyId: String, timelineId: String): List<StoryProposal> =
        helper.readableDatabase.query(
            StorySchema.PROPOSALS,
            null,
            """story_id = ? AND timeline_id = ? AND state = ? AND EXISTS
                (SELECT 1 FROM ${StorySchema.MESSAGES} m JOIN ${StorySchema.REVISIONS} r
                 ON r.id = m.active_revision_id WHERE r.id = ${StorySchema.PROPOSALS}.source_revision_id
                 AND r.state = 'complete')""",
            arrayOf(storyId, timelineId, StoryProposalState.Pending.dbValue),
            null,
            null,
            "updated_at DESC"
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toProposal()) } }

    /** Explicit UI decision; proposal state, optional memory record, change set and version are atomic. */
    fun decideProposal(storyId: String, timelineId: String, proposalId: String, accept: Boolean): Boolean =
        helper.writableDatabase.inTransaction { db ->
            val proposal = db.query(
                StorySchema.PROPOSALS,
                null,
                "id = ? AND story_id = ? AND timeline_id = ? AND state = 'pending'",
                arrayOf(proposalId, storyId, timelineId), null, null, null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toProposal() else null }
                ?: return@inTransaction false
            val sourceActive = db.rawQuery(
                """SELECT 1 FROM ${StorySchema.REVISIONS} r
                   JOIN ${StorySchema.MESSAGES} m ON m.active_revision_id = r.id
                   JOIN ${StorySchema.STORIES} s ON s.id = r.story_id
                   WHERE r.id = ? AND r.state = 'complete' AND m.role = 'assistant'
                     AND s.current_timeline_id = ? AND r.timeline_id = ?""",
                arrayOf(proposal.sourceRevisionId, timelineId, timelineId)
            ).use { cursor -> cursor.moveToFirst() }
            if (!sourceActive) return@inTransaction false

            val baseVersion = requireStoryMemoryVersion(db, storyId)
            val now = System.currentTimeMillis()
            val record = if (accept) {
                val record = StoryMemoryRecord(
                    storyId = storyId,
                    timelineId = timelineId,
                    kind = when (proposal.proposalKind) {
                        "world" -> StoryMemoryKind.WorldFact
                        "character" -> StoryMemoryKind.CharacterProfile
                        "author_plan", "plot" -> StoryMemoryKind.AuthorPlan
                        else -> StoryMemoryKind.OpenThread
                    },
                    content = proposal.content.trim(),
                    nature = StoryMemoryNature.UserConfirmed,
                    scope = "story",
                    effectiveSequence = db.rawQuery(
                        "SELECT COALESCE(MAX(sequence_no), 0) FROM ${StorySchema.MESSAGES} WHERE story_id = ? AND timeline_id = ?",
                        arrayOf(storyId, timelineId)
                    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L },
                    sourceRevisionId = proposal.sourceRevisionId,
                    pinned = false,
                    active = true,
                    createdAt = now,
                    updatedAt = now
                )
                insertMemory(db, record)
                commitManualChange(db, StoryManualMemoryOperation.Add, null, record, baseVersion, now)
                record
            } else null
            val committedVersion = if (accept) {
                requireStoryMemoryVersion(db, storyId)
            } else {
                val next = Math.addExact(baseVersion, 1L)
                check(db.update(
                    StorySchema.STORIES,
                    ContentValues().apply { put("memory_version", next); put("updated_at", now) },
                    "id = ? AND memory_version = ?", arrayOf(storyId, baseVersion.toString())
                ) == 1)
                next
            }
            check(db.update(
                StorySchema.PROPOSALS,
                ContentValues().apply {
                    put("state", if (accept) StoryProposalState.Accepted.dbValue else StoryProposalState.Rejected.dbValue)
                    put("updated_at", now)
                },
                "id = ? AND state = 'pending'", arrayOf(proposalId)
            ) == 1)
            db.insertOrThrow(StorySchema.CHANGE_SETS, null, ContentValues().apply {
                put("id", newChangeSetId())
                put("story_id", storyId)
                put("timeline_id", timelineId)
                put("base_memory_version", baseVersion)
                put("source_revision_id", proposal.sourceRevisionId)
                put("status", "committed")
                put("committed_version", committedVersion)
                put("operations_json", JSONObject().put("actor", "user_ui")
                    .put("proposal_id", proposalId).put("before", "pending")
                    .put("after", if (accept) "accepted" else "rejected")
                    .put("record_id", record?.id ?: JSONObject.NULL).toString())
                put("conflicts_json", "[]")
                put("created_at", now)
                put("updated_at", now)
            })
            true
        }

    fun listManualChanges(storyId: String, timelineId: String): List<StoryManualMemoryChange> =
        helper.readableDatabase.query(
            StorySchema.MANUAL_MEMORY_CHANGES,
            null,
            "story_id = ? AND timeline_id = ?",
            arrayOf(storyId, timelineId),
            null,
            null,
            "committed_version DESC"
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toManualChange()) } }

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
        return helper.writableDatabase.inTransaction { db ->
            val baseVersion = requireStoryMemoryVersion(db, storyId)
            val effectiveSequence = db.rawQuery(
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
            insertMemory(db, record)
            commitManualChange(
                db = db,
                operation = StoryManualMemoryOperation.Add,
                before = null,
                after = record,
                baseVersion = baseVersion,
                now = now
            )
            record
        }
    }

    fun updateConfirmedRecord(recordId: String, content: String, pinned: Boolean): Boolean {
        val cleaned = content.trim()
        if (cleaned.isBlank()) return false
        val now = System.currentTimeMillis()
        return helper.writableDatabase.inTransaction { db ->
            val before = queryMemory(db, recordId)?.takeIf(StoryMemoryRecord::active)
                ?: return@inTransaction false
            if (before.content == cleaned && before.pinned == pinned) return@inTransaction false
            val baseVersion = requireStoryMemoryVersion(db, before.storyId)
            val after = before.copy(content = cleaned, pinned = pinned, updatedAt = now)
            check(
                db.update(
                    StorySchema.MEMORIES,
                    ContentValues().apply {
                        put("content", after.content)
                        put("pinned", if (after.pinned) 1 else 0)
                        put("updated_at", after.updatedAt)
                    },
                    "id = ? AND active = 1",
                    arrayOf(recordId)
                ) == 1
            ) { "Manual memory record changed before update commit" }
            commitManualChange(
                db = db,
                operation = StoryManualMemoryOperation.Update,
                before = before,
                after = after,
                baseVersion = baseVersion,
                now = now
            )
            true
        }
    }

    fun setPinned(recordId: String, pinned: Boolean): Boolean {
        val now = System.currentTimeMillis()
        return helper.writableDatabase.inTransaction { db ->
            val before = queryMemory(db, recordId)?.takeIf(StoryMemoryRecord::active)
                ?: return@inTransaction false
            if (before.pinned == pinned) return@inTransaction false
            val baseVersion = requireStoryMemoryVersion(db, before.storyId)
            val after = before.copy(pinned = pinned, updatedAt = now)
            check(
                db.update(
                    StorySchema.MEMORIES,
                    ContentValues().apply {
                        put("pinned", if (after.pinned) 1 else 0)
                        put("updated_at", after.updatedAt)
                    },
                    "id = ? AND active = 1",
                    arrayOf(recordId)
                ) == 1
            ) { "Manual memory record changed before pin commit" }
            commitManualChange(
                db = db,
                operation = StoryManualMemoryOperation.Pin,
                before = before,
                after = after,
                baseVersion = baseVersion,
                now = now
            )
            true
        }
    }

    /** Manual removal remains a soft deactivation only; this audit log is not full undo/replay. */
    fun deactivateRecord(recordId: String): Boolean {
        val now = System.currentTimeMillis()
        return helper.writableDatabase.inTransaction { db ->
            val before = queryMemory(db, recordId)?.takeIf(StoryMemoryRecord::active)
                ?: return@inTransaction false
            val baseVersion = requireStoryMemoryVersion(db, before.storyId)
            val after = before.copy(active = false, updatedAt = now)
            check(
                db.update(
                    StorySchema.MEMORIES,
                    ContentValues().apply {
                        put("active", 0)
                        put("updated_at", after.updatedAt)
                    },
                    "id = ? AND active = 1",
                    arrayOf(recordId)
                ) == 1
            ) { "Manual memory record changed before deactivate commit" }
            commitManualChange(
                db = db,
                operation = StoryManualMemoryOperation.Deactivate,
                before = before,
                after = after,
                baseVersion = baseVersion,
                now = now
            )
            true
        }
    }

    override fun close() = helper.close()

    private fun insertMemory(db: SQLiteDatabase, record: StoryMemoryRecord) {
        db.insertOrThrow(
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
                record.sourceRevisionId?.let { put("source_revision_id", it) } ?: putNull("source_revision_id")
                put("pinned", if (record.pinned) 1 else 0)
                put("active", if (record.active) 1 else 0)
                put("created_at", record.createdAt)
                put("updated_at", record.updatedAt)
            }
        )
    }

    private fun commitManualChange(
        db: SQLiteDatabase,
        operation: StoryManualMemoryOperation,
        before: StoryMemoryRecord?,
        after: StoryMemoryRecord?,
        baseVersion: Long,
        now: Long
    ) {
        val record = after ?: before ?: error("Manual change requires a memory record")
        val committedVersion = Math.addExact(baseVersion, 1L)
        val change = StoryManualMemoryChange(
            storyId = record.storyId,
            timelineId = record.timelineId,
            recordId = record.id,
            operation = operation,
            baseMemoryVersion = baseVersion,
            committedVersion = committedVersion,
            beforeJson = before?.let(::memoryAuditJson),
            afterJson = after?.let(::memoryAuditJson),
            createdAt = now
        )
        db.insertOrThrow(
            StorySchema.MANUAL_MEMORY_CHANGES,
            null,
            ContentValues().apply {
                put("id", change.id)
                put("story_id", change.storyId)
                put("timeline_id", change.timelineId)
                put("record_id", change.recordId)
                put("operation", change.operation.dbValue)
                put("base_memory_version", change.baseMemoryVersion)
                put("committed_version", change.committedVersion)
                change.beforeJson?.let { put("before_json", it) } ?: putNull("before_json")
                change.afterJson?.let { put("after_json", it) } ?: putNull("after_json")
                put("created_at", change.createdAt)
            }
        )
        val advanced = db.update(
            StorySchema.STORIES,
            ContentValues().apply {
                put("memory_version", committedVersion)
                put("updated_at", now)
            },
            "id = ? AND memory_version = ?",
            arrayOf(record.storyId, baseVersion.toString())
        )
        check(advanced == 1) { "Story memoryVersion changed before manual mutation commit" }
    }

    private fun requireStoryMemoryVersion(db: SQLiteDatabase, storyId: String): Long = db.rawQuery(
        "SELECT memory_version FROM ${StorySchema.STORIES} WHERE id = ? LIMIT 1",
        arrayOf(storyId)
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Story not found for manual memory mutation" }
        cursor.getLong(0)
    }

    private fun queryMemory(db: SQLiteDatabase, recordId: String): StoryMemoryRecord? = db.query(
        StorySchema.MEMORIES,
        null,
        "id = ?",
        arrayOf(recordId),
        null,
        null,
        null,
        "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toMemory() else null }

    private fun memoryAuditJson(record: StoryMemoryRecord): String = JSONObject().apply {
        put("id", record.id)
        put("storyId", record.storyId)
        put("timelineId", record.timelineId)
        put("kind", record.kind.dbValue)
        put("content", record.content)
        put("nature", record.nature.dbValue)
        put("subjectEntityId", record.subjectEntityId ?: JSONObject.NULL)
        put("objectEntityId", record.objectEntityId ?: JSONObject.NULL)
        put("scope", record.scope)
        put("effectiveSequence", record.effectiveSequence)
        put("sourceRevisionId", record.sourceRevisionId ?: JSONObject.NULL)
        put("pinned", record.pinned)
        put("active", record.active)
        put("createdAt", record.createdAt)
        put("updatedAt", record.updatedAt)
    }.toString()

    private fun activeCharacterAndPlaceNames(storyId: String): Map<String, List<String>> =
        helper.readableDatabase.query(
            StorySchema.ENTITIES,
            arrayOf("id", "kind", "canonical_name", "aliases_json"),
            "story_id = ? AND active = 1 AND kind IN (?, ?)",
            arrayOf(storyId, StoryEntityKind.Character.dbValue, StoryEntityKind.Place.dbValue),
            null,
            null,
            null
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val id = cursor.string("id")
                    val names = buildList {
                        add(cursor.string("canonical_name"))
                        val aliases = runCatching { JSONArray(cursor.string("aliases_json")) }.getOrNull()
                        if (aliases != null) {
                            for (index in 0 until aliases.length()) {
                                aliases.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                    }.distinct()
                    put(id, names)
                }
            }
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

    private fun Cursor.toManualChange(): StoryManualMemoryChange = StoryManualMemoryChange(
        id = string("id"),
        storyId = string("story_id"),
        timelineId = string("timeline_id"),
        recordId = string("record_id"),
        operation = StoryManualMemoryOperation.fromDb(string("operation")),
        baseMemoryVersion = long("base_memory_version"),
        committedVersion = long("committed_version"),
        beforeJson = nullableString("before_json"),
        afterJson = nullableString("after_json"),
        createdAt = long("created_at")
    )

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private inline fun <T> SQLiteDatabase.inTransaction(block: (SQLiteDatabase) -> T): T {
        beginTransaction()
        return try {
            val result = block(this)
            setTransactionSuccessful()
            result
        } finally {
            endTransaction()
        }
    }
}
