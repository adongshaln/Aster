package com.adong.adchat.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val CREATE_FILE_TOOL = "create_file"
internal const val WEB_SEARCH_TOOL = "web_search"
internal const val MAX_TOOL_ROUNDS = 4

internal data class PendingToolCall(
    val itemId: String,
    val callId: String,
    val name: String,
    val arguments: String
)

internal data class ToolExecutionResult(
    val output: String,
    val generatedFile: GeneratedFileDraft? = null,
    val activity: ChatToolActivity
)

internal data class ProtocolRoundResult(
    val text: String,
    val usage: TokenUsage,
    val toolCalls: List<PendingToolCall>,
    val citations: List<ChatCitation>,
    val responseId: String = "",
    val usedWebSearch: Boolean = false
)

internal operator fun TokenUsage.plus(other: TokenUsage): TokenUsage = TokenUsage(
    inputTokens = inputTokens + other.inputTokens,
    cachedTokens = cachedTokens + other.cachedTokens,
    cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
    outputTokens = outputTokens + other.outputTokens,
    reasoningTokens = reasoningTokens + other.reasoningTokens,
    totalTokens = totalTokens + other.totalTokens,
    providerUsageReported = providerUsageReported || other.providerUsageReported,
    cacheMetricsReported = cacheMetricsReported || other.cacheMetricsReported
)

internal data class ChatToolPolicy(
    val webSearchEnabled: Boolean,
    val fileCreationEnabled: Boolean
)

internal fun resolveChatToolPolicy(
    webSearchEnabled: Boolean,
    fileCreationEnabled: Boolean
): ChatToolPolicy = if (webSearchEnabled) {
    // Chat search models commonly use web_search_options and frequently reject
    // custom function tools in the same request. The explicit search choice wins.
    ChatToolPolicy(webSearchEnabled = true, fileCreationEnabled = false)
} else {
    ChatToolPolicy(webSearchEnabled = false, fileCreationEnabled = fileCreationEnabled)
}

internal fun buildChatTools(fileCreationEnabled: Boolean): JSONArray = JSONArray().apply {
    if (fileCreationEnabled) {
        put(JSONObject()
            .put("type", "function")
            .put("function", createFileDefinition(responsesApi = false)))
    }
}

internal fun buildResponsesTools(
    fileCreationEnabled: Boolean,
    webSearchEnabled: Boolean
): JSONArray = JSONArray().apply {
    if (webSearchEnabled) put(JSONObject().put("type", WEB_SEARCH_TOOL))
    if (fileCreationEnabled) put(createFileDefinition(responsesApi = true))
}

private fun createFileDefinition(responsesApi: Boolean): JSONObject {
    val parameters = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("filename", JSONObject()
                .put("type", "string")
                .put("description", "The user-facing file name, including a supported extension."))
            .put("mime_type", JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(listOf("text/markdown", "text/plain", "application/json", "text/csv"))))
            .put("content", JSONObject()
                .put("type", "string")
                .put("description", "The complete UTF-8 text content of the file.")))
        .put("required", JSONArray(listOf("filename", "mime_type", "content")))
        .put("additionalProperties", false)
    val definition = JSONObject()
        .put("name", CREATE_FILE_TOOL)
        .put("description", "Create a real downloadable text file only when the user explicitly asks for a file or export. Do not use it merely because a normal answer contains Markdown formatting.")
        .put("parameters", parameters)
    return if (responsesApi) definition.put("type", "function").put("strict", true) else definition
}

internal class ChatToolCallAccumulator {
    private data class MutableCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    private val calls = linkedMapOf<Int, MutableCall>()

    fun accept(root: JSONObject) {
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return
        val toolCalls = choice.optJSONObject("delta")?.optJSONArray("tool_calls")
            ?: choice.optJSONObject("message")?.optJSONArray("tool_calls")
            ?: return
        for (index in 0 until toolCalls.length()) {
            val part = toolCalls.optJSONObject(index) ?: continue
            val position = part.optInt("index", index)
            val target = calls.getOrPut(position) { MutableCall() }
            part.optString("id").takeIf(String::isNotBlank)?.let { target.id = it }
            val function = part.optJSONObject("function") ?: continue
            function.optString("name").takeIf(String::isNotBlank)?.let { target.name = it }
            val arguments = function.optString("arguments")
            if (arguments.isNotEmpty()) {
                if (choice.has("message")) target.arguments.apply { clear(); append(arguments) }
                else target.arguments.append(arguments)
            }
        }
    }

