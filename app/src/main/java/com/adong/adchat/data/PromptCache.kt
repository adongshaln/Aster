package com.adong.adchat.data

import org.json.JSONArray
import org.json.JSONObject

/** 各家缓存协议不同，不能只用 GPT 的 prompt_cache_key。 */
internal enum class PromptCacheFamily { Gpt, Claude, Grok, None }

internal fun String.promptCacheFamily(): PromptCacheFamily {
    val id = trim().substringAfterLast('/').lowercase()
    return when {
        id.startsWith("claude") -> PromptCacheFamily.Claude
        id.isGptModel() -> PromptCacheFamily.Gpt
        id.isGrokModel() -> PromptCacheFamily.Grok
        else -> PromptCacheFamily.None
    }
}

internal object PromptCache {
    fun apply(
        body: JSONObject,
        enabled: Boolean,
        model: String,
        cacheKey: String,
        responsesApi: Boolean
    ) {
        if (!enabled) return
        when (model.promptCacheFamily()) {
            PromptCacheFamily.Gpt, PromptCacheFamily.Grok -> {
                // GPT 与 Grok 都靠稳定的 prompt_cache_key 把同一对话粘到同一缓存节点。
                // 不给 Grok 带 OpenAI 的 prompt_cache_options，xAI 会直接拒绝。
                if (cacheKey.isNotBlank()) body.put("prompt_cache_key", cacheKey.take(64))
            }
            PromptCacheFamily.Claude -> if (!responsesApi) markClaudeMessages(body.optJSONArray("messages"))
            PromptCacheFamily.None -> Unit
        }
    }

    fun strategy(enabled: Boolean, model: String, responsesApi: Boolean): String {
        if (!enabled) return "off"
        return when (model.promptCacheFamily()) {
            PromptCacheFamily.Claude -> "claude-breakpoints"
            PromptCacheFamily.Grok -> "grok-sticky"
            PromptCacheFamily.Gpt -> if (responsesApi) "automatic" else "gpt-key"
            PromptCacheFamily.None -> "off"
        }
    }

    fun requested(enabled: Boolean, model: String): Boolean =
        enabled && model.promptCacheFamily() != PromptCacheFamily.None

    /**
     * Messages API 的顶层 system 字段（字符串或块数组）打同一个断点。
     * system 是最稳定的前缀，断点放这里，长系统提示词跨轮整体命中。
     */
    internal fun markClaudeSystem(system: Any?): Any = withBreakpoint(system)

    /**
     * Claude 只缓存断点之前的稳定前缀。系统提示词、上一轮、以及本轮末尾各放一个断点，
     * 下一轮追加消息时前缀仍能命中。ttl 用 1h，避免 5 分钟默认过期后长对话全部失效。
     */
    internal fun markClaudeMessages(messages: JSONArray?) {
        if (messages == null || messages.length() == 0) return
        val conversational = mutableListOf<Int>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "system" -> message.put("content", withBreakpoint(message.opt("content")))
                "user", "assistant" -> conversational += index
            }
        }
        val breakpoints = buildList {
            if (conversational.size >= 2) add(conversational[conversational.lastIndex - 1])
            if (conversational.isNotEmpty()) add(conversational.last())
        }
        breakpoints.distinct().forEach { index ->
            val message = messages.getJSONObject(index)
            message.put("content", withBreakpoint(message.opt("content")))
        }
    }

    private fun withBreakpoint(content: Any?): Any {
        val control = JSONObject().put("type", "ephemeral").put("ttl", "1h")
        return when (content) {
            is JSONArray -> {
                val last = (content.length() - 1 downTo 0).firstOrNull { content.opt(it) is JSONObject }
                if (last != null) content.optJSONObject(last)?.put("cache_control", control)
                content
            }
            is JSONObject -> content.put("cache_control", control)
            else -> JSONArray().put(
                JSONObject().put("type", "text").put("text", content?.toString().orEmpty()).put("cache_control", control)
            )
        }
    }
}
