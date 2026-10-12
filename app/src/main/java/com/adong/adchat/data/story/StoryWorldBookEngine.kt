package com.adong.adchat.data.story

data class StoryWorldBookActivation(
    val before: List<String>,
    val after: List<String>,
    val omitted: Int
)

object StoryWorldBookEngine {
    fun activate(book: StoryWorldBook, scanText: String): StoryWorldBookActivation {
        val haystack = scanText.lowercase()
        val candidates = book.entries.filter { it.enabled && it.content.isNotBlank() }
        val selected = linkedSetOf<StoryWorldBookEntry>()

        fun matches(entry: StoryWorldBookEntry, text: String): Boolean =
            entry.constant || (entry.keys.isNotEmpty() && entry.keys.any { it.isNotBlank() && text.contains(it.lowercase()) })

        fun secondaryPasses(entry: StoryWorldBookEntry, text: String): Boolean {
            val secondary = entry.secondaryKeys.filter { it.isNotBlank() }
            if (secondary.isEmpty()) return true
            val present = secondary.map { text.contains(it.lowercase()) }
            return when (entry.logic) {
                WorldLogic.AND_ANY -> present.any { it }
                WorldLogic.NOT_ANY -> present.none { it }
                WorldLogic.AND_ALL -> present.all { it }
                WorldLogic.NOT_ALL -> !present.all { it }
            }
        }

        candidates.filter { it.constant }.forEach { selected += it }
        candidates.filter { !it.constant && it.keys.isNotEmpty() }
            .filter { matches(it, haystack) && secondaryPasses(it, haystack) }
            .forEach { selected += it }

        if (book.recursive) {
            var swept = selected.map { it.content }.joinToString("\n").lowercase()
            var changed = true
            var guard = 0
            while (changed && guard++ < 3) {
                changed = false
                candidates.filter { it !in selected && !it.constant && it.keys.isNotEmpty() }
                    .filter { matches(it, swept) && secondaryPasses(it, swept) }
                    .forEach {
                        selected += it
                        swept += "\n" + it.content.lowercase()
                        changed = true
                    }
            }
        }

        val ordered = selected.sortedWith(
            compareByDescending<StoryWorldBookEntry> { it.constant }.thenByDescending { it.insertionOrder }
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
