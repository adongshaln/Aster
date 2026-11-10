package com.adong.adchat.data.rp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpChatOpsTest {
    private fun msg(id: Long, role: String, vararg swipes: String, swipeId: Int = 0) =
        RpMessage(id = id, role = role, swipes = swipes.map { RpSwipe(it) }, swipeId = swipeId)

    private val greeting = msg(1, "assistant", "开场一", "开场二")
    private val chat = RpSession(
        tainted = true,
        messages = listOf(greeting, msg(2, "user", "你好"), msg(3, "assistant", "回复A", "回复B", swipeId = 0))
    )

    @Test
    fun swipeLeftWrapsAndRightMovesWithinRange() {
        val right = (RpChatOps.swipe(chat, 1) as RpSwipeResult.Moved).session
        assertEquals("回复B", right.messages.last().content)
        val left = (RpChatOps.swipe(chat, -1) as RpSwipeResult.Moved).session
        assertEquals("回复B", left.messages.last().content)
    }

    @Test
    fun overswipeRequestsGeneration() {
        val atEnd = chat.copy(messages = chat.messages.dropLast(1) + chat.messages.last().copy(swipeId = 1))
        assertEquals(RpSwipeResult.NeedsGeneration, RpChatOps.swipe(atEnd, 1))
        val committed = RpChatOps.addSwipe(atEnd, RpSwipe("回复C"))
        assertEquals(3, committed.messages.last().swipeCount)
        assertEquals("回复C", committed.messages.last().content)
        assertEquals(3, committed.messages.size)
    }

    @Test
    fun pristineGreetingCyclesInsteadOfGenerating() {
        val pristine = RpSession(messages = listOf(greeting.copy(swipeId = 1)))
        val result = RpChatOps.swipe(pristine, 1) as RpSwipeResult.Moved
        assertEquals("开场一", result.session.messages.single().content)
        assertEquals(0, result.session.config.openingIndex)
        val tainted = pristine.copy(tainted = true)
        assertEquals(RpSwipeResult.NeedsGeneration, RpChatOps.swipe(tainted, 1))
    }

    @Test
    fun userAndErrorMessagesAreNotSwipeable() {
        val endsWithUser = chat.copy(messages = chat.messages.take(2))
        assertEquals(RpSwipeResult.None, RpChatOps.swipe(endsWithUser, 1))
        val endsWithError = RpChatOps.appendError(chat, "失败")
        assertEquals(RpSwipeResult.None, RpChatOps.swipe(endsWithError, 1))
    }

    @Test
    fun deleteSwipeFollowsSillyTavernIndexRule() {
        val three = chat.copy(messages = chat.messages.dropLast(1) + msg(3, "assistant", "a", "b", "c", swipeId = 1))
        // Deleting before the current one shifts left.
        assertEquals(0, RpChatOps.deleteSwipe(three, 3, 0)!!.messages.last().swipeId)
        assertEquals("b", RpChatOps.deleteSwipe(three, 3, 0)!!.messages.last().content)
        // Deleting after keeps the index.
        assertEquals(1, RpChatOps.deleteSwipe(three, 3, 2)!!.messages.last().swipeId)
        // Deleting the current clamps to the new last index.
        val last = three.copy(messages = three.messages.dropLast(1) + three.messages.last().copy(swipeId = 2))
        val afterCurrent = RpChatOps.deleteSwipe(last, 3)!!
        assertEquals(1, afterCurrent.messages.last().swipeId)
        assertEquals("b", afterCurrent.messages.last().content)
        // A single swipe cannot be deleted.
        assertNull(RpChatOps.deleteSwipe(chat, 2))
        assertEquals(null, RpChatOps.swipeIndexAfterDelete(1, 1))
        assertEquals(1, RpChatOps.swipeIndexAfterDelete(0, 2))
        assertEquals(0, RpChatOps.swipeIndexAfterDelete(2, 0))
    }

    @Test
    fun editWritesCurrentSwipeAndTaints() {
        val pristine = RpSession(messages = listOf(greeting))
        val edited = RpChatOps.edit(pristine, 1, "改过的开场")!!
        assertEquals("改过的开场", edited.messages[0].content)
        assertEquals("开场二", edited.messages[0].swipes[1].content)
        assertTrue(edited.tainted)
        assertNull(RpChatOps.edit(pristine, 99, "x"))
    }

    @Test
    fun deleteAndTruncateReportRemovedIds() {
        val (deleted, ids) = RpChatOps.delete(chat, 2)!!
        assertEquals(listOf(1L, 3L), deleted.messages.map { it.id })
        assertEquals(listOf(2L), ids)
        val (truncated, removed) = RpChatOps.truncateFrom(chat, 2)!!
        assertEquals(listOf(1L), truncated.messages.map { it.id })
        assertEquals(listOf(2L, 3L), removed)
    }

    @Test
    fun hideExcludesFromPrompt() {
        val hidden = RpChatOps.setHidden(chat, listOf(2L), true)!!
        assertEquals(listOf(1L, 3L), hidden.promptMessages().map { it.id })
        assertEquals(3, hidden.messages.size)
        assertNull(RpChatOps.setHidden(hidden, listOf(2L), true))
        assertEquals(3, RpChatOps.setHidden(hidden, listOf(2L), false)!!.promptMessages().size)
    }

    @Test
    fun errorFloorIsReplacedAndRemoved() {
        val failed = RpChatOps.appendError(chat.copy(messages = chat.messages.take(2)), "超时")
        assertTrue(failed.messages.last().isError)
        assertEquals(2, failed.promptMessages().size)
        val again = RpChatOps.appendError(failed, "又超时")
        assertEquals(3, again.messages.size)
        assertEquals("又超时", again.messages.last().errorText)
        val recovered = RpChatOps.appendReply(again, RpSwipe("终于"))
        assertEquals(listOf("开场一", "你好", "终于"), recovered.messages.map { it.content })
        assertNotNull(RpChatOps.removeTrailingError(again))
        assertNull(RpChatOps.removeTrailingError(recovered))
    }

    @Test
    fun continueAppendsToCurrentSwipe() {
        val continued = RpChatOps.continueWith(chat, "后续", separator = " ")!!
        assertEquals("回复A 后续", continued.messages.last().content)
        assertEquals("回复B", continued.messages.last().swipes[1].content)
        assertNull(RpChatOps.continueWith(chat.copy(messages = chat.messages.take(2)), "x"))
    }

    @Test
    fun dropLastReplyAndSwipeBase() {
        val (base, ids) = RpChatOps.dropLastReply(chat)!!
        assertEquals(listOf(1L, 2L), base.messages.map { it.id })
        assertEquals(listOf(3L), ids)
        assertEquals(listOf(1L, 2L), RpChatOps.swipeBase(chat).map { it.id })
        // The greeting alone is never dropped by regenerate.
        assertNull(RpChatOps.dropLastReply(RpSession(messages = listOf(greeting))))
    }

    @Test
    fun branchCopiesPrefixWithMainChat() {
        val branch = RpChatOps.branch(chat.copy(id = "root", title = "主线"), 2)!!
        assertTrue(branch.id != "root")
        assertEquals("root", branch.mainChat)
        assertEquals(listOf(1L, 2L), branch.messages.map { it.id })
        assertEquals("主线 - 分支", branch.title)
    }

    @Test
    fun checkpointStoresInterruptedSwipe() {
        val base = chat.copy(messages = chat.messages.take(2))
        val normal = RpChatOps.checkpoint(base, RpPendingReply(RpGenerationType.Normal, "半句"))
        assertEquals("半句", normal.messages.last().content)
        assertTrue(normal.messages.last().current.interrupted)
        val swipe = RpChatOps.checkpoint(chat, RpPendingReply(RpGenerationType.Swipe, "新"))
        assertEquals(3, swipe.messages.last().swipeCount)
    }

    @Test
    fun selectSwipeAndInsert() {
        assertEquals("回复B", RpChatOps.selectSwipe(chat, 3, 1)!!.messages.last().content)
        assertNull(RpChatOps.selectSwipe(chat, 3, 5))
        val inserted = RpChatOps.insert(chat, listOf(msg(9, "system", "旁白")), 1)
        assertEquals(listOf(1L, 9L, 2L, 3L), inserted.messages.map { it.id })
    }
}
