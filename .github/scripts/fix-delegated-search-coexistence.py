from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"expected one anchor in {path}, found {text.count(old)}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# A successful HTTP response is not enough: Aster must observe the corresponding
# xAI server-side search call before claiming the delegated request was online.
replace_once(
    "app/src/main/java/com/adong/adchat/data/DelegatedSearch.kt",
    '''internal fun delegatedSearchToolOutput(
    query: String,''',
    '''internal fun responseUsedDelegatedSearch(root: JSONObject, source: String): Boolean {
    val expectedType = if (source.lowercase() == "x") "x_search_call" else "web_search_call"
    val response = root.optJSONObject("response") ?: root
    val output = response.optJSONArray("output") ?: return false
    for (index in 0 until output.length()) {
        if (output.optJSONObject(index)?.optString("type") == expectedType) return true
    }
    return false
}

internal fun delegatedSearchToolOutput(
    query: String,'''
)

replace_once(
    "app/src/main/java/com/adong/adchat/data/ApiRepository.kt",
    '''        val root = runCatching { JSONObject(raw) }.getOrElse { throw IllegalStateException("联网搜索后端返回的不是有效 JSON") }
        val research = parseResponsesText(root)
        val sources = parseServerSideSearchSources(root)''',
    '''        val root = runCatching { JSONObject(raw) }.getOrElse { throw IllegalStateException("联网搜索后端返回的不是有效 JSON") }
        require(responseUsedDelegatedSearch(root, normalizedSource)) {
            "联网搜索后端返回了响应，但未实际执行 ${if (normalizedSource == "x") "X Search" else "Web Search"}"
        }
        val research = parseResponsesText(root)
        val sources = parseServerSideSearchSources(root)'''
)

replace_once(
    "app/src/test/java/com/adong/adchat/data/DelegatedSearchTest.kt",
    '''    private fun JSONArray.toStringList(): List<String> = (0 until length()).map(::getString)
}''',
    '''    @Test
    fun confirmsActualServerSideSearchCallBeforeReportingSuccess() {
        val web = JSONObject().put("output", JSONArray().put(JSONObject().put("type", "web_search_call")))
        val x = JSONObject().put("output", JSONArray().put(JSONObject().put("type", "x_search_call")))
        val textOnly = JSONObject().put("output", JSONArray().put(JSONObject().put("type", "message")))

        assertTrue(responseUsedDelegatedSearch(web, "web"))
        assertFalse(responseUsedDelegatedSearch(web, "x"))
        assertTrue(responseUsedDelegatedSearch(x, "x"))
        assertFalse(responseUsedDelegatedSearch(textOnly, "web"))
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map(::getString)
}'''
)

print("Added strict delegated search execution validation")
