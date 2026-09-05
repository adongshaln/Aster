package com.adong.adchat.data.story

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
    fun initialSchemaContainsNoDestructiveMigrationStatements() {
        val sql = StorySchema.CREATE_STATEMENTS.joinToString("\n").uppercase()
        assertFalse(sql.contains("DROP TABLE"))
        assertFalse(sql.contains("DELETE FROM"))
    }
}
