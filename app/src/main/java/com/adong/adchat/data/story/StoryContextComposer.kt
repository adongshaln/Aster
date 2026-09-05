package com.adong.adchat.data.story

import com.adong.adchat.data.ChatMessage

/**
 * Conservative character budget used until provider-specific context limits are modeled.
 * The global cap always wins over section caps.
 */
data class StoryContextBudget(
    val maxInputChars: Int = 48_000,
    val pinnedMemoryChars: Int = 12_000,
    val confirmedMemoryChars: Int = 18_000,
    val candidateChars: Int = 6_000,
    val recentHistoryChars: Int = 20_000
) {
    init {
        require(maxInputChars >= 4_000)
        require(pinnedMemoryChars >= 0)
        require(confirmedMemoryChars >= 0)
        require(candidateChars >= 0)
        require(recentHistoryChars >= 0)
    }
}

data class StoryContextTruncation(
    val section: String,
    val omittedItems: Int
)

data class StoryContextResult(
    val systemPrompt: String,
    val history: List<ChatMessage>,
    val estimatedChars: Int,
    val maxChars: Int,
    val includedMemoryIds: Set<String>,
    val includedProposalIds: Set<String>,
    val truncations: List<StoryContextTruncation>
) {
    val wasTruncated: Boolean get() = truncations.isNotEmpty()

    val truncationNotice: String?
        get() = truncations.takeIf { it.isNotEmpty() }?.joinToString(
            prefix = "上下文已按预算裁剪：",
            separator = "，"
        ) { item -> "${item.section} ${item.omittedItems} 项" }
}

object StoryContextComposer {
    private const val SYSTEM_SECTION_RESERVE = 512

    fun compose(
        workspace: StoryWorkspace,
        baseInstruction: String,
        memoryRecords: List<StoryMemoryRecord>,
        proposals: List<StoryProposal>,
        proseMessages: List<StoryMessageWithRevision>,
        discussionMessages: List<StoryMessageWithRevision>,
        budget: StoryContextBudget = StoryContextBudget()
    ): StoryContextResult {
        val confirmed = memoryRecords.filter { record ->
            record.active && record.nature != StoryMemoryNature.Inference
        }
        val inferred = memoryRecords.filter { record ->
            record.active && record.nature == StoryMemoryNature.Inference
        }
        val pendingCandidates = proposals.filter { it.state == StoryProposalState.Pending }

        val eligibleHistory = when (workspace) {
            StoryWorkspace.Prose -> proseMessages
            StoryWorkspace.Discussion -> discussionMessages
        }.asSequence()
            .filter { row ->
                row.message.workspace == workspace &&
                    row.revision.workspace == workspace &&
                    row.revision.state == StoryRevisionState.Complete &&
                    row.revision.content.isNotBlank() &&
                    row.message.role in setOf("user", "assistant")
            }
            .toList()

        val currentTurn = eligibleHistory.lastOrNull { it.message.role == "user" }
        val currentTurnCost = currentTurn?.let(::historyCost) ?: 0
        var remaining = (budget.maxInputChars - baseInstruction.length - SYSTEM_SECTION_RESERVE - currentTurnCost)
            .coerceAtLeast(0)

        val includedMemoryIds = linkedSetOf<String>()
        val includedProposalIds = linkedSetOf<String>()
        val truncations = mutableListOf<StoryContextTruncation>()

        val pinnedLines = mutableListOf<String>()
        val pinned = confirmed.filter(StoryMemoryRecord::pinned)
            .sortedWith(compareByDescending<StoryMemoryRecord> { it.updatedAt }.thenByDescending { it.effectiveSequence })
        remaining = takeRenderedItems(
            items = pinned,
            sectionCap = budget.pinnedMemoryChars,
            remainingGlobal = remaining,
            render = ::renderMemory,
            onIncluded = { includedMemoryIds += it.id },
            output = pinnedLines,
            onTruncated = { omitted -> truncations += StoryContextTruncation("固定资料", omitted) }
        )

        val confirmedLines = mutableListOf<String>()
        val normalConfirmed = confirmed.filterNot(StoryMemoryRecord::pinned)
            .sortedWith(
                compareBy<StoryMemoryRecord> { memoryPriority(it.kind) }
                    .thenByDescending { it.effectiveSequence }
                    .thenByDescending { it.updatedAt }
            )
        remaining = takeRenderedItems(
            items = normalConfirmed,
            sectionCap = budget.confirmedMemoryChars,
            remainingGlobal = remaining,
            render = ::renderMemory,
            onIncluded = { includedMemoryIds += it.id },
            output = confirmedLines,
            onTruncated = { omitted -> truncations += StoryContextTruncation("已确认资料", omitted) }
        )

        val candidateLines = mutableListOf<String>()
        if (workspace == StoryWorkspace.Discussion) {
            val renderedCandidates = buildList {
                inferred.sortedByDescending { it.updatedAt }.forEach { record ->
                    add(CandidateItem("memory:${record.id}", "• [未确认推断] ${record.content.trim()}", record.id, null))
                }
                pendingCandidates.sortedByDescending { it.updatedAt }.forEach { proposal ->
                    add(CandidateItem("proposal:${proposal.id}", "• [待确认候选] ${proposal.content.trim()}", null, proposal.id))
                }
            }
            remaining = takeCandidateItems(
                items = renderedCandidates,
                sectionCap = budget.candidateChars,
                remainingGlobal = remaining,
                output = candidateLines,
                onMemoryIncluded = { includedMemoryIds += it },
                onProposalIncluded = { includedProposalIds += it },
                onTruncated = { omitted -> truncations += StoryContextTruncation("候选", omitted) }
            )
        }

        val historyWithoutCurrent = if (currentTurn == null) {
            eligibleHistory
        } else {
            eligibleHistory.filterNot { it.message.id == currentTurn.message.id }
        }
        val selectedHistoryNewestFirst = mutableListOf<StoryMessageWithRevision>()
        var historyRemaining = minOf(budget.recentHistoryChars, remaining)
        var omittedHistory = 0
        historyWithoutCurrent.asReversed().forEach { row ->
            val cost = historyCost(row)
            if (cost <= historyRemaining && cost <= remaining) {
                selectedHistoryNewestFirst += row
                historyRemaining -= cost
                remaining -= cost
            } else {
                omittedHistory += 1
            }
        }
        if (omittedHistory > 0) {
            truncations += StoryContextTruncation(
                if (workspace == StoryWorkspace.Prose) "较早正文" else "较早讨论",
                omittedHistory
            )
        }

        val historyRows = selectedHistoryNewestFirst.asReversed().toMutableList()
        currentTurn?.let(historyRows::add)
        val history = historyRows.map { row ->
            ChatMessage(role = row.message.role, content = row.revision.content)
        }

        val systemPrompt = buildString {
            append(baseInstruction.trim())
            if (pinnedLines.isNotEmpty()) {
                append("\n\n[固定且已确认的故事资料]\n")
                append(pinnedLines.joinToString("\n"))
            }
            if (confirmedLines.isNotEmpty()) {
                append("\n\n[已确认的故事资料]\n")
                append(confirmedLines.joinToString("\n"))
            }
            if (workspace == StoryWorkspace.Discussion && candidateLines.isNotEmpty()) {
                append("\n\n[未确认候选，仅供讨论；不得当作已发生事实]\n")
                append(candidateLines.joinToString("\n"))
            }
        }

        val estimated = systemPrompt.length + history.sumOf { messageCost(it) }
        return StoryContextResult(
            systemPrompt = systemPrompt,
            history = history,
            estimatedChars = estimated,
            maxChars = budget.maxInputChars,
            includedMemoryIds = includedMemoryIds,
            includedProposalIds = includedProposalIds,
            truncations = truncations
        )
    }

