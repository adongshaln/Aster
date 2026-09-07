package com.adong.adchat.data.story

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

internal object StoryDiscussionLinks {
    fun validDependencies(alias:String)="""NOT EXISTS (SELECT 1 FROM ${StorySchema.MEMORY_DEPENDENCIES} d WHERE d.record_id=$alias.id
        AND NOT EXISTS (SELECT 1 FROM ${StorySchema.MESSAGES} m JOIN ${StorySchema.REVISIONS} r ON r.id=m.active_revision_id
        WHERE r.id=d.source_revision_id AND r.state='complete' AND m.story_id=$alias.story_id AND m.timeline_id=$alias.timeline_id))"""
    fun saveQuote(db:SQLiteDatabase,source:StoryMessageWithRevision,quote:String) {
        db.insertOrThrow(StorySchema.DISCUSSION_LINKS,null,ContentValues().apply {
            put("id",UUID.randomUUID().toString());put("story_id",source.message.storyId);put("timeline_id",source.message.timelineId)
            put("source_revision_id",source.revision.id);put("quote_text",quote.removeSuffix("\n\n我的修改想法："))
        })
    }
    fun bind(db:SQLiteDatabase,user:StoryMessageRevision) {
        val ids=db.rawQuery("SELECT id,quote_text FROM ${StorySchema.DISCUSSION_LINKS} WHERE story_id=? AND timeline_id=? AND user_revision_id IS NULL",
            arrayOf(user.storyId,user.timelineId)).use { c -> buildList { while(c.moveToNext()) if(user.content.contains(c.getString(1))) add(c.getString(0)) } }
        ids.forEach { db.update(StorySchema.DISCUSSION_LINKS,ContentValues().apply { put("user_revision_id",user.id) },"id=?",arrayOf(it)) }
    }
    fun sources(db:SQLiteDatabase,reply:String): Set<String> {
        val user=db.rawQuery("""SELECT u.active_revision_id FROM ${StorySchema.MESSAGES} a JOIN ${StorySchema.MESSAGES} u
            ON a.story_id=u.story_id AND a.timeline_id=u.timeline_id AND u.workspace='discussion' AND u.role='user'
            WHERE a.active_revision_id=? AND u.sequence_no<a.sequence_no AND EXISTS
            (SELECT 1 FROM ${StorySchema.DISCUSSION_LINKS} l WHERE l.user_revision_id=u.active_revision_id) ORDER BY u.sequence_no DESC LIMIT 1""",arrayOf(reply)).use {
            if(it.moveToFirst()) it.getString(0) else null
        } ?: return emptySet()
        return db.rawQuery("SELECT source_revision_id FROM ${StorySchema.DISCUSSION_LINKS} WHERE user_revision_id=?",arrayOf(user)).use { c ->
            buildSet { while(c.moveToNext()) add(c.getString(0)) }
        }
    }
    fun attach(db:SQLiteDatabase,record:String,reply:String) = sources(db,reply).forEach { source ->
        db.insertWithOnConflict(StorySchema.MEMORY_DEPENDENCIES,null,ContentValues().apply {
            put("record_id",record);put("source_revision_id",source)
        },SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun isCompleteReply(db:SQLiteDatabase,id:String,story:String,timeline:String)=checkSource(db,id,story,timeline,"assistant")
    fun isUserDecision(db:SQLiteDatabase,id:String,story:String,timeline:String)=checkSource(db,id,story,timeline,"user")
    private fun checkSource(db:SQLiteDatabase,id:String,story:String,timeline:String,role:String)=db.rawQuery("""SELECT 1
        FROM ${StorySchema.MESSAGES} m JOIN ${StorySchema.REVISIONS} r ON r.id=m.active_revision_id
        JOIN ${StorySchema.STORIES} s ON s.id=m.story_id
        WHERE r.id=? AND m.story_id=? AND m.timeline_id=? AND m.workspace='discussion' AND m.role=? AND r.state='complete'
          AND s.current_timeline_id=m.timeline_id""",arrayOf(id,story,timeline,role)).use { it.moveToFirst() }
}

object StoryExplicitDecision {
    /** Deliberately exact: casual praise and model claims are never authorization. */
    fun match(input:String,proposals:List<StoryProposal>): StoryProposal? {
        val prefix=listOf("确认采用：","确认采用:","采用：","采用:").firstOrNull { input.startsWith(it) } ?: return null
        val content=input.removePrefix(prefix).trim().removeSurrounding("「","」")
        return proposals.filter { it.state==StoryProposalState.Pending && it.content.trim()==content }.singleOrNull()
    }
    fun isExplicit(input:String)=listOf("确认采用：","确认采用:","采用：","采用:").any { input.startsWith(it) }
}
