package com.adong.adchat.data.story

data class StoryWorldBookActivation(
    val before: List<String>,
    val after: List<String>,
    val omitted: Int
)

object StoryWorldBookEngine {
    fun activate(book: StoryWorldBook, scanText: String): StoryWorldBookActivation {
        val candidates = book.entries.filter { it.enabled && it.content.isNotBlank() }
        val selected = linkedSetOf<StoryWorldBookEntry>()

        fun contains(entry: StoryWorldBookEntry, text: String, key: String): Boolean =
            if (entry.caseSensitive) text.contains(key) else text.lowercase().contains(key.lowercase())

        fun matches(entry: StoryWorldBookEntry, text: String): Boolean =
            entry.constant || (entry.keys.isNotEmpty() && entry.keys.any { it.isNotBlank() && contains(entry, text, it) })

        fun secondaryPasses(entry: StoryWorldBookEntry, text: String): Boolean {
            val secondary = entry.secondaryKeys.filter { it.isNotBlank() }
            if (secondary.isEmpty()) return true
            val present = secondary.map { contains(entry, text, it) }
            return when (entry.logic) {
                WorldLogic.AND_ANY -> present.any { it }
                WorldLogic.NOT_ANY -> present.none { it }
                WorldLogic.AND_ALL -> present.all { it }
                WorldLogic.NOT_ALL -> !present.all { it }
            }
        }

        candidates.filter { it.constant }.forEach { selected += it }
        candidates.filter { !it.constant && it.keys.isNotEmpty() }
            .filter { matches(it, scanText) && secondaryPasses(it, scanText) }
            .forEach { selected += it }

        if (book.recursive) {
            var swept = selected.map { it.content }.joinToString("\n")
            var changed = true
            var guard = 0
            while (changed && guard++ < 3) {
                changed = false
                candidates.filter { it !in selected && !it.constant && it.keys.isNotEmpty() }
                    .filter { matches(it, swept) && secondaryPasses(it, swept) }
                    .forEach {
                        selected += it
                        swept += "\n" + it.content
                        changed = true
                    }
            }
        }

        // Keep higher-priority entries first when the budget runs out; insertion_order then orders placement.
        val ordered = selected.sortedWith(
            compareByDescending<StoryWorldBookEntry> { it.constant }
                .thenByDescending { it.priority }
                .thenByDescending { it.insertionOrder }
        )
        var budget = book.charBudget
        val kept = mutableListOf<StoryWorldBookEntry>()
        var omitted = 0
        for (entry in ordered) {
            if (entry.content.length <= budget) {
                kept += entry
                budget -= entry.content.length
            } else {
                omitted += 1
            }
        }
        val before = kept.filter { it.beforeChar }.sortedBy { it.insertionOrder }.map { it.content }
        val after = kept.filter { !it.beforeChar }.sortedBy { it.insertionOrder }.map { it.content }
        return StoryWorldBookActivation(before, after, omitted)
    }

    fun scanText(recentMessages: List<String>, card: StoryCharacterCard?, userPersona: String): String =
        buildString {
            card?.let {
                append(it.description).append('\n')
                append(it.personality).append('\n')
                append(it.scenario).append('\n')
            }
            append(userPersona).append('\n')
            recentMessages.forEach { append(it).append('\n') }
        }.trim()
}
