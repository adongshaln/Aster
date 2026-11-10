package com.adong.adchat.data.rp.prompt

import com.adong.adchat.data.ChatGenerationOptions
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.ContextTokenEstimate
import com.adong.adchat.data.DEFAULT_IMPERSONATION_PROMPT
import com.adong.adchat.data.TavernPreset
import com.adong.adchat.data.TavernPrompt
import com.adong.adchat.data.TavernPromptOrderEntry
import com.adong.adchat.data.TavernRegexScript
import com.adong.adchat.data.rp.RpGenerationType
import com.adong.adchat.data.rp.RpNotePosition
import com.adong.adchat.data.rp.RpPersonaPosition
import com.adong.adchat.data.rp.RpPromptMessage
import com.adong.adchat.data.rp.RpPromptRequest
import com.adong.adchat.data.rp.RpPromptResult
import com.adong.adchat.data.rp.RpVarScope
import com.adong.adchat.data.rp.RpWorldScanInput
import com.adong.adchat.data.rp.RpWorldScanResult
import org.json.JSONObject
import java.util.Date

/** Integration-side extras that are not part of [RpPromptRequest]. */
data class RpPromptOptions(
    /** Stable chat id seeding {{pick}}. Defaults to card name + first message id. */
    val chatId: String? = null,
    /** Regex scripts in run order; null = preset scripts then card scripts. */
    val regexScripts: List<TavernRegexScript>? = null,
    /** Variables of the latest floor that has any, for {{get_message_variable}}. */
    val messageVariables: (() -> JSONObject?)? = null,
    /** World-info budget in tokens; null = 25% of the input budget (ST default). */
    val worldBudgetTokens: Int? = null,
    val now: () -> Date = { Date() }
)

/**
 * SillyTavern Chat Completion prompt assembly (preparePromptsForChatCompletion + populateChatCompletion).
 * Leading contiguous system blocks become [RpPromptResult.systemPrompt]; everything else is inline.
 */
object RpPromptEngine {
    const val DEFAULT_MAIN_PROMPT =
        "你是沉浸式角色扮演的叙述者与 {{char}} 的扮演者，正在与 {{user}} 进行一场持续的中文角色扮演。" +
            "始终以 {{char}} 的身份、口吻和视角回应，结合角色设定、世界信息与此前剧情推进故事；" +
            "描写动作、神态、环境与对白，保持人物性格一致，不替 {{user}} 说话、行动或做决定，不跳出角色解释自己是 AI。"

    private val DEFAULT_ORDER = listOf(
        "main", "worldInfoBefore", "personaDescription", "charDescription", "charPersonality", "scenario",
        "worldInfoAfter", "dialogueExamples", "chatHistory", "jailbreak"
    )
    private val MARKERS = setOf(
        "worldInfoBefore", "worldInfoAfter", "charDescription", "charPersonality", "scenario",
        "personaDescription", "dialogueExamples", "chatHistory"
    )
    private const val MAX_INJECTION_DEPTH = 10_000
    private const val MESSAGE_OVERHEAD = 16

    private data class Slot(val identifier: String, val role: String, val content: String)

    private data class Entry(val role: String, val content: String, val injected: Boolean = false, val tag: String = "")

    private data class Injection(val depth: Int, val order: Int, val role: String, val content: String)

    private data class Extension(val key: String, val depth: Int, val role: String, val value: String)

    fun build(request: RpPromptRequest): RpPromptResult = build(request, RpPromptOptions())

