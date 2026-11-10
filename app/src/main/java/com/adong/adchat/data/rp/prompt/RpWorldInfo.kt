package com.adong.adchat.data.rp.prompt

import com.adong.adchat.data.ContextTokenEstimate
import com.adong.adchat.data.rp.RpWorldDepthEntry
import com.adong.adchat.data.rp.RpWorldScanInput
import com.adong.adchat.data.rp.RpWorldScanResult
import com.adong.adchat.data.story.StoryWorldBookEntry
import org.json.JSONObject

/**
 * SillyTavern world-info scanner (world-info.js checkWorldInfo) for one card lorebook.
 *
 * Global settings follow ST defaults: scan depth 2, recursion off unless the book declares
 * `recursive_scanning`, case-insensitive, no whole-word matching, no group scoring. Timed effects live
 * in `timedState = {sticky:{uid:{hash,start,end,protected}}, cooldown:{…}}` (chat_metadata.timedWorldInfo).
 * [RpWorldScanInput.budgetTokens] <= 0 means unlimited.
 */
object RpWorldInfo {
    const val DEFAULT_SCAN_DEPTH = 2
    private const val MAX_SCAN_DEPTH = 1000
    private const val MAX_LOOPS = 64

    private enum class State { None, Initial, Recursion }

    /** One entry prepared for scanning; identity is the list index. */
    private class Item(val index: Int, val entry: StoryWorldBookEntry, val decorators: List<String>, val body: String) {
        val uid: Int = if (entry.uid >= 0) entry.uid else index
        val key: String = uid.toString()
        val hash: String = entry.toJson().toString().hashCode().toString()
        var content: String = body
    }

    fun scan(input: RpWorldScanInput): RpWorldScanResult {
        val book = input.book
        val recursive = book.recursiveScanning ?: false
        val budget = if (input.budgetTokens <= 0) Int.MAX_VALUE else input.budgetTokens
        val sorted = book.entries.mapIndexed { index, entry ->
            val (decorators, body) = parseDecorators(entry.content)
            Item(index, entry, decorators, body)
        }.sortedByDescending { it.entry.insertionOrder }
        val sortIndex = sorted.withIndex().associate { it.value to it.index }
        if (sorted.isEmpty()) return RpWorldScanResult(timedState = copy(input.timedState))

        val timed = Timed(input, sorted)
        timed.check()

        val buffer = Buffer(input, book.bookScanDepth ?: DEFAULT_SCAN_DEPTH)
        val delayLevels = sorted.map { it.entry.delayUntilRecursion }.filter { it > 0 }.distinct().sorted().toMutableList()
        var currentDelayLevel = if (delayLevels.isEmpty()) 0 else delayLevels.removeAt(0)

        val activated = LinkedHashMap<Item, Unit>()
        val failedProbability = mutableSetOf<Item>()
        var allActivatedText = ""
        var overflowed = false
        var state = State.Initial
        var loops = 0

        while (state != State.None && loops++ < MAX_LOOPS) {
            var next = State.None
            val now = linkedSetOf<Item>()
            for (item in sorted) {
                val entry = item.entry
                if (item in failedProbability || item in activated) continue
                if (!entry.enabled) continue
                if (entry.triggers.isNotEmpty() && entry.triggers.none { it.equals(input.type.wire, true) }) continue
                val sticky = item in timed.sticky
                if (item in timed.delay) continue
                if (item in timed.cooldown && !sticky) continue
                if (state != State.Recursion && entry.delayUntilRecursion > 0 && !sticky) continue
                if (state == State.Recursion && entry.delayUntilRecursion > currentDelayLevel && !sticky) continue
                if (state == State.Recursion && recursive && entry.excludeRecursion && !sticky) continue
                if (item.decorators.contains("@@activate")) { now += item; continue }
                if (item.decorators.contains("@@dont_activate")) continue
                if (entry.constant || sticky) { now += item; continue }
                if (entry.keys.isEmpty()) continue
                val text = buffer.get(entry)
                val primary = entry.keys.any { key -> input.macros(key).trim().let { it.isNotEmpty() && matchKeys(text, it, entry) } }
                if (!primary) continue
                val secondary = entry.secondaryKeys
                if (!entry.selective || secondary.isEmpty()) { now += item; continue }
                if (secondaryPasses(entry, secondary.map { key ->
                        input.macros(key).trim().let { it.isNotEmpty() && matchKeys(text, it, entry) }
                    })) now += item
            }

            val newEntries = now.sortedWith(
                compareByDescending<Item> { it in timed.sticky }.thenBy { sortIndex[it] ?: -1 }
            ).toMutableList()
            filterByInclusionGroups(newEntries, activated.keys, buffer, timed, input)

            val activatedTokens = ContextTokenEstimate.text(allActivatedText)
            var newContent = ""
            var ignoresBudget = newEntries.count { it.entry.ignoreBudget }
            for (item in newEntries) {
                val entry = item.entry
                if (entry.ignoreBudget) ignoresBudget--
                if (overflowed && !entry.ignoreBudget) {
                    if (ignoresBudget > 0) continue
                    break
                }
                val probable = !entry.useProbability || entry.probability >= 100 || item in timed.sticky ||
                    input.random.nextDouble() * 100 <= entry.probability
                if (!probable) {
                    failedProbability += item
                    continue
                }
                item.content = input.macros(item.body)
                newContent += item.content + "\n"
                if (!entry.ignoreBudget && activatedTokens + ContextTokenEstimate.text(newContent) >= budget) {
                    overflowed = true
                    continue
                }
                activated[item] = Unit
            }

            val forRecursion = newEntries.filter { it !in failedProbability && !it.entry.preventRecursion }
            if (recursive && !overflowed && forRecursion.isNotEmpty()) next = State.Recursion
            if (next == State.None && delayLevels.isNotEmpty()) {
                next = State.Recursion
                currentDelayLevel = delayLevels.removeAt(0)
            }
            state = next
            if (state != State.None) {
                val text = forRecursion.joinToString("\n") { it.content }
                if (text.isNotEmpty()) {
                    buffer.recurse += text
                    allActivatedText = text + "\n" + allActivatedText
                }
            }
        }

        timed.set(activated.keys)
        return assemble(activated.keys.toList(), timed.state)
    }

