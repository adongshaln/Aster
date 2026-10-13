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

    @Test
    fun anthropicSearchRequestUsesTheMessagesWebSearchTool() {
        val body = anthropicWebSearchRequest("claude-sonnet-4-5", "查找作品")
        assertEquals("claude-sonnet-4-5", body.getString("model"))
        assertEquals("web_search_20250305", body.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test
    fun anthropicSearchResultKeepsNotesAndSources() {
        val root = JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "server_tool_use").put("name", "web_search"))
            .put(JSONObject().put("type", "web_search_tool_result").put("content", JSONArray()
                .put(JSONObject().put("type", "web_search_result").put("title", "作品页").put("url", "https://www.pixiv.net/artworks/1"))))
            .put(JSONObject().put("type", "text").put("text", "找到一张全身图。").put("citations", JSONArray()
                .put(JSONObject().put("url", "https://www.pixiv.net/artworks/1").put("title", "作品页")))))
        assertTrue(anthropicSearchPerformed(root))
        val (research, sources) = parseAnthropicSearch(root)
        assertEquals("找到一张全身图。", research)
        assertEquals(listOf("https://www.pixiv.net/artworks/1"), sources.map { it.url })
        assertFalse(anthropicSearchPerformed(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "没有搜")))))
    }

    @Test
    fun stepSearchDoesNotUseTheAnthropicServerTool() {
        assertTrue("stepfun/step-5-preview".isStepModel())
        assertFalse("claude-sonnet-4-5".isStepModel())
        val search = stepSearchRequest("樋口円香")
        assertEquals("樋口円香", search.getString("query"))
        val parsed = parseStepSearch(JSONObject().put("results", JSONArray().put(JSONObject()
            .put("title", "作品页")
            .put("url", "https://www.pixiv.net/artworks/1")
            .put("snippet", "日常全身"))))
        assertEquals("https://www.pixiv.net/artworks/1", parsed.second.single().url)
        assertTrue(parsed.first.contains("日常全身"))
        val followUp = stepResearchRequest("step-5-preview", "樋口円香", parsed.first)
        assertFalse(followUp.has("tools"))
        assertEquals("step-5-preview", followUp.getString("model"))
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map(::getString)
}
