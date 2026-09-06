package com.adong.adchat.data.story

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class StoryDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        StorySchema.CREATE_STATEMENTS.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        while (version < newVersion) {
            when (version) {
                1 -> {
                    StorySchema.MIGRATION_1_TO_2_STATEMENTS.forEach(db::execSQL)
                    version = 2
                }
                2 -> {
                    StorySchema.MIGRATION_2_TO_3_STATEMENTS.forEach(db::execSQL)
                    version = 3
                }
                3 -> {
                    StorySchema.MIGRATION_3_TO_4_STATEMENTS.forEach(db::execSQL)
                    version = 4
                }
                4 -> {
                    StorySchema.MIGRATION_4_TO_5_STATEMENTS.forEach(db::execSQL)
                    version = 5
                }
                else -> error("No story database migration from version $version to $newVersion")
            }
        }
    }

    companion object {
        const val DATABASE_NAME = "aster_story.db"
        const val DATABASE_VERSION = 5
    }
}

internal object StorySchema {
    const val STORIES = "stories"
    const val TIMELINES = "timelines"
    const val MESSAGES = "story_messages"
    const val REVISIONS = "message_revisions"
    const val ENTITIES = "story_entities"
    const val MEMORIES = "memory_records"
    const val PROPOSALS = "proposals"
    const val CHANGE_SETS = "memory_change_sets"
    const val JOBS = "memory_jobs"
    const val SNAPSHOTS = "story_snapshots"
    const val WORKSPACE_STATE = "story_workspace_state"
    const val SUMMARY_SOURCES = "summary_sources"
    const val CONFLICTS = "state_conflicts"
    const val MANUAL_MEMORY_CHANGES = "manual_memory_changes"