    private fun renderMemory(record: StoryMemoryRecord): String =
        "• [${memoryLabel(record.kind)}] ${record.content.trim()}"

    private fun memoryLabel(kind: StoryMemoryKind): String = when (kind) {
        StoryMemoryKind.WorldFact -> "世界设定"
        StoryMemoryKind.CharacterProfile -> "人物"
        StoryMemoryKind.CurrentState -> "当前状态"
        StoryMemoryKind.DirectedRelationship -> "关系"
        StoryMemoryKind.CharacterKnowledge -> "角色认知"
        StoryMemoryKind.PlotEvent -> "已发生剧情"
        StoryMemoryKind.OpenThread -> "未完线索"
        StoryMemoryKind.AuthorPlan -> "作者计划"
        StoryMemoryKind.Summary -> "剧情摘要"
    }

    private fun memoryPriority(kind: StoryMemoryKind): Int = when (kind) {
        StoryMemoryKind.CurrentState -> 0
        StoryMemoryKind.CharacterProfile -> 1
        StoryMemoryKind.DirectedRelationship -> 2
        StoryMemoryKind.CharacterKnowledge -> 3
        StoryMemoryKind.WorldFact -> 4
        StoryMemoryKind.OpenThread -> 5
        StoryMemoryKind.AuthorPlan -> 6
        StoryMemoryKind.PlotEvent -> 7
        StoryMemoryKind.Summary -> 8
    }

    private fun historyCost(row: StoryMessageWithRevision): Int =
        row.message.role.length + row.revision.content.length + 16

    private fun messageCost(message: ChatMessage): Int = message.role.length + message.content.length + 16

    private fun <T> takeRenderedItems(
        items: List<T>,
        sectionCap: Int,
        remainingGlobal: Int,
        render: (T) -> String,
        onIncluded: (T) -> Unit,
        output: MutableList<String>,
        onTruncated: (Int) -> Unit
    ): Int {
        var remaining = remainingGlobal
        var sectionRemaining = sectionCap
        var omitted = 0
        items.forEach { item ->
            val line = render(item)
            val cost = line.length + 1
            if (cost <= sectionRemaining && cost <= remaining) {
                output += line
                sectionRemaining -= cost
                remaining -= cost
                onIncluded(item)
            } else {
                omitted += 1
            }
        }
        if (omitted > 0) onTruncated(omitted)
        return remaining
    }

    private fun takeCandidateItems(
        items: List<CandidateItem>,
        sectionCap: Int,
        remainingGlobal: Int,
        output: MutableList<String>,
        onMemoryIncluded: (String) -> Unit,
        onProposalIncluded: (String) -> Unit,
        onTruncated: (Int) -> Unit
    ): Int {
        var remaining = remainingGlobal
        var sectionRemaining = sectionCap
        var omitted = 0
        items.forEach { item ->
            val cost = item.line.length + 1
            if (cost <= sectionRemaining && cost <= remaining) {
                output += item.line
                sectionRemaining -= cost
                remaining -= cost
                item.memoryId?.let(onMemoryIncluded)
                item.proposalId?.let(onProposalIncluded)
            } else {
                omitted += 1
            }
        }
        if (omitted > 0) onTruncated(omitted)
        return remaining
    }

    private data class CandidateItem(
        val key: String,
        val line: String,
        val memoryId: String?,
        val proposalId: String?
    )
}
