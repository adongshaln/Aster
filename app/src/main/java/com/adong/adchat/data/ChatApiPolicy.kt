package com.adong.adchat.data

/** Provider-prefixed GPT IDs follow the same policy as direct model IDs. */
fun String.isGptModel(): Boolean = trim().substringAfterLast('/').let {
    it.startsWith("gpt-", ignoreCase = true) ||
        it.startsWith("gpt_", ignoreCase = true) ||
        (it.length > 3 && it.startsWith("gpt", ignoreCase = true) && it[3].isDigit())
}

/** Provider-prefixed Grok IDs follow the same policy as direct model IDs. */
fun String.isGrokModel(): Boolean = trim().substringAfterLast('/').startsWith("grok", ignoreCase = true)

/** StepFun model ids, including step-router. Their Messages API has no Anthropic server web_search tool. */
fun String.isStepModel(): Boolean = trim().substringAfterLast('/').startsWith("step-", ignoreCase = true)

/** Provider-prefixed Gemini IDs follow the same policy as direct model IDs. */
fun String.isGeminiModel(): Boolean = trim().substringAfterLast('/').startsWith("gemini", ignoreCase = true)

/** Provider-prefixed DeepSeek IDs follow the same policy as direct model IDs. */
fun String.isDeepSeekModel(): Boolean = trim().substringAfterLast('/').startsWith("deepseek", ignoreCase = true)

/**
 * Dedicated search-preview models (e.g. gpt-4o-search-preview, sonar, search-model) use
 * top-level `web_search_options` on Chat Completions and reject custom function tools.
 * Standard models (Gemini, DeepSeek, Claude, GLM, Kimi, Qwen, Step, etc.) use agentic tool calling (`search_web`).
 */
fun String.isDedicatedChatSearchModel(): Boolean = trim().substringAfterLast('/').lowercase().let {
    it.contains("search") || it.startsWith("sonar")
}

/**
 * GPT 与 Grok 模型固定走 Responses API，其他模型走 Chat Completions。
 * 以实际请求模型判定，因为它可能与 Profile 默认模型不同；模型切换后无需重新配置。
 */
fun ApiProfile.usesResponses(model: String = chatModel): Boolean = model.isGptModel() || model.isGrokModel()

/** 配置显式选 Anthropic 格式、且模型不是 GPT/Grok（它们固定走 Responses）时，对话走 Messages API。 */
fun ApiProfile.usesAnthropicMessages(model: String = chatModel): Boolean =
    apiFormat == API_FORMAT_ANTHROPIC && !usesResponses(model)
