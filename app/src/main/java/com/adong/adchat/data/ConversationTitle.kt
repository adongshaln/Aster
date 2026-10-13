package com.adong.adchat.data

internal fun userRounds(messages: List<ChatMessage>): Int =
    messages.count { it.role == "user" && !it.isError && !it.isStreaming && !it.isContinuation }

internal fun automaticConversationTitle(messages: List<ChatMessage>): String =
    messages.firstOrNull { it.role == "user" && !it.isContinuation }?.content
        ?.replace(Regex("\\s+"), " ")?.trim()?.take(24)?.ifBlank { null } ?: "新对话"

/** A title the user typed themselves should not be replaced by the one-shot summary. */
internal fun isAutomaticTitle(title: String, messages: List<ChatMessage>): Boolean {
    val trimmed = title.trim()
    return trimmed == automaticConversationTitle(messages) || trimmed == "新对话" || trimmed == "未命名对话"
}

internal fun sanitizeConversationTitle(raw: String): String =
    raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        .trim()
        .trim('"', '“', '”', '「', '」', '『', '』', '。', '.', '：', ':')
        .replace(Regex("\\s+"), " ")
        .take(18)
        .trim()