    private val MANUAL_MEMORY_CHANGE_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE $MANUAL_MEMORY_CHANGES (
            id TEXT PRIMARY KEY NOT NULL,
            story_id TEXT NOT NULL,
            timeline_id TEXT NOT NULL,
            record_id TEXT NOT NULL,
            operation TEXT NOT NULL CHECK(operation IN ('add','update','pin','deactivate')),
            base_memory_version INTEGER NOT NULL,
            committed_version INTEGER NOT NULL,
            before_json TEXT,
            after_json TEXT,
            created_at INTEGER NOT NULL,
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(timeline_id) REFERENCES $TIMELINES(id) ON DELETE CASCADE,
            FOREIGN KEY(record_id) REFERENCES $MEMORIES(id) ON DELETE CASCADE,
            UNIQUE(story_id, committed_version)
        )
        """.trimIndent(),
        "CREATE INDEX idx_manual_memory_changes_story ON $MANUAL_MEMORY_CHANGES(story_id, timeline_id, committed_version DESC)"
    )

    val MIGRATION_1_TO_2_STATEMENTS: List<String> = MANUAL_MEMORY_CHANGE_STATEMENTS

    val MIGRATION_2_TO_3_STATEMENTS = listOf(
        "ALTER TABLE $MEMORIES ADD COLUMN state_key TEXT"
    )

    val MIGRATION_3_TO_4_STATEMENTS = listOf(
        """CREATE TABLE $CONFLICTS (
            id TEXT PRIMARY KEY NOT NULL,
            story_id TEXT NOT NULL,
            timeline_id TEXT NOT NULL,
            earlier_record_id TEXT NOT NULL,
            latest_record_id TEXT NOT NULL,
            source_revision_id TEXT,
            fingerprint TEXT NOT NULL,
            earlier_json TEXT NOT NULL,
            latest_json TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('pending','accepted','rejected','superseded')),
            created_version INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            UNIQUE(story_id, timeline_id, fingerprint),
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(timeline_id) REFERENCES $TIMELINES(id) ON DELETE CASCADE,
            FOREIGN KEY(earlier_record_id) REFERENCES $MEMORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(latest_record_id) REFERENCES $MEMORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(source_revision_id) REFERENCES $REVISIONS(id) ON DELETE SET NULL
        )""".trimIndent(),
        "CREATE INDEX idx_state_conflicts_scope ON $CONFLICTS(story_id, timeline_id, state)"
    )

    val MIGRATION_4_TO_5_STATEMENTS = listOf(
        """CREATE TABLE $SUMMARY_SOURCES (
            record_id TEXT NOT NULL,
            source_revision_id TEXT NOT NULL,
            PRIMARY KEY(record_id, source_revision_id),
            FOREIGN KEY(record_id) REFERENCES $MEMORIES(id) ON DELETE CASCADE
        )""".trimIndent()
    )

    val CREATE_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE $STORIES (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            profile_id TEXT NOT NULL,
            model TEXT NOT NULL,
            current_timeline_id TEXT NOT NULL,
            memory_version INTEGER NOT NULL DEFAULT 0,
            automatic_memory_enabled INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE $TIMELINES (
            id TEXT PRIMARY KEY NOT NULL,
            story_id TEXT NOT NULL,
            parent_timeline_id TEXT,
            fork_revision_id TEXT,
            created_at INTEGER NOT NULL,
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(parent_timeline_id) REFERENCES $TIMELINES(id) ON DELETE SET NULL
        )
        """.trimIndent(),
        "CREATE INDEX idx_timelines_story ON $TIMELINES(story_id)",
        """
        CREATE TABLE $MESSAGES (
            id TEXT PRIMARY KEY NOT NULL,
            story_id TEXT NOT NULL,
            timeline_id TEXT NOT NULL,
            workspace TEXT NOT NULL CHECK(workspace IN ('discussion','prose')),
            role TEXT NOT NULL,
            sequence_no INTEGER NOT NULL,
            active_revision_id TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(timeline_id) REFERENCES $TIMELINES(id) ON DELETE CASCADE,
            UNIQUE(timeline_id, sequence_no)
        )
        """.trimIndent(),
        "CREATE INDEX idx_story_messages_scope ON $MESSAGES(story_id, timeline_id, workspace, sequence_no)",
        """
        CREATE TABLE $REVISIONS (
            id TEXT PRIMARY KEY NOT NULL,
            message_id TEXT NOT NULL,
            story_id TEXT NOT NULL,
            timeline_id TEXT NOT NULL,
            workspace TEXT NOT NULL CHECK(workspace IN ('discussion','prose')),
            content TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('complete','streaming','interrupted','stopped','superseded')),
            profile_name TEXT NOT NULL DEFAULT '',
            model TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            completed_at INTEGER,
            FOREIGN KEY(message_id) REFERENCES $MESSAGES(id) ON DELETE CASCADE,
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(timeline_id) REFERENCES $TIMELINES(id) ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX idx_revisions_message ON $REVISIONS(message_id, created_at)",
        "CREATE INDEX idx_revisions_source ON $REVISIONS(story_id, timeline_id, workspace, state)",
        """
        CREATE TABLE $ENTITIES (
            id TEXT PRIMARY KEY NOT NULL,
            story_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            canonical_name TEXT NOT NULL,
            aliases_json TEXT NOT NULL DEFAULT '[]',
            active INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX idx_entities_story_name ON $ENTITIES(story_id, canonical_name, active)",
        """
        CREATE TABLE $MEMORIES (
            id TEXT PRIMARY KEY NOT NULL,
            story_id TEXT NOT NULL,
            timeline_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            content TEXT NOT NULL,
            nature TEXT NOT NULL,
            subject_entity_id TEXT,
            object_entity_id TEXT,
            scope TEXT NOT NULL DEFAULT 'story',
            state_key TEXT,
            effective_sequence INTEGER NOT NULL DEFAULT 0,
            source_revision_id TEXT,
            pinned INTEGER NOT NULL DEFAULT 0,
            active INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(timeline_id) REFERENCES $TIMELINES(id) ON DELETE CASCADE,
            FOREIGN KEY(subject_entity_id) REFERENCES $ENTITIES(id) ON DELETE SET NULL,
            FOREIGN KEY(object_entity_id) REFERENCES $ENTITIES(id) ON DELETE SET NULL,
            FOREIGN KEY(source_revision_id) REFERENCES $REVISIONS(id) ON DELETE SET NULL
        )
        """.trimIndent(),
        "CREATE INDEX idx_memories_story_scope ON $MEMORIES(story_id, timeline_id, active, kind, effective_sequence)",
        "CREATE INDEX idx_memories_subject ON $MEMORIES(story_id, subject_entity_id, active)",
        "CREATE INDEX idx_memories_source_revision ON $MEMORIES(source_revision_id, active)",
        """
        CREATE TABLE $PROPOSALS (
            id TEXT PRIMARY KEY NOT NULL,
            story_id TEXT NOT NULL,
            timeline_id TEXT NOT NULL,
            content TEXT NOT NULL,
            proposal_kind TEXT NOT NULL,
            source_revision_id TEXT NOT NULL,
            decision_source_revision_id TEXT,
            state TEXT NOT NULL CHECK(state IN ('pending','accepted','rejected','superseded')),
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(timeline_id) REFERENCES $TIMELINES(id) ON DELETE CASCADE,
            FOREIGN KEY(source_revision_id) REFERENCES $REVISIONS(id) ON DELETE CASCADE,
            FOREIGN KEY(decision_source_revision_id) REFERENCES $REVISIONS(id) ON DELETE SET NULL
        )
        """.trimIndent(),
        "CREATE INDEX idx_proposals_story_state ON $PROPOSALS(story_id, timeline_id, state, updated_at)",
        """
        CREATE TABLE $CHANGE_SETS (
            id TEXT PRIMARY KEY NOT NULL,
            story_id TEXT NOT NULL,
            timeline_id TEXT NOT NULL,
            base_memory_version INTEGER NOT NULL,
            source_revision_id TEXT NOT NULL,
            status TEXT NOT NULL,
            operations_json TEXT NOT NULL,
            conflicts_json TEXT NOT NULL DEFAULT '[]',
            committed_version INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(timeline_id) REFERENCES $TIMELINES(id) ON DELETE CASCADE,
            FOREIGN KEY(source_revision_id) REFERENCES $REVISIONS(id) ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX idx_change_sets_story ON $CHANGE_SETS(story_id, timeline_id, base_memory_version)",
        """
        CREATE TABLE $JOBS (
            id TEXT PRIMARY KEY NOT NULL,
            story_id TEXT NOT NULL,
            timeline_id TEXT NOT NULL,
            source_revision_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            dedupe_key TEXT NOT NULL UNIQUE,
            base_memory_version INTEGER NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('pending','running','completed','failed','stale')),
            attempts INTEGER NOT NULL DEFAULT 0,
            error TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(timeline_id) REFERENCES $TIMELINES(id) ON DELETE CASCADE,
            FOREIGN KEY(source_revision_id) REFERENCES $REVISIONS(id) ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX idx_jobs_story_state ON $JOBS(story_id, timeline_id, state, created_at)",
        """
        CREATE TABLE $SNAPSHOTS (
            id TEXT PRIMARY KEY NOT NULL,
            story_id TEXT NOT NULL,
            timeline_id TEXT NOT NULL,
            sequence_no INTEGER NOT NULL,
            memory_version INTEGER NOT NULL,
            snapshot_json TEXT NOT NULL,
            log_cursor TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE,
            FOREIGN KEY(timeline_id) REFERENCES $TIMELINES(id) ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX idx_snapshots_story ON $SNAPSHOTS(story_id, timeline_id, memory_version DESC)",
        """
        CREATE TABLE $WORKSPACE_STATE (
            story_id TEXT NOT NULL,
            workspace TEXT NOT NULL CHECK(workspace IN ('discussion','prose')),
            draft TEXT NOT NULL DEFAULT '',
            first_visible_index INTEGER NOT NULL DEFAULT 0,
            first_visible_offset INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(story_id, workspace),
            FOREIGN KEY(story_id) REFERENCES $STORIES(id) ON DELETE CASCADE
        )
        """.trimIndent()
    ) + MANUAL_MEMORY_CHANGE_STATEMENTS + MIGRATION_3_TO_4_STATEMENTS + MIGRATION_4_TO_5_STATEMENTS
}
