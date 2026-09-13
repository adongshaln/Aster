package com.adong.adchat.data.story

internal data class StoryThoughtFragment(val text: String, val incomplete: Boolean)
internal data class StoryThoughtSplit(
    val body: String,
    val planning: List<StoryThoughtFragment>,
    val thinking: List<StoryThoughtFragment>
)

/** Reads visible reply markup only; it does not infer or generate model reasoning. */
internal object StoryThoughtParser {
    private val tagPattern = Regex("<(/?)(konatan_planning~|think|thinking)\\s*>", RegexOption.IGNORE_CASE)
    private val fenceLine = Regex("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)\\r?$", RegexOption.MULTILINE)

    fun split(raw: String): StoryThoughtSplit {
        val tags = tagPattern.findAll(raw).toList()
        val body = StringBuilder()
        val planning = mutableListOf<StoryThoughtFragment>()
        val thinking = mutableListOf<StoryThoughtFragment>()
        var cursor = 0
        var bodyFences = fences(raw, cursor)
        for ((index, tag) in tags.withIndex()) {
            if (tag.range.first < cursor || bodyFences.any { tag.range.first in it }) continue
            val name = tag.groupValues[2]
            if (tag.groupValues[1].isEmpty()) {
                body.append(raw.substring(cursor, tag.range.first))
                val contentStart = tag.range.last + 1
                // Inside a thought the owning closing tag is structural, ahead of Markdown.
                // Code fences cannot extend the thought into the subsequent answer. Other
                // tag types inside it remain part of its text, including planning drafts.
                val end = (index + 1 until tags.size).asSequence().map { tags[it] }.firstOrNull {
                    it.groupValues[1] == "/" && it.groupValues[2].equals(name, true)
                }
                val fragment = StoryThoughtFragment(raw.substring(contentStart, end?.range?.first ?: raw.length).trim(), end == null)
                if (name.equals("konatan_planning~", true)) planning += fragment else thinking += fragment
                cursor = end?.range?.last?.plus(1) ?: raw.length
                // Start fresh after the owning tag: a fence inside it cannot affect siblings.
                bodyFences = fences(raw, cursor)
            } else if (cursor == 0 && name.equals("konatan_planning~", true)) {
                // Some assistant prefills contain the opening tag outside the returned text.
                planning += StoryThoughtFragment(raw.substring(0, tag.range.first).trim(), false)
                cursor = tag.range.last + 1
                bodyFences = fences(raw, cursor)
            }
        }
        body.append(raw.substring(cursor))
        return StoryThoughtSplit(body.toString(), planning, thinking)
    }

    private fun fences(raw: String, start: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var opening: MatchResult? = null
        for (line in fenceLine.findAll(raw, start)) {
            val current = opening
            if (current == null) {
                // Backtick fence info strings cannot themselves contain backticks.
                if (line.groupValues[1][0] == '`' && '`' in line.groupValues[2]) continue
                opening = line
            } else if (line.groupValues[1][0] == current.groupValues[1][0] &&
                line.groupValues[1].length >= current.groupValues[1].length && line.groupValues[2].isBlank()) {
                ranges += current.range.first..line.range.last
                opening = null
            }
        }
        opening?.let { ranges += it.range.first until raw.length }
        return ranges
    }
}
