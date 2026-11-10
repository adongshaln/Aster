package com.adong.adchat.data.rp

import com.adong.adchat.data.story.StoryCharacterCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpOpeningsTest {
    private val card = StoryCharacterCard(name = "朝凪", description = "图书管理员", personality = "", scenario = "")
    private val config = RpConfig(
        source = RpSource.CharacterCard, card = card,
        opening = "开场一", openings = listOf("开场一", "开场二", "开场三")
    )

    private fun fresh(): RpSession = RpSession(config = config, messages = listOfNotNull(RpOpenings.greetingMessage(config, "m")))

    @Test
    fun greetingsBecomeSwipesOfMessageZero() {
        val message = requireNotNull(RpOpenings.greetingMessage(config, "m"))
        assertEquals("assistant", message.role)
        assertEquals("朝凪", message.name)
        assertEquals(listOf("开场一", "开场二", "开场三"), message.swipes.map { it.content })
        assertEquals(0, message.swipeId)
        assertNull(RpOpenings.greetingMessage(config.copy(opening = "", openings = emptyList())))
    }

    @Test
    fun rawMacrosAreKeptInGreetings() {
        val message = requireNotNull(RpOpenings.greetingMessage(config.copy(opening = "{{user}}，你好", openings = emptyList())))
        assertEquals("{{user}}，你好", message.content)
    }

    @Test
    fun editedOpeningReplacesItsSlot() {
        val edited = config.copy(opening = "我改过的开场")
        assertEquals(listOf("我改过的开场", "开场二", "开场三"), edited.greetings)
    }

    @Test
    fun rotateWrapsWhilePristine() {
        val session = fresh()
        val forward = requireNotNull(RpOpenings.rotated(session, 1))
        assertEquals("开场二", forward.messages.single().content)
        assertEquals(1, forward.config.openingIndex)
        val back = requireNotNull(RpOpenings.rotated(session, -1))
        assertEquals("开场三", back.messages.single().content)
        val wrap = requireNotNull(RpOpenings.rotated(back, 1))
        assertEquals("开场一", wrap.messages.single().content)
    }

    @Test
    fun rotateRefusedOnceChatHasMoved() {
        val withUser = RpChatOps.append(fresh(), "user", "你好")
        assertNull(RpOpenings.rotated(withUser, 1))
        val taintedSingle = fresh().copy(tainted = true)
        assertNull(RpOpenings.rotated(taintedSingle, 1))
        assertTrue(!RpOpenings.canCycle(taintedSingle))
    }

    @Test
    fun rotateNullWithoutMultipleGreetings() {
        val single = config.copy(openings = listOf("开场一"))
        val session = RpSession(config = single, messages = listOfNotNull(RpOpenings.greetingMessage(single)))
        assertNull(RpOpenings.rotated(session, 1))
    }

    @Test
    fun reconcileRebuildsGreetingWhilePristineAndKeepsId() {
        val session = fresh()
        val reimported = config.copy(opening = "新开场", openings = listOf("新开场", "新备选"))
        val updated = RpOpenings.reconcile(session, reimported)
        assertEquals(session.messages[0].id, updated.messages[0].id)
        assertEquals(listOf("新开场", "新备选"), updated.messages[0].swipes.map { it.content })
        assertEquals(reimported, updated.config)
    }

    @Test
    fun reconcileBakesGreetingIntoEmptySession() {
        val updated = RpOpenings.reconcile(RpSession(), config)
        assertEquals(1, updated.messages.size)
        assertEquals(3, updated.messages[0].swipeCount)
    }

    @Test
    fun reconcileNeverTouchesTaintedChat() {
        val chat = RpChatOps.appendReply(RpChatOps.append(fresh(), "user", "你好"), RpSwipe("回复"))
        val updated = RpOpenings.reconcile(chat, config.copy(opening = "别的", openings = listOf("别的")))
        assertEquals(chat.messages, updated.messages)
        assertEquals("别的", updated.config.opening)
    }

    @Test
    fun reconcileLeavesUserFirstHistoryAlone() {
        val session = RpSession(messages = listOf(RpMessage(role = "user", swipes = listOf(RpSwipe("你好")))))
        assertEquals(session.messages, RpOpenings.reconcile(session, config).messages)
    }

    @Test
    fun withIndexSelectsGreeting() {
        val updated = requireNotNull(RpOpenings.withIndex(fresh(), 2))
        assertEquals("开场三", updated.messages[0].content)
        assertNull(RpOpenings.withIndex(fresh(), 5))
    }

    @Test
    fun openingIndexSurvivesConfigRoundTrip() {
        val restored = RpConfig.fromJson(config.copy(openingIndex = 2, opening = "开场三").toJson())
        assertEquals(2, restored.openingIndex)
        assertEquals(listOf("开场一", "开场二", "开场三"), restored.openings)
        assertEquals("开场三", restored.opening)
    }
}
