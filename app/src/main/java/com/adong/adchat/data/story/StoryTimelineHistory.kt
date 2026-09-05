package com.adong.adchat.data.story

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Materialized checkpoints; original timelines and their audit history are never rewritten. */
internal object StoryTimelineHistory {
    fun captureBoundary(db: SQLiteDatabase, storyId: String, timelineId: String, messageId: String, sequence: Long) {
        val args = arrayOf(storyId, timelineId)
        val snapshot = JSONObject().put("format", 1)
            .put("messages", rows(db, "SELECT * FROM ${StorySchema.MESSAGES} WHERE story_id = ? AND timeline_id = ? ORDER BY sequence_no", args))
            .put("revisions", rows(db, """SELECT r.id, r.state FROM ${StorySchema.REVISIONS} r JOIN ${StorySchema.MESSAGES} m
                ON m.active_revision_id = r.id WHERE m.story_id = ? AND m.timeline_id = ?""", args))
            .put("memories", rows(db, "SELECT * FROM ${StorySchema.MEMORIES} WHERE story_id = ? AND timeline_id = ?", args))
            .put("proposals", rows(db, "SELECT * FROM ${StorySchema.PROPOSALS} WHERE story_id = ? AND timeline_id = ?", args))
            .put("completed", rows(db, "SELECT source_revision_id FROM ${StorySchema.JOBS} WHERE story_id = ? AND timeline_id = ? AND state = 'completed'", args))
            .put("entities", rows(db, "SELECT * FROM ${StorySchema.ENTITIES} WHERE story_id = ?", arrayOf(storyId)))
        saveSnapshot(db, storyId, timelineId, sequence, "boundary:$messageId", snapshot)
    }

