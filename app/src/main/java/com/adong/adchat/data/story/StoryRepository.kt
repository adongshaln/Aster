package com.adong.adchat.data.story

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

class StoryRepository(context: Context) : AutoCloseable {
    private val helper = StoryDatabase(context)

    /** Called once when the story ViewModel starts; a dead process cannot resume an HTTP stream. */
    fun recoverInterruptedGenerations(): Int = helper.writableDatabase.update(
        StorySchema.REVISIONS,
        ContentValues().apply { put("state", StoryRevisionState.Interrupted.dbValue); putNull("completed_at") },
        "state = 'streaming' AND id IN (SELECT active_revision_id FROM ${StorySchema.MESSAGES})", null
    )

    fun createStory(title: String, profileId: String, model: String): Story {
        val storyId = newStoryId()
        val timelineId = newTimelineId()
        val now = System.currentTimeMillis()
        val story = Story(
            id = storyId,
            title = title.trim().ifBlank { "未命名故事" },
            profileId = profileId,
            model = model,
            currentTimelineId = timelineId,
            createdAt = now,
            updatedAt = now
        )
        helper.writableDatabase.inTransaction { db ->
            db.insertOrThrow(
                StorySchema.STORIES,
                null,
                ContentValues().apply {
                    put("id", story.id)
                    put("title", story.title)
                    put("profile_id", story.profileId)
                    put("model", story.model)
                    put("current_timeline_id", story.currentTimelineId)
                    put("memory_version", story.memoryVersion)
                    put("automatic_memory_enabled", if (story.automaticMemoryEnabled) 1 else 0)
                    put("created_at", story.createdAt)
                    put("updated_at", story.updatedAt)
                }
            )
            db.insertOrThrow(
                StorySchema.TIMELINES,
                null,
                ContentValues().apply {
                    put("id", timelineId)
                    put("story_id", storyId)
                    putNull("parent_timeline_id")
                    putNull("fork_revision_id")
                    put("created_at", now)
                }
            )
            StoryWorkspace.entries.forEach { workspace ->
                db.insertOrThrow(
                    StorySchema.WORKSPACE_STATE,
                    null,
                    ContentValues().apply {
                        put("story_id", storyId)
                        put("workspace", workspace.dbValue)
                        put("draft", "")
                        put("first_visible_index", 0)
                        put("first_visible_offset", 0)
                        put("updated_at", now)
                    }
                )
            }
        }
        return story
    }