    private fun assemble(items: List<Item>, timedState: JSONObject): RpWorldScanResult {
        // ST sorts by order desc then unshifts each entry: lists end up ascending, ties reversed.
        val ascending = items.sortedByDescending { it.entry.insertionOrder }.reversed().filter { it.content.isNotEmpty() }
        fun at(position: Int) = ascending.filter { it.entry.position == position }.map { it.content }
        val outlets = LinkedHashMap<String, MutableList<String>>()
        ascending.asReversed().filter { it.entry.position == 7 && it.entry.outletName.isNotBlank() }
            .forEach { outlets.getOrPut(it.entry.outletName) { mutableListOf() } += it.content }
        return RpWorldScanResult(
            before = at(0).joinToString("\n"),
            after = at(1).joinToString("\n"),
            anTop = at(2),
            anBottom = at(3),
            emTop = at(5),
            emBottom = at(6),
            depth = ascending.filter { it.entry.position == 4 }.map {
                RpWorldDepthEntry(
                    depth = it.entry.depth.coerceAtLeast(0),
                    role = roleName(it.entry.role),
                    content = it.content,
                    order = it.entry.insertionOrder
                )
            },
            outlets = outlets.mapValues { it.value.joinToString("\n") },
            activated = items.sortedBy { it.index }.map { label(it) },
            timedState = timedState
        )
    }

    private fun label(item: Item): String = item.entry.comment.ifBlank { item.entry.entryName }
        .ifBlank { item.entry.keys.firstOrNull().orEmpty() }.ifBlank { "#${item.uid}" }

    fun roleName(role: Int): String = when (role) { 1 -> "user"; 2 -> "assistant"; else -> "system" }

    /** selectiveLogic over the secondary key hits, in key order. */
    private fun secondaryPasses(entry: StoryWorldBookEntry, hits: List<Boolean>): Boolean = when (entry.selectiveLogic) {
        1 -> hits.any { !it } // NOT_ALL
        2 -> hits.none { it } // NOT_ANY
        3 -> hits.all { it } // AND_ALL
        else -> hits.any { it } // AND_ANY
    }

    /* ---------------- Key matching ---------------- */

    private val REGEX_KEY = Regex("""^/([\s\S]+?)/([gimsuy]*)$""")

