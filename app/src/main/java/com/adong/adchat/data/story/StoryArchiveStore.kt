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

    /** Compensating mutation: original audit entries and record identity are retained. */
    fun undoManualChange(storyId: String, timelineId: String, changeId: String): Boolean =
        helper.writableDatabase.inTransaction { db ->
            require(db.rawQuery("SELECT 1 FROM ${StorySchema.STORIES} WHERE id = ? AND current_timeline_id = ?",
                arrayOf(storyId, timelineId)).use { it.moveToFirst() }) { "故事路线已变化，请重新打开档案" }
            val change = db.query(StorySchema.MANUAL_MEMORY_CHANGES, null,
                "id = ? AND story_id = ? AND timeline_id = ?", arrayOf(changeId, storyId, timelineId), null, null, null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toManualChange() else null } ?: return@inTransaction false
            if (undoMarkerExists(db, storyId, timelineId, changeId)) return@inTransaction false
            require(!isProposalDecision(db, change)) { "候选采用涉及确认状态，暂不支持从单条资料日志撤销" }
            val current = queryMemory(db, change.recordId) ?: return@inTransaction false
            require(latestManualChangeId(db, current.id) == change.id && auditMatches(current, change.afterJson)) {
                "这条资料已有后续改动，请从最新变更操作，避免覆盖新内容"
            }
            require(sourceIsEffective(db, current)) { "资料来源已不属于当前正文版本，不能在此恢复" }
            val before = change.beforeJson?.let(::JSONObject)
            val now = System.currentTimeMillis()
            val restored = if (before == null) current.copy(active = false, updatedAt = now) else current.copy(
                content = before.getString("content"), pinned = before.getBoolean("pinned"),
                active = before.getBoolean("active"), updatedAt = now
            )
            val base = requireStoryMemoryVersion(db, storyId)
            check(db.update(StorySchema.MEMORIES, ContentValues().apply {
                put("content", restored.content); put("pinned", if (restored.pinned) 1 else 0)
                put("active", if (restored.active) 1 else 0); put("updated_at", now)
            }, "id = ?", arrayOf(current.id)) == 1)
            val inverseId = commitManualChange(db, if (before == null) StoryManualMemoryOperation.Deactivate else StoryManualMemoryOperation.Update,
                current, restored, base, now)
            db.insertOrThrow(StorySchema.SNAPSHOTS, null, ContentValues().apply {
                put("id", "undo_${java.util.UUID.randomUUID()}"); put("story_id", storyId); put("timeline_id", timelineId)
                put("sequence_no", current.effectiveSequence); put("memory_version", Math.addExact(base, 1L))
                put("log_cursor", "undo:$changeId"); put("created_at", now)
                put("snapshot_json", JSONObject().put("operation", "undo_manual_change").put("change_id", changeId)
                    .put("inverse_change_id", inverseId).put("before", JSONObject(memoryAuditJson(current)))
                    .put("after", JSONObject(memoryAuditJson(restored))).toString())
            })
            true
        }

    fun undoChangeSet(storyId: String, timelineId: String, changeId: String): Boolean =
        helper.writableDatabase.inTransaction { db -> StoryChangeSetUndo.undo(db, storyId, timelineId, changeId) }

    fun listChanges(storyId: String, timelineId: String): List<StoryChangeEntry> = helper.readableDatabase.inTransaction { db ->
        val manual = db.query(StorySchema.MANUAL_MEMORY_CHANGES, null, "story_id = ? AND timeline_id = ?",
            arrayOf(storyId, timelineId), null, null, "committed_version DESC", "100").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toManualChange()) }
        }
        val result = manual.map { change ->
            val record = queryMemory(db, change.recordId)
            val undone = undoMarkerExists(db, storyId, timelineId, change.id)
            val proposal = isProposalDecision(db, change)
            val latest = record != null && latestManualChangeId(db, record.id) == change.id
            val effective = record != null && sourceIsEffective(db, record)
            StoryChangeEntry(change.id, change.committedVersion, when (change.operation) {
                StoryManualMemoryOperation.Add -> "新增资料"
                StoryManualMemoryOperation.Update -> "修改 / 恢复资料"
                StoryManualMemoryOperation.Pin -> "调整固定状态"
                StoryManualMemoryOperation.Deactivate -> "停用资料"
            }, auditDescription(change.beforeJson), auditDescription(change.afterJson),
                source = record?.sourceRevisionId?.let { sourceText(db, it) }.orEmpty(),
                note = when { undone -> "已撤销"; proposal -> "来自候选确认，单条撤销暂不可用"; !effective -> "历史版本来源";
                    !latest -> "已有后续资料改动"; else -> "" },
                canUndo = !undone && !proposal && latest && effective && record != null && auditMatches(record, change.afterJson))
        }.toMutableList()
        db.rawQuery("""SELECT c.*, r.content AS source_text FROM ${StorySchema.CHANGE_SETS} c
            LEFT JOIN ${StorySchema.REVISIONS} r ON r.id = c.source_revision_id
            WHERE c.story_id = ? AND c.timeline_id = ? ORDER BY c.committed_version DESC LIMIT 100""",
            arrayOf(storyId, timelineId)).use { cursor ->
            while (cursor.moveToNext()) {
                val operations = JSONObject(cursor.string("operations_json"))
                val description = when {
                    operations.optString("operation") == "reverse_change_set" -> "撤销 / 恢复整批变更"
                    operations.optString("operation") == "switch_revision" -> "切换正文版本"
                    operations.has("proposal_id") -> if (operations.optString("after") == "accepted") "采用候选" else "废弃候选"
                    else -> "自动整理：新增 ${operations.optJSONArray("added_memory_ids")?.length() ?: 0} 条资料、${operations.optJSONArray("proposal_ids")?.length() ?: 0} 条候选"
                }
                val id = cursor.string("id")
                val version = cursor.getLong(cursor.getColumnIndexOrThrow("committed_version"))
                val canUndo = StoryChangeSetUndo.canUndo(db, storyId, timelineId, id, version, cursor.string("source_revision_id"), operations)
                val undone = StoryChangeSetUndo.wasUndone(db, storyId, timelineId, id)
                result += StoryChangeEntry(id, version, description, source = cursor.nullableString("source_text").orEmpty(),
                    note = if (undone) "已整体撤销" else if (canUndo) "资料与候选状态将一起撤销；反向记录可用于恢复"
                        else "仅当前来源、且没有后续记忆变更的批次可整体撤销",
                    canUndo = canUndo, batch = true)
            }
        }
        result.sortedByDescending { it.version }.take(100)
    }

    private fun undoMarkerExists(db: SQLiteDatabase, storyId: String, timelineId: String, changeId: String): Boolean =
        db.rawQuery("SELECT 1 FROM ${StorySchema.SNAPSHOTS} WHERE story_id = ? AND timeline_id = ? AND log_cursor = ? LIMIT 1",
            arrayOf(storyId, timelineId, "undo:$changeId")).use { it.moveToFirst() }

    private fun latestManualChangeId(db: SQLiteDatabase, recordId: String): String? = db.rawQuery(
        "SELECT id FROM ${StorySchema.MANUAL_MEMORY_CHANGES} WHERE record_id = ? ORDER BY committed_version DESC LIMIT 1", arrayOf(recordId)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun isProposalDecision(db: SQLiteDatabase, change: StoryManualMemoryChange): Boolean = db.rawQuery(
        "SELECT operations_json FROM ${StorySchema.CHANGE_SETS} WHERE story_id = ? AND timeline_id = ? AND committed_version = ?",
        arrayOf(change.storyId, change.timelineId, change.committedVersion.toString())
    ).use { cursor ->
        var found = false
        while (cursor.moveToNext()) {
            val op = JSONObject(cursor.getString(0))
            if (op.has("proposal_id") && op.optString("record_id") == change.recordId) found = true
        }
        found
    }

    private fun sourceIsEffective(db: SQLiteDatabase, record: StoryMemoryRecord): Boolean = record.sourceRevisionId == null ||
        db.rawQuery("""SELECT 1 FROM ${StorySchema.REVISIONS} r JOIN ${StorySchema.MESSAGES} m ON m.active_revision_id = r.id
            WHERE r.id = ? AND r.state = 'complete' AND m.story_id = ? AND m.timeline_id = ?""",
            arrayOf(record.sourceRevisionId, record.storyId, record.timelineId)).use { it.moveToFirst() }

    private fun sourceText(db: SQLiteDatabase, revisionId: String): String = db.rawQuery(
        "SELECT content FROM ${StorySchema.REVISIONS} WHERE id = ?", arrayOf(revisionId)
    ).use { if (it.moveToFirst()) it.getString(0) else "" }

    private fun auditMatches(record: StoryMemoryRecord, json: String?): Boolean {
        if (json == null) return false
        val expected = JSONObject(json)
        val actual = JSONObject(memoryAuditJson(record))
        return actual.keys().asSequence().all { key -> expected.has(key) && actual.get(key).toString() == expected.get(key).toString() }
    }

    private fun auditDescription(json: String?): String = json?.let { value ->
        val row = JSONObject(value)
        "${row.getString("content")}\n${if (row.getBoolean("pinned")) "固定" else "未固定"} · ${if (row.getBoolean("active")) "启用" else "停用"}"
    }.orEmpty()

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
    ): String {
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
        return change.id
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
