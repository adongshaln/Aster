package com.adong.adchat.data.story

import java.security.MessageDigest

/** Partial summaries are durable work, never formal memory. Only the root is committed. */
internal object StorySummaryPipeline {
    suspend fun run(
        parts: List<String>,
        load: (String, String) -> String?,
        save: (String, String, String) -> Unit,
        generate: suspend (String) -> String
    ): String {
        require(parts.size in 1..16 && parts.all { it.isNotBlank() && it.length <= 29_000 })
        var inputs = parts
        var level = 0
        while (true) {
            val outputs = inputs.mapIndexed { index, input ->
                val node = "$level:$index"
                val hash = MessageDigest.getInstance("SHA-256").digest((StorySummaries.prompt + "\n" + input).toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                load(node, hash)?.also { StorySummaries.parse(it) } ?: generate(input).also {
                    StorySummaries.parse(it)
                    save(node, hash, it)
                }
            }
            if (outputs.size == 1) return outputs.single()
            inputs = outputs.chunked(4).map { group -> group.mapIndexed { index, raw ->
                "[按时间顺序的局部摘要 ${index + 1}]\n${StorySummaries.parse(raw)}"
            }.joinToString("\n\n") }
            level++
            check(level <= 2)
        }
    }
}