    /** `/pattern/flags` key → Regex; null when the key is plain text or the pattern is invalid. */
    fun parseRegexKey(key: String): Regex? {
        val match = REGEX_KEY.find(key) ?: return null
        val pattern = match.groupValues[1]
        if (Regex("""(?<!\\)/""").containsMatchIn(pattern)) return null
        val options = buildSet {
            if ('i' in match.groupValues[2]) add(RegexOption.IGNORE_CASE)
            if ('m' in match.groupValues[2]) add(RegexOption.MULTILINE)
            if ('s' in match.groupValues[2]) add(RegexOption.DOT_MATCHES_ALL)
        }
        return runCatching { Regex(pattern.replaceFirst("\\/", "/"), options) }.getOrNull()
    }

    /**
     * ST matchKeys. Whole-word mode uses `(?:^|\W)key(?:$|\W)` like JS, so CJK neighbours count as
     * boundaries and CJK keys effectively match as substrings.
     */
    fun matchKeys(haystack: String, needle: String, entry: StoryWorldBookEntry): Boolean {
        parseRegexKey(needle)?.let { return it.containsMatchIn(haystack) }
        val caseSensitive = entry.caseSensitiveOverride ?: entry.caseSensitive
        val text = if (caseSensitive) haystack else haystack.lowercase()
        val key = if (caseSensitive) needle else needle.lowercase()
        if (entry.matchWholeWords != true) return text.contains(key)
        if (key.split(Regex("\\s+")).size > 1) return text.contains(key)
        return Regex("(?:^|\\W)(${Regex.escape(key)})(?:$|\\W)").containsMatchIn(text)
    }

    /* ---------------- Decorators ---------------- */

    private val KNOWN_DECORATORS = listOf("@@activate", "@@dont_activate")

