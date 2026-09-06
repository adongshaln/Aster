package com.adong.adchat.data.story

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

sealed interface StoryMemoryApplyResult {
    data class Committed(
        val addedMemories: Int,
        val addedProposals: Int,
        val committedVersion: Long
    ) : StoryMemoryApplyResult

    data class Requeued(val newJobId: String?) : StoryMemoryApplyResult
    data object StaleSource : StoryMemoryApplyResult
}

class StoryMemoryStore(context: Context) : AutoCloseable {
    private val helper = StoryDatabase(context)

    fun recoverRunningJobs(): Int = helper.writableDatabase.update(
        StorySchema.JOBS,
        ContentValues().apply {
            put("state", StoryJobState.Pending.dbValue)
            put("error", "Recovered after process restart")
            put("updated_at", System.currentTimeMillis())
        },
        "state = ? AND kind IN (?, ?)",
        arrayOf(StoryJobState.Running.dbValue, ORGANIZER_JOB_KIND, DISCUSSION_JOB_KIND)
    )

    fun enqueueForRevision(storyId: String, timelineId: String, sourceRevisionId: String): StoryMemoryJob? =
        helper.writableDatabase.inTransaction { db ->
            val source = queryActiveCompleteSource(db, sourceRevisionId)
                ?.takeIf { it.storyId == storyId && it.timelineId == timelineId }
                ?: return@inTransaction null
            val storyState = queryStoryState(db, storyId) ?: return@inTransaction null
            if (!storyState.automaticMemoryEnabled) return@inTransaction null
            insertJobIfAbsent(db, source, storyState.memoryVersion, System.currentTimeMillis())
        }

    fun nextPendingJob(storyId: String, timelineId: String): StoryMemoryJob? = helper.readableDatabase.rawQuery(
        """SELECT j.* FROM ${StorySchema.JOBS} j
           JOIN ${StorySchema.REVISIONS} r ON r.id = j.source_revision_id
           JOIN ${StorySchema.MESSAGES} m ON m.id = r.message_id
           WHERE j.story_id = ? AND j.timeline_id = ? AND j.kind IN (?, ?) AND j.state = ?
           ORDER BY m.sequence_no ASC, j.created_at ASC LIMIT 1""",
        arrayOf(storyId, timelineId, ORGANIZER_JOB_KIND, DISCUSSION_JOB_KIND, StoryJobState.Pending.dbValue)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toJob() else null }

    fun markRunning(job: StoryMemoryJob, configurationAvailable: Boolean = true): StoryMemoryJob? {
        // Missing configuration is a blocked task, not a failed API attempt.
        if (!configurationAvailable) return null
        return helper.writableDatabase.inTransaction { db ->
            val current = queryJob(db, job.id) ?: return@inTransaction null
            if (current.state != StoryJobState.Pending) return@inTransaction null
            val totalAttempts = sourceAttempts(db, current.sourceRevisionId)
            if (totalAttempts >= MAX_SOURCE_ATTEMPTS) {
                markJobState(db, current.id, StoryJobState.Failed, "Retry limit reached; review and retry manually")
                return@inTransaction null
            }
            val now = System.currentTimeMillis()
            val next = current.copy(
                state = StoryJobState.Running,
                attempts = current.attempts + 1,
                error = "",
                updatedAt = now
            )
            val changed = db.update(
                StorySchema.JOBS,
                ContentValues().apply {
                    put("state", next.state.dbValue)
                    put("attempts", next.attempts)
                    put("error", "")
                    put("updated_at", now)
                },
                "id = ? AND state = ?",
                arrayOf(current.id, StoryJobState.Pending.dbValue)
            ) == 1
            if (changed) next else null
        }
    }

