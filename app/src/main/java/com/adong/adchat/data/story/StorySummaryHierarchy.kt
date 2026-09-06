package com.adong.adchat.data.story

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Inputs are flattened transitively: changing any ancestor invalidates every derived summary. */
internal object StorySummaryHierarchy {
    fun validInputs(alias: String) = """NOT EXISTS (SELECT 1 FROM ${StorySchema.SUMMARY_INPUTS} si
        WHERE si.record_id=$alias.id AND NOT EXISTS (SELECT 1 FROM ${StorySchema.MEMORIES} child
            WHERE child.id=si.input_record_id AND child.story_id=$alias.story_id AND child.timeline_id=$alias.timeline_id
              AND child.active=1 AND child.pinned=0 AND child.content=si.input_content))"""

    fun replacedInputs(db: SQLiteDatabase, story: String, timeline: String, includeInactive: Boolean): Set<String> =
        db.rawQuery("""SELECT si.input_record_id FROM ${StorySchema.SUMMARY_INPUTS} si
            JOIN ${StorySchema.MEMORIES} f ON f.id=si.record_id
            WHERE f.story_id=? AND f.timeline_id=? ${if (includeInactive) "" else "AND f.active=1"}
              AND ${StorySummaries.validDependencies("f")}""", arrayOf(story,timeline)).use { c ->
            buildSet { while(c.moveToNext()) add(c.getString(0)) }
        }

    private fun inputs(db: SQLiteDatabase, id: String): Map<String,String> = db.rawQuery(
        "SELECT input_record_id,input_content FROM ${StorySchema.SUMMARY_INPUTS} WHERE record_id=?", arrayOf(id)
    ).use { c -> buildMap { while(c.moveToNext()) put(c.getString(0),c.getString(1)) } }

    private fun sources(db: SQLiteDatabase, id: String): Set<String> = db.rawQuery(
        "SELECT source_revision_id FROM ${StorySchema.SUMMARY_SOURCES} WHERE record_id=?", arrayOf(id)
    ).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }

    fun plan(db: SQLiteDatabase, story: String, timeline: String): JSONObject? {
        // An undone parent blocks automatic regeneration, while its children remain available to context.
        val replaced = replacedInputs(db,story,timeline,includeInactive=true)
        val candidates = db.rawQuery("""SELECT f.id,f.content FROM ${StorySchema.MEMORIES} f
            WHERE f.story_id=? AND f.timeline_id=? AND f.kind='summary' AND f.active=1 AND f.pinned=0
              AND f.scope IN ('summary:v1','summary:hierarchy:v1') AND ${StorySummaries.validDependencies("f")}
            ORDER BY f.effective_sequence,f.id""",arrayOf(story,timeline)).use { c ->
            buildList { while(c.moveToNext()) if(c.getString(0) !in replaced) add(c.getString(0) to c.getString(1)) }
        }
        val selected = mutableListOf<Pair<String,String>>()
        val covered = mutableSetOf<String>()
        val lineage = linkedMapOf<String,String>()
        // Merge equal-depth trees, avoiding repeated lossy compression of the oldest summary on every batch.
        val level = candidates.groupBy { inputs(db,it.first).size }.toSortedMap().values.firstOrNull { it.size >= 4 } ?: return null
        for ((id,content) in level) {
            val sourceIds = sources(db,id)
            if(content.length > 3000 || sourceIds.isEmpty() || sourceIds.any { it in covered }) continue
            val ancestors = inputs(db,id)
            // Bound metadata growth as well as model input. Never discard dependencies to meet this cap.
            if(covered.size + sourceIds.size > 4096 || lineage.size + ancestors.size + 1 > 1024) break
            selected += id to content; covered += sourceIds
            lineage.putAll(ancestors); lineage[id] = content
            if(selected.size == 4) break
        }
        if(selected.size != 4) return null
        val ordered = db.rawQuery("""SELECT r.id FROM ${StorySchema.MESSAGES} m
            JOIN ${StorySchema.REVISIONS} r ON r.id=m.active_revision_id
            WHERE m.story_id=? AND m.timeline_id=? AND m.workspace='prose' AND m.role='assistant' AND r.state='complete'
            ORDER BY m.sequence_no""",arrayOf(story,timeline)).use { c ->
            buildList { while(c.moveToNext()) if(c.getString(0) in covered) add(c.getString(0)) }
        }
        if(ordered.size != covered.size) return null
        return JSONObject().put("sources",JSONArray(ordered))
            .put("inputs",JSONArray(lineage.map { (id,content) -> JSONObject().put("id",id).put("content",content) }))
            .put("input_text",selected.mapIndexed { index, row -> "[历史摘要 ${index+1}，按时间顺序；保留认知与历史状态边界]\n${row.second}" }.joinToString("\n\n"))
    }

    fun validPlan(db: SQLiteDatabase, job: StoryMemoryJob, plan: JSONObject): Boolean {
        val rows = plan.optJSONArray("inputs") ?: return false
        if(rows.length() !in 4..1024 || plan.optString("input_text").length !in 1..14000) return false
        for(i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val exists = db.rawQuery("""SELECT 1 FROM ${StorySchema.MEMORIES} f WHERE f.id=? AND f.story_id=?
                AND f.timeline_id=? AND f.active=1 AND f.pinned=0 AND f.content=? AND ${StorySummaries.validDependencies("f")}""",
                arrayOf(row.getString("id"),job.storyId,job.timelineId,row.getString("content"))).use { it.moveToFirst() }
            if(!exists) return false
        }
        return true
    }

    fun saveInputs(db: SQLiteDatabase, id: String, plan: JSONObject) {
        val rows = plan.optJSONArray("inputs") ?: return
        for(i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            db.insertOrThrow(StorySchema.SUMMARY_INPUTS,null,ContentValues().apply {
                put("record_id",id);put("input_record_id",row.getString("id"));put("input_content",row.getString("content"))
            })
        }
    }
}
