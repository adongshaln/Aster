package com.adong.adchat.data.rp

import com.adong.adchat.data.TokenUsage
import com.adong.adchat.data.story.ImportedCharacterCard
import com.adong.adchat.data.story.StoryCharacterCard
import com.adong.adchat.data.story.StoryWorldBook
import com.adong.adchat.data.story.StoryWorldBookEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpModelsTest {
    private val card = StoryCharacterCard(
        name = "朝凪",
        description = "一个安静的图书管理员",
        personality = "温和而疏离",
        scenario = "旧图书馆的深夜",
        mesExample = "{{user}}: 你好。\n{{char}}: 嗯。",
        worldBook = StoryWorldBook(entries = listOf(StoryWorldBookEntry(keys = listOf("图书馆"), content = "馆内藏有禁书")))
    )

    @Test
    fun textConfigMapsToCharacterContext() {
        val config = RpConfig(source = RpSource.Text, card = card, userPersona = "我是访客")
        val context = config.toCharacterContext(worldBefore = "前置设定", worldAfter = "后置设定")
        assertEquals("朝凪", context.name)
        assertEquals("一个安静的图书管理员", context.description)
        assertEquals("温和而疏离", context.personality)
        assertEquals("旧图书馆的深夜", context.scenario)
        assertEquals("我是访客", context.persona)
        assertEquals(card.mesExample, context.mesExample)
        assertEquals("前置设定", context.wiBefore)
        assertEquals("后置设定", context.wiAfter)
    }

    @Test
    fun importedCardMapsToCharacterContext() {
        val config = RpConfig.fromImported(ImportedCharacterCard(card = card, opening = "她抬起头。"), userPersona = "访客")
        assertEquals(RpSource.CharacterCard, config.source)
        assertEquals("她抬起头。", config.opening)
        val context = config.toCharacterContext()
        assertEquals("朝凪", context.name)
        assertEquals("访客", context.persona)
    }

    @Test
    fun configJsonRoundTrip() {
        val config = RpConfig(source = RpSource.CharacterCard, card = card, opening = "开场", userPersona = "persona", presetId = "preset-1")
        val restored = RpConfig.fromJson(config.toJson())
        assertEquals(config.source, restored.source)
        assertEquals(config.card.name, restored.card.name)
        assertEquals(config.card.description, restored.card.description)
        assertEquals(config.card.mesExample, restored.card.mesExample)
        assertEquals(1, restored.card.worldBook.entries.size)
        assertEquals("馆内藏有禁书", restored.card.worldBook.entries.first().content)
        assertEquals(config.opening, restored.opening)
        assertEquals(config.userPersona, restored.userPersona)
        assertEquals(config.presetId, restored.presetId)
    }

    @Test
    fun configWithoutPresetRoundTripsToNull() {
        val restored = RpConfig.fromJson(RpConfig().toJson())
        assertNull(restored.presetId)
        assertEquals(RpSource.Text, restored.source)
    }

    @Test
    fun sessionJsonRoundTrip() {
        val session = RpSession(
            id = "session-1",
            title = "深夜图书馆",
            createdAt = 1000L,
            updatedAt = 2000L,
            config = RpConfig(source = RpSource.CharacterCard, card = card, opening = "开场"),
            messages = listOf(
                RpMessage(id = 1L, role = "assistant", swipes = listOf(RpSwipe("她抬起头。", model = "m"), RpSwipe("她合上书。", startedAt = 5, finishedAt = 9)), swipeId = 1),
                RpMessage(id = 2L, role = "user", swipes = listOf(RpSwipe("你好", usage = TokenUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15))), hidden = true)
            ),
            profileId = "profile-1",
            model = "gemini-4-argon-high",
            injectionEnabled = true,
            memoryEnabled = true,
            tainted = true,
            personaId = "p1",
            authorsNote = RpAuthorsNote(text = "保持紧张感", depth = 2),
            timedWorldInfo = JSONObject().put("sticky", JSONObject()),
            cardRegexAllowed = true,
            metadata = JSONObject().put(RpSession.META_MAIN_CHAT, "root")
        )
        val restored = RpSession.fromJson(JSONObject(session.toJson().toString()))
        assertEquals(session.id, restored.id)
        assertEquals(session.title, restored.title)
        assertEquals(session.createdAt, restored.createdAt)
        assertEquals(session.updatedAt, restored.updatedAt)
        assertEquals(session.profileId, restored.profileId)
        assertEquals(session.model, restored.model)
        assertTrue(restored.injectionEnabled)
        assertTrue(restored.memoryEnabled)
        assertTrue(restored.tainted)
        assertEquals("p1", restored.personaId)
        assertEquals("保持紧张感", restored.authorsNote.text)
        assertEquals(2, restored.authorsNote.depth)
        assertTrue(restored.timedWorldInfo.has("sticky"))
        assertEquals(true, restored.cardRegexAllowed)
        assertNull(restored.cardScriptsAllowed)
        assertEquals("root", restored.mainChat)
        assertEquals(RpSource.CharacterCard, restored.config.source)
        assertEquals(2, restored.messages.size)
        assertEquals("她合上书。", restored.messages[0].content)
        assertEquals(2, restored.messages[0].swipeCount)
        assertEquals(9L, restored.messages[0].current.finishedAt)
        assertEquals("user", restored.messages[1].role)
        assertTrue(restored.messages[1].hidden)
        assertEquals(15, restored.messages[1].current.usage?.totalTokens)
        assertEquals(listOf(1L), restored.promptMessages().map { it.id })
    }

    @Test
    fun legacySessionDecodesContentAsSingleSwipeAndLiftsGreetings() {
        val legacy = JSONObject()
            .put("id", "old")
            .put("config", RpConfig(card = card, opening = "开场二", openings = listOf("开场一", "开场二"), openingIndex = 1).toJson())
            .put("messages", JSONArray()
                .put(JSONObject().put("id", 7L).put("role", "assistant").put("content", "开场二").put("model", "m"))
                .put(JSONObject().put("id", 8L).put("role", "user").put("content", "你好"))
                .put(JSONObject().put("id", 9L).put("role", "assistant").put("content", "回复").put("is_interrupted", true))
                .put(JSONObject().put("id", 10L).put("role", "assistant").put("content", "网络错误").put("is_error", true)))
        val session = RpSession.fromJson(legacy)
        assertEquals(4, session.messages.size)
        val first = session.messages[0]
        assertEquals(7L, first.id)
        assertEquals(listOf("开场一", "开场二"), first.swipes.map { it.content })
        assertEquals(1, first.swipeId)
        assertEquals("m", first.current.model)
        assertTrue(session.messages[2].current.interrupted)
        assertTrue(session.messages[3].isError)
        assertEquals("网络错误", session.messages[3].errorText)
        assertEquals("", session.messages[3].content)
        assertTrue(session.tainted)
        assertEquals(listOf(7L, 8L, 9L), session.promptMessages().map { it.id })
    }

    @Test
    fun legacyPristineSessionStaysUntainted() {
        val legacy = JSONObject().put("id", "old")
            .put("messages", JSONArray().put(JSONObject().put("id", 1L).put("role", "assistant").put("content", "开场")))
        val session = RpSession.fromJson(legacy)
        assertTrue(!session.tainted)
        assertTrue(session.pristine)
    }

    @Test
    fun duplicateIdsAreRepairedOnLoad() {
        val obj = RpSession(messages = listOf(RpMessage(id = 5, role = "user", swipes = listOf(RpSwipe("a"))), RpMessage(id = 5, role = "assistant", swipes = listOf(RpSwipe("b"))))).toJson()
        val restored = RpSession.fromJson(obj)
        assertEquals(2, restored.messages.map { it.id }.toSet().size)
        assertTrue(RpIds.next() > restored.messages.maxOf { it.id })
    }

    @Test
    fun messageConversions() {
        val message = RpMessage(id = 3, role = "assistant", name = "朝凪", swipes = listOf(RpSwipe("a"), RpSwipe("b", reasoning = "想", interrupted = true)), swipeId = 1)
        val chat = message.toChatMessage()
        assertEquals("b", chat.content)
        assertEquals("想", chat.reasoning)
        assertTrue(chat.isInterrupted)
        assertEquals(RpPromptMessage(3, "assistant", "b", "朝凪"), message.toPromptMessage())
        val error = RpMessage(role = "assistant", isError = true, errorText = "失败")
        assertEquals("失败", error.toChatMessage().content)
        assertTrue(error.toChatMessage().isError)
    }

    @Test
    fun cardKeyIsStableAndDistinct() {
        assertEquals(RpCardKey.of(card), RpCardKey.of(card.copy(description = "改了描述")))
        assertTrue(RpCardKey.of(card) != RpCardKey.of(card.copy(name = "别人")))
        assertEquals("", RpCardKey.of(card.copy(name = "")))
    }
}