    fun currentMemoryVersion(storyId: String): Long? = helper.readableDatabase.rawQuery(
        "SELECT memory_version FROM ${StorySchema.STORIES} WHERE id = ? LIMIT 1",
        arrayOf(storyId)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    fun resetPending(jobId: String, reason: String) {
        helper.writableDatabase.update(
            StorySchema.JOBS,
            ContentValues().apply {
                put("state", StoryJobState.Pending.dbValue)
                put("error", reason.take(MAX_JOB_ERROR_CHARS))
                put("updated_at", System.currentTimeMillis())
            },
            "id = ? AND state = ?",
            arrayOf(jobId, StoryJobState.Running.dbValue)
        )
    }

    fun markFailed(jobId: String, error: String) {
        helper.writableDatabase.update(
            StorySchema.JOBS,
            ContentValues().apply {
                put("state", StoryJobState.Failed.dbValue)
                put("error", error.take(MAX_JOB_ERROR_CHARS))
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(jobId)
        )
    }

    fun markStale(jobId: String, reason: String) {
        helper.writableDatabase.update(
            StorySchema.JOBS,
            ContentValues().apply {
                put("state", StoryJobState.Stale.dbValue)
                put("error", reason.take(MAX_JOB_ERROR_CHARS))
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(jobId)
        )
    }

    fun requeueStale(job: StoryMemoryJob, newBaseVersion: Long): StoryMemoryJob? =
        helper.writableDatabase.inTransaction { db ->
            val source = queryActiveCompleteSource(db, job.sourceRevisionId)
            db.update(
                StorySchema.JOBS,
                ContentValues().apply {
                    put("state", StoryJobState.Stale.dbValue)
                    put("error", "memoryVersion changed before organizer execution")
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ?",
                arrayOf(job.id)
            )
            val actualVersion = queryStoryState(db, job.storyId)?.memoryVersion
            source?.takeIf { it.storyId == job.storyId && it.timelineId == job.timelineId }
                ?.let { insertJobIfAbsent(db, it, actualVersion ?: newBaseVersion, System.currentTimeMillis()) }
        }

    fun applyOrganizerOutput(job: StoryMemoryJob, output: StoryOrganizerOutput): StoryMemoryApplyResult =
        helper.writableDatabase.inTransaction { db ->
            val persistedJob = queryJob(db, job.id) ?: return@inTransaction StoryMemoryApplyResult.StaleSource
            if (persistedJob.state != StoryJobState.Running) return@inTransaction StoryMemoryApplyResult.StaleSource

            val source = queryActiveCompleteSource(db, persistedJob.sourceRevisionId)
            if (source == null || source.storyId != persistedJob.storyId || source.timelineId != persistedJob.timelineId) {
                markJobState(db, persistedJob.id, StoryJobState.Stale, "Source revision is no longer active complete prose")
                return@inTransaction StoryMemoryApplyResult.StaleSource
            }

            val storyState = queryStoryState(db, persistedJob.storyId)
                ?: return@inTransaction StoryMemoryApplyResult.StaleSource
            if (!storyState.automaticMemoryEnabled) {
                markJobState(db, persistedJob.id, StoryJobState.Pending, "Automatic memory disabled")
                return@inTransaction StoryMemoryApplyResult.StaleSource
            }
            require(source.workspace == StoryWorkspace.Prose || output.memories.isEmpty()) {
                "Discussion cannot commit prose facts"
            }
            if (storyState.memoryVersion != persistedJob.baseMemoryVersion) {
                markJobState(db, persistedJob.id, StoryJobState.Stale, "memoryVersion changed before organizer commit")
                val requeued = insertJobIfAbsent(db, source, storyState.memoryVersion, System.currentTimeMillis())
                return@inTransaction StoryMemoryApplyResult.Requeued(requeued?.id)
            }

            output.memories.forEach { it.validate() }
            // Resolve names only within this route's effective records, never another route's entities.
            // Resolution and any new entities are in this same atomic memory transaction.
            val entities = StoryOrganizerEntities(db, persistedJob.storyId, persistedJob.timelineId)
            val resolved = output.memories.distinct().map { candidate ->
                Triple(candidate, candidate.subject?.let(entities::resolve), candidate.objectName?.let(entities::resolve))
            }.distinctBy { (candidate, subjectId, objectId) ->
                listOf(candidate.kind.dbValue, candidate.nature.dbValue, candidate.content, subjectId, objectId, candidate.stateKey)
            }
            resolved.filter { it.first.kind == StoryMemoryKind.CurrentState }
                .groupBy { it.second to it.first.stateKey }.values.forEach { states ->
                    require(states.map { it.first.content }.distinct().size == 1) { "Conflicting state values after entity resolution" }
                }
            val memoryCandidates = resolved.filterNot { (candidate, subjectId, objectId) ->
                // A → B → A is a real transition. Job/source idempotence already protects retries.
                candidate.kind != StoryMemoryKind.CurrentState &&
                    activeMemoryContentExists(db, persistedJob.storyId, persistedJob.timelineId, candidate, subjectId, objectId)
            }
            val proposalCandidates = output.proposals.filterNot { candidate ->
                pendingProposalContentExists(db, persistedJob.storyId, persistedJob.timelineId, candidate.content)
            }

            val now = System.currentTimeMillis()
            val committedVersion = if (memoryCandidates.isNotEmpty() || proposalCandidates.isNotEmpty()) {
                Math.addExact(storyState.memoryVersion, 1L)
            } else {
                storyState.memoryVersion
            }
            val versionLocked = db.update(
                StorySchema.STORIES,
                ContentValues().apply {
                    put("memory_version", committedVersion)
                    if (committedVersion != storyState.memoryVersion) put("updated_at", now)
                },
                "id = ? AND memory_version = ?",
                arrayOf(persistedJob.storyId, storyState.memoryVersion.toString())
            )
            check(versionLocked == 1) { "Story memoryVersion changed during organizer commit" }

            val addedMemoryIds = mutableListOf<String>()
            memoryCandidates.forEach { (candidate, subjectId, objectId) ->
                val record = StoryMemoryRecord(
                    storyId = persistedJob.storyId,
                    timelineId = persistedJob.timelineId,
                    kind = candidate.kind,
                    content = candidate.content,
                    nature = candidate.nature,
                    subjectEntityId = subjectId,
                    objectEntityId = objectId,
                    stateKey = candidate.stateKey,
                    effectiveSequence = source.sequence,
                    sourceRevisionId = persistedJob.sourceRevisionId,
                    pinned = false,
                    active = true,
                    createdAt = now,
                    updatedAt = now
                )
                insertMemory(db, record)
                addedMemoryIds += record.id
            }

            val addedProposalIds = mutableListOf<String>()
            proposalCandidates.forEach { candidate ->
                val proposal = StoryProposal(
                    storyId = persistedJob.storyId,
                    timelineId = persistedJob.timelineId,
                    content = candidate.content,
                    proposalKind = candidate.proposalKind,
                    sourceRevisionId = persistedJob.sourceRevisionId,
                    state = StoryProposalState.Pending,
                    createdAt = now,
                    updatedAt = now
                )
                insertProposal(db, proposal)
                addedProposalIds += proposal.id
            }

            val operationsJson = JSONObject()
                .put("added_memory_ids", JSONArray(addedMemoryIds))
                .put("proposal_ids", JSONArray(addedProposalIds))
                .toString()
            db.insertOrThrow(
                StorySchema.CHANGE_SETS,
                null,
                ContentValues().apply {
                    put("id", newChangeSetId())
                    put("story_id", persistedJob.storyId)
                    put("timeline_id", persistedJob.timelineId)
                    put("base_memory_version", persistedJob.baseMemoryVersion)
                    put("source_revision_id", persistedJob.sourceRevisionId)
                    put("status", "committed")
                    put("operations_json", operationsJson)
                    put("conflicts_json", "[]")
                    put("committed_version", committedVersion)
                    put("created_at", now)
                    put("updated_at", now)
                }
            )
            markJobState(db, persistedJob.id, StoryJobState.Completed, "")

            StoryMemoryApplyResult.Committed(
                addedMemories = addedMemoryIds.size,
                addedProposals = addedProposalIds.size,
                committedVersion = committedVersion
            )
        }

    override fun close() = helper.close()

    private fun insertJobIfAbsent(
        db: SQLiteDatabase,
        source: SourceMeta,
        baseMemoryVersion: Long,
        now: Long
    ): StoryMemoryJob? {
        if (db.rawQuery("SELECT 1 FROM ${StorySchema.JOBS} WHERE source_revision_id = ? AND state = 'completed' LIMIT 1",
                arrayOf(source.revisionId)).use { it.moveToFirst() }) return null
        if (sourceAttempts(db, source.revisionId) >= MAX_SOURCE_ATTEMPTS) {
            db.execSQL("UPDATE ${StorySchema.JOBS} SET state = 'failed', error = 'Retry limit reached' WHERE source_revision_id = ? AND state = 'stale'",
                arrayOf(source.revisionId))
            return null
        }
        val dedupeKey = storyOrganizerDedupeKey(source.revisionId, baseMemoryVersion)
        val candidate = StoryMemoryJob(
            storyId = source.storyId,
            timelineId = source.timelineId,
            sourceRevisionId = source.revisionId,
            kind = if (source.workspace == StoryWorkspace.Prose) ORGANIZER_JOB_KIND else DISCUSSION_JOB_KIND,
            dedupeKey = dedupeKey,
            baseMemoryVersion = baseMemoryVersion,
            state = StoryJobState.Pending,
            createdAt = now,
            updatedAt = now
        )
        db.insertWithOnConflict(
            StorySchema.JOBS,
            null,
            ContentValues().apply {
                put("id", candidate.id)
                put("story_id", candidate.storyId)
                put("timeline_id", candidate.timelineId)
                put("source_revision_id", candidate.sourceRevisionId)
                put("kind", candidate.kind)
                put("dedupe_key", candidate.dedupeKey)
                put("base_memory_version", candidate.baseMemoryVersion)
                put("state", candidate.state.dbValue)
                put("attempts", candidate.attempts)
                put("error", candidate.error)
                put("created_at", candidate.createdAt)
                put("updated_at", candidate.updatedAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        return db.query(
            StorySchema.JOBS,
            null,
            "dedupe_key = ?",
            arrayOf(dedupeKey),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toJob() else null }
    }

    private fun queryActiveCompleteSource(db: SQLiteDatabase, revisionId: String): SourceMeta? = db.rawQuery(
        """
        SELECT r.id, r.story_id, r.timeline_id, r.workspace, r.state, r.content,
               m.sequence_no, m.role
        FROM ${StorySchema.REVISIONS} r
        JOIN ${StorySchema.MESSAGES} m ON m.active_revision_id = r.id
        JOIN ${StorySchema.STORIES} s ON s.id = r.story_id AND s.current_timeline_id = r.timeline_id
        WHERE r.id = ? AND m.story_id = r.story_id AND m.timeline_id = r.timeline_id
          AND m.workspace = r.workspace AND m.id = r.message_id
        LIMIT 1
        """.trimIndent(),
        arrayOf(revisionId)
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        if (StoryRevisionState.fromDb(cursor.string("state")) != StoryRevisionState.Complete) return@use null
        if (cursor.string("role") != "assistant") return@use null
        val content = cursor.string("content")
        if (content.isBlank()) return@use null
        SourceMeta(
            revisionId = cursor.string("id"),
            storyId = cursor.string("story_id"),
            timelineId = cursor.string("timeline_id"),
            sequence = cursor.long("sequence_no"),
            workspace = StoryWorkspace.fromDb(cursor.string("workspace"))
        )
    }

    private fun queryStoryState(db: SQLiteDatabase, storyId: String): StoryState? = db.rawQuery(
        "SELECT memory_version, automatic_memory_enabled FROM ${StorySchema.STORIES} WHERE id = ? LIMIT 1",
        arrayOf(storyId)
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        StoryState(
            memoryVersion = cursor.getLong(0),
            automaticMemoryEnabled = cursor.getInt(1) != 0
        )
    }

    private fun queryJob(db: SQLiteDatabase, jobId: String): StoryMemoryJob? = db.query(
        StorySchema.JOBS,
        null,
        "id = ?",
        arrayOf(jobId),
        null,
        null,
        null,
        "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toJob() else null }

    private fun activeMemoryContentExists(
        db: SQLiteDatabase,
        storyId: String,
        timelineId: String,
        candidate: StoryOrganizerMemoryCandidate,
        subjectId: String?,
        objectId: String?
    ): Boolean = db.rawQuery(
        """SELECT 1 FROM ${StorySchema.MEMORIES} WHERE story_id = ? AND timeline_id = ? AND content = ?
           AND kind = ? AND nature = ? AND COALESCE(subject_entity_id, '') = ? AND COALESCE(object_entity_id, '') = ?
           AND (source_revision_id IS NULL OR EXISTS (SELECT 1 FROM ${StorySchema.MESSAGES} m
               JOIN ${StorySchema.REVISIONS} r ON r.id = m.active_revision_id
               WHERE r.id = ${StorySchema.MEMORIES}.source_revision_id AND r.state = 'complete')) LIMIT 1""",
        arrayOf(storyId, timelineId, candidate.content, candidate.kind.dbValue, candidate.nature.dbValue, subjectId.orEmpty(), objectId.orEmpty())
    ).use { it.moveToFirst() }

    private fun pendingProposalContentExists(
        db: SQLiteDatabase,
        storyId: String,
        timelineId: String,
        content: String
    ): Boolean = db.rawQuery(
        """SELECT 1 FROM ${StorySchema.PROPOSALS} WHERE story_id = ? AND timeline_id = ? AND content = ?
           AND (source_revision_id IS NULL OR EXISTS (SELECT 1 FROM ${StorySchema.MESSAGES} m
               JOIN ${StorySchema.REVISIONS} r ON r.id = m.active_revision_id
               WHERE r.id = ${StorySchema.PROPOSALS}.source_revision_id AND r.state = 'complete')) LIMIT 1""",
        arrayOf(storyId, timelineId, content)
    ).use { it.moveToFirst() }

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
                put("subject_entity_id", record.subjectEntityId)
                put("object_entity_id", record.objectEntityId)
                put("scope", record.scope)
                put("state_key", record.stateKey)
                put("effective_sequence", record.effectiveSequence)
                put("source_revision_id", record.sourceRevisionId)
                put("pinned", 0)
                put("active", 1)
                put("created_at", record.createdAt)
                put("updated_at", record.updatedAt)
            }
        )
    }

    private fun insertProposal(db: SQLiteDatabase, proposal: StoryProposal) {
        db.insertOrThrow(
            StorySchema.PROPOSALS,
            null,
            ContentValues().apply {
                put("id", proposal.id)
                put("story_id", proposal.storyId)
                put("timeline_id", proposal.timelineId)
                put("content", proposal.content)
                put("proposal_kind", proposal.proposalKind)
                put("source_revision_id", proposal.sourceRevisionId)
                putNull("decision_source_revision_id")
                put("state", proposal.state.dbValue)
                put("created_at", proposal.createdAt)
                put("updated_at", proposal.updatedAt)
            }
        )
    }

    private fun markJobState(db: SQLiteDatabase, jobId: String, state: StoryJobState, error: String) {
        db.update(
            StorySchema.JOBS,
            ContentValues().apply {
                put("state", state.dbValue)
                put("error", error.take(MAX_JOB_ERROR_CHARS))
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(jobId)
        )
    }

    private fun Cursor.toJob(): StoryMemoryJob = StoryMemoryJob(
        id = string("id"),
        storyId = string("story_id"),
        timelineId = string("timeline_id"),
        sourceRevisionId = string("source_revision_id"),
        kind = string("kind"),
        dedupeKey = string("dedupe_key"),
        baseMemoryVersion = long("base_memory_version"),
        state = StoryJobState.fromDb(string("state")),
        attempts = int("attempts"),
        error = string("error"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at")
    )

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

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

    private data class SourceMeta(
        val revisionId: String,
        val storyId: String,
        val timelineId: String,
        val sequence: Long,
        val workspace: StoryWorkspace
    )

    private data class StoryState(
        val memoryVersion: Long,
        val automaticMemoryEnabled: Boolean
    )

    private fun sourceAttempts(db: SQLiteDatabase, revisionId: String): Int = db.rawQuery(
        "SELECT COALESCE(SUM(attempts), 0) FROM ${StorySchema.JOBS} WHERE source_revision_id = ?",
        arrayOf(revisionId)
    ).use { it.moveToFirst(); it.getInt(0) }

    // Recover the gap between a completed reply and enqueue, including while memory was disabled.
    fun enqueueMissingSources(storyId: String, timelineId: String) {
        val revisions = helper.readableDatabase.rawQuery(
            """SELECT r.id FROM ${StorySchema.REVISIONS} r
               JOIN ${StorySchema.MESSAGES} m ON m.active_revision_id = r.id
               WHERE r.story_id = ? AND r.timeline_id = ? AND r.state = 'complete'
                 AND m.role = 'assistant' ORDER BY m.sequence_no""",
            arrayOf(storyId, timelineId)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        revisions.forEach { id ->
            // Existing failed/pending jobs must not be reset by reopening the app.
            val exists = helper.readableDatabase.rawQuery(
                "SELECT 1 FROM ${StorySchema.JOBS} WHERE source_revision_id = ? LIMIT 1", arrayOf(id)
            ).use { it.moveToFirst() }
            if (!exists) enqueueForRevision(storyId, timelineId, id)
        }
    }

    fun retryFailed(storyId: String, timelineId: String) = helper.writableDatabase.inTransaction { db ->
        val ids = db.rawQuery("SELECT DISTINCT source_revision_id FROM ${StorySchema.JOBS} WHERE story_id = ? AND timeline_id = ? AND state = 'failed'",
            arrayOf(storyId, timelineId)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        ids.forEach { id ->
            val source = queryActiveCompleteSource(db, id) ?: return@forEach
            db.execSQL("UPDATE ${StorySchema.JOBS} SET attempts = 0 WHERE source_revision_id = ?", arrayOf(id))
            val version = queryStoryState(db, storyId)?.memoryVersion ?: return@forEach
            val job = insertJobIfAbsent(db, source, version, System.currentTimeMillis()) ?: return@forEach
            if (job.state != StoryJobState.Completed) markJobState(db, job.id, StoryJobState.Pending, "User requested retry")
        }
    }

    fun jobStatus(storyId: String, timelineId: String): String = helper.readableDatabase.rawQuery(
        "SELECT state, COUNT(*) FROM ${StorySchema.JOBS} WHERE story_id = ? AND timeline_id = ? GROUP BY state",
        arrayOf(storyId, timelineId)
    ).use { c -> buildList {
        while (c.moveToNext()) {
            val label = when (c.getString(0)) {
                "pending" -> "待整理"; "running" -> "整理中"; "failed" -> "失败"
                "completed" -> "已整理"; else -> "已过期"
            }
            add("$label ${c.getInt(1)}")
        }
    }.joinToString(" · ").ifBlank { "暂无整理任务" } }

    private companion object {
        const val MAX_SOURCE_ATTEMPTS = 4
        const val DISCUSSION_JOB_KIND = "organize_discussion"
        const val ORGANIZER_JOB_KIND = "organize_prose"
        const val MAX_JOB_ERROR_CHARS = 500
    }
}
