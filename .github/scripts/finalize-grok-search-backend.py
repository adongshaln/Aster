from pathlib import Path

ROOT = Path('.')

def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'anchor not found: {old!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

path = 'app/src/main/java/com/adong/adchat/data/ApiRepository.kt'
replace_once(path,
'''        if (profile.webSearchEnabled) recordActivity(ChatToolActivity("web_search", WEB_SEARCH_TOOL, "正在搜索网页", TOOL_STATUS_RUNNING))''',
'''        if (profile.webSearchEnabled && !delegatedSearchEnabled) recordActivity(ChatToolActivity("web_search", WEB_SEARCH_TOOL, "正在搜索网页", TOOL_STATUS_RUNNING))''')
replace_once(path,
'''        if (profile.webSearchEnabled) recordActivity(ChatToolActivity("web_search", WEB_SEARCH_TOOL, "已完成网页搜索", TOOL_STATUS_COMPLETED))''',
'''        if (profile.webSearchEnabled && !delegatedSearchEnabled) recordActivity(ChatToolActivity("web_search", WEB_SEARCH_TOOL, "已完成网页搜索", TOOL_STATUS_COMPLETED))''')

integration = r'''package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegatedSearchIntegrationTest {
    @Test
    fun chatDelegatesSearchToResponsesBackendAndReturnsEvidenceToOriginalModel() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-search","function":{"name":"search_web","arguments":"{\"query\":\"latest Aster test fact\",\"source\":\"web\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

            """.trimIndent()))
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "id":"resp-search",
                      "status":"completed",
                      "output":[
                        {
                          "type":"web_search_call",
                          "id":"ws-1",
                          "action":{
                            "type":"search",
                            "query":"latest Aster test fact",
                            "sources":[{"title":"Primary source","url":"https://example.com/primary"}]
                          }
                        },
                        {
                          "type":"message",
                          "role":"assistant",
                          "content":[{
                            "type":"output_text",
                            "text":"Research says the current fact is 42.",
                            "annotations":[{
                              "type":"url_citation",
                              "title":"Primary source",
                              "url":"https://example.com/primary"
                            }]
                          }]
                        }
                      ],
                      "usage":{"input_tokens":10,"output_tokens":8,"total_tokens":18}
                    }
                """.trimIndent()))
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"content":"根据联网资料，答案是 42。"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":7,"total_tokens":27}}

                data: [DONE]

            """.trimIndent()))

            val profile = ApiProfile(
                name = "gateway",
                baseUrl = server.url("/").toString(),
                apiKey = "test-key",
                chatModel = "gemini-test",
                chatApiMode = "chat",
                chatPath = "/v1/chat/completions",
                responsesPath = "/v1/responses",
                webSearchEnabled = true,
                fileCreationEnabled = true
            )
            val backendProfile = profile.copy(searchModel = "grok-4.6")
            val result = ApiRepository().streamChat(
                profile = profile,
                model = profile.chatModel,
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "现在的事实是什么？")),
                cacheKey = "delegated-search-test",
                searchBackend = SearchBackendConfig(backendProfile, "grok-4.6", allowXSearch = true)
            ) {}

            assertEquals("根据联网资料，答案是 42。", result.text)
            assertTrue(result.citations.any { it.url == "https://example.com/primary" })
            assertTrue(result.toolActivities.any {
                it.name == DELEGATED_WEB_SEARCH_TOOL && it.status == TOOL_STATUS_COMPLETED
            })
            assertFalse(result.toolActivities.any { it.id == "web_search" })

            val first = server.takeRequest()
            assertEquals("/v1/chat/completions", first.path)
            val firstBody = JSONObject(first.body.readUtf8())
            assertFalse(firstBody.has("web_search_options"))
            val firstTools = firstBody.getJSONArray("tools")
            assertTrue((0 until firstTools.length()).any {
                firstTools.getJSONObject(it).getJSONObject("function").optString("name") == DELEGATED_WEB_SEARCH_TOOL
            })
            assertTrue((0 until firstTools.length()).any {
                firstTools.getJSONObject(it).getJSONObject("function").optString("name") == CREATE_FILE_TOOL
            })

            val second = server.takeRequest()
            assertEquals("/v1/responses", second.path)
            val searchBody = JSONObject(second.body.readUtf8())
            assertEquals("grok-4.6", searchBody.getString("model"))
            assertEquals("web_search", searchBody.getJSONArray("tools").getJSONObject(0).getString("type"))
            assertEquals("web_search_call.action.sources", searchBody.getJSONArray("include").getString(0))
            assertFalse(searchBody.has("stream"))

            val third = server.takeRequest()
            assertEquals("/v1/chat/completions", third.path)
            val finalBody = JSONObject(third.body.readUtf8())
            val messages = finalBody.getJSONArray("messages")
            val toolMessage = (0 until messages.length())
                .map { messages.getJSONObject(it) }
                .first { it.optString("role") == "tool" }
            val toolOutput = JSONObject(toolMessage.getString("content"))
            assertTrue(toolOutput.getBoolean("ok"))
            assertEquals("grok-4.6", toolOutput.getString("backend_model"))
            assertTrue(toolOutput.getString("research").contains("42"))
            assertEquals("https://example.com/primary", toolOutput.getJSONArray("sources").getJSONObject(0).getString("url"))
        } finally {
            server.shutdown()
        }
    }

    private fun sse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)
}
'''
(ROOT / 'app/src/test/java/com/adong/adchat/data/DelegatedSearchIntegrationTest.kt').write_text(integration, encoding='utf-8')

print('Finalized delegated search activity behavior and integration test')
