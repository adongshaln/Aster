package com.adong.adchat.data.story

import com.adong.adchat.data.ChatMessage

/**
 * Conservative character budget used until provider-specific context limits are modeled.
 * [maxInputChars] is a hard final-request ceiling. Section caps only govern optional material.
 * Pinned confirmed memory is mandatory and may exceed [pinnedMemoryChars] when the global budget permits.
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

enum class StoryContextOverflowSection {
    CurrentInput,
    PinnedMemory,
    FinalRequest
}

class StoryContextOverflowException(
    val section: StoryContextOverflowSection,
    val requiredChars: Int,
    val maxChars: Int
) : IllegalStateException(
    when (section) {
        StoryContextOverflowSection.CurrentInput -> "当前输入超过故事上下文硬预算（$requiredChars > $maxChars）。"
        StoryContextOverflowSection.PinnedMemory -> "固定资料与当前输入无法同时装入故事上下文（$requiredChars > $maxChars）。请先精简固定资料。"
        StoryContextOverflowSection.FinalRequest -> "最终故事请求超过上下文硬预算（$requiredChars > $maxChars）。"
    }
)

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
    val withinHardBudget: Boolean get() = estimatedChars <= maxChars

    val truncationNotice: String?
        get() = truncations.takeIf { it.isNotEmpty() }?.joinToString(
            prefix = "上下文已按预算裁剪：",
            separator = "，"
        ) { item -> "${item.section} ${item.omittedItems} 项" }
}

object StoryContextComposer {
    private const val PINNED_HEADER = "\n\n[固定故事资料；仍须遵守每项性质与认知归属]\n"
    private const val CONFIRMED_HEADER = "\n\n[故事资料；主观看法不等于事实，资料可见不代表所有角色知情]\n"
    private const val CANDIDATE_HEADER = "\n\n[未确认候选，仅供讨论；不得当作已发生事实]\n"

    fun compose(
        workspace: StoryWorkspace,
        baseInstruction: String,
        memoryRecords: List<StoryMemoryRecord>,
        proposals: List<StoryProposal>,
        proseMessages: List<StoryMessageWithRevision>,
        discussionMessages: List<StoryMessageWithRevision>,
        budget: StoryContextBudget = StoryContextBudget()
    ): StoryContextResult {
        val stateView = StoryStateProjection.project(memoryRecords)
        if (workspace == StoryWorkspace.Prose && stateView.conflicts.isNotEmpty()) {
            throw StoryStateConflictException(stateView.conflicts)
        }
        val confirmed = stateView.records.filter { record ->
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
            .sortedBy { it.message.sequence }
            .toList()

        val currentTurn = eligibleHistory.lastOrNull { it.message.role == "user" }
        val currentTurnCost = currentTurn?.let(::historyCost) ?: 0
        val base = buildString {
            append(baseInstruction.trim())
            if (stateView.conflicts.isNotEmpty()) {
                append("\n[以下状态尚有冲突，仅供讨论，不得选一方当作既定事实]\n")
                append(stateView.conflicts.joinToString("\n") { it.description })
            }
        }
        val baseAndCurrentCost = base.length + currentTurnCost
        if (baseAndCurrentCost > budget.maxInputChars) {
            throw StoryContextOverflowException(
                StoryContextOverflowSection.CurrentInput,
                baseAndCurrentCost,
                budget.maxInputChars
            )
        }

        val includedMemoryIds = linkedSetOf<String>()
        val includedProposalIds = linkedSetOf<String>()
        val truncations = mutableListOf<StoryContextTruncation>()

        // Pinned confirmed material is mandatory. The pinned section cap is a planning target only;
        // it must never cause an individual pinned fact to disappear automatically.
        val pinned = confirmed.filter(StoryMemoryRecord::pinned)
            .sortedWith(compareByDescending<StoryMemoryRecord> { it.updatedAt }.thenByDescending { it.effectiveSequence })
        val pinnedLines = pinned.map(::renderMemory)
        val mandatorySystem = buildString {
            append(base)
            if (pinnedLines.isNotEmpty()) {
                append(PINNED_HEADER)
                append(pinnedLines.joinToString("\n"))
            }
        }
        val mandatoryCost = mandatorySystem.length + currentTurnCost
        if (mandatoryCost > budget.maxInputChars) {
            throw StoryContextOverflowException(
                StoryContextOverflowSection.PinnedMemory,
                mandatoryCost,
                budget.maxInputChars
            )
        }
        pinned.forEach { includedMemoryIds += it.id }
        var remaining = budget.maxInputChars - mandatoryCost

        val relevanceText = eligibleHistory.takeLast(6)
            .joinToString("\n") { it.revision.content }
            .lowercase()

        val confirmedLines = mutableListOf<String>()
        val normalConfirmed = confirmed.filterNot(StoryMemoryRecord::pinned)
            .sortedWith(
                compareBy<StoryMemoryRecord> { relevancePriority(it, relevanceText) }
                    .thenBy { memoryPriority(it.kind) }
                    .thenByDescending { it.effectiveSequence }
                    .thenByDescending { it.updatedAt }
            )
        remaining = takeRenderedItems(
            items = normalConfirmed,
            sectionCap = budget.confirmedMemoryChars,
            remainingGlobal = remaining,
            headerCost = CONFIRMED_HEADER.length,
            render = ::renderMemory,
            onIncluded = { includedMemoryIds += it.id },
            output = confirmedLines,
            onTruncated = { omitted -> truncations += StoryContextTruncation("已确认资料", omitted) }
        )

        val candidateLines = mutableListOf<String>()
        if (workspace == StoryWorkspace.Discussion) {
            val renderedCandidates = buildList {
                inferred.sortedWith(
                    compareBy<StoryMemoryRecord> { relevancePriority(it, relevanceText) }
                        .thenByDescending { it.updatedAt }
                ).forEach { record ->
                    add(CandidateItem("• [未确认推断] ${record.content.trim()}", record.id, null))
                }
                pendingCandidates.sortedByDescending { it.updatedAt }.forEach { proposal ->
                    add(CandidateItem("• [待确认候选] ${proposal.content.trim()}", null, proposal.id))
                }
            }
            remaining = takeCandidateItems(
                items = renderedCandidates,
                sectionCap = budget.candidateChars,
                remainingGlobal = remaining,
                headerCost = CANDIDATE_HEADER.length,
                output = candidateLines,
                onMemoryIncluded = { includedMemoryIds += it },
                onProposalIncluded = { includedProposalIds += it },
                onTruncated = { omitted -> truncations += StoryContextTruncation("候选", omitted) }
            )
        }

        val historyBeforeCurrent = eligibleHistory.filter { row ->
            currentTurn == null || row.message.sequence < currentTurn.message.sequence
        }
        val completeTurns = completeHistoryTurns(historyBeforeCurrent)
        val selectedTurnsNewestFirst = mutableListOf<HistoryTurn>()
        var historyRemaining = minOf(budget.recentHistoryChars, remaining)
        var omittedTurns = 0
        for (index in completeTurns.indices.reversed()) {
            val turn = completeTurns[index]
            val cost = turn.cost
            if (cost <= historyRemaining && cost <= remaining) {
                selectedTurnsNewestFirst += turn
                historyRemaining -= cost
                remaining -= cost
            } else {
                // History is a continuous suffix of complete user/assistant rounds. Once a newer
                // round cannot fit, older short messages may not leapfrog it into the request.
                omittedTurns = index + 1
                break
            }
        }
        if (omittedTurns > 0) {
            truncations += StoryContextTruncation(
                if (workspace == StoryWorkspace.Prose) "较早正文轮次" else "较早讨论轮次",
                omittedTurns
            )
        }

        val historyRows = selectedTurnsNewestFirst.asReversed()
            .flatMap { it.rows }
            .toMutableList()
        currentTurn?.let(historyRows::add)
        val history = historyRows.map { row ->
            ChatMessage(role = row.message.role, content = row.revision.content)
        }

        val systemPrompt = buildString {
            append(mandatorySystem)
            if (confirmedLines.isNotEmpty()) {
                append(CONFIRMED_HEADER)
                append(confirmedLines.joinToString("\n"))
            }
            if (workspace == StoryWorkspace.Discussion && candidateLines.isNotEmpty()) {
                append(CANDIDATE_HEADER)
                append(candidateLines.joinToString("\n"))
            }
        }

        val estimated = systemPrompt.length + history.sumOf(::messageCost)
        if (estimated > budget.maxInputChars) {
            throw StoryContextOverflowException(
                StoryContextOverflowSection.FinalRequest,
                estimated,
                budget.maxInputChars
            )
        }
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

    private fun renderMemory(record: StoryMemoryRecord): String = renderStoryMemory(record)

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

    /**
     * Basic relevance pass for entity-scoped memory. Character/place canonical names and aliases
     * are attached by StoryArchiveStore. Mentioned entities rank first, story-global material next,
     * and unrelated entity-scoped material last.
     */
    private fun relevancePriority(record: StoryMemoryRecord, relevanceText: String): Int {
        val names = record.subjectEntityNames + record.objectEntityNames
        if (names.isEmpty()) return 1
        return if (names.any { name ->
                val cleaned = name.trim().lowercase()
                cleaned.isNotEmpty() && relevanceText.contains(cleaned)
            }) 0 else 2
    }

    private fun historyCost(row: StoryMessageWithRevision): Int =
        row.message.role.length + row.revision.content.length + 16

    private fun messageCost(message: ChatMessage): Int = message.role.length + message.content.length + 16

    private fun completeHistoryTurns(rows: List<StoryMessageWithRevision>): List<HistoryTurn> {
        val turns = mutableListOf<HistoryTurn>()
        var pendingUser: StoryMessageWithRevision? = null
        rows.forEach { row ->
            when (row.message.role) {
                "user" -> pendingUser = row
                "assistant" -> {
                    val user = pendingUser
                    if (user != null && user.message.sequence < row.message.sequence) {
                        turns += HistoryTurn(listOf(user, row))
                        pendingUser = null
                    }
                }
            }
        }
        return turns
    }

    private fun <T> takeRenderedItems(
        items: List<T>,
        sectionCap: Int,
        remainingGlobal: Int,
        headerCost: Int,
        render: (T) -> String,
        onIncluded: (T) -> Unit,
        output: MutableList<String>,
        onTruncated: (Int) -> Unit
    ): Int {
        var remaining = remainingGlobal
        var sectionRemaining = sectionCap
        var omitted = 0
        var hasAny = false
        items.forEach { item ->
            val line = render(item)
            val lineCost = line.length + if (hasAny) 1 else 0
            val globalCost = lineCost + if (hasAny) 0 else headerCost
            if (lineCost <= sectionRemaining && globalCost <= remaining) {
                output += line
                sectionRemaining -= lineCost
                remaining -= globalCost
                hasAny = true
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
        headerCost: Int,
        output: MutableList<String>,
        onMemoryIncluded: (String) -> Unit,
        onProposalIncluded: (String) -> Unit,
        onTruncated: (Int) -> Unit
    ): Int {
        var remaining = remainingGlobal
        var sectionRemaining = sectionCap
        var omitted = 0
        var hasAny = false
        items.forEach { item ->
            val lineCost = item.line.length + if (hasAny) 1 else 0
            val globalCost = lineCost + if (hasAny) 0 else headerCost
            if (lineCost <= sectionRemaining && globalCost <= remaining) {
                output += item.line
                sectionRemaining -= lineCost
                remaining -= globalCost
                hasAny = true
                item.memoryId?.let(onMemoryIncluded)
                item.proposalId?.let(onProposalIncluded)
            } else {
                omitted += 1
            }
        }
        if (omitted > 0) onTruncated(omitted)
        return remaining
    }

    private data class HistoryTurn(val rows: List<StoryMessageWithRevision>) {
        val cost: Int = rows.sumOf(::historyCost)
    }

    private data class CandidateItem(
        val line: String,
        val memoryId: String?,
        val proposalId: String?
    )
}
