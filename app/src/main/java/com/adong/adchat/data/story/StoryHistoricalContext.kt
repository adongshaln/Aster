package com.adong.adchat.data.story

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Reconstruct only the material captured before the original generation, never current future facts. */
internal object StoryHistoricalContext {
    private fun JSONArray.rows()=(0 until length()).map(::getJSONObject)
    private fun JSONObject.nullable(key:String)=optString(key).takeUnless { isNull(key) || it.isBlank() }
    fun compose(db: SQLiteDatabase, source: StoryMessageWithRevision, snapshot: JSONObject, instruction: String, originalInput: String? = null): StoryContextResult {
        val states=snapshot.getJSONArray("revisions").rows().associate { it.getString("id") to it.getString("state") }
        val history=snapshot.getJSONArray("messages").rows().map { m ->
            val revision=m.getString("active_revision_id")
            val text=db.rawQuery("SELECT content FROM ${StorySchema.REVISIONS} WHERE id=?",arrayOf(revision)).use {
                check(it.moveToFirst()) { "快照来源已丢失" };it.getString(0)
            }
            val workspace=StoryWorkspace.fromDb(m.getString("workspace"))
            StoryMessageWithRevision(StoryMessage(m.getString("id"),source.message.storyId,source.message.timelineId,workspace,
                m.getString("role"),m.getLong("sequence_no"),revision,m.getLong("created_at")),
                StoryMessageRevision(revision,m.getString("id"),source.message.storyId,source.message.timelineId,workspace,text,
                    StoryRevisionState.fromDb(states.getValue(revision))))
        }
        val complete=history.filter { it.revision.state==StoryRevisionState.Complete }.map { it.revision.id }.toSet()
        val summarySources=(snapshot.optJSONArray("summary_sources") ?: JSONArray()).rows()
            .groupBy({ it.getString("record_id") },{ it.getString("source_revision_id") })
        val inputs=(snapshot.optJSONArray("summary_inputs") ?: JSONArray()).rows().groupBy { it.getString("record_id") }
        val raw=snapshot.getJSONArray("memories").rows().associateBy { it.getString("id") }
        val names=snapshot.getJSONArray("entities").rows().associate { it.getString("id") to listOf(it.getString("canonical_name")) }
        val records=raw.values.filter { m ->
            m.getInt("active")==1 && (m.nullable("source_revision_id")==null || m.getString("source_revision_id") in complete) &&
                summarySources[m.getString("id")].orEmpty().all { it in complete } &&
                inputs[m.getString("id")].orEmpty().all { dependency ->
                    val child=raw[dependency.getString("input_record_id")]
                    child!=null && child.getInt("active")==1 && child.getInt("pinned")==0 &&
                        child.getString("content")==dependency.getString("input_content")
                }
        }.map { m -> StoryMemoryRecord(
            id=m.getString("id"),storyId=source.message.storyId,timelineId=source.message.timelineId,
            kind=StoryMemoryKind.fromDb(m.getString("kind")),content=m.getString("content"),nature=StoryMemoryNature.fromDb(m.getString("nature")),
            subjectEntityId=m.nullable("subject_entity_id"),objectEntityId=m.nullable("object_entity_id"),scope=m.getString("scope"),
            effectiveSequence=m.getLong("effective_sequence"),sourceRevisionId=m.nullable("source_revision_id"),pinned=m.getInt("pinned")==1,
            subjectEntityNames=names[m.nullable("subject_entity_id")].orEmpty(),objectEntityNames=names[m.nullable("object_entity_id")].orEmpty(),
            stateKey=m.nullable("state_key"),summarySourceRevisionIds=summarySources[m.getString("id")].orEmpty()) }
        val replaced=records.flatMap { inputs[it.id].orEmpty().map { row -> row.getString("input_record_id") } }.toSet()
        val visible=records.filter { it.pinned || it.id !in replaced }
        val coverage=snapshot.getJSONArray("completed").rows().map { it.getString("source_revision_id") }.toSet()
        val memory=StoryContextMemorySnapshot(visible,emptyList(),coverage,
            visible.filter { it.summarySourceRevisionIds.isNotEmpty() }.associate { it.id to it.summarySourceRevisionIds.toSet() })
        return StoryRewriteContext.compose(source,instruction,memory,history,originalInput)
    }
}
