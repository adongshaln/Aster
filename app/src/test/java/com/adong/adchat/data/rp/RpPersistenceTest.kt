package com.adong.adchat.data.rp

import android.content.Context
import com.adong.adchat.data.story.StoryCharacterCard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RpPersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val root: File = Files.createTempDirectory("rp-test").toFile()
    private val card = StoryCharacterCard(name = "朝凪", description = "图书管理员", personality = "", scenario = "")

    private fun session(id: String, vararg texts: String) = RpSession(
        id = id, title = id, config = RpConfig(card = card),
        messages = texts.mapIndexed { i, t -> RpMessage(id = i + 1L, role = if (i % 2 == 0) "assistant" else "user", swipes = listOf(RpSwipe(t))) }
    )

    @Test
    fun storeWritesOneFilePerSessionAndReloads() {
        val store = RpStore(root, null, debounceMs = 10)
        store.save(session("a", "开场"))
        store.save(session("b", "开场", "你好"))
        store.flush()
        assertTrue(File(root, "sessions/a.json").exists())
        assertTrue(File(root, "sessions/index.json").exists())
        val loaded = RpStore(root, null).loadAll()
        assertEquals(setOf("a", "b"), loaded.map { it.id }.toSet())
        assertEquals("你好", loaded.first { it.id == "b" }.messages[1].content)
        assertEquals(2, store.listIndex().size)
        store.delete("a")
        store.flush()
        assertFalse(File(root, "sessions/a.json").exists())
        assertEquals(listOf("b"), RpStore(root, null).loadAll().map { it.id })
    }

    @Test
    fun debouncedSavesKeepTheLatestVersion() {
        val store = RpStore(root, null, debounceMs = 50)
        repeat(20) { store.save(session("a", "开场$it")) }
        store.flush()
        assertEquals("开场19", store.loadSession("a")!!.messages[0].content)
        assertFalse(File(root, "sessions/a.json.tmp").exists())
    }

    @Test
    fun checkpointPersistsPartialReplyAsInterruptedSwipe() {
        val store = RpStore(root, null, debounceMs = 10, checkpointIntervalMs = 10_000)
        val base = session("a", "开场", "你好")
        store.checkpoint(base, RpPendingReply(RpGenerationType.Normal, "她慢慢"))
        store.checkpoint(base, RpPendingReply(RpGenerationType.Normal, "她慢慢地抬"))
        store.flush()
        val restored = store.loadSession("a")!!
        assertEquals("她慢慢", restored.messages.last().content)
        assertTrue(restored.messages.last().current.interrupted)
        store.checkpoint(base, RpPendingReply(RpGenerationType.Normal, "她慢慢地抬头"), force = true)
        store.flush()
        assertEquals("她慢慢地抬头", store.loadSession("a")!!.messages.last().content)
    }

    @Test
    fun legacyPreferencesAreMigratedOnceAndRemoved() {
        val prefs = context.getSharedPreferences("rp-legacy-test", Context.MODE_PRIVATE)
        val legacy = JSONArray().put(
            JSONObject().put("id", "old").put("title", "旧剧情").put("messages", JSONArray()
                .put(JSONObject().put("id", 1L).put("role", "assistant").put("content", "开场")))
        )
        prefs.edit().putString("sessions", legacy.toString()).commit()
        val loaded = RpStore(root, prefs).loadAll()
        assertEquals(listOf("old"), loaded.map { it.id })
        assertEquals("开场", loaded[0].messages[0].content)
        assertNull(prefs.getString("sessions", null))
        assertEquals(1, RpStore(root, prefs).loadAll().size)
    }

    @Test
    fun variablesPersistPerScopeAndFloorsKeyById() {
        val vars = RpSessionVariables(root, "s1", "card:x")
        vars.replace(RpVarScope.Chat, JSONObject().put("世界", "图书馆"))
        vars.replace(RpVarScope.Message, JSONObject().put("hp", 1), messageId = 10, swipeId = 0)
        vars.replace(RpVarScope.Message, JSONObject().put("hp", 2), messageId = 10, swipeId = 1)
        vars.replace(RpVarScope.Message, JSONObject().put("mp", 5), messageId = 20, swipeId = 0)
        vars.replace(RpVarScope.Global, JSONObject().put("g", true))
        vars.replace(RpVarScope.Character, JSONObject().put("c", "卡"))

        val reopened = RpSessionVariables(root, "s1", "card:x")
        assertEquals("图书馆", reopened.table(RpVarScope.Chat).getString("世界"))
        assertEquals(2, reopened.table(RpVarScope.Message, 10, 1).getInt("hp"))
        assertTrue(RpSessionVariables(root, "s2", "card:x").table(RpVarScope.Character).has("c"))
        assertTrue(RpSessionVariables(root, "s2", "card:y").table(RpVarScope.Global).getBoolean("g"))
        assertFalse(RpSessionVariables(root, "s2", "card:y").table(RpVarScope.Character).has("c"))
        assertFalse(RpSessionVariables(root, "s2", "card:x").table(RpVarScope.Chat).has("世界"))

        val messages = listOf(
            RpMessage(id = 10, role = "assistant", swipes = listOf(RpSwipe("a"), RpSwipe("b")), swipeId = 1),
            RpMessage(id = 20, role = "user", swipes = listOf(RpSwipe("c")))
        )
        val merged = reopened.merged(messages, untilMessageId = 20)
        assertEquals(2, merged.getInt("hp"))
        assertEquals(5, merged.getInt("mp"))
        assertEquals("卡", merged.getString("c"))
        assertEquals(20L to 0, reopened.latestFloor(messages))

        reopened.dropFloors(listOf(10L))
        assertFalse(reopened.hasFloor(10, 1))
        assertTrue(RpSessionVariables(root, "s1", "card:x").hasFloor(20, 0))
    }

    @Test
    fun swipeDeletionRekeysFloors() {
        val vars = RpSessionVariables(root, "s3", "card:x")
        (0..2).forEach { vars.replace(RpVarScope.Message, JSONObject().put("v", it), messageId = 7, swipeId = it) }
        vars.onSwipeDeleted(7, 1)
        assertEquals(0, vars.table(RpVarScope.Message, 7, 0).getInt("v"))
        assertEquals(2, vars.table(RpVarScope.Message, 7, 1).getInt("v"))
        assertFalse(vars.hasFloor(7, 2))
    }

    @Test
    fun legacyHelperStateIsMigratedByListIndex() {
        val legacy = File(root, "legacy.json")
        legacy.writeText(JSONObject()
            .put("chat", JSONObject().put("k", 1))
            .put("message", JSONObject().put("1", JSONObject().put("stat", 9)))
            .put("storage", JSONObject().put("key", "v")).toString())
        val vars = RpSessionVariables(root, "s4", "card:z")
        val messages = listOf(RpMessage(id = 100, role = "assistant"), RpMessage(id = 200, role = "user"))
        vars.migrateLegacy(legacy, messages)
        assertEquals(1, vars.table(RpVarScope.Chat).getInt("k"))
        assertEquals(9, vars.table(RpVarScope.Message, 200, 0).getInt("stat"))
        assertEquals("v", vars.storage().getString("key"))
        assertFalse(legacy.exists())
    }

    @Test
    fun personaResolutionOrder() {
        val store = RpPersonaStore(File(root, "personas.json"))
        val s = session("p", "开场")
        assertEquals(RpPersonaSource.Fallback, store.resolve(s, "阿东").source)
        assertEquals("阿东", store.context(s, "阿东").name)

        val default = store.upsert(RpPersona(name = "默认", description = "普通人"))
        val knight = store.upsert(RpPersona(name = "骑士", position = RpPersonaPosition.AtDepth, depth = 3))
        val mage = store.upsert(RpPersona(name = "法师"))
        assertEquals(default.id, store.library().defaultId)
        assertEquals(RpPersonaSource.Default, store.resolve(s, "阿东").source)

        store.connect(knight.id, s.cardKey)
        val byCard = store.resolve(s, "阿东")
        assertEquals(RpPersonaSource.Character, byCard.source)
        assertEquals("骑士", byCard.persona.name)
        assertEquals(RpPersonaPosition.AtDepth, byCard.toContext().position)
        assertEquals(3, byCard.toContext().depth)

        val locked = s.copy(personaId = mage.id)
        assertEquals("法师", RpPersonaStore(File(root, "personas.json")).resolve(locked, "阿东").persona.name)
        assertEquals("骑士", store.resolve(s.copy(personaId = "gone"), "阿东").persona.name)

        store.connect(mage.id, s.cardKey)
        assertEquals("法师", store.resolve(s, "阿东").persona.name)
        assertFalse(s.cardKey in store.get(knight.id)!!.connections)
        store.delete(default.id)
        assertTrue(store.library().defaultId != default.id)
    }

    @Test
    fun legacyUserPersonaMigratesToDefaultThenSessionLock() {
        val store = RpPersonaStore(File(root, "personas2.json"))
        val first = session("a", "开场").copy(config = RpConfig(card = card, userPersona = "我是旅人"))
        val migrated = store.migrateLegacy(first, "阿东")
        assertEquals("", migrated.config.userPersona)
        assertNull(migrated.personaId)
        assertEquals("我是旅人", store.resolve(migrated, "阿东").persona.description)
        assertEquals("阿东", store.resolve(migrated, "阿东").persona.name)

        val second = session("b", "开场").copy(config = RpConfig(card = card, userPersona = "我是刺客"))
        val locked = store.migrateLegacy(second, "阿东")
        assertEquals("我是刺客", store.resolve(locked, "阿东").persona.description)
        assertEquals(RpPersonaSource.Chat, store.resolve(locked, "阿东").source)
        assertEquals(locked, store.migrateLegacy(locked, "阿东"))
    }
}
