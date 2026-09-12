package com.adong.adchat.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegatedSearchTest {
    @Test
    fun delegatedDefinitionExposesXOnlyWhenEnabled() {
        val webOnly = delegatedSearchDefinition(false)
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("source").getJSONArray("enum")
        assertEquals(listOf("web"), webOnly.toStringList())

        val withX = delegatedSearchDefinition(true)
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("source").getJSONArray("enum")
        assertEquals(listOf("web", "x"), withX.toStringList())
    }

    @Test
    fun parsesServerSideSourcesAndFinalCitations() {
        val root = JSONObject()
            .put("output", JSONArray()
                .put(JSONObject()
                    .put("type", "web_search_call")
                    .put("action", JSONObject().put("sources", JSONArray()
                        .put(JSONObject().put("title", "Primary").put("url", "https://example.com/primary")))))
                .put(JSONObject()
                    .put("type", "message")
                    .put("content", JSONArray().put(JSONObject()
                        .put("type", "output_text")
                        .put("text", "answer")
                        .put("annotations", JSONArray().put(JSONObject()
                            .put("type", "url_citation")
                            .put("url", "https://example.com/cited")
                            .put("title", "Cited")))))))
        val citations = parseServerSideSearchSources(root)
        assertEquals(setOf("https://example.com/primary", "https://example.com/cited"), citations.map { it.url }.toSet())
    }

    @Test
    fun chatToolsCanMixSkillsFilesAndDelegatedSearch() {
        val tools = buildChatTools(
            fileCreationEnabled = true,
            skillLoadingEnabled = true,
            skillSelectors = listOf("demo-skill"),
            delegatedSearchEnabled = true,
            allowXSearch = true
        )
        val names = (0 until tools.length()).mapNotNull { index ->
            tools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
        }
        assertTrue(CREATE_FILE_TOOL in names)
        assertTrue(LOAD_SKILL_TOOL in names)
        assertTrue(READ_SKILL_FILE_TOOL in names)
        assertTrue(DELEGATED_WEB_SEARCH_TOOL in names)
    }

    @Test
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
}
