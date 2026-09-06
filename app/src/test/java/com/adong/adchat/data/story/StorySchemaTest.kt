package com.adong.adchat.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorySchemaTest {
    @Test
    fun schemaUsesGlobalTimelineSequenceAndDurableJobDeduplication() {
        val sql = StorySchema.CREATE_STATEMENTS.joinToString("\n")

        assertTrue(sql.contains("UNIQUE(timeline_id, sequence_no)"))
        assertTrue(sql.contains("dedupe_key TEXT NOT NULL UNIQUE"))
        assertTrue(sql.contains("FOREIGN KEY(source_revision_id)"))
    }

    @Test
    fun manualMemoryLogHasVersionBoundaryAndAuditSnapshots() {
        val sql = StorySchema.MIGRATION_1_TO_2_STATEMENTS.joinToString("\n")

        assertTrue(sql.contains("CREATE TABLE ${StorySchema.MANUAL_MEMORY_CHANGES}"))
        assertTrue(sql.contains("base_memory_version INTEGER NOT NULL"))
        assertTrue(sql.contains("committed_version INTEGER NOT NULL"))
        assertTrue(sql.contains("before_json TEXT"))
        assertTrue(sql.contains("after_json TEXT"))
        assertTrue(sql.contains("CHECK(operation IN ('add','update','pin','deactivate'))"))
        assertTrue(sql.contains("UNIQUE(story_id, committed_version)"))
        assertTrue(sql.contains("FOREIGN KEY(record_id) REFERENCES ${StorySchema.MEMORIES}(id)"))
        assertEquals(3, StoryDatabase.DATABASE_VERSION)
    }

    @Test
    fun schemaAndMigrationContainNoDestructiveStatements() {
        val sql = (StorySchema.CREATE_STATEMENTS + StorySchema.MIGRATION_1_TO_2_STATEMENTS + StorySchema.MIGRATION_2_TO_3_STATEMENTS)
            .joinToString("\n")
            .uppercase()
        assertFalse(sql.contains("DROP TABLE"))
        assertFalse(sql.contains("DELETE FROM"))
    }
}
