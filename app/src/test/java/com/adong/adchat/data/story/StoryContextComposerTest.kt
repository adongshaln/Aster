package com.adong.adchat.data.story

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            message("p1", StoryWorkspace.Prose, "assistant", "她抵达王都。", StoryRevisionState.Complete, 1),
            message("p2", StoryWorkspace.Prose, "assistant", "不应进入上下文的中断正文", StoryRevisionState.Interrupted, 2),
            message("p3", StoryWorkspace.Prose, "user", "继续写她进入城门。", StoryRevisionState.Complete, 3)
        )
        val discussion = listOf(
            message("d1", StoryWorkspace.Discussion, "user", "秘密讨论候选：改成南方城市。", StoryRevisionState.Complete, 4)
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
    }

    @Test
    fun discussionCanSeeCandidatesButTheyAreExplicitlyNonAuthoritative() {
        val confirmed = memory("confirmed", "骑士团驻扎在旧港。", StoryMemoryNature.UserConfirmed)
        val inference = memory("inference", "团长也许认识幕后人物。", StoryMemoryNature.Inference)
        val candidate = proposal("candidate", "候选方案：让团长主动求援。")
        val prose = listOf(
            message("p1", StoryWorkspace.Prose, "assistant", "正文机密段落", StoryRevisionState.Complete, 1)
        )
        val discussion = listOf(
            message("d1", StoryWorkspace.Discussion, "user", "我们讨论一下团长。", StoryRevisionState.Complete, 2)
        )

        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Discussion,
            baseInstruction = "讨论规则",
            memoryRecords = listOf(confirmed, inference),
            proposals = listOf(candidate),
            proseMessages = prose,
            discussionMessages = discussion
        )

        assertTrue(result.systemPrompt.contains("骑士团驻扎在旧港"))
        assertTrue(result.systemPrompt.contains("未确认候选，仅供讨论"))
        assertTrue(result.systemPrompt.contains("团长也许认识幕后人物"))
        assertTrue(result.systemPrompt.contains("让团长主动求援"))
        assertFalse(result.history.any { it.content.contains("正文机密段落") })
        assertTrue(result.history.any { it.content.contains("我们讨论一下团长") })
        assertTrue("candidate" in result.includedProposalIds)
    }

    @Test
    fun budgetTruncationKeepsResultBoundedAndReportsDroppedPinnedFacts() {
        val pinnedNewest = memory(
            id = "pin-new",
            content = "新".repeat(1_600),
            nature = StoryMemoryNature.UserConfirmed,
            pinned = true,
            updatedAt = 20
        )
        val pinnedOld = memory(
            id = "pin-old",
            content = "旧".repeat(1_600),
            nature = StoryMemoryNature.UserConfirmed,
            pinned = true,
            updatedAt = 10
        )
        val current = message(
            "current",
            StoryWorkspace.Prose,
            "user",
            "继续。",
            StoryRevisionState.Complete,
            3
        )

        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Prose,
            baseInstruction = "正文规则",
            memoryRecords = listOf(pinnedOld, pinnedNewest),
            proposals = emptyList(),
            proseMessages = listOf(current),
            discussionMessages = emptyList(),
            budget = StoryContextBudget(
                maxInputChars = 4_000,
                pinnedMemoryChars = 2_000,
                confirmedMemoryChars = 0,
                candidateChars = 0,
                recentHistoryChars = 1_000
            )
        )

        assertTrue("pin-new" in result.includedMemoryIds)
        assertFalse("pin-old" in result.includedMemoryIds)
        assertTrue(result.truncations.any { it.section == "固定资料" && it.omittedItems == 1 })
        assertTrue(result.estimatedChars <= result.maxChars)
        assertTrue(result.truncationNotice?.contains("固定资料") == true)
    }

    @Test
    fun historyBudgetDropsOlderTurnsBeforeCurrentUserTurn() {
        val older = (1..6).map { index ->
            message(
                id = "old-$index",
                workspace = StoryWorkspace.Prose,
                role = if (index % 2 == 0) "assistant" else "user",
                content = "旧内容$index" + "x".repeat(700),
                state = StoryRevisionState.Complete,
                sequence = index.toLong()
            )
        }
        val current = message(
            "current",
            StoryWorkspace.Prose,
            "user",
            "这是当前用户输入，必须保留。",
            StoryRevisionState.Complete,
            99
        )

        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Prose,
            baseInstruction = "正文规则",
            memoryRecords = emptyList(),
            proposals = emptyList(),
            proseMessages = older + current,
            discussionMessages = emptyList(),
            budget = StoryContextBudget(
                maxInputChars = 4_000,
                pinnedMemoryChars = 0,
                confirmedMemoryChars = 0,
                candidateChars = 0,
                recentHistoryChars = 1_200
            )
        )

        assertTrue(result.history.last().content.contains("当前用户输入"))
        assertTrue(result.truncations.any { it.section == "较早正文" })
    }

    private fun memory(
        id: String,
        content: String,
        nature: StoryMemoryNature,
        pinned: Boolean = false,
        updatedAt: Long = 1
    ): StoryMemoryRecord = StoryMemoryRecord(
        id = id,
        storyId = storyId,
        timelineId = timelineId,
        kind = StoryMemoryKind.WorldFact,
        content = content,
        nature = nature,
        pinned = pinned,
        updatedAt = updatedAt
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