    fun listStories(): List<Story> = helper.readableDatabase.query(
        StorySchema.STORIES,
        null,
        null,
        null,
        null,
        null,
        "updated_at DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toStory()) } }

    fun getStory(storyId: String): Story? = helper.readableDatabase.query(
        StorySchema.STORIES,
        null,
        "id = ?",
        arrayOf(storyId),
        null,
        null,
        null,
        "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toStory() else null }

    fun renameStory(storyId: String, title: String): Boolean {
        val cleaned = title.trim()
        if (cleaned.isBlank()) return false
        return helper.writableDatabase.update(
            StorySchema.STORIES,
            ContentValues().apply {
                put("title", cleaned)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(storyId)
        ) == 1
    }

    fun updateStoryRoute(storyId: String, profileId: String, model: String): Boolean =
        helper.writableDatabase.update(
            StorySchema.STORIES,
            ContentValues().apply {
                put("profile_id", profileId)
                put("model", model)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(storyId)
        ) == 1

    fun setAutomaticMemoryEnabled(storyId: String, enabled: Boolean): Boolean =
        helper.writableDatabase.update(
            StorySchema.STORIES,
            ContentValues().apply {
                put("automatic_memory_enabled", if (enabled) 1 else 0)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(storyId)
        ) == 1

    fun deleteStory(storyId: String): Boolean =
        helper.writableDatabase.delete(StorySchema.STORIES, "id = ?", arrayOf(storyId)) == 1

    fun appendMessage(
        storyId: String,
        timelineId: String,
        workspace: StoryWorkspace,
        role: String,
        content: String,
        state: StoryRevisionState = StoryRevisionState.Complete,
        profileName: String = "",
        model: String = ""
    ): StoryMessageWithRevision {
        val messageId = newMessageId()
        val revisionId = newRevisionId()
        val now = System.currentTimeMillis()
        return helper.writableDatabase.inTransaction { db ->
            requireStoryTimeline(db, storyId, timelineId)
            val sequence = nextSequence(db, storyId, timelineId)
            val message = StoryMessage(
                id = messageId,
                storyId = storyId,
                timelineId = timelineId,
                workspace = workspace,
                role = role,
                sequence = sequence,
                activeRevisionId = revisionId,
                createdAt = now
            )
            val revision = StoryMessageRevision(
                id = revisionId,
                messageId = messageId,
                storyId = storyId,
                timelineId = timelineId,
                workspace = workspace,
                content = content,
                state = state,
                profileName = profileName,
                model = model,
                createdAt = now,
                completedAt = now.takeIf { state == StoryRevisionState.Complete }
            )
            if (workspace == StoryWorkspace.Prose && role == "assistant") {
                StoryTimelineHistory.captureBoundary(db, storyId, timelineId, messageId, sequence)
            }
            insertMessage(db, message)
            insertRevision(db, revision)
            touchStory(db, storyId, now)
            StoryMessageWithRevision(message, revision)
        }
    }

    fun replaceMessageRevision(
        messageId: String,
        content: String,
        state: StoryRevisionState = StoryRevisionState.Complete,
        profileName: String = "",
        model: String = "",
        expectedRevisionId: String? = null,
        allowLaterDiscussion: Boolean = false
    ): StoryMessageWithRevision? = helper.writableDatabase.inTransaction { db ->
        val current = queryMessageWithRevision(db, messageId) ?: return@inTransaction null
        requireRevisionChangeAllowed(db, current, expectedRevisionId, allowLaterDiscussion)
        require(state == StoryRevisionState.Complete && content.isNotBlank()) { "修订正文不能为空，且必须保存为完整版本" }
        if (current.revision.content == content && current.revision.state == state) return@inTransaction current
        val now = System.currentTimeMillis()
        val newRevision = StoryMessageRevision(
            id = newRevisionId(),
            messageId = messageId,
            storyId = current.message.storyId,
            timelineId = current.message.timelineId,
            workspace = current.message.workspace,
            content = content,
            state = state,
            profileName = profileName,
            model = model,
            createdAt = now,
            completedAt = now.takeIf { state == StoryRevisionState.Complete }
        )
        insertRevision(db, newRevision)
        activateRevision(db, current, newRevision, now)
        StoryMessageWithRevision(current.message.copy(activeRevisionId = newRevision.id), newRevision)
    }

    fun forkProseRevision(messageId: String, expectedRevisionId: String, content: String): String =
        helper.writableDatabase.inTransaction { db -> StoryTimelineHistory.fork(db, messageId, expectedRevisionId, content) }

    fun switchTimeline(storyId: String, targetTimelineId: String, expectedTimelineId: String): Boolean =
        helper.writableDatabase.inTransaction { db -> StoryTimelineHistory.switch(db, storyId, targetTimelineId, expectedTimelineId) }

    fun listTimelines(storyId: String): List<StoryTimeline> = helper.readableDatabase.query(
        StorySchema.TIMELINES, null, "story_id = ?", arrayOf(storyId), null, null, "created_at ASC, rowid ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(StoryTimeline(
        id = cursor.string("id"), storyId = storyId, parentTimelineId = cursor.getString(cursor.getColumnIndexOrThrow("parent_timeline_id")),
        forkRevisionId = cursor.getString(cursor.getColumnIndexOrThrow("fork_revision_id")), createdAt = cursor.long("created_at")
    )) } }

    fun listRevisions(messageId: String): List<StoryMessageRevision> = helper.readableDatabase.query(
        StorySchema.REVISIONS, null, "message_id = ?", arrayOf(messageId), null, null, "created_at ASC, rowid ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRevision()) } }

    fun restoreMessageRevision(messageId: String, revisionId: String, expectedRevisionId: String): Boolean =
        helper.writableDatabase.inTransaction { db ->
            val current = queryMessageWithRevision(db, messageId) ?: return@inTransaction false
            requireRevisionChangeAllowed(db, current, expectedRevisionId, allowLaterDiscussion=true)
            if (current.revision.id == revisionId) return@inTransaction false
            val target = db.query(StorySchema.REVISIONS, null, "id = ? AND message_id = ?",
                arrayOf(revisionId, messageId), null, null, null).use { cursor ->
                if (cursor.moveToFirst()) cursor.toRevision() else null
            } ?: return@inTransaction false
            require(target.state == StoryRevisionState.Complete && target.content.isNotBlank()) {
                "只能恢复已完整完成的正文版本"
            }
            activateRevision(db, current, target, System.currentTimeMillis())
            true
        }

    private fun requireRevisionChangeAllowed(db: SQLiteDatabase, current: StoryMessageWithRevision, expected: String?, allowLaterDiscussion: Boolean = false) {
        require(expected == null || current.revision.id == expected) { "正文版本已变化，请重新打开后操作" }
        require(current.message.workspace == StoryWorkspace.Prose && current.message.role == "assistant") {
            "目前仅支持修订末尾正文回复"
        }
        require(current.revision.state != StoryRevisionState.Streaming) { "请等待正文生成结束" }
        val blocked = db.rawQuery(
            """SELECT 1 FROM ${StorySchema.MESSAGES} m JOIN ${StorySchema.REVISIONS} r ON r.id = m.active_revision_id
               WHERE m.story_id = ? AND m.timeline_id = ? AND ((m.sequence_no > ? ${if (allowLaterDiscussion) "AND m.workspace = 'prose'" else ""}) OR r.state = 'streaming') LIMIT 1""",
            arrayOf(current.message.storyId, current.message.timelineId, current.message.sequence.toString())
        ).use { it.moveToFirst() }
        require(!blocked) { "已有后续内容或正在生成，请先结束生成；较早正文的分支修订尚未开放" }
        val activeTimeline = db.rawQuery("SELECT 1 FROM ${StorySchema.STORIES} WHERE id = ? AND current_timeline_id = ?",
            arrayOf(current.message.storyId, current.message.timelineId)).use { it.moveToFirst() }
        require(activeTimeline) { "故事时间线已变化" }
    }

    private fun activateRevision(db: SQLiteDatabase, current: StoryMessageWithRevision, target: StoryMessageRevision, now: Long) {
        val baseVersion = db.rawQuery("SELECT memory_version FROM ${StorySchema.STORIES} WHERE id = ?",
            arrayOf(current.message.storyId)).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        val nextVersion = Math.addExact(baseVersion, 1L)
        check(db.update(StorySchema.MESSAGES, ContentValues().apply { put("active_revision_id", target.id) },
            "id = ? AND active_revision_id = ?", arrayOf(current.message.id, current.revision.id)) == 1)
        check(db.update(StorySchema.STORIES, ContentValues().apply {
            put("memory_version", nextVersion); put("updated_at", now)
        }, "id = ? AND memory_version = ?", arrayOf(current.message.storyId, baseVersion.toString())) == 1)
        db.update(StorySchema.JOBS, ContentValues().apply {
            put("state", StoryJobState.Stale.dbValue); put("error", "Source revision switched"); put("updated_at", now)
        }, "source_revision_id = ? AND state IN ('pending','running')", arrayOf(current.revision.id))
        // Records retain their source IDs and manual active/pin choices. Query visibility follows the active revision.
        db.insertOrThrow(StorySchema.CHANGE_SETS, null, ContentValues().apply {
            put("id", newChangeSetId()); put("story_id", current.message.storyId)
            put("timeline_id", current.message.timelineId); put("source_revision_id", target.id)
            put("base_memory_version", baseVersion); put("committed_version", nextVersion)
            put("status", "committed"); put("created_at", now); put("updated_at", now)
            put("operations_json", JSONObject().put("actor", "user_ui").put("operation", "switch_revision")
                .put("message_id", current.message.id).put("before_revision_id", current.revision.id)
                .put("after_revision_id", target.id).toString())
            put("conflicts_json", "[]")
        })
        StoryConflicts.refresh(db, current.message.storyId, current.message.timelineId)
    }

    fun updateActiveRevision(
        revisionId: String,
        content: String,
        state: StoryRevisionState,
        profileName: String? = null,
        model: String? = null
    ): Boolean = helper.writableDatabase.inTransaction { db ->
        val active = db.rawQuery(
            """
            SELECT m.story_id
            FROM ${StorySchema.MESSAGES} m
            JOIN ${StorySchema.REVISIONS} r ON r.id = m.active_revision_id
            WHERE m.active_revision_id = ? AND r.state = 'streaming'
            LIMIT 1
            """.trimIndent(),
            arrayOf(revisionId)
        ).use { cursor -> cursor.takeIf { it.moveToFirst() }?.getString(0) }
            ?: return@inTransaction false
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("content", content)
            put("state", state.dbValue)
            if (profileName != null) put("profile_name", profileName)
            if (model != null) put("model", model)
            if (state == StoryRevisionState.Complete) put("completed_at", now) else putNull("completed_at")
        }
        val updated = db.update(StorySchema.REVISIONS, values, "id = ?", arrayOf(revisionId)) == 1
        if (updated) touchStory(db, active, now)
        updated
    }

    fun deleteMessage(messageId: String): Boolean = helper.writableDatabase.inTransaction { db ->
        val current = queryMessageWithRevision(db, messageId) ?: return@inTransaction false
        // Only disposable empty generation placeholders may be physically removed.
        if (current.revision.content.isNotBlank() || current.revision.state == StoryRevisionState.Complete) return@inTransaction false
        val deleted = db.delete(StorySchema.MESSAGES, "id = ?", arrayOf(messageId)) == 1
        if (deleted) touchStory(db, current.message.storyId, System.currentTimeMillis())
        deleted
    }

    fun isRevisionActive(revisionId: String): Boolean = helper.readableDatabase.rawQuery(
        "SELECT 1 FROM ${StorySchema.MESSAGES} WHERE active_revision_id = ? LIMIT 1",
        arrayOf(revisionId)
    ).use { it.moveToFirst() }

    fun getActiveRevision(revisionId: String): StoryMessageRevision? = helper.readableDatabase.rawQuery(
        """
        SELECT r.*
        FROM ${StorySchema.REVISIONS} r
        JOIN ${StorySchema.MESSAGES} m ON m.active_revision_id = r.id
        WHERE r.id = ?
        LIMIT 1
        """.trimIndent(),
        arrayOf(revisionId)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRevision() else null }

    fun loadMessages(
        storyId: String,
        timelineId: String,
        workspace: StoryWorkspace
    ): List<StoryMessageWithRevision> = helper.readableDatabase.rawQuery(
        """
        SELECT
            m.id AS m_id, m.story_id AS m_story_id, m.timeline_id AS m_timeline_id,
            m.workspace AS m_workspace, m.role AS m_role, m.sequence_no AS m_sequence_no,
            m.active_revision_id AS m_active_revision_id, m.created_at AS m_created_at,
            r.id AS r_id, r.content AS r_content, r.state AS r_state,
            r.profile_name AS r_profile_name, r.model AS r_model,
            r.created_at AS r_created_at, r.completed_at AS r_completed_at
        FROM ${StorySchema.MESSAGES} m
        JOIN ${StorySchema.REVISIONS} r ON r.id = m.active_revision_id
        WHERE m.story_id = ? AND m.timeline_id = ? AND m.workspace = ?
        ORDER BY m.sequence_no ASC
        """.trimIndent(),
        arrayOf(storyId, timelineId, workspace.dbValue)
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val message = StoryMessage(
                    id = cursor.string("m_id"),
                    storyId = cursor.string("m_story_id"),
                    timelineId = cursor.string("m_timeline_id"),
                    workspace = StoryWorkspace.fromDb(cursor.string("m_workspace")),
                    role = cursor.string("m_role"),
                    sequence = cursor.long("m_sequence_no"),
                    activeRevisionId = cursor.string("m_active_revision_id"),
                    createdAt = cursor.long("m_created_at")
                )
                val revision = StoryMessageRevision(
                    id = cursor.string("r_id"),
                    messageId = message.id,
                    storyId = message.storyId,
                    timelineId = message.timelineId,
                    workspace = message.workspace,
                    content = cursor.string("r_content"),
                    state = StoryRevisionState.fromDb(cursor.string("r_state")),
                    profileName = cursor.string("r_profile_name"),
                    model = cursor.string("r_model"),
                    createdAt = cursor.long("r_created_at"),
                    completedAt = cursor.nullableLong("r_completed_at")
                )
                add(StoryMessageWithRevision(message, revision))
            }
        }
    }

