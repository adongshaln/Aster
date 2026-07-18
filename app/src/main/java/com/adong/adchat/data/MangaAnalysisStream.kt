package com.adong.adchat.data

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal class MangaAnalysisStreamCollector(
    private val responsesApi: Boolean
) {
    private val output = StringBuilder()
    var completed: Boolean = false
        private set

    fun accept(payload: String): Boolean {
        val value = payload.trim()
        if (value == "[DONE]") {
            completed = true
            return false
        }
        val root = runCatching { JSONObject(value) }.getOrNull() ?: return true
        if (responsesApi) acceptResponsesEvent(root) else acceptChatEvent(root)
        return !completed
    }

    fun requireResult(): String {
        if (!completed) throw IOException("Manga analysis stream ended before completion")
        return output.toString().ifBlank { throw IllegalStateException("漫画辅助模型的流式响应没有返回分析文本") }
    }

    private fun acceptResponsesEvent(root: JSONObject) {
        when (root.optString("type")) {
            "response.output_text.delta" -> output.append(root.optString("delta"))
            "response.output_text.done" -> if (output.isBlank()) output.append(root.optString("text"))
            "response.completed" -> {
                if (output.isBlank()) output.append(extractResponsesText(root.optJSONObject("response") ?: root))
                completed = true
            }
            "response.failed", "response.incomplete", "error" -> {
                val response = root.optJSONObject("response")
                val detail = response?.optJSONObject("error")?.optString("message")
                    ?: root.optJSONObject("error")?.optString("message")
                throw IllegalStateException(detail.orEmpty().ifBlank { "漫画辅助模型流式分析失败" })
            }
        }
    }

    private fun acceptChatEvent(root: JSONObject) {
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return
        val content = choice.optJSONObject("delta")?.opt("content")
        when (content) {
            is String -> output.append(content)
            is JSONArray -> {
                for (index in 0 until content.length()) {
                    output.append(content.optJSONObject(index)?.optString("text").orEmpty())
                }
            }
        }
        if (choice.has("finish_reason") && !choice.isNull("finish_reason")) completed = true
    }

    private fun extractResponsesText(root: JSONObject): String {
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val items = root.optJSONArray("output") ?: return ""
        return buildString {
            for (index in 0 until items.length()) {
                val content = items.optJSONObject(index)?.optJSONArray("content") ?: continue
                for (contentIndex in 0 until content.length()) {
                    append(content.optJSONObject(contentIndex)?.optString("text").orEmpty())
                }
            }
        }
    }
}