    fun completedCalls(): List<PendingToolCall> = calls.values.mapNotNull { call ->
        call.name.takeIf(String::isNotBlank)?.let {
            PendingToolCall(
                itemId = call.id.ifBlank { UUID.randomUUID().toString() },
                callId = call.id.ifBlank { UUID.randomUUID().toString() },
                name = call.name,
                arguments = call.arguments.toString().ifBlank { "{}" }
            )
        }
    }
}

internal class ResponsesToolCallAccumulator {
    private data class MutableCall(
        var itemId: String = "",
        var callId: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
        var done: Boolean = false
    )

    private val calls = linkedMapOf<String, MutableCall>()

    fun accept(root: JSONObject) {
        when (root.optString("type")) {
            "response.output_item.added", "response.output_item.done" -> {
                val item = root.optJSONObject("item") ?: return
                if (item.optString("type") != "function_call") return
                val key = item.optString("id").ifBlank { item.optString("call_id") }
                if (key.isBlank()) return
                val call = calls.getOrPut(key) { MutableCall(itemId = key) }
                call.itemId = item.optString("id").ifBlank { call.itemId }
                call.callId = item.optString("call_id").ifBlank { call.callId }
                call.name = item.optString("name").ifBlank { call.name }
                item.optString("arguments").takeIf(String::isNotBlank)?.let {
                    call.arguments.clear(); call.arguments.append(it)
                }
                if (root.optString("type") == "response.output_item.done") call.done = true
            }
            "response.function_call_arguments.delta" -> {
                val key = root.optString("item_id")
                if (key.isNotBlank()) calls.getOrPut(key) { MutableCall(itemId = key) }
                    .arguments.append(root.optString("delta"))
            }
            "response.function_call_arguments.done" -> {
                val key = root.optString("item_id")
                if (key.isNotBlank()) {
                    val call = calls.getOrPut(key) { MutableCall(itemId = key) }
                    root.optString("arguments").takeIf(String::isNotBlank)?.let {
                        call.arguments.clear(); call.arguments.append(it)
                    }
                    call.done = true
                }
            }
            "response.completed" -> acceptResponse(root.optJSONObject("response") ?: root)
        }
    }

    fun acceptResponse(root: JSONObject) {
        val response = root.optJSONObject("response") ?: root
        val output = response.optJSONArray("output") ?: return
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            if (item.optString("type") != "function_call") continue
            val key = item.optString("id").ifBlank { item.optString("call_id") }
            if (key.isBlank()) continue
            val call = calls.getOrPut(key) { MutableCall(itemId = key) }
            call.itemId = item.optString("id").ifBlank { call.itemId }
            call.callId = item.optString("call_id").ifBlank { call.callId }
            call.name = item.optString("name").ifBlank { call.name }
            item.optString("arguments").takeIf(String::isNotBlank)?.let {
                call.arguments.clear(); call.arguments.append(it)
            }
            call.done = true
        }
    }

    fun completedCalls(): List<PendingToolCall> = calls.values.mapNotNull { call ->
        call.name.takeIf(String::isNotBlank)?.let {
            PendingToolCall(
                itemId = call.itemId,
                callId = call.callId.ifBlank { call.itemId },
                name = call.name,
                arguments = call.arguments.toString().ifBlank { "{}" }
            )
        }
    }
}