    fun fork(db: SQLiteDatabase, messageId: String, expectedRevisionId: String, content: String): String {
        require(content.isNotBlank()) { "修订正文不能为空" }
        val message = rows(db, "SELECT * FROM ${StorySchema.MESSAGES} WHERE id = ?", arrayOf(messageId)).objects().single()
        val storyId = message.getString("story_id")
        val oldTimeline = message.getString("timeline_id")
        requireCurrentAndIdle(db, storyId, oldTimeline)
        require(message.getString("active_revision_id") == expectedRevisionId) { "正文版本已变化，请重新打开" }
        require(message.getString("workspace") == "prose" && message.getString("role") == "assistant") { "只能从正文回复修订" }
        val boundary = rows(db, """SELECT snapshot_json FROM ${StorySchema.SNAPSHOTS}
            WHERE story_id = ? AND timeline_id = ? AND log_cursor = ? ORDER BY rowid DESC LIMIT 1""",
            arrayOf(storyId, oldTimeline, "boundary:$messageId")).objects().firstOrNull()
        require(boundary != null) { "这段旧正文没有生成前快照，暂不能安全重写较早章节" }
        val snapshot = JSONObject(boundary.getString("snapshot_json"))
        require(snapshot.getInt("format") == 1)
        val prefix = snapshot.getJSONArray("messages").objects()
        // Never replay a checkpoint against a different prefix after another revision change.
        prefix.forEach { previous ->
            val active = rows(db, "SELECT active_revision_id FROM ${StorySchema.MESSAGES} WHERE id = ? AND timeline_id = ?",
                arrayOf(previous.getString("id"), oldTimeline)).objects().singleOrNull()
            require(active?.getString("active_revision_id") == previous.getString("active_revision_id")) { "前文已变化，原快照不再适用" }
        }
        require(prefix.all { it.getLong("sequence_no") < message.getLong("sequence_no") })
        val timelineId = newTimelineId()
        val now = System.currentTimeMillis()
        insert(db, StorySchema.TIMELINES, JSONObject().put("id", timelineId).put("story_id", storyId)
            .put("parent_timeline_id", oldTimeline).put("fork_revision_id", expectedRevisionId).put("created_at", now))
        val messageIds = prefix.associate { it.getString("id") to newMessageId() }
        val revisionRefs = snapshot.getJSONArray("revisions").objects()
        require(revisionRefs.none { it.getString("state") == "streaming" }) { "该快照含未完成的并行生成，暂不能安全恢复" }
        val revisions = revisionRefs.map { ref -> rows(db, "SELECT * FROM ${StorySchema.REVISIONS} WHERE id = ?",
            arrayOf(ref.getString("id"))).objects().single() }
        val revisionIds = revisions.associate { it.getString("id") to newRevisionId() }
        prefix.forEach { row ->
            val copy = JSONObject(row.toString()).put("id", messageIds.getValue(row.getString("id")))
                .put("timeline_id", timelineId).put("active_revision_id", revisionIds.getValue(row.getString("active_revision_id")))
            insert(db, StorySchema.MESSAGES, copy)
        }
        revisions.forEach { row -> insert(db, StorySchema.REVISIONS, JSONObject(row.toString())
            .put("id", revisionIds.getValue(row.getString("id"))).put("timeline_id", timelineId)
            .put("message_id", messageIds.getValue(row.getString("message_id")))) }
        val entityIds = mutableMapOf<String, String>()
        snapshot.getJSONArray("entities").objects().forEach { row ->
            val id = newEntityId(); entityIds[row.getString("id")] = id
            insert(db, StorySchema.ENTITIES, JSONObject(row.toString()).put("id", id))
        }
        val completeIds = revisions.filter { it.getString("state") == "complete" }.map { it.getString("id") }.toSet()
        snapshot.getJSONArray("memories").objects().forEach { row ->
            val source = row.nullableString("source_revision_id")
            if (source == null || source in completeIds) {
                val copy = JSONObject(row.toString()).put("id", newMemoryId()).put("timeline_id", timelineId)
                    .put("source_revision_id", source?.let(revisionIds::get) ?: JSONObject.NULL)
                listOf("subject_entity_id", "object_entity_id").forEach { field ->
                    copy.put(field, row.nullableString(field)?.let(entityIds::get) ?: JSONObject.NULL)
                }
                insert(db, StorySchema.MEMORIES, copy)
            }
        }
        snapshot.getJSONArray("proposals").objects().forEach { row ->
            val source = row.getString("source_revision_id")
            if (source in completeIds) insert(db, StorySchema.PROPOSALS, JSONObject(row.toString())
                .put("id", newProposalId()).put("timeline_id", timelineId).put("source_revision_id", revisionIds.getValue(source))
                .put("decision_source_revision_id", row.nullableString("decision_source_revision_id")?.let(revisionIds::get) ?: JSONObject.NULL))
        }
        snapshot.getJSONArray("completed").objects().map { it.getString("source_revision_id") }.distinct().forEach { source ->
            revisionIds[source]?.let { id ->
                val workspace = revisions.first { it.getString("id") == source }.getString("workspace")
                insert(db, StorySchema.JOBS, JSONObject().put("id", newJobId()).put("story_id", storyId)
                    .put("timeline_id", timelineId).put("source_revision_id", id)
                    .put("kind", if (workspace == "prose") "organize_prose" else "organize_discussion")
                    .put("dedupe_key", "inherited:$id").put("base_memory_version", version(db, storyId))
                    .put("state", "completed").put("attempts", 0).put("error", "")
                    .put("created_at", now).put("updated_at", now))
            }
        }
        val newMessage = newMessageId()
        val newRevision = newRevisionId()
        captureBoundary(db, storyId, timelineId, newMessage, message.getLong("sequence_no"))
        insert(db, StorySchema.MESSAGES, JSONObject(message.toString()).put("id", newMessage)
            .put("timeline_id", timelineId).put("active_revision_id", newRevision).put("created_at", now))
        val sourceRow = rows(db, "SELECT * FROM ${StorySchema.REVISIONS} WHERE id = ?", arrayOf(expectedRevisionId)).objects().single()
        insert(db, StorySchema.REVISIONS, JSONObject(sourceRow.toString()).put("id", newRevision).put("message_id", newMessage)
            .put("timeline_id", timelineId).put("content", content.trim()).put("state", "complete")
            .put("created_at", now).put("completed_at", now))
        switch(db, storyId, timelineId, oldTimeline)
        return timelineId
    }

