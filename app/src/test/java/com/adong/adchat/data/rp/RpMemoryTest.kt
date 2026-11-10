package com.adong.adchat.data.rp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpMemoryTest {
    private fun turns(count: Int, prefix: String = "消息"): List<RpMessage> = (1..count).map {
        RpMessage(id = it.toLong(), role = if (it % 2 == 0) "assistant" else "user", swipes = listOf(RpSwipe("$prefix$it")))
    }

    private fun commit(plan: RpMemoryPlan, summary: String = "摘要") = RpMemoryState(summary, plan.coveredUntilId, plan.fingerprint)

    @Test
    fun planReturnsNullWhenNothingBeyondRecentWindow() {
        assertNull(RpMemory.plan(turns(5), null))
    }

    @Test
    fun planSummarizesOlderTurnsAndKeepsRecentVerbatim() {
        val plan = RpMemory.plan(turns(6), null)!!
        assertEquals(2L, plan.coveredUntilId)
        assertEquals(2, plan.coveredCount)
        assertEquals(1, plan.parts.size)
        assertTrue(plan.parts[0].contains("消息1"))
        assertTrue(plan.parts[0].contains("消息2"))
        assertTrue(!plan.parts[0].contains("消息3"))
    }

    @Test
    fun planContinuesAfterValidStateAndPrependsSummary() {
        val messages = turns(10)
        val first = RpMemory.plan(turns(6), null)!!
        val state = commit(first, "旧摘要内容")
        assertTrue(RpMemory.isValid(state, messages))
        val plan = RpMemory.plan(messages, state)!!
        assertEquals(6L, plan.coveredUntilId)
        assertEquals(6, plan.coveredCount)
        assertTrue(plan.parts[0].contains("旧摘要内容"))
        assertTrue(plan.parts[1].contains("消息3"))
        assertTrue(!plan.parts[1].contains("消息2"))
    }

    @Test
    fun editDeleteSwipeOrTruncateInsideCoveredPrefixInvalidates() {
        val messages = turns(10)
        val state = commit(RpMemory.plan(messages, null)!!)
        assertTrue(RpMemory.isValid(state, messages))
        val session = RpSession(messages = messages)
        assertTrue(!RpMemory.isValid(state, RpChatOps.edit(session, 2, "改了")!!.messages))
        assertTrue(!RpMemory.isValid(state, RpChatOps.delete(session, 1)!!.first.messages))
        assertTrue(!RpMemory.isValid(state, RpChatOps.truncateFrom(session, 3)!!.first.messages))
        assertTrue(!RpMemory.isValid(state, RpChatOps.setHidden(session, listOf(1L), true)!!.messages))
        // Changes after the covered prefix keep it valid.
        assertTrue(RpMemory.isValid(state, RpChatOps.edit(session, 10, "改了")!!.messages))
        assertTrue(RpMemory.isValid(state, RpChatOps.truncateFrom(session, 9)!!.first.messages))
    }

    @Test
    fun uncoveredUsesIdNotIndex() {
        val messages = turns(10)
        val state = commit(RpMemory.plan(messages, null)!!)
        assertEquals((7L..10L).toList(), RpMemory.uncovered(messages, state).map { it.id })
        val truncated = messages.take(4)
        assertEquals(truncated, RpMemory.uncovered(truncated, state))
    }

    @Test
    fun invalidStateTriggersFullRebuild() {
        val messages = turns(10)
        val stale = RpMemoryState("旧", 6L, "bogus")
        val plan = RpMemory.plan(messages, stale)!!
        assertEquals(6L, plan.coveredUntilId)
        assertTrue(plan.parts.none { it.contains("旧") && it.contains("已有历史摘要") })
        assertTrue(plan.parts[0].contains("消息1"))
    }

    @Test
    fun hiddenAndErrorMessagesAreSkipped() {
        val messages = turns(8).toMutableList()
        messages[0] = messages[0].copy(hidden = true)
        messages.add(2, RpMessage(id = 100, role = "assistant", isError = true, errorText = "失败"))
        val plan = RpMemory.plan(messages, null)!!
        assertTrue(plan.parts.none { it.contains("消息1\n") || it.contains("失败") })
    }

    @Test
    fun planChunksLargeTranscriptWithinPipelineLimits() {
        val big = "x".repeat(20_000)
        val messages = (listOf(big, big, big, big, "r1", "r2", "r3", "r4")).mapIndexed { i, text ->
            RpMessage(id = i + 1L, role = if (i % 2 == 0) "user" else "assistant", swipes = listOf(RpSwipe(text)))
        }
        val plan = RpMemory.plan(messages, null)!!
        assertEquals(4L, plan.coveredUntilId)
        assertTrue(plan.parts.size in 1..16)
        assertTrue(plan.parts.all { it.isNotBlank() && it.length <= 29_000 })
    }

    @Test
    fun planSplitsOversizedSingleMessageOnce() {
        val huge = "y".repeat(60_000)
        val messages = listOf(huge, "ok", "r1", "r2", "r3", "r4").mapIndexed { i, text ->
            RpMessage(id = i + 1L, role = if (i % 2 == 0) "user" else "assistant", swipes = listOf(RpSwipe(text)))
        }
        val plan = RpMemory.plan(messages, null)!!
        assertEquals(2L, plan.coveredUntilId)
        assertEquals(60_000 + 4, plan.parts.sumOf { it.count { c -> c == 'y' } } + 4)
        assertTrue(plan.parts.all { it.isNotBlank() && it.length <= 29_000 })
    }

    @Test
    fun longSummaryNoLongerDeadStops() {
        val messages = turns(12)
        val first = RpMemory.plan(messages.take(8), null)!!
        // A 40k-char rolling summary previously exceeded the 29k part limit and blocked every later plan.
        val state = RpMemoryState("摘".repeat(40_000), first.coveredUntilId, first.fingerprint)
        val plan = RpMemory.plan(messages, state)
        assertNotNull(plan)
        assertTrue(plan!!.parts.all { it.length <= 29_000 })
        assertEquals(8L, plan.coveredUntilId)
    }

    @Test
    fun hugeBacklogIsSummarizedAcrossBatchesWithoutLoss() {
        val messages = (1..60).map { i ->
            RpMessage(id = i.toLong(), role = if (i % 2 == 0) "assistant" else "user", swipes = listOf(RpSwipe("$i:" + "z".repeat(9_000))))
        }
        var state: RpMemoryState? = null
        var rounds = 0
        while (true) {
            val plan = RpMemory.plan(messages, state) ?: break
            assertTrue(plan.parts.size <= 16 && plan.parts.all { it.length <= 29_000 })
            state = commit(plan, "摘要$rounds")
            rounds++
            check(rounds < 20)
        }
        assertEquals(56L, state!!.coveredUntilId)
        assertTrue(rounds >= 2)
    }

    @Test
    fun fingerprintMissingIdIsNull() {
        assertNull(RpMemory.fingerprint(turns(3), 42))
    }

    @Test
    fun budgetFilledByLargeMessagesStillYieldsPlan() {
        val texts = (1..5).map { "w".repeat(70_000) } + (1..30).map { "短$it" } + listOf("r1", "r2", "r3", "r4")
        val messages = texts.mapIndexed { i, t -> RpMessage(id = i + 1L, role = if (i % 2 == 0) "user" else "assistant", swipes = listOf(RpSwipe(t))) }
        val plan = RpMemory.plan(messages, null)!!
        assertTrue(plan.parts.size <= 16 && plan.parts.all { it.length <= 29_000 })
        assertTrue(plan.coveredUntilId >= 5L)
    }
}