    fun build(request: RpPromptRequest, options: RpPromptOptions): RpPromptResult {
        val card = request.card
        val preset = request.preset
        val type = request.type
        val userName = request.persona.name
        val charName = card.macroName
        val messages = request.messages
        val baseCtx = RpMacroContext(
            user = userName,
            char = charName,
            persona = request.persona.description,
            description = card.description,
            personality = card.personality,
            scenario = card.scenario,
            mesExamples = card.mesExample,
            charPrompt = card.systemPrompt,
            charJailbreak = card.postHistory,
            charDepthPrompt = card.depthPrompt?.prompt.orEmpty(),
            creatorNotes = card.creatorNotes,
            charVersion = card.characterVersion,
            messages = messages,
            model = request.modelName,
            variables = request.variables,
            chatId = options.chatId ?: "${card.name}:${messages.firstOrNull()?.id ?: 0}",
            messageVariables = options.messageVariables ?: { latestFloor(request) },
            random = request.random,
            now = options.now
        )
        val scripts = if (!request.regexEnabled) emptyList() else options.regexScripts ?: RpRegex.scripts(preset, card)

        // ---- world info ----
        val world: RpWorldScanResult = runCatching {
            RpWorldInfo.scan(
                RpWorldScanInput(
                    book = request.worldBook ?: card.worldBook,
                    chatNewestFirst = messages.asReversed().map { "${displayName(it, userName, charName)}: ${it.content}" },
                    characterName = charName,
                    personaDescription = request.persona.description,
                    characterDescription = card.description,
                    characterPersonality = card.personality,
                    characterDepthPrompt = card.depthPrompt?.prompt.orEmpty(),
                    scenario = card.scenario,
                    creatorNotes = card.creatorNotes,
                    type = type,
                    chatLength = messages.size,
                    budgetTokens = options.worldBudgetTokens ?: ((request.inputBudgetTokens ?: 32_000) / 4).coerceAtLeast(1),
                    timedState = request.timedState,
                    macros = { RpMacros.expand(it, baseCtx) },
                    random = request.random
                )
            )
        }.getOrElse { RpWorldScanResult(timedState = request.timedState) }
        val ctx = baseCtx.copy(outlets = world.outlets)
        fun m(text: String) = RpMacros.expand(text, ctx)

        val wiFormat = preset?.wiFormat ?: "{0}"
        fun formatWi(value: String): String {
            val text = RpRegex.worldInfo(value, null, scripts, ctx)
            if (text.isEmpty()) return ""
            return if (wiFormat.isBlank()) text else wiFormat.replace("{0}", text)
        }

        // ---- system prompt contents (markers) ----
        val scenarioFormat = preset?.scenarioFormat ?: "{{scenario}}"
        val personalityFormat = preset?.personalityFormat ?: "{{personality}}"
        val markerContent = mutableMapOf(
            "worldInfoBefore" to formatWi(world.before),
            "worldInfoAfter" to formatWi(world.after),
            "charDescription" to m(card.description.trim()),
            "charPersonality" to (if (card.personality.isNotBlank() && personalityFormat.isNotBlank()) m(personalityFormat) else m(card.personality.trim())),
            "scenario" to (if (card.scenario.isNotBlank() && scenarioFormat.isNotBlank()) m(scenarioFormat) else m(card.scenario.trim()))
        )
        if (request.persona.position == RpPersonaPosition.InPrompt && request.persona.description.isNotBlank()) {
            markerContent["personaDescription"] = m(request.persona.description)
        }

        // ---- prompt collection ----
        val promptsById: Map<String, TavernPrompt> = preset?.prompts?.associateBy { it.identifier } ?: defaultPrompts()
        val order: List<TavernPromptOrderEntry> = when {
            preset == null -> DEFAULT_ORDER.map { TavernPromptOrderEntry(it, true) }
            preset.promptOrderRp.isNotEmpty() -> preset.promptOrderRp
            preset.promptOrder.isNotEmpty() -> preset.promptOrder
            else -> preset.prompts.map { TavernPromptOrderEntry(it.identifier, it.enabled) }
        }
        val disabled = order.filter { !it.enabled }.mapTo(hashSetOf()) { it.identifier }
        val collection = mutableListOf<TavernPrompt>()
        val seen = hashSetOf<String>()
        for (entry in order) {
            if (!seen.add(entry.identifier)) continue
            val prompt = promptsById[entry.identifier]
                ?: if (entry.identifier in MARKERS) markerPrompt(entry.identifier) else continue
            val trigger = prompt.injectionTrigger.isEmpty() || type.wire in prompt.injectionTrigger
            when {
                entry.enabled && trigger -> collection += prompt.copy(content = if (prompt.marker || prompt.identifier in MARKERS) "" else m(prompt.content))
                entry.identifier == "main" -> collection += prompt.copy(content = "")
            }
        }
        fun index(id: String) = collection.indexOfFirst { it.identifier == id }
        // ST prompts.add: markers absent from prompt_order are appended after every ordered prompt.
        markerContent.forEach { (id, content) ->
            val at = index(id)
            if (at >= 0) collection[at] = collection[at].copy(content = content)
            else if (id !in disabled) collection += markerPrompt(id).copy(content = content)
        }

        // Card overrides of main / jailbreak with {{original}}.
        fun override(id: String, text: String) {
            val at = index(id)
            if (text.isBlank() || at < 0 || id in disabled) return
            val prompt = collection[at]
            if (prompt.forbidOverrides) return
            collection[at] = prompt.copy(content = RpMacros.expand(text, ctx, prompt.content))
        }
        override("main", card.systemPrompt)
        override("jailbreak", card.postHistory)

        // ---- extension prompts (author's note, depth prompt, persona at depth, WI at depth) ----
        val extensions = mutableListOf<Extension>()
        val note = request.authorsNote
        val userCount = messages.count { it.role == "user" }.let { if (note.interval == 1) 1 else it }
        val noteActive = userCount > 0 && note.interval > 0 &&
            (if (userCount >= note.interval) userCount % note.interval else note.interval - userCount) == 0
        var noteText = if (noteActive) note.text else ""
        if (noteActive && (world.anTop.isNotEmpty() || world.anBottom.isNotEmpty())) {
            noteText = "${world.anTop.joinToString("\n")}\n$noteText\n${world.anBottom.joinToString("\n")}".removePrefix("\n").removeSuffix("\n")
        }
        val persona = request.persona
        if (noteActive && persona.description.isNotBlank()) {
            if (persona.position == RpPersonaPosition.TopAuthorsNote) noteText = "${persona.description}\n$noteText"
            if (persona.position == RpPersonaPosition.BottomAuthorsNote) noteText = "$noteText\n${persona.description}"
        }
        val relativeToMain = mutableListOf<Pair<Slot, Boolean>>() // (slot, atStart)
        if (noteText.isNotBlank()) when (note.position) {
            RpNotePosition.InChat -> extensions += Extension("2_floating_prompt", note.depth, note.role, noteText)
            RpNotePosition.AfterMain -> relativeToMain += Slot("authorsNote", note.role, m(noteText)) to false
            RpNotePosition.BeforeMain -> relativeToMain += Slot("authorsNote", note.role, m(noteText)) to true
        }
        card.depthPrompt?.takeIf { it.prompt.isNotBlank() }?.let {
            extensions += Extension("DEPTH_PROMPT", it.depth, it.role.ifBlank { "system" }, it.prompt)
        }
        if (persona.position == RpPersonaPosition.AtDepth && persona.description.isNotBlank()) {
            extensions += Extension("PERSONA_DESCRIPTION", persona.depth, persona.role, persona.description)
        }
        world.depth.groupBy { it.depth to it.role }.forEach { (key, entries) ->
            val text = entries.joinToString("\n") { RpRegex.worldInfo(it.content, it.depth, scripts, ctx) }
            if (text.isNotBlank()) extensions += Extension("customDepthWI_${key.first}_${key.second}", key.first, key.second, text)
        }
        extensions.sortBy { it.key }

        // ---- absolute (in-chat) prompts from the preset ----
        val absolute = collection.filter { it.injectionPosition == 1 && it.identifier != "chatHistory" && it.identifier != "dialogueExamples" }
            .map { Injection(it.injectionDepth ?: 4, it.injectionOrder, it.role, it.content) }.toMutableList()
        val mainPrompt = collection.firstOrNull { it.identifier == "main" }
        if (mainPrompt?.injectionPosition == 1) {
            relativeToMain.forEach { (slot, _) ->
                absolute += Injection(mainPrompt.injectionDepth ?: 4, mainPrompt.injectionOrder, mainPrompt.role, slot.content)
            }
            relativeToMain.clear()
        }

        // ---- relative slots in collection order ----
        val slots = mutableListOf<Any>() // Slot, or "chatHistory" / "dialogueExamples"
        for (prompt in collection) {
            val id = prompt.identifier
            if (id in disabled && id != "main") continue
            if (prompt.injectionPosition == 1) continue
            when (id) {
                "chatHistory", "dialogueExamples" -> slots += id
                else -> {
                    if (id == "main") relativeToMain.filter { it.second }.forEach { slots += it.first }
                    slots += Slot(id, prompt.role, prompt.content)
                    if (id == "main") relativeToMain.filter { !it.second }.forEach { slots += it.first }
                }
            }
        }

        // ---- chat history ----
        val isContinue = type == RpGenerationType.Continue
        val namesBehavior = preset?.namesBehavior ?: 0
        val summary = request.memorySummary.trim()
        val covered = request.coveredUntilId?.takeIf { summary.isNotEmpty() }
        val chat = covered?.let { id -> messages.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { messages.drop(it + 1) } } ?: messages
        val historyNewest = chat.mapIndexed { i, msg ->
            val depth = chat.size - i - (if (isContinue) 2 else 1)
            var text = RpRegex.prompt(msg.content, msg.role, depth, scripts, ctx).replace("\r", "")
            if (namesBehavior == 2 && msg.role != "system") text = "${displayName(msg, userName, charName)}: $text"
            Entry(msg.role, text)
        }.asReversed().toMutableList()

        val control = mutableListOf<Entry>()
        if (type == RpGenerationType.Impersonate) {
            control += Entry("system", m(preset?.impersonationPrompt ?: DEFAULT_IMPERSONATION_PROMPT))
        }
        if (request.quietPrompt.isNotBlank()) control += Entry("system", m(request.quietPrompt))

        val continuePrefill = isContinue && preset?.continuePrefill == true
        if (continuePrefill && historyNewest.isNotEmpty()) {
            val last = historyNewest.removeAt(0)
            control += Entry(last.role, m(last.content), tag = "continuePrefill")
        }
        val injected = inject(absolute, extensions, historyNewest, ::m)
        val continueNudge = mutableListOf<Entry>()
        if (isContinue && !continuePrefill) {
            val lastText = messages.lastOrNull()?.content.orEmpty()
            val postfix = preset?.continuePostfix ?: " "
            val cycle = if (lastText.endsWith(" ")) lastText else lastText + postfix
            val at = injected.indexOfLast { !it.injected }
            if (at >= 0) continueNudge += injected.removeAt(at).let { it.copy(content = m(it.content)) }
            val nudge = preset?.continueNudgePrompt ?: "[Continue your last message without repeating its original content.]"
            continueNudge += Entry("system", RpMacros.expand(nudge, ctx.copy(dynamic = ctx.dynamic + ("lastChatMessage" to cycle.trim()))), tag = "continueNudge")
        }
        val history = injected.map { it.copy(content = m(it.content)) }.toMutableList()
        val sendIfEmpty = preset?.sendIfEmpty.orEmpty()
        if (sendIfEmpty.isNotEmpty() && history.lastOrNull()?.role == "assistant") history += Entry("user", sendIfEmpty)

        val prefillText = when {
            type == RpGenerationType.Quiet || continuePrefill -> ""
            type == RpGenerationType.Impersonate -> preset?.assistantImpersonation.orEmpty()
            else -> preset?.assistantPrefill.orEmpty()
        }.let(::m).trim()
        if (prefillText.isNotEmpty()) control += Entry("assistant", prefillText, tag = "assistantPrefill")

        // ---- examples ----
        val exampleBlocks = buildList {
            world.emTop.forEach { addAll(RpExamples.parseMesExamples(m(it))) }
            addAll(RpExamples.parseMesExamples(m(card.mesExample)))
            world.emBottom.forEach { addAll(RpExamples.parseMesExamples(m(it))) }
        }.map { block ->
            RpExamples.parseBlock(block.replace("<START>", "{Example Dialogue:}"), userName, charName)
                .map { Entry(if (it.isUser) "user" else "assistant", it.content, tag = "example") }
        }.filter { it.isNotEmpty() }
        val exampleSeparator = Entry("system", m(preset?.newExampleChatPrompt ?: "[Example Chat]"), tag = "newChat")
        val newChat = Entry("system", m(preset?.newChatPrompt ?: "[Start a new Chat]"), tag = "newChat")
        val memoryEntry = summary.takeIf { it.isNotEmpty() }?.let { Entry("system", "[前情提要]\n$it", tag = "memory") }

        // ---- budget ----
        val hasHistory = "chatHistory" in slots
        val hasExamples = "dialogueExamples" in slots
        fun cost(e: Entry) = ContextTokenEstimate.text(e.content) + MESSAGE_OVERHEAD
        val budget = request.inputBudgetTokens
        var keptHistory = history
        var keptBlocks = if (hasExamples) exampleBlocks else emptyList()
        var omitted = 0
        if (budget != null) {
            var remaining = budget - 3L
            slots.filterIsInstance<Slot>().forEach { if (it.content.isNotEmpty()) remaining -= cost(Entry(it.role, it.content)) }
            (control + continueNudge).forEach { remaining -= cost(it) }
            memoryEntry?.let { remaining -= cost(it) }
            if (hasHistory && newChat.content.isNotEmpty()) remaining -= cost(newChat)
            check(remaining >= 0) { "必需的提示词已超过模型上下文预算，尚未发送。请提高该模型的上下文长度或精简预设与角色资料。" }
            if (hasHistory) {
                val kept = ArrayDeque<Entry>()
                for (entry in history.asReversed()) {
                    val c = cost(entry)
                    if (c > remaining) break
                    remaining -= c; kept.addFirst(entry)
                }
                omitted = history.count { !it.injected } - kept.count { !it.injected }
                keptHistory = kept.toMutableList()
            }
            keptBlocks = buildList {
                for (block in keptBlocks) {
                    val c = cost(exampleSeparator) + block.sumOf(::cost)
                    if (c > remaining) break
                    remaining -= c; add(block)
                }
            }
        }

        // ---- flatten ----
        val flat = mutableListOf<Entry>()
        for (slot in slots) when (slot) {
            is Slot -> flat += Entry(slot.role, slot.content, tag = slot.identifier)
            "dialogueExamples" -> keptBlocks.forEach { block -> flat += exampleSeparator; flat += block }
            "chatHistory" -> {
                memoryEntry?.let { flat += it }
                flat += newChat
                flat += keptHistory
            }
        }
        if (!hasHistory) memoryEntry?.let { flat += it }
        flat += continueNudge
        flat += control
        var final = flat.filter { it.content.isNotEmpty() }
        if (preset?.squashSystemMessages == true) final = squash(final)

        val leading = final.takeWhile { it.role == "system" }
        val rest = final.drop(leading.size)
        var nextId = 1L
        return RpPromptResult(
            systemPrompt = leading.joinToString("\n\n") { it.content.trim() },
            messages = rest.map { ChatMessage(id = nextId++, role = it.role, content = it.content) },
            generationOptions = preset?.generationOptions ?: ChatGenerationOptions(),
            timedState = world.timedState,
            activatedWorldEntries = world.activated,
            omittedMessages = omitted
        )
    }

