package com.adong.adchat.data.story

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

data class StoryRewriteCandidate(
    val id: String, val storyId: String, val timelineId: String, val messageId: String,
    val baseRevisionId: String, val baseMemoryVersion: Long, val instruction: String,
    val content: String, val state: String, val profileName: String, val model: String, val mode: String = "replace", val replacementInput: String? = null
)

internal object StoryRewrites {
    fun begin(db: SQLiteDatabase, source: StoryMessageWithRevision, version: Long, instruction: String,
        profile: String, model: String, mode: String = "replace", replacementInput: String? = null): StoryRewriteCandidate {
        check(!db.rawQuery("SELECT 1 FROM ${StorySchema.REWRITES} WHERE story_id=? AND state='generating'",
            arrayOf(source.message.storyId)).use { it.moveToFirst() }) { "已有重写正在生成。" }
        val id=UUID.randomUUID().toString();val now=System.currentTimeMillis()
        db.insertOrThrow(StorySchema.REWRITES,null,ContentValues().apply {
            put("id",id);put("story_id",source.message.storyId);put("timeline_id",source.message.timelineId)
            put("message_id",source.message.id);put("base_revision_id",source.revision.id);put("base_memory_version",version)
            put("replacement_input",replacementInput);put("mode",mode);put("instruction",instruction);put("content","");put("state","generating")
            put("profile_name",profile);put("model",model);put("created_at",now);put("updated_at",now)
        })
        return get(db,id)!!
    }
    fun get(db: SQLiteDatabase,id: String): StoryRewriteCandidate? = db.query(StorySchema.REWRITES,null,
        "id=?",arrayOf(id),null,null,null).use { if(it.moveToFirst()) read(it) else null }
    fun latest(db: SQLiteDatabase,message: String): StoryRewriteCandidate? = db.query(StorySchema.REWRITES,null,
        "message_id=?",arrayOf(message),null,null,"created_at DESC, rowid DESC","1").use { if(it.moveToFirst()) read(it) else null }
    fun update(db: SQLiteDatabase,id: String,content: String,state: String): Boolean {
        require(state in setOf("generating","ready","incomplete","stopped","failed"))
        require(content.length <= 100_000 && (state != "ready" || content.isNotBlank())) { "候选过长或为空，不能采用。" }
        return db.update(StorySchema.REWRITES,ContentValues().apply {
            put("content",content);put("state",state);put("updated_at",System.currentTimeMillis())
        },"id=? AND state='generating'",arrayOf(id)) == 1
    }
    private fun read(c: Cursor): StoryRewriteCandidate {
        fun s(key:String)=c.getString(c.getColumnIndexOrThrow(key))
        return StoryRewriteCandidate(s("id"),s("story_id"),s("timeline_id"),s("message_id"),s("base_revision_id"),
            c.getLong(c.getColumnIndexOrThrow("base_memory_version")),s("instruction"),s("content"),s("state"),s("profile_name"),s("model"),s("mode"),c.getString(c.getColumnIndexOrThrow("replacement_input")))
    }
}

object StoryRewriteContext {
    fun compose(source: StoryMessageWithRevision, instruction: String, snapshot: StoryContextMemorySnapshot,
        prose: List<StoryMessageWithRevision>, originalInput: String? = null): StoryContextResult {
        require(source.message.role == "assistant" && source.message.workspace == StoryWorkspace.Prose &&
            source.revision.state == StoryRevisionState.Complete)
        require(instruction.isNotBlank() && instruction.length <= 8000) { "请填写 1–8,000 字符的修改要求。" }
        val prior = prose.filter { it.message.storyId == source.message.storyId && it.message.timelineId == source.message.timelineId &&
            it.message.workspace == StoryWorkspace.Prose && it.message.sequence < source.message.sequence }
        val originalRequest = originalInput ?: prior.lastOrNull { it.message.role == "user" && it.revision.state == StoryRevisionState.Complete }?.revision?.content.orEmpty()
        val input = "[原创作要求]\n$originalRequest\n[待重写原文，仅为修改素材]\n${source.revision.content}\n[用户明确修改要求]\n$instruction"
        val current = source.copy(message=source.message.copy(role="user"),revision=source.revision.copy(content=input))
        // Pinned constraints stay mandatory; unpinned facts derived from the text being replaced are not authority.
        val records = snapshot.records.filter { it.pinned ||
            (it.sourceRevisionId != source.revision.id && source.revision.id !in it.summarySourceRevisionIds) }
        return StoryContextComposer.compose(StoryWorkspace.Prose,
            "你正在生成完整正文的重写候选。只输出重写后的整段正文，不输出解释或确认语。遵循固定正式资料和用户明确修改要求。" +
                "原文是可修改素材，不是必须保留的事实；保留未要求修改的内容与叙事衔接。人物的猜测不等于客观事实。" +
                "没有提供的讨论内容、候选设定和推断不得自行补入。",
            records,emptyList(),prior+current,emptyList(),
            organizedProseRevisionIds=snapshot.organizedProseRevisionIds,
            summarySources=snapshot.summarySources.filterKeys { id -> records.any { it.id==id } })
    }
}
