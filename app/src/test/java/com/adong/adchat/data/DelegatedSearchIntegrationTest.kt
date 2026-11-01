package com.adong.adchat.data

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

    @Test
    fun deepSeekThinkingModePreservesReasoningContentAcrossToolCallRound() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"reasoning_content":"用户询问实时消息，我需要先调用 search_web 搜索。"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-ds","function":{"name":"search_web","arguments":"{\"query\":\"DeepSeek V4.1 Flash\"}"}}]},"finish_reason":"tool_calls"}]}

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
                            "query":"DeepSeek V4.1 Flash",
                            "sources":[{"title":"DeepSeek News","url":"https://www.deepseek.com/news"}]
                          }
                        },
                        {
                          "type":"message",
                          "role":"assistant",
                          "content":[{"type":"output_text","text":"DeepSeek V4.1 Flash 已正式发布。"}]
                        }
                      ]
                    }
                """.trimIndent()))
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"content":"DeepSeek V4.1 Flash 已经发布。"},"finish_reason":"stop"}]}

                data: [DONE]

            """.trimIndent()))

            val profile = ApiProfile(
                name = "deepseek",
                baseUrl = server.url("/").toString(),
                apiKey = "test-key",
                chatModel = "deepseek-flash",
                chatPath = "/v1/chat/completions",
                responsesPath = "/v1/responses",
                webSearchEnabled = true
            )
            val result = ApiRepository().streamChat(
                profile = profile,
                model = profile.chatModel,
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "DeepSeek 最新 Flash 模型是什么？")),
                cacheKey = "ds-thinking-search",
                searchBackend = SearchBackendConfig(profile, "grok-4.6", allowXSearch = false, directSearch = false)
            ) {}

            assertEquals("DeepSeek V4.1 Flash 已经发布。", result.text)
            assertTrue(result.reasoning.contains("需要先调用 search_web"))

            server.takeRequest() // 1st chat request
            server.takeRequest() // search request
            val third = server.takeRequest() // 2nd chat request after tool call
            val finalBody = JSONObject(third.body.readUtf8())
            val messages = finalBody.getJSONArray("messages")
            val assistantToolMsg = (0 until messages.length())
                .map { messages.getJSONObject(it) }
                .first { it.optString("role") == "assistant" && it.has("tool_calls") }
            assertEquals(
                "用户询问实时消息，我需要先调用 search_web 搜索。",
                assistantToolMsg.getString("reasoning_content")
            )
        } finally {
            server.shutdown()
        }
    }

    private fun sse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)
}
