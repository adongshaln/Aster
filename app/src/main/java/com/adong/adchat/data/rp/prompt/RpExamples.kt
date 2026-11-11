package com.adong.adchat.data.rp.prompt

/** One parsed dialogue example message (ST example_user / example_assistant). */
data class RpExampleMessage(val name: String, val content: String) {
    val isUser: Boolean get() = name == "example_user"
}

/** SillyTavern parseMesExamples / parseExampleIntoIndividual. */
object RpExamples {
    /** Splits mes_example into `<START>\n…\n` blocks; empty or a bare `<START>` yields nothing. */
    fun parseMesExamples(examples: String): List<String> {
        if (examples.isBlank() || examples.trim().equals("<START>", ignoreCase = true)) return emptyList()
        val text = if (!examples.trimStart().startsWith("<START>", ignoreCase = true)) "<START>\n${examples.trim()}" else examples
        return text.split(Regex("<START>", RegexOption.IGNORE_CASE)).drop(1)
            .map { "<START>\n" + it.trim() + "\n" }
    }

    /** Parses one block (first line skipped) into user/assistant example messages. */
    fun parseBlock(block: String, userName: String, charName: String): List<RpExampleMessage> {
        val result = mutableListOf<RpExampleMessage>()
        val lines = block.split('\n')
        val current = mutableListOf<String>()
        var inUser = false
        var inBot = false
        fun add(name: String, systemName: String) {
            val parsed = current.joinToString("\n").replaceFirst("$name:", "").trim()
            result += RpExampleMessage(systemName, parsed)
            current.clear()
        }
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.startsWith("$userName:")) {
                if (inBot) add(charName, "example_assistant")
                inUser = true; inBot = false
            } else if (line.startsWith("$charName:")) {
                if (inUser) add(userName, "example_user")
                inBot = true; inUser = false
            }
            current += line
        }
        if (inUser) add(userName, "example_user") else if (inBot) add(charName, "example_assistant")
        return result
    }
}
