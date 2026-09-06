package com.adong.adchat.data.story

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class StoryConflictEntry(
    val id: String,
    val conflict: StoryStateConflict,
    val memoryVersion: Long,
    val earlierSource: String,
    val latestSource: String
)

/** Caller owns one SQLite transaction. Materialization never changes canonical memory or its version. */
internal object StoryConflicts {
    fun refresh(db: SQLiteDatabase, storyId: String, timelineId: String): List<StoryConflictEntry> {
        val version = db.rawQuery("SELECT memory_version FROM ${StorySchema.STORIES} WHERE id = ?",
            arrayOf(storyId)).use { if (!it.moveToFirst()) return emptyList(); it.getLong(0) }
        val current = StoryStateProjection.project(records(db, storyId, timelineId)).conflicts
        val pendingIds = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        val result = current.map { conflict ->
            val earlier = snapshot(conflict.earlier)
            val latest = snapshot(conflict.latest)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest((earlier + "\n" + latest).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val existing = db.rawQuery("SELECT id,state FROM ${StorySchema.CONFLICTS} WHERE story_id = ? AND timeline_id = ? AND fingerprint = ?",
                arrayOf(storyId, timelineId, fingerprint)).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
            val id = existing?.first ?: "conflict_${UUID.randomUUID()}"
            if (existing == null) db.insertOrThrow(StorySchema.CONFLICTS, null, ContentValues().apply {
                put("id", id); put("story_id", storyId); put("timeline_id", timelineId)
                put("earlier_record_id", conflict.earlier.id); put("latest_record_id", conflict.latest.id)
                put("source_revision_id", conflict.latest.sourceRevisionId ?: conflict.earlier.sourceRevisionId)
                put("fingerprint", fingerprint); put("earlier_json", earlier); put("latest_json", latest)
                put("state", "pending"); put("created_version", version); put("created_at", now); put("updated_at", now)
            }) else if (existing.second != "pending") {
                // Source restoration or an explicit undo can make the same conflict relevant again.
                db.update(StorySchema.CONFLICTS, ContentValues().apply { put("state", "pending"); put("updated_at", now) },
                    "id = ?", arrayOf(id))
            }
            pendingIds += id
            StoryConflictEntry(id, conflict, version, sourceText(db, conflict.earlier.sourceRevisionId), sourceText(db, conflict.latest.sourceRevisionId))
        }
        val pending = db.rawQuery("SELECT id FROM ${StorySchema.CONFLICTS} WHERE story_id = ? AND timeline_id = ? AND state = 'pending'",
            arrayOf(storyId, timelineId)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        pending.filterNot { it in pendingIds }.forEach { id ->
            db.update(StorySchema.CONFLICTS, ContentValues().apply { put("state", "superseded"); put("updated_at", now) },
                "id = ? AND state = 'pending'", arrayOf(id))
        }
        return result
    }

    fun resolve(db: SQLiteDatabase, storyId: String, timelineId: String, id: String, expectedVersion: Long, acceptNew: Boolean): Boolean {
        val version = db.rawQuery("SELECT memory_version FROM ${StorySchema.STORIES} WHERE id = ? AND current_timeline_id = ?",
            arrayOf(storyId, timelineId)).use {
            require(it.moveToFirst()) { "故事路线已变化，请重新打开档案" }; it.getLong(0)
        }
        // Repeated delivery is harmless; no mutation or extra version is created.
        val state = db.rawQuery("SELECT state FROM ${StorySchema.CONFLICTS} WHERE id = ? AND story_id = ? AND timeline_id = ?",
            arrayOf(id, storyId, timelineId)).use { if (it.moveToFirst()) it.getString(0) else null }
        if (state != "pending") return false
        require(version == expectedVersion) { "资料已有变化，请刷新档案后重新决定" }
        val entry = refresh(db, storyId, timelineId).firstOrNull { it.id == id }
            ?: error("这项冲突的来源或内容已变化，请刷新档案")
        val earlier = entry.conflict.earlier; val latest = entry.conflict.latest
        val anchor = latest.sourceRevisionId ?: earlier.sourceRevisionId
            ?: error("此冲突没有正文来源，请使用手动资料编辑")
        val winner = if (acceptNew) latest else earlier
        val loser = if (acceptNew) earlier else latest
        val now = System.currentTimeMillis()
        val updates = JSONArray()
        fun change(table: String, recordId: String, field: String, before: String, after: String, source: String?) {
            check(db.update(table, ContentValues().apply {
                if (field == "state") put(field, after) else put(field, after.toInt())
                put("updated_at", now)
            }, "id = ? AND story_id = ? AND timeline_id = ? AND $field = ?",
                arrayOf(recordId, storyId, timelineId, before)) == 1) { "关联资料已变化，未处理冲突" }
            updates.put(JSONObject().put("table", table).put("id", recordId).put("field", field)
                .put("before", before).put("after", after).put("source", source ?: JSONObject.NULL))
        }
        change(StorySchema.MEMORIES, loser.id, "active", "1", "0", loser.sourceRevisionId)
        if (loser.pinned && !winner.pinned) change(StorySchema.MEMORIES, winner.id, "pinned", "0", "1", winner.sourceRevisionId)
        change(StorySchema.CONFLICTS, id, "state", "pending", if (acceptNew) "accepted" else "rejected", anchor)
        val next = Math.addExact(version, 1L)
        check(db.update(StorySchema.STORIES, ContentValues().apply { put("memory_version", next); put("updated_at", now) },
            "id = ? AND memory_version = ?", arrayOf(storyId, version.toString())) == 1)
        db.insertOrThrow(StorySchema.CHANGE_SETS, null, ContentValues().apply {
            put("id", newChangeSetId()); put("story_id", storyId); put("timeline_id", timelineId)
            put("source_revision_id", anchor); put("base_memory_version", version); put("committed_version", next)
            put("status", "committed"); put("created_at", now); put("updated_at", now)
            put("conflicts_json", JSONArray().put(id).toString())
            put("operations_json", JSONObject().put("operation", "resolve_state_conflict").put("actor", "user_ui")
                .put("conflict_id", id).put("accept_new", acceptNew).put("updates", updates)
                .put("before_description", entry.conflict.description).put("after_description", "选择「${winner.content}」；另一记录已停用，原固定约束保留")
                .put("earlier_source", earlier.sourceRevisionId ?: JSONObject.NULL)
                .put("latest_source", latest.sourceRevisionId ?: JSONObject.NULL).toString())
        })
        refresh(db, storyId, timelineId)
        return true
    }

    private fun snapshot(record: StoryMemoryRecord): String = JSONObject().apply {
        put("id", record.id); put("content", record.content); put("nature", record.nature.dbValue)
        put("subject", record.subjectEntityId); put("state_key", record.stateKey)
        put("source", record.sourceRevisionId ?: JSONObject.NULL); put("sequence", record.effectiveSequence)
        put("pinned", record.pinned); put("active", record.active)
    }.toString()

    private fun sourceText(db: SQLiteDatabase, id: String?): String = if (id == null) "用户独立资料" else db.rawQuery(
        "SELECT content FROM ${StorySchema.REVISIONS} WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else "来源已不存在" }

    private fun records(db: SQLiteDatabase, storyId: String, timelineId: String): List<StoryMemoryRecord> = db.rawQuery(
        """SELECT f.*, e.canonical_name AS owner_name FROM ${StorySchema.MEMORIES} f
            LEFT JOIN ${StorySchema.ENTITIES} e ON e.id = f.subject_entity_id
            WHERE f.story_id = ? AND f.timeline_id = ? AND f.active = 1 AND f.kind = 'current_state'
            AND (f.source_revision_id IS NULL OR EXISTS (SELECT 1 FROM ${StorySchema.MESSAGES} m
                JOIN ${StorySchema.REVISIONS} r ON r.id = m.active_revision_id WHERE r.id = f.source_revision_id AND r.state = 'complete'))""",
        arrayOf(storyId, timelineId)).use { c ->
        fun string(key: String): String? = c.getString(c.getColumnIndexOrThrow(key))
        fun long(key: String): Long = c.getLong(c.getColumnIndexOrThrow(key))
        buildList { while (c.moveToNext()) add(StoryMemoryRecord(
            id = string("id")!!, storyId = storyId, timelineId = timelineId, kind = StoryMemoryKind.CurrentState,
            content = string("content")!!, nature = StoryMemoryNature.fromDb(string("nature")!!),
            subjectEntityId = string("subject_entity_id"), stateKey = string("state_key"),
            sourceRevisionId = string("source_revision_id"), effectiveSequence = long("effective_sequence"),
            pinned = long("pinned") != 0L, active = true, createdAt = long("created_at"), updatedAt = long("updated_at"),
            subjectEntityNames = listOfNotNull(string("owner_name"))
        )) }
    }
}