    /** ST populationInjectionPrompts: [newestFirst] index 0 = newest; returns chronological order. */
    private fun inject(
        prompts: List<Injection>,
        extensions: List<Extension>,
        newestFirst: MutableList<Entry>,
        macros: (String) -> String
    ): MutableList<Entry> {
        val list = newestFirst.toMutableList()
        val depths = (prompts.map { it.depth } + extensions.map { it.depth }).filter { it in 0..MAX_INJECTION_DEPTH }.toSortedSet()
        var total = 0
        for (depth in depths) {
            val atDepth = prompts.filter { it.depth == depth && it.content.isNotEmpty() }
            val groups = (atDepth.map { it.order } + 100).distinct().sortedDescending()
            val roleMessages = mutableListOf<Entry>()
            for (order in groups) {
                for (role in listOf("system", "user", "assistant")) {
                    val text = atDepth.filter { it.order == order && it.role == role }.joinToString("\n") { it.content }
                    val ext = if (order == 100) {
                        extensions.filter { it.depth == depth && it.role == role && it.value.isNotEmpty() }
                            .joinToString("\n") { it.value.trim() }.let { if (it.isEmpty()) it else macros(it) }
                    } else ""
                    val joint = listOf(text, ext).filter(String::isNotEmpty).joinToString("\n") { it.trim() }
                    if (joint.isNotEmpty()) roleMessages += Entry(role, joint, injected = true)
                }
            }
            list.addAll((depth + total).coerceAtMost(list.size), roleMessages)
            total += roleMessages.size
        }
        list.reverse()
        return list
    }

