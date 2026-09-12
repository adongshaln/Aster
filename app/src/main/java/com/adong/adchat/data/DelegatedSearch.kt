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
