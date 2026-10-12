package com.adong.adchat.data.story

/**
 * Extracts the JSON payload of a ```charactercard fenced block from a model reply.
 * Mirrors [StoryProsePresenter]'s fence rules: the language tag must not contain a backtick,
 * and a closing fence must own its line (^ {0,3}```[ \t]*$); an unterminated fence still
 * yields the tail of the message via the \z fallback.
 */
object CharacterCardFence {
    private val fence = Regex("```charactercard[ \\t]*\\n([\\s\\S]*?)(?:^ {0,3}```[ \\t]*$|\\z)", RegexOption.MULTILINE)

    /** First ```charactercard fenced payload, trimmed; null when the message has none. */
    fun extract(text: String): String? =
        fence.findAll(text).firstOrNull()?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
}
