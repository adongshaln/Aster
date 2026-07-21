package com.adong.adchat.data

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal class GeminiImageResponseCollector {
    private val sources = linkedSetOf<String>()
    var completed: Boolean = false
        private set

    fun accept(payload: String): Boolean {
        if (payload.trim() == "[DONE]") {
            completed = true
            return false
        }
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return true
        collectNode(root.opt("images"), imageContext = true)
        collectNode(root.opt("data"), imageContext = true)
        collectNode(root.opt("output"), imageContext = false)
        val choices = root.optJSONArray("choices") ?: JSONArray()
        for (index in 0 until choices.length()) {
            val choice = choices.optJSONObject(index) ?: continue
            collectFromMessage(choice.optJSONObject("message"))
            collectFromMessage(choice.optJSONObject("delta"))
            if (choice.has("finish_reason") && !choice.isNull("finish_reason")) completed = true
        }
        return !completed
    }

    fun result(): List<String> = sources.toList().ifEmpty {
        throw IOException("Gemini 图片接口没有返回图片")
    }

    private fun collectFromMessage(message: JSONObject?) {
        if (message == null) return
        collectNode(message.opt("images"), imageContext = true)
        collectNode(message.opt("content"), imageContext = false)
    }

    private fun collectNode(node: Any?, imageContext: Boolean) {
        when (node) {
            is String -> if (imageContext) addSource(node, "")
            is JSONArray -> for (index in 0 until node.length()) collectNode(node.opt(index), imageContext)
            is JSONObject -> {
                val type = node.optString("type").lowercase()
                val imageUrl = node.optJSONObject("image_url") ?: node.optJSONObject("imageUrl")
                if (imageUrl != null) {
                    addSource(imageUrl.optString("url"), imageUrl.optString("mime_type"))
                } else {
                    addSource(node.optString("image_url"), node.optString("mime_type"))
                    addSource(node.optString("imageUrl"), node.optString("mimeType"))
                }
                val inlineData = node.optJSONObject("inline_data") ?: node.optJSONObject("inlineData")
                if (inlineData != null) {
                    addSource(inlineData.optString("data"), inlineData.optString("mime_type").ifBlank { inlineData.optString("mimeType") })
                }
                if (type.contains("image") || type.contains("inline")) {
                    addSource(node.optString("url"), node.optString("mime_type").ifBlank { node.optString("mimeType") })
                    addSource(node.optString("data"), node.optString("mime_type").ifBlank { node.optString("mimeType") })
                }
                collectNode(node.opt("images"), imageContext = true)
                collectNode(node.opt("content"), imageContext = imageContext)
                collectNode(node.opt("parts"), imageContext = imageContext)
                if (imageContext) {
                    addSource(node.optString("url"), node.optString("mime_type"))
                    addSource(node.optString("b64_json"), node.optString("mime_type"))
                }
            }
        }
    }

    private fun addSource(value: String, mimeType: String) {
        val source = when {
            value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:") -> value
            value.isNotBlank() -> "data:${mimeType.ifBlank { "image/png" }};base64,$value"
            else -> ""
        }
        if (source.isNotBlank()) sources += source
    }
}

internal fun parseGeminiImageResponse(root: JSONObject): List<String> {
    val collector = GeminiImageResponseCollector()
    collector.accept(root.toString())
    return collector.result()
}