    fun loadWorkspaceState(storyId: String, workspace: StoryWorkspace): StoryWorkspaceState =
        helper.readableDatabase.rawQuery(
            """SELECT w.*, s.current_timeline_id FROM ${StorySchema.WORKSPACE_STATE} w
               JOIN ${StorySchema.STORIES} s ON s.id = w.story_id WHERE w.story_id = ? AND w.workspace = ? LIMIT 1""",
            arrayOf(storyId, workspace.dbValue)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use StoryWorkspaceState(storyId, workspace)
            StoryWorkspaceState(
                storyId = storyId,
                workspace = workspace,
                draft = cursor.string("draft"),
                firstVisibleIndex = cursor.int("first_visible_index"),
                firstVisibleOffset = cursor.int("first_visible_offset"),
                updatedAt = cursor.long("updated_at"),
                timelineId = cursor.string("current_timeline_id")
            )
        }

    fun saveWorkspaceState(state: StoryWorkspaceState): Boolean = helper.writableDatabase.inTransaction { db ->
        if (state.timelineId != null && db.rawQuery("SELECT 1 FROM ${StorySchema.STORIES} WHERE id = ? AND current_timeline_id = ?",
                arrayOf(state.storyId, state.timelineId)).use { !it.moveToFirst() }) return@inTransaction false
        val existingUpdatedAt = db.rawQuery(
            "SELECT updated_at FROM ${StorySchema.WORKSPACE_STATE} WHERE story_id = ? AND workspace = ? LIMIT 1",
            arrayOf(state.storyId, state.workspace.dbValue)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (!shouldPersistStoryWorkspaceState(existingUpdatedAt, state.updatedAt)) return@inTransaction false

        val values = ContentValues().apply {
            put("story_id", state.storyId)
            put("workspace", state.workspace.dbValue)
            put("draft", state.draft.take(MAX_WORKSPACE_DRAFT))
            put("first_visible_index", state.firstVisibleIndex.coerceAtLeast(0))
            put("first_visible_offset", state.firstVisibleOffset)
            put("updated_at", state.updatedAt)
        }
        if (existingUpdatedAt == null) {
            db.insertOrThrow(StorySchema.WORKSPACE_STATE, null, values)
            true
        } else {
            db.update(
                StorySchema.WORKSPACE_STATE,
                values,
                "story_id = ? AND workspace = ?",
                arrayOf(state.storyId, state.workspace.dbValue)
            ) == 1
        }
    }

    fun beginRewrite(messageId: String, revisionId: String, memoryVersion: Long, instruction: String,
        profileName: String, model: String): StoryRewriteCandidate = helper.writableDatabase.inTransaction { db ->
        val source = queryMessageWithRevision(db,messageId) ?: error("正文已不存在")
        requireRevisionChangeAllowed(db,source,revisionId,allowLaterDiscussion=true)
        check(source.revision.state == StoryRevisionState.Complete) { "只能重写完整正文。" }
        val story = getStory(source.message.storyId) ?: error("故事已删除")
        check(story.memoryVersion == memoryVersion) { "资料已变化，请重新生成。" }
        require(instruction.isNotBlank() && instruction.length <= 8000) { "请填写 1–8,000 字符的修改要求。" }
        StoryRewrites.begin(db,source,memoryVersion,instruction,profileName,model)
    }

    fun updateRewrite(id: String, content: String, state: String): Boolean =
        StoryRewrites.update(helper.writableDatabase,id,content,state)
    fun latestRewrite(messageId: String): StoryRewriteCandidate? = StoryRewrites.latest(helper.readableDatabase,messageId)
    fun recoverRewrites(): Int = helper.writableDatabase.update(StorySchema.REWRITES,ContentValues().apply {
        put("state","interrupted");put("updated_at",System.currentTimeMillis())
    },"state='generating'",null)

    fun adoptRewrite(id: String): StoryMessageWithRevision = helper.writableDatabase.inTransaction { db ->
        val candidate = StoryRewrites.get(db,id) ?: error("候选已不存在")
        check(candidate.state == "ready" && candidate.content.isNotBlank()) { "只有完整候选可以采用。" }
        val story = getStory(candidate.storyId) ?: error("故事已删除")
        check(story.currentTimelineId == candidate.timelineId && story.memoryVersion == candidate.baseMemoryVersion) {
            "路线或资料已变化，候选仍保留，请重新生成后再采用。"
        }
        check(queryMessageWithRevision(db,candidate.messageId)?.revision?.state == StoryRevisionState.Complete) { "原文已不再是完整正文。" }
        val revised = replaceMessageRevision(candidate.messageId,candidate.content,
            profileName=candidate.profileName,model=candidate.model,expectedRevisionId=candidate.baseRevisionId,
            allowLaterDiscussion=true) ?: error("原文已不存在")
        check(db.update(StorySchema.REWRITES,ContentValues().apply { put("state","adopted");put("updated_at",System.currentTimeMillis()) },
            "id=? AND state='ready'",arrayOf(id)) == 1)
        revised
    }

    fun appendDiscussionQuote(messageId: String, expectedRevisionId: String, start: Int, end: Int,
        expectedDraft: StoryWorkspaceState): StoryWorkspaceState = helper.writableDatabase.inTransaction { db ->
        val source = queryMessageWithRevision(db,messageId) ?: error("正文已不存在")
        check(source.revision.id == expectedRevisionId) { "正文版本已变化，请重新打开后选择。" }
        check(source.message.storyId == expectedDraft.storyId && source.message.timelineId == expectedDraft.timelineId &&
            expectedDraft.workspace == StoryWorkspace.Discussion) { "故事或路线已变化，请重新选择。" }
        val stored = loadWorkspaceState(expectedDraft.storyId,StoryWorkspace.Discussion)
        check(stored.updatedAt <= expectedDraft.updatedAt &&
            (stored.updatedAt != expectedDraft.updatedAt || stored.draft == expectedDraft.draft)) {
            "讨论草稿已变化，请重新打开后重试。"
        }
        val next = expectedDraft.copy(
            draft = StoryDiscussionQuote.append(expectedDraft.draft,source,start,end),
            updatedAt = nextStoryWorkspaceUpdatedAt(expectedDraft.updatedAt,System.currentTimeMillis())
        )
        check(saveWorkspaceState(next)) { "路线或草稿已变化，引用未写入。" }
        next
    }

    override fun close() = helper.close()

    private fun insertMessage(db: SQLiteDatabase, message: StoryMessage) {
        db.insertOrThrow(
            StorySchema.MESSAGES,
            null,
            ContentValues().apply {
                put("id", message.id)
                put("story_id", message.storyId)
                put("timeline_id", message.timelineId)
                put("workspace", message.workspace.dbValue)
                put("role", message.role)
                put("sequence_no", message.sequence)
                put("active_revision_id", message.activeRevisionId)
                put("created_at", message.createdAt)
            }
        )
    }

    private fun insertRevision(db: SQLiteDatabase, revision: StoryMessageRevision) {
        db.insertOrThrow(
            StorySchema.REVISIONS,
            null,
            ContentValues().apply {
                put("id", revision.id)
                put("message_id", revision.messageId)
                put("story_id", revision.storyId)
                put("timeline_id", revision.timelineId)
                put("workspace", revision.workspace.dbValue)
                put("content", revision.content)
                put("state", revision.state.dbValue)
                put("profile_name", revision.profileName)
                put("model", revision.model)
                put("created_at", revision.createdAt)
                revision.completedAt?.let { put("completed_at", it) } ?: putNull("completed_at")
            }
        )
    }

    private fun queryMessageWithRevision(db: SQLiteDatabase, messageId: String): StoryMessageWithRevision? = db.rawQuery(
        """
        SELECT
            m.id AS m_id, m.story_id AS m_story_id, m.timeline_id AS m_timeline_id,
            m.workspace AS m_workspace, m.role AS m_role, m.sequence_no AS m_sequence_no,
            m.active_revision_id AS m_active_revision_id, m.created_at AS m_created_at,
            r.id AS r_id, r.content AS r_content, r.state AS r_state,
            r.profile_name AS r_profile_name, r.model AS r_model,
            r.created_at AS r_created_at, r.completed_at AS r_completed_at
        FROM ${StorySchema.MESSAGES} m
        JOIN ${StorySchema.REVISIONS} r ON r.id = m.active_revision_id
        WHERE m.id = ?
        LIMIT 1
        """.trimIndent(),
        arrayOf(messageId)
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val message = StoryMessage(
            id = cursor.string("m_id"),
            storyId = cursor.string("m_story_id"),
            timelineId = cursor.string("m_timeline_id"),
            workspace = StoryWorkspace.fromDb(cursor.string("m_workspace")),
            role = cursor.string("m_role"),
            sequence = cursor.long("m_sequence_no"),
            activeRevisionId = cursor.string("m_active_revision_id"),
            createdAt = cursor.long("m_created_at")
        )
        val revision = StoryMessageRevision(
            id = cursor.string("r_id"),
            messageId = message.id,
            storyId = message.storyId,
            timelineId = message.timelineId,
            workspace = message.workspace,
            content = cursor.string("r_content"),
            state = StoryRevisionState.fromDb(cursor.string("r_state")),
            profileName = cursor.string("r_profile_name"),
            model = cursor.string("r_model"),
            createdAt = cursor.long("r_created_at"),
            completedAt = cursor.nullableLong("r_completed_at")
        )
        StoryMessageWithRevision(message, revision)
    }

    private fun nextSequence(db: SQLiteDatabase, storyId: String, timelineId: String): Long = db.rawQuery(
        "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM ${StorySchema.MESSAGES} WHERE story_id = ? AND timeline_id = ?",
        arrayOf(storyId, timelineId)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 1L }

    private fun requireStoryTimeline(db: SQLiteDatabase, storyId: String, timelineId: String) {
        val exists = db.rawQuery(
            "SELECT 1 FROM ${StorySchema.TIMELINES} t JOIN ${StorySchema.STORIES} s ON s.current_timeline_id = t.id WHERE t.id = ? AND t.story_id = ? LIMIT 1",
            arrayOf(timelineId, storyId)
        ).use { it.moveToFirst() }
        require(exists) { "Timeline does not belong to story" }
    }

    private fun touchStory(db: SQLiteDatabase, storyId: String, now: Long) {
        db.update(
            StorySchema.STORIES,
            ContentValues().apply { put("updated_at", now) },
            "id = ?",
            arrayOf(storyId)
        )
    }

    private fun Cursor.toStory(): Story = Story(
        id = string("id"),
        title = string("title"),
        profileId = string("profile_id"),
        model = string("model"),
        currentTimelineId = string("current_timeline_id"),
        memoryVersion = long("memory_version"),
        automaticMemoryEnabled = int("automatic_memory_enabled") != 0,
        createdAt = long("created_at"),
        updatedAt = long("updated_at")
    )

    private fun Cursor.toRevision(): StoryMessageRevision = StoryMessageRevision(
        id = string("id"),
        messageId = string("message_id"),
        storyId = string("story_id"),
        timelineId = string("timeline_id"),
        workspace = StoryWorkspace.fromDb(string("workspace")),
        content = string("content"),
        state = StoryRevisionState.fromDb(string("state")),
        profileName = string("profile_name"),
        model = string("model"),
        createdAt = long("created_at"),
        completedAt = nullableLong("completed_at")
    )

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

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

    private companion object {
        const val MAX_WORKSPACE_DRAFT = 100_000
    }
}