    /** Leading `@@` lines → (known decorators, remaining content). `@@@x` is a fallback for an unknown line. */
    fun parseDecorators(content: String): Pair<List<String>, String> {
        if (!content.startsWith("@@")) return emptyList<String>() to content
        val lines = content.split("\n")
        val decorators = mutableListOf<String>()
        var fallback = false
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("@@")) return decorators to lines.drop(i).joinToString("\n")
            if (line.startsWith("@@@") && !fallback) continue
            val normalized = if (line.startsWith("@@@")) line.substring(1) else line
            if (KNOWN_DECORATORS.any { normalized.startsWith(it) }) {
                decorators += KNOWN_DECORATORS.first { normalized.startsWith(it) }
                fallback = false
            } else {
                fallback = true
            }
        }
        return decorators to content
    }

    /* ---------------- Scan buffer ---------------- */

    private class Buffer(val input: RpWorldScanInput, val defaultDepth: Int) {
        val messages = input.chatNewestFirst.take(MAX_SCAN_DEPTH).map { it.trim() }
        val recurse = mutableListOf<String>()

        fun get(entry: StoryWorldBookEntry): String {
            val depth = (entry.scanDepth ?: defaultDepth).coerceAtMost(MAX_SCAN_DEPTH)
            if (depth <= 0) return ""
            val out = StringBuilder("\u0001").append(messages.take(depth).joinToString("\n\u0001"))
            fun add(enabled: Boolean, text: String) { if (enabled && text.isNotEmpty()) out.append("\n\u0001").append(text) }
            add(entry.matchPersonaDescription, input.personaDescription)
            add(entry.matchCharacterDescription, input.characterDescription)
            add(entry.matchCharacterPersonality, input.characterPersonality)
            add(entry.matchCharacterDepthPrompt, input.characterDepthPrompt)
            add(entry.matchScenario, input.scenario)
            add(entry.matchCreatorNotes, input.creatorNotes)
            if (recurse.isNotEmpty()) out.append("\n\u0001").append(recurse.joinToString("\n\u0001"))
            return out.toString()
        }

        fun score(entry: StoryWorldBookEntry): Int {
            val text = get(entry)
            fun hit(key: String) = input.macros(key).trim().let { it.isNotEmpty() && matchKeys(text, it, entry) }
            if (entry.keys.isEmpty()) return 0
            val primary = entry.keys.count(::hit)
            val secondaryKeys = entry.secondaryKeys
            if (secondaryKeys.isEmpty()) return primary
            val secondary = secondaryKeys.count(::hit)
            return when (entry.selectiveLogic) {
                0 -> primary + secondary
                3 -> if (secondary == secondaryKeys.size) primary + secondary else primary
                else -> primary
            }
        }
    }

    /* ---------------- Inclusion groups ---------------- */

    private fun filterByInclusionGroups(
        newEntries: MutableList<Item>,
        activated: Set<Item>,
        buffer: Buffer,
        timed: Timed,
        input: RpWorldScanInput
    ) {
        val grouped = LinkedHashMap<String, MutableList<Item>>()
        newEntries.filter { it.entry.group.isNotBlank() }.forEach { item ->
            item.entry.group.split(Regex(",\\s*")).filter { it.isNotEmpty() }.forEach { grouped.getOrPut(it) { mutableListOf() } += item }
        }
        if (grouped.isEmpty()) return
        val hasSticky = mutableMapOf<String, Boolean>()
        for ((key, group) in grouped) {
            val sticky = group.filter { it in timed.sticky }
            hasSticky[key] = sticky.isNotEmpty()
            if (sticky.isNotEmpty()) group.filter { it !in sticky }.forEach { newEntries.remove(it) }
            group.filter { it in timed.cooldown || it in timed.delay }.forEach { newEntries.remove(it) }
        }
        for ((key, group) in grouped) {
            if (group.none { it.entry.useGroupScoring == true } || hasSticky[key] == true) continue
            val scores = group.associateWith { buffer.score(it.entry) }
            val max = scores.values.maxOrNull() ?: 0
            group.filter { it.entry.useGroupScoring == true && (scores[it] ?: 0) < max }.forEach {
                newEntries.remove(it)
                group.remove(it)
            }
        }
        for ((key, group) in grouped) {
            if (hasSticky[key] == true) continue
            if (activated.any { it.entry.group == key }) {
                group.forEach { newEntries.remove(it) }
                continue
            }
            if (group.size <= 1) continue
            val winner = group.filter { it.entry.groupOverride }.maxByOrNull { it.entry.insertionOrder }
                ?: run {
                    val total = group.sumOf { it.entry.groupWeight }
                    val roll = input.random.nextDouble() * total
                    var cumulative = 0
                    group.firstOrNull { cumulative += it.entry.groupWeight; roll <= cumulative }
                } ?: continue
            group.filter { it !== winner }.forEach { newEntries.remove(it) }
        }
    }

    /* ---------------- Timed effects ---------------- */

    private class Timed(val input: RpWorldScanInput, val items: List<Item>) {
        val state: JSONObject = copy(input.timedState)
        val sticky = mutableSetOf<Item>()
        val cooldown = mutableSetOf<Item>()
        val delay = mutableSetOf<Item>()
        private val length = input.chatLength

        private fun table(type: String): JSONObject =
            state.optJSONObject(type) ?: JSONObject().also { state.put(type, it) }

        private fun effect(item: Item, duration: Int, protected: Boolean) = JSONObject()
            .put("hash", item.hash).put("start", length).put("end", length + duration).put("protected", protected)

        private fun duration(item: Item, type: String): Int =
            (if (type == "sticky") item.entry.sticky else item.entry.cooldown) ?: 0

        fun check() {
            if (!input.dryRun) {
                checkType("sticky", sticky) { item ->
                    val cd = item.entry.cooldown ?: 0
                    if (cd > 0) {
                        table("cooldown").put(item.key, effect(item, cd, true))
                        cooldown += item
                    }
                }
                checkType("cooldown", cooldown) {}
            }
            items.filter { (it.entry.delay ?: 0) > 0 && length < (it.entry.delay ?: 0) }.forEach { delay += it }
        }

        private fun checkType(type: String, buffer: MutableSet<Item>, onEnded: (Item) -> Unit) {
            val table = table(type)
            for (key in table.keys().asSequence().toList()) {
                val value = table.optJSONObject(key)
                if (value == null) { table.remove(key); continue }
                val item = items.firstOrNull { it.hash == value.optString("hash") }
                if (length <= value.optInt("start") && !value.optBoolean("protected")) { table.remove(key); continue }
                if (item == null) {
                    if (length >= value.optInt("end")) table.remove(key)
                    continue
                }
                if (duration(item, type) <= 0) { table.remove(key); continue }
                if (length >= value.optInt("end")) {
                    table.remove(key)
                    onEnded(item)
                    continue
                }
                buffer += item
            }
        }

        fun set(activated: Collection<Item>) {
            if (input.dryRun) return
            for (item in activated) for (type in listOf("sticky", "cooldown")) {
                val n = duration(item, type)
                if (n > 0 && !table(type).has(item.key)) table(type).put(item.key, effect(item, n, false))
            }
        }
    }

    private fun copy(obj: JSONObject): JSONObject = JSONObject(obj.toString())
}