    private fun squash(entries: List<Entry>): List<Entry> {
        val out = mutableListOf<Entry>()
        for (e in entries) {
            if (e.role == "system" && e.content.isBlank()) continue
            val last = out.lastOrNull()
            val mergeable = e.role == "system" && e.tag != "newChat" && e.tag != "example"
            if (mergeable && last != null && last.role == "system" && last.tag != "newChat" && last.tag != "example") {
                out[out.lastIndex] = last.copy(content = last.content + "\n" + e.content)
            } else out += e
        }
        return out
    }

    private fun displayName(msg: RpPromptMessage, user: String, char: String): String = msg.name.ifBlank {
        when (msg.role) { "user" -> user; "assistant" -> char; else -> "System" }
    }

    private fun latestFloor(request: RpPromptRequest): JSONObject? {
        for (msg in request.messages.asReversed()) {
            val table = request.variables.table(RpVarScope.Message, msg.id, 0)
            if (table.length() > 0) return table
        }
        return null
    }

    private fun markerPrompt(id: String) = TavernPrompt(
        identifier = id, name = id, role = "system", content = "", enabled = true, marker = true,
        injectionPosition = 0, injectionDepth = 4, systemPrompt = true
    )

    private fun defaultPrompts(): Map<String, TavernPrompt> = DEFAULT_ORDER.associateWith { id ->
        when (id) {
            "main" -> markerPrompt(id).copy(content = DEFAULT_MAIN_PROMPT, marker = false)
            "jailbreak" -> markerPrompt(id).copy(marker = false)
            else -> markerPrompt(id)
        }
    }
}
