package com.adong.adchat.data.rp.prompt

import com.adong.adchat.data.ChatMessage

/**
 * SillyTavern "prompt post-processing": presets emit many system messages inside the chat, which
 * most endpoints reject or silently drop (Anthropic only accepts a top-level system; Gemini/Responses
 * expect system first and a user turn last). This folds the assembled request into a shape every
 * protocol accepts while keeping the preset's order:
 *
 * - leading system messages join the system prompt;
 * - a system message elsewhere becomes a user message (strict) or merges into the previous one;
 * - consecutive messages with the same role are merged;
 * - the request starts with a user turn and, unless the preset ends on an assistant prefill, ends on one.
 */
object RpPromptPostProcess {
    enum class Mode { Lenient, Strict }

    data class Result(val systemPrompt: String, val messages: List<ChatMessage>)

    fun apply(systemPrompt: String, messages: List<ChatMessage>, mode: Mode, keepAssistantPrefill: Boolean): Result {
        val system = StringBuilder(systemPrompt.trim())
        var i = 0
        while (i < messages.size && messages[i].role == "system") {
            if (system.isNotEmpty()) system.append("\n\n")
            system.append(messages[i].content.trim())
            i++
        }
        val rest = messages.drop(i).map { m ->
            when {
                m.role != "system" && m.role != "developer" -> m
                mode == Mode.Strict -> m.copy(role = "user")
                else -> m
            }
        }
        val merged = mutableListOf<ChatMessage>()
        for (m in rest) {
            if (m.content.isBlank() && m.attachments.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.role == m.role && last.attachments.isEmpty() && m.attachments.isEmpty()) {
                merged[merged.lastIndex] = last.copy(content = last.content.trimEnd() + "\n\n" + m.content.trimStart())
            } else merged += m
        }
        if (mode == Mode.Strict) {
            if (merged.firstOrNull()?.role == "assistant") merged.add(0, ChatMessage(role = "user", content = "[Start a new Chat]"))
            val endsOnPrefill = keepAssistantPrefill && merged.lastOrNull()?.role == "assistant"
            if (merged.isEmpty() || (merged.last().role != "user" && !endsOnPrefill)) {
                merged += ChatMessage(role = "user", content = "[Continue]")
            }
        }
        return Result(system.toString(), merged)
    }
}
