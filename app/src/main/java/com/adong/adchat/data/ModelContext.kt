package com.adong.adchat.data

import org.json.JSONObject

/** A local request budget, not a claim about a provider's real capacity. */
data class ModelContextLimits(val windowTokens: Int, val outputTokens: Int) {
    val safetyTokens: Int get() = maxOf(512, windowTokens / 20)
    val inputTokens: Int get() = windowTokens - outputTokens - safetyTokens
    fun validate(): ModelContextLimits = apply {
        require(windowTokens in 4096..2_097_152) { "上下文窗口需为 4,096–2,097,152 Token。" }
        require(outputTokens in 256..(windowTokens - safetyTokens - 1024)) { "最大输出至少 256 Token，且需为输入保留至少 1,024 Token。" }
    }
}

object ModelContextSettings {
    fun encode(values: Map<String, ModelContextLimits>): JSONObject = JSONObject().apply {
        values.forEach { (model, limits) ->
            limits.validate()
            put(model, JSONObject().put("windowTokens", limits.windowTokens).put("outputTokens", limits.outputTokens))
        }
    }
    fun decode(value: JSONObject?): Map<String, ModelContextLimits> = buildMap {
        value?.keys()?.forEach { model ->
            require(model.isNotBlank()) { "上下文配置缺少模型 ID。" }
            val item = value.getJSONObject(model)
            val window = item.getLong("windowTokens")
            val output = item.getLong("outputTokens")
            require(window in 4096L..2_097_152L && output in 256L..2_097_152L) { "模型上下文数值超出范围。" }
            put(model, ModelContextLimits(window.toInt(), output.toInt()).validate())
        }
    }
}

fun ApiProfile.contextLimits(model: String): ModelContextLimits? = modelContexts[model.trim()]?.validate()

/** Deliberately conservative local estimate; provider tokenizers and image accounting differ. */
object ContextTokenEstimate {
    fun text(value: String): Int {
        var units = 0L
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            units += when {
                cp < 128 -> 1 // Three ASCII characters per estimated token.
                cp <= 0xffff -> 6 // Two tokens per non-ASCII BMP character.
                else -> 12
            }
            i += Character.charCount(cp)
        }
        return ((units + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    const val IMAGE_TOKENS = 4096
    fun message(value: ChatMessage): Int = text(value.content) + 16 + value.attachments.size * IMAGE_TOKENS
}

data class PreparedContext(val history: List<ChatMessage>, val omittedTurns: Int)

object ModelContextPolicy {
    const val REQUEST_OVERHEAD = 1024
    fun prepare(system: String, history: List<ChatMessage>, limits: ModelContextLimits?, trimHistory: Boolean): PreparedContext {
        if (limits == null) return PreparedContext(history, 0)
        limits.validate()
        val stable = history.filterNot { it.isError || it.isStreaming || it.isInterrupted || it.isStopped }
        val systemCost = ContextTokenEstimate.text(system).toLong() + REQUEST_OVERHEAD
        fun cost(rows: List<ChatMessage>) = systemCost + rows.sumOf { ContextTokenEstimate.message(it).toLong() }
        if (!trimHistory) {
            require(cost(stable) <= limits.inputTokens) { "故事必需资料与输入超过模型上下文预算，尚未发送。请提高该模型的上下文长度或精简资料。" }
            return PreparedContext(stable, 0)
        }
        // Each user message begins a whole turn. Preserve the latest turn and a continuous suffix.
        val turns = mutableListOf<MutableList<ChatMessage>>()
        val mandatory = mutableListOf<ChatMessage>()
        stable.forEach { message ->
            if (message.role in setOf("system", "developer")) mandatory += message
            else if (message.role == "user") turns += mutableListOf(message)
            else if (turns.isNotEmpty()) turns.last() += message
        }
        val latest = turns.lastOrNull().orEmpty()
        require(cost(mandatory + latest) <= limits.inputTokens) {
            "当前输入、附件和系统提示超过模型上下文预算，尚未发送。请精简输入、减少附件或提高上下文长度。"
        }
        var remaining = limits.inputTokens - cost(mandatory + latest)
        val chosen = mutableListOf<List<ChatMessage>>(latest)
        var omitted = 0
        for (i in (0 until (turns.size - 1)).reversed()) {
            val turnCost = turns[i].sumOf { ContextTokenEstimate.message(it).toLong() }
            if (turnCost > remaining) { omitted = i + 1; break }
            chosen += turns[i]; remaining -= turnCost
        }
        return PreparedContext(mandatory + chosen.asReversed().flatten(), omitted)
    }

    // Count actual payload content, including tool schemas/results, without counting base64 as text.
    fun wireCost(value: Any?): Long = when (value) {
        null, JSONObject.NULL -> 0L
        is JSONObject -> if (value.optString("type") in setOf("input_image", "image_url")) ContextTokenEstimate.IMAGE_TOKENS.toLong()
            else 8L + value.keys().asSequence().sumOf { key -> ContextTokenEstimate.text(key).toLong() + wireCost(value.opt(key)) }
        is org.json.JSONArray -> (0 until value.length()).sumOf { wireCost(value.opt(it)) }
        is String -> ContextTokenEstimate.text(value).toLong()
        else -> 1L
    }
    fun applyToRequest(body: JSONObject, limits: ModelContextLimits?, responses: Boolean, carriedTokens: Long = 0): Long {
        if (limits == null) return 0
        limits.validate()
        val inputCost = wireCost(body.opt(if (responses) "input" else "messages")) +
            wireCost(body.opt("instructions")) + wireCost(body.opt("tools")) + 128 + carriedTokens
        require(inputCost <= limits.inputTokens) { "包含工具结果的请求超过模型上下文预算，已停止发送。请提高上下文长度或减少输入。" }
        body.put(if (responses) "max_output_tokens" else "max_tokens", limits.outputTokens)
        return inputCost
    }
}
