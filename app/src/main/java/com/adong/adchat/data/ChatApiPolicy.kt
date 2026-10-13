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

/**
 * GPT 与 Grok 模型固定走 Responses API，其他模型走 Chat Completions。
 * 以实际请求模型判定，因为它可能与 Profile 默认模型不同；模型切换后无需重新配置。
 */
fun ApiProfile.usesResponses(model: String = chatModel): Boolean = model.isGptModel() || model.isGrokModel()
