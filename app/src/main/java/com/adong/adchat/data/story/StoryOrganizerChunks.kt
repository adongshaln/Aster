package com.adong.adchat.data.story

import java.security.MessageDigest

data class StoryOrganizerChunk(val index: Int, val start: Int, val end: Int, val text: String, val precedingContext: String) {
    fun fingerprint(userInput: String): String = MessageDigest.getInstance("SHA-256")
        .digest(("v1:$start:$end\n$userInput\n$precedingContext\n$text\n" + StoryMemoryOrganizer.systemPrompt + StoryMemoryOrganizer.discussionPrompt).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

data class StoryOrganizerPartRange(val start: Int, val end: Int)

object StoryOrganizerChunks {
    fun plan(content: String, userInput: String): List<StoryOrganizerChunk> {
        require(content.isNotBlank())
        val capacity = minOf(24_000, 36_000 - userInput.length - 512)
        require(capacity >= 1_024) { "本轮用户输入过长，无法为分段整理保留空间；请精简输入后重试。" }
        val chunks = mutableListOf<StoryOrganizerChunk>()
        var start = 0
        while (start < content.length) {
            require(chunks.size < 16) { "正文需要超过 16 段整理，已暂停以限制自动调用次数。" }
            var end = minOf(content.length, start + capacity)
            if (end < content.length) {
                val newline = content.lastIndexOf('\n', end - 1)
                if (newline >= end - 2_000 && newline > start) end = newline + 1
                if (content[end - 1].isHighSurrogate() && content[end].isLowSurrogate()) end--
            }
            var precedingStart = (start - 512).coerceAtLeast(0)
            if (precedingStart > 0 && content[precedingStart].isLowSurrogate() && content[precedingStart - 1].isHighSurrogate()) precedingStart++
            chunks += StoryOrganizerChunk(chunks.size, start, end, content.substring(start, end), content.substring(precedingStart, start))
            start = end
        }
        return chunks
    }

    fun combine(chunks: List<StoryOrganizerChunk>, outputs: List<StoryOrganizerOutput>): StoryOrganizerOutput {
        require(chunks.isNotEmpty() && chunks.size == outputs.size)
        require(chunks.first().start == 0 && chunks.zipWithNext().all { (a, b) -> a.end == b.start })
        return StoryOrganizerOutput(
            outputs.flatMapIndexed { index, output -> output.memories.map { it.copy(sourcePart = index) } },
            outputs.flatMap { it.proposals }.distinct(),
            chunks.map { StoryOrganizerPartRange(it.start, it.end) }
        )
    }
}
