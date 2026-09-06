package com.adong.adchat.data.story

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StoryStateMigrationTest {
    @Test fun populatedOlderDatabasesUpgradeWithoutReclassifyingOldData() {
        val context: Context = RuntimeEnvironment.getApplication()
        for (version in listOf(1, 2, 3)) {
            context.deleteDatabase(StoryDatabase.DATABASE_NAME)
            val old = object : SQLiteOpenHelper(context, StoryDatabase.DATABASE_NAME, null, version) {
                override fun onCreate(db: SQLiteDatabase) {
                    StorySchema.CREATE_STATEMENTS.filterNot {
                        (version == 1 && it.contains(StorySchema.MANUAL_MEMORY_CHANGES)) || it.contains(StorySchema.CONFLICTS)
                    }.forEach { db.execSQL(it.lineSequence().filterNot { line -> version < 3 && line.trim() == "state_key TEXT," }.joinToString("\n")) }
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("not used")
            }
            val db = old.writableDatabase
            // Ensure the fixture actually has the historical schema, not a disguised v3 database.
            db.rawQuery("PRAGMA table_info(${StorySchema.MEMORIES})", null).use { cursor ->
                while (cursor.moveToNext()) if (version < 3) assertNotEquals("state_key", cursor.getString(1))
            }
            db.execSQL("INSERT INTO stories(id,title,profile_id,model,current_timeline_id,created_at,updated_at) VALUES('s','旧故事','p','m','t',1,1)")
            db.execSQL("INSERT INTO timelines(id,story_id,created_at) VALUES('t','s',1)")
            db.execSQL("INSERT INTO memory_records(id,story_id,timeline_id,kind,content,nature,pinned,created_at,updated_at) VALUES('f','s','t','current_state','旧版位置描述','user_confirmed',1,1,1)")
            if (version >= 2) db.execSQL("""INSERT INTO manual_memory_changes(id,story_id,timeline_id,record_id,operation,
                base_memory_version,committed_version,before_json,after_json,created_at)
                VALUES('audit','s','t','f','add',0,1,NULL,'{"legacy":"unchanged"}',1)""")
            old.close()
            val upgraded = StoryDatabase(context)
            try {
                assertEquals(4, upgraded.readableDatabase.version)
                upgraded.readableDatabase.rawQuery("SELECT content,state_key,pinned FROM memory_records WHERE id='f'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("旧版位置描述", it.getString(0)); assertTrue(it.isNull(1)); assertEquals(1, it.getInt(2))
                }
                upgraded.readableDatabase.rawQuery("SELECT after_json FROM manual_memory_changes", null).use {
                    if (version >= 2) { assertTrue(it.moveToFirst()); assertEquals("{\"legacy\":\"unchanged\"}", it.getString(0)) }
                    else assertFalse(it.moveToFirst())
                }
                upgraded.readableDatabase.rawQuery("SELECT COUNT(*) FROM state_conflicts", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                upgraded.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            } finally { upgraded.close() }
            val archive = StoryArchiveStore(context)
            try {
                val legacy = archive.listMemoryRecords("s", "t").single()
                assertNull(legacy.stateKey)
                assertTrue(renderStoryMemory(legacy).contains("非当前值"))
                assertEquals(legacy.id, StoryStateProjection.project(listOf(legacy)).records.single().id)
            } finally { archive.close() }
        }
    }
}
