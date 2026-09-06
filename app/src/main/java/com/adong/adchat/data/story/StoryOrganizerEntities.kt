package com.adong.adchat.data.story

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

/** Conservative exact-name/known-alias resolution; never infers an identity or edits an entity. */
internal class StoryOrganizerEntities(
    private val db: SQLiteDatabase,
    private val storyId: String,
    timelineId: String
) {
    private val matches = mutableMapOf<String, MutableSet<String>>()

    init {
        db.rawQuery("""SELECT DISTINCT e.id, e.canonical_name, e.aliases_json
            FROM ${StorySchema.ENTITIES} e JOIN ${StorySchema.MEMORIES} f
            ON (f.subject_entity_id = e.id OR f.object_entity_id = e.id)
            WHERE e.story_id = ? AND f.story_id = ? AND f.timeline_id = ? AND e.active = 1
            AND (f.source_revision_id IS NULL OR EXISTS (
                SELECT 1 FROM ${StorySchema.MESSAGES} m JOIN ${StorySchema.REVISIONS} r ON r.id = m.active_revision_id
                WHERE r.id = f.source_revision_id AND r.state = 'complete'))""",
            arrayOf(storyId, storyId, timelineId)).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                remember(cursor.getString(1), id)
                val aliases = JSONArray(cursor.getString(2))
                for (index in 0 until aliases.length()) remember(aliases.getString(index), id)
            }
        }
    }

    private fun remember(name: String, id: String) {
        matches.getOrPut(name.trim()) { mutableSetOf() }.add(id)
    }

    fun resolve(name: String): String {
        val key = name.trim()
        require(key.isNotEmpty() && key.length <= 120)
        val ids = matches[key].orEmpty()
        require(ids.size <= 1) { "人物名称「$key」对应多个身份，整理已暂停，需先明确身份。" }
        ids.singleOrNull()?.let { return it }
        val id = newEntityId()
        val now = System.currentTimeMillis()
        db.insertOrThrow(StorySchema.ENTITIES, null, ContentValues().apply {
            put("id", id); put("story_id", storyId); put("kind", StoryEntityKind.Character.dbValue)
            put("canonical_name", key); put("aliases_json", "[]"); put("active", 1)
            put("created_at", now); put("updated_at", now)
        })
        remember(key, id)
        return id
    }
}