    fun switch(db: SQLiteDatabase, storyId: String, target: String, expected: String): Boolean {
        requireCurrentAndIdle(db, storyId, expected)
        if (target == expected) return false
        require(rows(db, "SELECT id FROM ${StorySchema.TIMELINES} WHERE id = ? AND story_id = ?", arrayOf(target, storyId)).length() == 1)
        // Workspace snapshots are separate from historical memory checkpoints.
        saveSnapshot(db, storyId, expected, 0, "workspace", JSONObject().put("states",
            rows(db, "SELECT * FROM ${StorySchema.WORKSPACE_STATE} WHERE story_id = ?", arrayOf(storyId))))
        val saved = rows(db, "SELECT snapshot_json FROM ${StorySchema.SNAPSHOTS} WHERE story_id = ? AND timeline_id = ? AND log_cursor = 'workspace' ORDER BY rowid DESC LIMIT 1",
            arrayOf(storyId, target)).objects().firstOrNull()?.let { JSONObject(it.getString("snapshot_json")).getJSONArray("states").objects() }.orEmpty()
        val now = System.currentTimeMillis()
        val previousStates = rows(db, "SELECT * FROM ${StorySchema.WORKSPACE_STATE} WHERE story_id = ?", arrayOf(storyId)).objects()
        previousStates.forEach { previous ->
            val restored = saved.firstOrNull { it.getString("workspace") == previous.getString("workspace") }
            val values = ContentValues().apply {
                put("draft", restored?.getString("draft") ?: "")
                put("first_visible_index", restored?.getInt("first_visible_index") ?: 0)
                put("first_visible_offset", restored?.getInt("first_visible_offset") ?: 0)
                put("updated_at", nextStoryWorkspaceUpdatedAt(previous.getLong("updated_at"), now))
            }
            db.update(StorySchema.WORKSPACE_STATE, values, "story_id = ? AND workspace = ?", arrayOf(storyId, previous.getString("workspace")))
        }
        val base = version(db, storyId)
        check(db.update(StorySchema.STORIES, ContentValues().apply {
            put("current_timeline_id", target); put("memory_version", Math.addExact(base, 1L)); put("updated_at", now)
        }, "id = ? AND current_timeline_id = ? AND memory_version = ?", arrayOf(storyId, expected, base.toString())) == 1)
        saveSnapshot(db, storyId, target, 0, "timeline_switch", JSONObject().put("from", expected).put("to", target).put("base_memory_version", base))
        return true
    }

    private fun requireCurrentAndIdle(db: SQLiteDatabase, storyId: String, timelineId: String) {
        require(rows(db, "SELECT 1 FROM ${StorySchema.STORIES} WHERE id = ? AND current_timeline_id = ?", arrayOf(storyId, timelineId)).length() == 1) { "故事路线已变化" }
        require(rows(db, """SELECT 1 FROM ${StorySchema.REVISIONS} r JOIN ${StorySchema.MESSAGES} m ON m.active_revision_id = r.id
            WHERE m.story_id = ? AND r.state = 'streaming' LIMIT 1""", arrayOf(storyId)).length() == 0) { "请先等待生成结束" }
    }

    private fun version(db: SQLiteDatabase, storyId: String): Long = db.rawQuery(
        "SELECT memory_version FROM ${StorySchema.STORIES} WHERE id = ?", arrayOf(storyId)
    ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun saveSnapshot(db: SQLiteDatabase, storyId: String, timelineId: String, sequence: Long, kind: String, value: JSONObject) {
        insert(db, StorySchema.SNAPSHOTS, JSONObject().put("id", "snapshot_${UUID.randomUUID()}")
            .put("story_id", storyId).put("timeline_id", timelineId).put("sequence_no", sequence)
            .put("memory_version", version(db, storyId)).put("snapshot_json", value.toString())
            .put("log_cursor", kind).put("created_at", System.currentTimeMillis()))
    }

    private fun rows(db: SQLiteDatabase, sql: String, args: Array<String>): JSONArray = db.rawQuery(sql, args).use { cursor ->
        JSONArray().apply { while (cursor.moveToNext()) put(JSONObject().apply {
            cursor.columnNames.forEachIndexed { index, name ->
                put(name, if (cursor.isNull(index)) JSONObject.NULL else if (cursor.getType(index) == android.database.Cursor.FIELD_TYPE_INTEGER) cursor.getLong(index) else cursor.getString(index))
            }
        }) }
    }
    private fun insert(db: SQLiteDatabase, table: String, row: JSONObject) {
        val values = ContentValues()
        row.keys().forEach { key ->
            val value = row.get(key)
            when { value == JSONObject.NULL -> values.putNull(key); value is Number -> values.put(key, value.toLong()); else -> values.put(key, value.toString()) }
        }
        db.insertOrThrow(table, null, values)
    }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
}
