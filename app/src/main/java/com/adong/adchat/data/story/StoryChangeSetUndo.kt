package com.adong.adchat.data.story

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Only reverses an unchanged latest batch. Caller owns the transaction. */
internal object StoryChangeSetUndo {
    fun undo(db: SQLiteDatabase, storyId: String, timelineId: String, changeId: String): Boolean {
        val currentVersion = db.rawQuery("SELECT memory_version FROM ${StorySchema.STORIES} WHERE id = ? AND current_timeline_id = ?",
            arrayOf(storyId, timelineId)).use { cursor ->
            require(cursor.moveToFirst()) { "故事路线已变化，请重新打开档案" }; cursor.getLong(0)
        }
        if (wasUndone(db, storyId, timelineId, changeId)) return false
        val batch = db.rawQuery("SELECT * FROM ${StorySchema.CHANGE_SETS} WHERE id = ? AND story_id = ? AND timeline_id = ? AND status = 'committed'",
            arrayOf(changeId, storyId, timelineId)).use { cursor ->
            if (!cursor.moveToFirst()) null else Triple(cursor.getString(cursor.getColumnIndexOrThrow("source_revision_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("committed_version")), JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("operations_json"))))
        } ?: return false
        require(currentVersion == batch.second) { "已有后续记忆变更，不能直接撤销这一批；请先处理最新变更" }
        require(sourceActive(db, storyId, timelineId, batch.first)) { "来源已不属于当前正文版本，不能恢复该批资料" }
        val updates = inverseUpdates(batch.third)
        require(updates.length() > 0) { "这条记录没有可整体撤销的资料或候选" }
        val now = System.currentTimeMillis()
        val seen = mutableSetOf<String>()
        for (i in 0 until updates.length()) {
            val update = updates.getJSONObject(i)
            val table = update.getString("table")
            require(table in setOf(StorySchema.MEMORIES, StorySchema.PROPOSALS, StorySchema.CONFLICTS))
            val field = update.optString("field", if (table == StorySchema.MEMORIES) "active" else "state")
            require(if (table == StorySchema.MEMORIES) field in setOf("active", "pinned") else field == "state")
            val id = update.getString("id")
            require(seen.add("$table:$id:$field"))
            val before = update.getString("before")
            val after = update.getString("after")
            val allowed = if (field != "state") setOf("0", "1") else setOf("pending", "accepted", "rejected", "superseded")
            require(before in allowed && after in allowed)
            val values = ContentValues().apply {
                if (field != "state") put(field, after.toInt()) else put(field, after)
                put("updated_at", now)
            }
            val source = if (update.has("source")) {
                if (update.isNull("source")) null else update.getString("source")
            } else batch.first
            require(source == null || sourceActive(db, storyId, timelineId, source)) { "关联的另一段正文来源已变化" }
            check(db.update(table, values,
                "id = ? AND story_id = ? AND timeline_id = ? AND COALESCE(source_revision_id, '') = ? AND $field = ?",
                arrayOf(id, storyId, timelineId, source.orEmpty(), before)) == 1) { "关联资料或候选已变化，整批撤销未执行" }
        }
        val nextVersion = Math.addExact(currentVersion, 1L)
        check(db.update(StorySchema.STORIES, ContentValues().apply {
            put("memory_version", nextVersion); put("updated_at", now)
        }, "id = ? AND memory_version = ?", arrayOf(storyId, currentVersion.toString())) == 1)
        val inverseId = newChangeSetId()
        db.insertOrThrow(StorySchema.CHANGE_SETS, null, ContentValues().apply {
            put("id", inverseId); put("story_id", storyId); put("timeline_id", timelineId)
            put("source_revision_id", batch.first); put("base_memory_version", currentVersion); put("committed_version", nextVersion)
            put("status", "committed"); put("created_at", now); put("updated_at", now); put("conflicts_json", "[]")
            put("operations_json", JSONObject().put("operation", "reverse_change_set").put("actor", "user_ui")
                .put("reverses", changeId).put("updates", updates).toString())
        })
        db.insertOrThrow(StorySchema.SNAPSHOTS, null, ContentValues().apply {
            put("id", "undo_set_${UUID.randomUUID()}"); put("story_id", storyId); put("timeline_id", timelineId)
            put("sequence_no", 0); put("memory_version", nextVersion); put("created_at", now)
            put("log_cursor", "undo_set:$changeId")
            put("snapshot_json", JSONObject().put("operation", "undo_change_set").put("change_id", changeId)
                .put("inverse_change_id", inverseId).put("updates", updates).toString())
        })
        StoryConflicts.refresh(db, storyId, timelineId)
        // Completed organizer jobs remain completed: undo must not automatically recreate the batch.
        return true
    }

    fun canUndo(db: SQLiteDatabase, storyId: String, timelineId: String, id: String, version: Long, source: String, operations: JSONObject): Boolean =
        !wasUndone(db, storyId, timelineId, id) && inverseUpdates(operations).length() > 0 &&
            db.rawQuery("SELECT 1 FROM ${StorySchema.STORIES} WHERE id = ? AND current_timeline_id = ? AND memory_version = ?",
                arrayOf(storyId, timelineId, version.toString())).use { it.moveToFirst() } && sourceActive(db, storyId, timelineId, source)

    fun wasUndone(db: SQLiteDatabase, storyId: String, timelineId: String, id: String): Boolean = db.rawQuery(
        "SELECT 1 FROM ${StorySchema.SNAPSHOTS} WHERE story_id = ? AND timeline_id = ? AND log_cursor = ? LIMIT 1",
        arrayOf(storyId, timelineId, "undo_set:$id")
    ).use { it.moveToFirst() }

    private fun sourceActive(db: SQLiteDatabase, storyId: String, timelineId: String, id: String): Boolean = db.rawQuery(
        """SELECT 1 FROM ${StorySchema.REVISIONS} r JOIN ${StorySchema.MESSAGES} m ON m.active_revision_id = r.id
            WHERE r.id = ? AND r.state = 'complete' AND m.story_id = ? AND m.timeline_id = ?""",
        arrayOf(id, storyId, timelineId)
    ).use { it.moveToFirst() }

    private fun inverseUpdates(op: JSONObject): JSONArray = JSONArray().apply {
        fun add(table: String, id: String, before: String, after: String) {
            put(JSONObject().put("table", table).put("id", id).put("before", before).put("after", after))
        }
        when {
            op.optString("operation") in setOf("reverse_change_set", "resolve_state_conflict") -> {
                val updates = op.getJSONArray("updates")
                for (i in 0 until updates.length()) {
                    val row = updates.getJSONObject(i)
                    put(JSONObject(row.toString()).put("before", row.getString("after")).put("after", row.getString("before")))
                }
            }
            op.has("proposal_id") -> {
                val state = op.getString("after")
                require(state == "accepted" || state == "rejected")
                add(StorySchema.PROPOSALS, op.getString("proposal_id"), state, "pending")
                if (state == "accepted") add(StorySchema.MEMORIES, op.getString("record_id"), "1", "0")
            }
            op.has("added_memory_ids") && op.has("proposal_ids") -> {
                val memories = op.getJSONArray("added_memory_ids")
                val proposals = op.getJSONArray("proposal_ids")
                for (i in 0 until memories.length()) add(StorySchema.MEMORIES, memories.getString(i), "1", "0")
                for (i in 0 until proposals.length()) add(StorySchema.PROPOSALS, proposals.getString(i), "pending", "superseded")
            }
        }
    }
}
