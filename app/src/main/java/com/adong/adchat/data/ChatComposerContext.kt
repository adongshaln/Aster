package com.adong.adchat.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Source snapshots are separate from the user's editable message. */
data class ChatDocumentAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uri: String,
    val mimeType: String,
    val size: Long,
    val text: String
)

data class ChatQuote(
    val id: String = UUID.randomUUID().toString(),
    val messageId: Long,
    val role: String,
    val text: String,
    val partial: Boolean = false
)

data class ChatComposerContext(
    val documents: List<ChatDocumentAttachment> = emptyList(),
    val quotes: List<ChatQuote> = emptyList()
) {
    val isEmpty: Boolean get() = documents.isEmpty() && quotes.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("documents", JSONArray().apply {
            documents.forEach { file -> put(JSONObject().put("id", file.id).put("name", file.name)
                .put("uri", file.uri).put("mimeType", file.mimeType).put("size", file.size).put("text", file.text)) }
        })
        .put("quotes", JSONArray().apply {
            quotes.forEach { quote -> put(JSONObject().put("id", quote.id).put("messageId", quote.messageId)
                .put("role", quote.role).put("text", quote.text).put("partial", quote.partial)) }
        })

    companion object {
        fun fromJson(json: JSONObject?): ChatComposerContext {
            if (json == null) return ChatComposerContext()
            return ChatComposerContext(
                documents = objects(json.optJSONArray("documents")).mapNotNull { item ->
                    val text = item.optString("text")
                    val uri = item.optString("uri")
                    if (text.isBlank() || uri.isBlank()) null else ChatDocumentAttachment(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = item.optString("name"), uri = uri, mimeType = item.optString("mimeType"),
                        size = item.optLong("size"), text = text)
                },
                quotes = objects(json.optJSONArray("quotes")).mapNotNull { item ->
                    val text = item.optString("text")
                    if (text.isBlank()) null else ChatQuote(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        messageId = item.optLong("messageId"), role = item.optString("role"),
                        text = text, partial = item.optBoolean("partial"))
                }
            )
        }

        private fun objects(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
    }
}

/** All three API protocols receive the full snapshot; UI previews may be ellipsized. */
fun ChatMessage.requestContent(): String {
    if (documents.isEmpty() && quotes.isEmpty()) return content
    return buildList {
        if (content.isNotBlank()) add(content)
        quotes.forEach { quote ->
            val source = if (quote.role == "user") "用户" else "助手"
            add("[引用${if (quote.partial) "片段" else "消息"} · $source · ${quote.messageId}]\n" +
                quote.text.lineSequence().joinToString("\n") { "> $it" } + "\n[引用结束]")
        }
        documents.forEach { file ->
            val name = file.name.replace(Regex("[\\r\\n\\[\\]]"), "_")
            add("[附件：$name；以下为用户提供的参考资料]\n${file.text}\n[附件结束]")
        }
    }.joinToString("\n\n")
}
