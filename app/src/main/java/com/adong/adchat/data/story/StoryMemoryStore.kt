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
        "state = ? AND kind = ?",
        arrayOf(StoryJobState.Running.dbValue, ORGANIZER_JOB_KIND)
    )

    fun enqueueForRevision(storyId: String, timelineId: String, sourceRevisionId: String): StoryMemoryJob? =
        helper.writableDatabase.inTransaction { db ->
            val source = queryActiveCompleteProseSource(db, sourceRevisionId)
                ?.takeIf { it.storyId == storyId && it.timelineId == timelineId }
                ?: return@inTransaction null
            val storyState = queryStoryState(db, storyId) ?: return@inTransaction null
            if (!storyState.automaticMemoryEnabled) return@inTransaction null
            insertJobIfAbsent(db, source, storyState.memoryVersion, System.currentTimeMillis())
        }

    fun nextPendingJob(storyId: String, timelineId: String): StoryMemoryJob? = helper.readableDatabase.query(
        StorySchema.JOBS,
        null,
        "story_id = ? AND timeline_id = ? AND kind = ? AND state = ?",
        arrayOf(storyId, timelineId, ORGANIZER_JOB_KIND, StoryJobState.Pending.dbValue),
        null,
        null,
        "created_at ASC",
        "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toJob() else null }

    fun markRunning(job: StoryMemoryJob): StoryMemoryJob? = helper.writableDatabase.inTransaction { db ->
        val current = queryJob(db, job.id) ?: return@inTransaction null
        if (current.state != StoryJobState.Pending) return@inTransaction null
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
            val source = queryActiveCompleteProseSource(db, job.sourceRevisionId)
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
            source?.let { insertJobIfAbsent(db, it, newBaseVersion, System.currentTimeMillis()) }
        }

    fun applyOrganizerOutput(job: StoryMemoryJob, output: StoryOrganizerOutput): StoryMemoryApplyResult =
        helper.writableDatabase.inTransaction { db ->
            val persistedJob = queryJob(db, job.id) ?: return@inTransaction StoryMemoryApplyResult.StaleSource
            if (persistedJob.state != StoryJobState.Running) return@inTransaction StoryMemoryApplyResult.StaleSource

            val source = queryActiveCompleteProseSource(db, persistedJob.sourceRevisionId)
            if (source == null) {
                markJobState(db, persistedJob.id, StoryJobState.Stale, "Source revision is no longer active complete prose")
                return@inTransaction StoryMemoryApplyResult.StaleSource
            }

            val storyState = queryStoryState(db, persistedJob.storyId)
                ?: return@inTransaction StoryMemoryApplyResult.StaleSource
            if (storyState.memoryVersion != persistedJob.baseMemoryVersion) {
                markJobState(db, persistedJob.id, StoryJobState.Stale, "memoryVersion changed before organizer commit")
                val requeued = insertJobIfAbsent(db, source, storyState.memoryVersion, System.currentTimeMillis())
                return@inTransaction StoryMemoryApplyResult.Requeued(requeued?.id)
            }

            val memoryCandidates = output.memories.filterNot { candidate ->
                activeMemoryContentExists(db, persistedJob.storyId, persistedJob.timelineId, candidate.content)
            }
            val proposalCandidates = output.proposals.filterNot { candidate ->
                pendingProposalContentExists(db, persistedJob.storyId, persistedJob.timelineId, candidate.content)
            }

            val now = System.currentTimeMillis()
            val committedVersion = if (memoryCandidates.isNotEmpty()) {
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
            memoryCandidates.forEach { candidate ->
                val record = StoryMemoryRecord(
                    storyId = persistedJob.storyId,
                    timelineId = persistedJob.timelineId,
                    kind = candidate.kind,
                    content = candidate.content,
                    nature = StoryMemoryNature.ProseOccurred,
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
        val dedupeKey = storyOrganizerDedupeKey(source.revisionId, baseMemoryVersion)
        val candidate = StoryMemoryJob(
            storyId = source.storyId,
            timelineId = source.timelineId,
            sourceRevisionId = source.revisionId,
            kind = ORGANIZER_JOB_KIND,
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

    private fun queryActiveCompleteProseSource(db: SQLiteDatabase, revisionId: String): SourceMeta? = db.rawQuery(
        """
        SELECT r.id, r.story_id, r.timeline_id, r.workspace, r.state, r.content,
               m.sequence_no, m.role
        FROM ${StorySchema.REVISIONS} r
        JOIN ${StorySchema.MESSAGES} m ON m.active_revision_id = r.id
        WHERE r.id = ?
        LIMIT 1
        """.trimIndent(),
        arrayOf(revisionId)
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        if (StoryWorkspace.fromDb(cursor.string("workspace")) != StoryWorkspace.Prose) return@use null
        if (StoryRevisionState.fromDb(cursor.string("state")) != StoryRevisionState.Complete) return@use null
        if (cursor.string("role") != "assistant") return@use null
        val content = cursor.string("content")
        if (content.isBlank()) return@use null
        SourceMeta(
            revisionId = cursor.string("id"),
            storyId = cursor.string("story_id"),
            timelineId = cursor.string("timeline_id"),
            sequence = cursor.long("sequence_no")
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
        content: String
    ): Boolean = db.rawQuery(
        "SELECT 1 FROM ${StorySchema.MEMORIES} WHERE story_id = ? AND timeline_id = ? AND active = 1 AND content = ? LIMIT 1",
        arrayOf(storyId, timelineId, content)
    ).use { it.moveToFirst() }

    private fun pendingProposalContentExists(
        db: SQLiteDatabase,
        storyId: String,
        timelineId: String,
        content: String
    ): Boolean = db.rawQuery(
        "SELECT 1 FROM ${StorySchema.PROPOSALS} WHERE story_id = ? AND timeline_id = ? AND state = ? AND content = ? LIMIT 1",
        arrayOf(storyId, timelineId, StoryProposalState.Pending.dbValue, content)
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
                putNull("subject_entity_id")
                putNull("object_entity_id")
                put("scope", record.scope)
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
        val sequence: Long
    )

    private data class StoryState(
        val memoryVersion: Long,
        val automaticMemoryEnabled: Boolean
    )

    private companion object {
        const val ORGANIZER_JOB_KIND = "organize_prose"
        const val MAX_JOB_ERROR_CHARS = 500
    }
}