internal fun executeAppTool(call: PendingToolCall): ToolExecutionResult {
    if (call.name != CREATE_FILE_TOOL) {
        return ToolExecutionResult(
            output = JSONObject().put("ok", false).put("error", "Unsupported tool: ${call.name}").toString(),
            activity = ChatToolActivity(call.callId, call.name, "工具 ${call.name} 不受支持", TOOL_STATUS_FAILED)
        )
    }
    return runCatching {
        val arguments = JSONObject(call.arguments)
        val mimeType = arguments.optString("mime_type")
        require(mimeType in MIME_EXTENSIONS) { "不支持的文件类型" }
        val content = arguments.optString("content")
        val file = GeneratedFileDraft(
            name = sanitizeFileName(arguments.optString("filename"), mimeType),
            mimeType = mimeType,
            content = content
        )
        ToolExecutionResult(
            output = JSONObject()
                .put("ok", true)
                .put("filename", file.name)
                .put("mime_type", file.mimeType)
                .put("size_bytes", file.content.toByteArray(Charsets.UTF_8).size)
                .toString(),
            generatedFile = file,
            activity = ChatToolActivity(call.callId, CREATE_FILE_TOOL, "已创建 ${file.name}", TOOL_STATUS_COMPLETED)
        )
    }.getOrElse { error ->
        ToolExecutionResult(
            output = JSONObject().put("ok", false).put("error", error.message ?: "文件创建失败").toString(),
            activity = ChatToolActivity(call.callId, CREATE_FILE_TOOL, error.message ?: "文件创建失败", TOOL_STATUS_FAILED)
        )
    }
}

internal fun parseCitations(root: JSONObject): List<ChatCitation> {
    val result = linkedMapOf<String, ChatCitation>()

    fun add(url: String, title: String = "", startIndex: Int? = null, endIndex: Int? = null) {
        if (url.isBlank()) return
        result[url] = ChatCitation(title.ifBlank { url }, url, startIndex, endIndex)
    }

    fun acceptAnnotations(array: JSONArray?) {
        if (array == null) return
        for (index in 0 until array.length()) {
            val raw = array.optJSONObject(index) ?: continue
            val citation = raw.optJSONObject("url_citation") ?: raw
            if (raw.optString("type") == "url_citation" || raw.has("url_citation") || citation.has("url")) {
                add(
                    citation.optString("url"),
                    citation.optString("title"),
                    citation.optIntOrNull("start_index"),
                    citation.optIntOrNull("end_index")
                )
            }
        }
    }

    fun acceptMessage(message: JSONObject?) {
        if (message == null) return
        acceptAnnotations(message.optJSONArray("annotations"))
        val content = message.optJSONArray("content") ?: return
        for (index in 0 until content.length()) {
            acceptAnnotations(content.optJSONObject(index)?.optJSONArray("annotations"))
        }
    }

    listOf("citations", "web_search_sources", "sources").forEach { key ->
        val array = root.optJSONArray(key) ?: return@forEach
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is String -> add(item)
                is JSONObject -> add(item.optString("url"), item.optString("title"))
            }
        }
    }
    val response = root.optJSONObject("response") ?: root
    val output = response.optJSONArray("output")
    if (output != null) for (index in 0 until output.length()) acceptMessage(output.optJSONObject(index))
    val choice = root.optJSONArray("choices")?.optJSONObject(0)
    acceptMessage(choice?.optJSONObject("message"))
    acceptMessage(choice?.optJSONObject("delta"))
    return result.values.toList()
}

internal fun responseUsedWebSearch(root: JSONObject): Boolean {
    val response = root.optJSONObject("response") ?: root
    val output = response.optJSONArray("output") ?: return false
    for (index in 0 until output.length()) {
        if (output.optJSONObject(index)?.optString("type") == "web_search_call") return true
    }
    return false
}

private val MIME_EXTENSIONS = mapOf(
    "text/markdown" to "md",
    "text/plain" to "txt",
    "application/json" to "json",
    "text/csv" to "csv"
)

private val ALLOWED_EXTENSIONS = MIME_EXTENSIONS.values.toSet()

private fun sanitizeFileName(requested: String, mimeType: String): String {
    val defaultExtension = MIME_EXTENSIONS.getValue(mimeType)
    val raw = requested.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_")
        .trim().trim('.').take(96)
    val base = raw.ifBlank { "document.$defaultExtension" }
    val requestedExtension = base.substringAfterLast('.', "").lowercase()
    if (requestedExtension == defaultExtension) return base
    val stem = if (requestedExtension in ALLOWED_EXTENSIONS) base.substringBeforeLast('.') else base
    return "${stem.trimEnd('.')}.$defaultExtension"
}

private fun JSONObject.optIntOrNull(name: String): Int? =
    optInt(name).takeIf { has(name) && !isNull(name) }
