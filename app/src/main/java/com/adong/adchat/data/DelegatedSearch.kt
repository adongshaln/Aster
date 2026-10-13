package com.adong.adchat.data

import org.json.JSONArray
import org.json.JSONObject

const val DELEGATED_WEB_SEARCH_TOOL = "search_web"
const val MAX_DELEGATED_SEARCH_CALLS = 4

data class SearchBackendConfig(
    val profile: ApiProfile,
    val model: String,
    val allowXSearch: Boolean = false
)

data class DelegatedSearchResult(
    val output: String,
    val citations: List<ChatCitation>,
    val activity: ChatToolActivity
)

internal fun delegatedSearchDefinition(allowXSearch: Boolean): JSONObject {
    val sourceValues = mutableListOf("web")
    if (allowXSearch) sourceValues += "x"
    return JSONObject()
        .put("name", DELEGATED_WEB_SEARCH_TOOL)
        .put("description", "Search current internet information through Aster's configured search backend. Use this when the answer depends on recent, changing, external, or source-verifiable information. The backend performs real server-side search. Do not invent search results. Prefer one focused query; make another call only when materially different evidence is needed.")
        .put("parameters", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("query", JSONObject()
                    .put("type", "string")
                    .put("description", "A self-contained search query. Include names, dates, versions, or constraints needed to research the user's question without the full chat history."))
                .put("source", JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(sourceValues))
                    .put("description", if (allowXSearch) "Use web for normal internet research or x for public X posts." else "Internet source; only web is enabled.")))
            .put("required", JSONArray(listOf("query")))
            .put("additionalProperties", false))
}

internal fun parseServerSideSearchSources(root: JSONObject): List<ChatCitation> {
    val result = linkedMapOf<String, ChatCitation>()
    parseCitations(root).forEach { result[it.url] = it }
    val response = root.optJSONObject("response") ?: root
    val output = response.optJSONArray("output") ?: return result.values.toList()
    for (index in 0 until output.length()) {
        val item = output.optJSONObject(index) ?: continue
        if (item.optString("type") !in setOf("web_search_call", "x_search_call")) continue
        val action = item.optJSONObject("action") ?: continue
        val sources = action.optJSONArray("sources") ?: continue
        for (sourceIndex in 0 until sources.length()) {
            val source = sources.optJSONObject(sourceIndex) ?: continue
            val url = source.optString("url").trim()
            if (url.isBlank()) continue
            result[url] = ChatCitation(source.optString("title").ifBlank { url }, url)
        }
    }
    return result.values.toList()
}

internal fun responseUsedDelegatedSearch(root: JSONObject, source: String): Boolean {
    val expectedType = if (source.lowercase() == "x") "x_search_call" else "web_search_call"
    val response = root.optJSONObject("response") ?: root
    val output = response.optJSONArray("output") ?: return false
    for (index in 0 until output.length()) {
        if (output.optJSONObject(index)?.optString("type") == expectedType) return true
    }
    return false
}

internal fun delegatedSearchToolOutput(
    query: String,
    source: String,
    backendModel: String,
    research: String,
    citations: List<ChatCitation>,
    reused: Boolean = false
): String = JSONObject()
    .put("ok", true)
    .put("query", query)
    .put("source", source)
    .put("backend_model", backendModel)
    .put("reused", reused)
    .put("research", research)
    .put("sources", JSONArray().apply {
        citations.forEach { citation ->
            put(JSONObject().put("title", citation.title).put("url", citation.url))
        }
    })
    .put("instruction", "Use the research above as tool evidence. Cite or describe only claims supported by it. The search backend is not the final-answer model.")
    .toString()

const val API_FORMAT_OPENAI = "openai"
const val API_FORMAT_ANTHROPIC = "anthropic"

internal fun anthropicWebSearchRequest(model: String, prompt: String): JSONObject = JSONObject()
    .put("model", model)
    .put("max_tokens", 4096)
    .put("tools", JSONArray().put(JSONObject()
        .put("type", "web_search_20250305")
        .put("name", "web_search")
        .put("max_uses", 5)))
    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))

internal fun anthropicSearchPerformed(root: JSONObject): Boolean {
    val content = root.optJSONArray("content") ?: return false
    for (index in 0 until content.length()) {
        val block = content.optJSONObject(index) ?: continue
        when (block.optString("type")) {
            "server_tool_use" -> if (block.optString("name") == "web_search") return true
            "web_search_tool_result" -> return true
        }
    }
    return false
}

internal fun parseAnthropicSearch(root: JSONObject): Pair<String, List<ChatCitation>> {
    val content = root.optJSONArray("content") ?: return "" to emptyList()
    val text = StringBuilder()
    val citations = linkedMapOf<String, ChatCitation>()
    for (index in 0 until content.length()) {
        val block = content.optJSONObject(index) ?: continue
        when (block.optString("type")) {
            "text" -> {
                text.append(block.optString("text"))
                val cites = block.optJSONArray("citations") ?: continue
                for (citeIndex in 0 until cites.length()) {
                    val cite = cites.optJSONObject(citeIndex) ?: continue
                    val url = cite.optString("url").trim()
                    if (url.isBlank()) continue
                    citations.putIfAbsent(url, ChatCitation(cite.optString("title").ifBlank { url }, url))
                }
            }
            "web_search_tool_result" -> {
                val results = block.optJSONArray("content") ?: continue
                for (resultIndex in 0 until results.length()) {
                    val item = results.optJSONObject(resultIndex) ?: continue
                    if (item.optString("type") != "web_search_result") continue
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue
                    citations.putIfAbsent(url, ChatCitation(item.optString("title").ifBlank { url }, url))
                }
            }
        }
    }
    return text.toString().trim() to citations.values.toList()
}

internal fun stepSearchRequest(query: String): JSONObject = JSONObject().put("query", query).put("n", 8)

internal fun parseStepSearch(root: JSONObject): Pair<String, List<ChatCitation>> {
    val results = root.optJSONArray("results") ?: return "" to emptyList()
    val notes = StringBuilder()
    val citations = mutableListOf<ChatCitation>()
    for (index in 0 until results.length()) {
        val item = results.optJSONObject(index) ?: continue
        val url = item.optString("url").trim()
        if (url.isBlank()) continue
        val title = item.optString("title").ifBlank { url }
        val snippet = item.optString("snippet").ifBlank { item.optString("content") }.take(500)
        citations += ChatCitation(title, url)
        notes.append(title).append('\n').append(url)
        if (snippet.isNotBlank()) notes.append('\n').append(snippet)
        notes.append("\n\n")
    }
    return notes.toString().trim() to citations
}

internal fun stepResearchRequest(model: String, query: String, evidence: String): JSONObject = JSONObject()
    .put("model", model)
    .put("max_tokens", 4096)
    .put("system", "你是 Aster 的检索助手。只根据给出的网页检索结果写简明研究笔记，保留日期、名称、数字和不确定之处。不要编造结果里没有的事实。")
    .put("messages", JSONArray().put(JSONObject()
        .put("role", "user")
        .put("content", "检索问题：$query\n\n检索结果：\n$evidence")))
