package com.adong.adchat.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoryContextComposerTest {
    private val storyId = "story-test"
    private val timelineId = "timeline-test"

    @Test
    fun proseGetsConfirmedFactsButNeverDiscussionCandidatesOrInference() {
        val confirmed = memory("confirmed", "王都位于北方。", StoryMemoryNature.UserConfirmed)
        val inference = memory("inference", "国王可能已经中毒。", StoryMemoryNature.Inference)
        val candidate = proposal("candidate", "让国王在下一幕突然死亡。")
        val prose = listOf(
            message("p0", StoryWorkspace.Prose, "user", "她来到王都外。", StoryRevisionState.Complete, 1),
            message("p1", StoryWorkspace.Prose, "assistant", "她抵达王都。", StoryRevisionState.Complete, 2),
            message("p2", StoryWorkspace.Prose, "assistant", "不应进入上下文的中断正文", StoryRevisionState.Interrupted, 3),
            message("p3", StoryWorkspace.Prose, "user", "继续写她进入城门。", StoryRevisionState.Complete, 4)
        )
        val discussion = listOf(
            message("d1", StoryWorkspace.Discussion, "user", "秘密讨论候选：改成南方城市。", StoryRevisionState.Complete, 5)
        )

        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Prose,
            baseInstruction = "正文规则",
            memoryRecords = listOf(confirmed, inference),
            proposals = listOf(candidate),
            proseMessages = prose,
            discussionMessages = discussion
        )

        assertTrue(result.systemPrompt.contains("王都位于北方"))
        assertFalse(result.systemPrompt.contains("国王可能已经中毒"))
        assertFalse(result.systemPrompt.contains("突然死亡"))
        assertFalse(result.systemPrompt.contains("秘密讨论候选"))
        assertTrue(result.history.any { it.content.contains("她抵达王都") })
        assertTrue(result.history.any { it.content.contains("继续写她进入城门") })
        assertFalse(result.history.any { it.content.contains("中断正文") })
        assertFalse(result.history.any { it.content.contains("秘密讨论候选") })
        assertTrue(result.includedProposalIds.isEmpty())
        assertTrue(result.withinHardBudget)
    }

    @Test
    fun discussionCanSeeCandidatesButTheyAreExplicitlyNonAuthoritative() {
        val confirmed = memory("confirmed", "骑士团驻扎在旧港。", StoryMemoryNature.UserConfirmed)
        val inference = memory("inference", "团长也许认识幕后人物。", StoryMemoryNature.Inference)
        val candidate = proposal("candidate", "候选方案：让团长主动求援。")
        val discussion = listOf(
            message("d1", StoryWorkspace.Discussion, "user", "我们讨论一下团长。", StoryRevisionState.Complete, 2)
        )

        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Discussion,
            baseInstruction = "讨论规则",
            memoryRecords = listOf(confirmed, inference),
            proposals = listOf(candidate),
            proseMessages = emptyList(),
            discussionMessages = discussion
        )

        assertTrue(result.systemPrompt.contains("骑士团驻扎在旧港"))
        assertTrue(result.systemPrompt.contains("未确认候选，仅供讨论"))
        assertTrue(result.systemPrompt.contains("团长也许认识幕后人物"))
        assertTrue(result.systemPrompt.contains("让团长主动求援"))
        assertTrue(result.history.any { it.content.contains("我们讨论一下团长") })
        assertTrue("candidate" in result.includedProposalIds)
    }

    @Test
    fun overlongCurrentInputFailsHardBudgetBeforeRequest() {
        val current = message(
            "current",
            StoryWorkspace.Prose,
            "user",
            "超".repeat(4_100),
            StoryRevisionState.Complete,
            1
        )

        val error = expectOverflow {
            StoryContextComposer.compose(
                workspace = StoryWorkspace.Prose,
                baseInstruction = "正文规则",
                memoryRecords = emptyList(),
                proposals = emptyList(),
                proseMessages = listOf(current),
                discussionMessages = emptyList(),
                budget = StoryContextBudget(maxInputChars = 4_000)
            )
        }

        assertEquals(StoryContextOverflowSection.CurrentInput, error.section)
    }

    @Test
    fun pinnedFactsMayExceedPinnedSectionCapWhenGlobalBudgetCanHoldThem() {
        val pinA = memory("pin-a", "甲".repeat(1_100), StoryMemoryNature.UserConfirmed, pinned = true)
        val pinB = memory("pin-b", "乙".repeat(1_100), StoryMemoryNature.UserConfirmed, pinned = true)
        val current = message("current", StoryWorkspace.Prose, "user", "继续。", StoryRevisionState.Complete, 3)

        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Prose,
            baseInstruction = "正文规则",
            memoryRecords = listOf(pinA, pinB),
            proposals = emptyList(),
            proseMessages = listOf(current),
            discussionMessages = emptyList(),
            budget = StoryContextBudget(
                maxInputChars = 4_000,
                pinnedMemoryChars = 1_000,
                confirmedMemoryChars = 0,
                candidateChars = 0,
                recentHistoryChars = 0
            )
        )

        assertTrue("pin-a" in result.includedMemoryIds)
        assertTrue("pin-b" in result.includedMemoryIds)
        assertTrue(result.systemPrompt.contains("甲".repeat(100)))
        assertTrue(result.systemPrompt.contains("乙".repeat(100)))
        assertFalse(result.truncations.any { it.section == "固定资料" })
        assertTrue(result.withinHardBudget)
    }

    @Test
    fun pinnedFactsThatCannotFitGlobalBudgetFailInsteadOfBeingOmitted() {
        val pinned = memory(
            "pin",
            "固".repeat(3_950),
            StoryMemoryNature.UserConfirmed,
            pinned = true
        )
        val current = message("current", StoryWorkspace.Prose, "user", "继续。", StoryRevisionState.Complete, 2)

        val error = expectOverflow {
            StoryContextComposer.compose(
                workspace = StoryWorkspace.Prose,
                baseInstruction = "正文规则",
                memoryRecords = listOf(pinned),
                proposals = emptyList(),
                proseMessages = listOf(current),
                discussionMessages = emptyList(),
                budget = StoryContextBudget(maxInputChars = 4_000, pinnedMemoryChars = 100)
            )
        }

        assertEquals(StoryContextOverflowSection.PinnedMemory, error.section)
    }

    @Test
    fun historyBudgetKeepsOnlyContinuousCompleteRounds() {
        val messages = listOf(
            message("u1", StoryWorkspace.Prose, "user", "第一轮问题", StoryRevisionState.Complete, 1),
            message("a1", StoryWorkspace.Prose, "assistant", "第一轮回答", StoryRevisionState.Complete, 2),
            message("u2", StoryWorkspace.Prose, "user", "第二轮问题", StoryRevisionState.Complete, 3),
            message("a2", StoryWorkspace.Prose, "assistant", "第二轮回答" + "x".repeat(900), StoryRevisionState.Complete, 4),
            message("current", StoryWorkspace.Prose, "user", "当前输入", StoryRevisionState.Complete, 5)
        )

        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Prose,
            baseInstruction = "正文规则",
            memoryRecords = emptyList(),
            proposals = emptyList(),
            proseMessages = messages,
            organizedProseRevisionIds = messages.filter { it.message.role == "assistant" }.map { it.revision.id }.toSet(),
            discussionMessages = emptyList(),
            budget = StoryContextBudget(
                maxInputChars = 4_000,
                pinnedMemoryChars = 0,
                confirmedMemoryChars = 0,
                candidateChars = 0,
                recentHistoryChars = 1_000
            )
        )

        assertEquals(listOf("第二轮问题", "第二轮回答" + "x".repeat(900), "当前输入"), result.history.map { it.content })
    }

    @Test
    fun newestLongReplyCannotBeReplacedByOlderShortTurn() {
        val messages = listOf(
            message("u1", StoryWorkspace.Prose, "user", "旧短问题", StoryRevisionState.Complete, 1),
            message("a1", StoryWorkspace.Prose, "assistant", "旧短回答", StoryRevisionState.Complete, 2),
            message("u2", StoryWorkspace.Prose, "user", "最近问题", StoryRevisionState.Complete, 3),
            message("a2", StoryWorkspace.Prose, "assistant", "长".repeat(1_300), StoryRevisionState.Complete, 4),
            message("current", StoryWorkspace.Prose, "user", "继续当前场景。", StoryRevisionState.Complete, 5)
        )

        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Prose,
            baseInstruction = "正文规则",
            memoryRecords = emptyList(),
            proposals = emptyList(),
            proseMessages = messages,
            organizedProseRevisionIds = messages.filter { it.message.role == "assistant" }.map { it.revision.id }.toSet(),
            discussionMessages = emptyList(),
            budget = StoryContextBudget(
                maxInputChars = 4_000,
                pinnedMemoryChars = 0,
                confirmedMemoryChars = 0,
                candidateChars = 0,
                recentHistoryChars = 800
            )
        )

        assertEquals(listOf("继续当前场景。"), result.history.map { it.content })
        assertTrue(result.truncations.any { it.section == "较早正文轮次" && it.omittedItems == 2 })
    }

    @Test
    fun characterAliasAndPlaceMentionsRankRelevantMemoryAheadOfUnrelatedEntityMemory() {
        val alice = memory(
            id = "alice",
            content = "爱丽丝擅长长剑。" + "甲".repeat(220),
            nature = StoryMemoryNature.UserConfirmed,
            kind = StoryMemoryKind.CharacterProfile,
            entityNames = listOf("爱丽丝", "莉丝")
        )
        val capital = memory(
            id = "capital",
            content = "莱茵城北门连接旧大道。" + "乙".repeat(220),
            nature = StoryMemoryNature.UserConfirmed,
            kind = StoryMemoryKind.WorldFact,
            entityNames = listOf("莱茵城", "王都")
        )
        val unrelated = memory(
            id = "other",
            content = "鲍勃正在南港经商。" + "丙".repeat(220),
            nature = StoryMemoryNature.UserConfirmed,
            kind = StoryMemoryKind.CharacterProfile,
            entityNames = listOf("鲍勃")
        )
        val current = message(
            "current",
            StoryWorkspace.Prose,
            "user",
            "让莉丝从王都北门出发。",
            StoryRevisionState.Complete,
            10
        )

        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Prose,
            baseInstruction = "正文规则",
            memoryRecords = listOf(unrelated, capital, alice),
            proposals = emptyList(),
            proseMessages = listOf(current),
            discussionMessages = emptyList(),
            budget = StoryContextBudget(
                maxInputChars = 4_000,
                pinnedMemoryChars = 0,
                confirmedMemoryChars = 650,
                candidateChars = 0,
                recentHistoryChars = 0
            )
        )

        assertTrue("alice" in result.includedMemoryIds)
        assertTrue("capital" in result.includedMemoryIds)
        assertFalse("other" in result.includedMemoryIds)
    }

    @Test fun pinnedBeliefAndAuthorPlanKeepTheirBoundariesInBothWorkspaces() {
        val belief = memory("belief", "怀疑林遥偷了钥匙", StoryMemoryNature.CharacterBelief,
            kind = StoryMemoryKind.CharacterKnowledge, entityNames = listOf("守卫")).copy(pinned = true)
        val plan = memory("plan", "守卫未来背叛", StoryMemoryNature.UserConfirmed, kind = StoryMemoryKind.AuthorPlan)
        StoryWorkspace.entries.forEach { workspace ->
            val result = StoryContextComposer.compose(workspace, "规则", listOf(belief, plan),
                emptyList(), emptyList(), emptyList())
            assertTrue(result.systemPrompt.contains("角色主观看法 · 不等于事实"))
            assertTrue(result.systemPrompt.contains("认知主体：守卫"))
            assertTrue(result.systemPrompt.contains("不得扩散为其他角色已知"))
            assertTrue(result.systemPrompt.contains("尚未发生，不得提前兑现"))
            assertTrue(result.withinHardBudget)
        }
    }

    @Test fun unorganizedSuffixIsReservedBeforeOptionalMemoryEvenWithZeroHistoryCap() {
        val rows = listOf(
            message("u0", StoryWorkspace.Prose, "user", "旧输入", StoryRevisionState.Complete, 1),
            message("a0", StoryWorkspace.Prose, "assistant", "已整理旧文", StoryRevisionState.Complete, 2),
            message("u1", StoryWorkspace.Prose, "user", "待整理输入", StoryRevisionState.Complete, 3),
            message("a1", StoryWorkspace.Prose, "assistant", "未整理" + "甲".repeat(1200), StoryRevisionState.Complete, 4),
            message("u2", StoryWorkspace.Prose, "user", "后续输入", StoryRevisionState.Complete, 5),
            message("a2", StoryWorkspace.Prose, "assistant", "较新但已整理" + "乙".repeat(1200), StoryRevisionState.Complete, 6),
            message("u3", StoryWorkspace.Prose, "user", "继续", StoryRevisionState.Complete, 7)
        )
        val result = StoryContextComposer.compose(StoryWorkspace.Prose, "规则",
            listOf(memory("large", "丙".repeat(2300), StoryMemoryNature.UserConfirmed)),
            emptyList(), rows, emptyList(),
            budget = StoryContextBudget(maxInputChars = 4000, recentHistoryChars = 0),
            organizedProseRevisionIds = setOf("revision-a0", "revision-a2"))
        assertEquals(rows.drop(2).map { it.revision.content }, result.history.map { it.content })
        assertFalse("large" in result.includedMemoryIds)
        assertEquals(setOf("revision-a1", "revision-a2"), result.protectedProseRevisionIds)
        assertTrue(result.withinHardBudget)
    }

    @Test fun unorganizedGlobalOverflowFailsRatherThanDroppingStoryOrPinnedFacts() {
        val rows = listOf(
            message("u1", StoryWorkspace.Prose, "user", "输入", StoryRevisionState.Complete, 1),
            message("a1", StoryWorkspace.Prose, "assistant", "正文" + "甲".repeat(2800), StoryRevisionState.Complete, 2),
            message("u2", StoryWorkspace.Prose, "user", "继续", StoryRevisionState.Complete, 3)
        )
        org.junit.Assert.assertThrows(StoryUnorganizedHistoryException::class.java) {
            StoryContextComposer.compose(StoryWorkspace.Prose, "规则",
                listOf(memory("pin", "固".repeat(1300), StoryMemoryNature.UserConfirmed, pinned = true)),
                emptyList(), rows, emptyList(), budget = StoryContextBudget(maxInputChars = 4000))
        }
    }

    @Test fun unknownCoverageProtectsOrphanReplyButNeverInterruptedOrDiscussionText() {
        val rows = listOf(
            message("orphan", StoryWorkspace.Prose, "assistant", "旧版完整正文", StoryRevisionState.Complete, 1),
            message("stopped", StoryWorkspace.Prose, "assistant", "停止的正文", StoryRevisionState.Stopped, 2),
            message("current", StoryWorkspace.Prose, "user", "继续", StoryRevisionState.Complete, 3)
        )
        val result = StoryContextComposer.compose(StoryWorkspace.Prose, "规则", emptyList(), emptyList(), rows,
            listOf(message("discussion", StoryWorkspace.Discussion, "assistant", "讨论秘密", StoryRevisionState.Complete, 4)),
            budget = StoryContextBudget(recentHistoryChars = 0))
        assertEquals(listOf("旧版完整正文", "继续"), result.history.map { it.content })
        assertEquals(setOf("revision-orphan"), result.protectedProseRevisionIds)
        val discussion = StoryContextComposer.compose(StoryWorkspace.Discussion, "讨论", emptyList(), emptyList(), rows,
            emptyList(), budget = StoryContextBudget(recentHistoryChars = 0))
        assertTrue(discussion.protectedProseRevisionIds.isEmpty())
        assertTrue(discussion.history.isEmpty())
    }

    private fun expectOverflow(block: () -> Unit): StoryContextOverflowException {
        try {
            block()
        } catch (error: StoryContextOverflowException) {
            return error
        }
        fail("Expected StoryContextOverflowException")
        throw AssertionError("unreachable")
    }

    private fun memory(
        id: String,
        content: String,
        nature: StoryMemoryNature,
        pinned: Boolean = false,
        updatedAt: Long = 1,
        kind: StoryMemoryKind = StoryMemoryKind.WorldFact,
        entityNames: List<String> = emptyList()
    ): StoryMemoryRecord = StoryMemoryRecord(
        id = id,
        storyId = storyId,
        timelineId = timelineId,
        kind = kind,
        content = content,
        nature = nature,
        pinned = pinned,
        updatedAt = updatedAt,
        subjectEntityNames = entityNames
    )

    private fun proposal(id: String, content: String): StoryProposal = StoryProposal(
        id = id,
        storyId = storyId,
        timelineId = timelineId,
        content = content,
        proposalKind = "plot",
        sourceRevisionId = "revision-source",
        state = StoryProposalState.Pending
    )

    private fun message(
        id: String,
        workspace: StoryWorkspace,
        role: String,
        content: String,
        state: StoryRevisionState,
        sequence: Long
    ): StoryMessageWithRevision {
        val revisionId = "revision-$id"
        return StoryMessageWithRevision(
            message = StoryMessage(
                id = id,
                storyId = storyId,
                timelineId = timelineId,
                workspace = workspace,
                role = role,
                sequence = sequence,
                activeRevisionId = revisionId,
                createdAt = sequence
            ),
            revision = StoryMessageRevision(
                id = revisionId,
                messageId = id,
                storyId = storyId,
                timelineId = timelineId,
                workspace = workspace,
                content = content,
                state = state,
                createdAt = sequence,
                completedAt = sequence.takeIf { state == StoryRevisionState.Complete }
            )
        )
    }
}
