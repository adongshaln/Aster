package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolIntegrationTest {
    @Test
    fun chatFunctionCallCreatesDownloadableFileAndContinuesConversation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-file","function":{"name":"create_file","arguments":"{\"filename\":\"notes.md\",\"mime_type\":\"text/markdown\",\"content\":\"# Notes\\nhello\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

            """.trimIndent()))
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"content":"文件已经创建。"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}

                data: [DONE]

            """.trimIndent()))

            val profile = ApiProfile(
                name = "test",
                baseUrl = server.url("/").toString(),
                apiKey = "test-key",
                chatModel = "test-model",
                chatPath = "/v1/chat/completions",
                fileCreationEnabled = true
            )
            val result = ApiRepository().streamChat(
                profile = profile,
                model = profile.chatModel,
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "创建 md 文件")),
                cacheKey = "test"
            ) {}

            assertEquals("文件已经创建。", result.text)
            assertEquals("notes.md", result.generatedFiles.single().name)
            assertEquals("# Notes\nhello", result.generatedFiles.single().content)
            assertEquals(TOOL_STATUS_COMPLETED, result.toolActivities.single().status)

            server.takeRequest()
            val secondBody = JSONObject(server.takeRequest().body.readUtf8())
            val messages = secondBody.getJSONArray("messages")
            assertTrue((0 until messages.length()).any { messages.getJSONObject(it).optString("role") == "tool" })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun chatSearchUsesWebSearchOptionsWithoutCustomTools() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"content":"搜索结果"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}

                data: [DONE]

            """.trimIndent()))

            val profile = ApiProfile(
                name = "test",
                baseUrl = server.url("/").toString(),
                apiKey = "test-key",
                chatModel = "search-model",
                chatPath = "/v1/chat/completions",
                webSearchEnabled = true,
                fileCreationEnabled = true
            )
            val result = ApiRepository().streamChat(
                profile = profile,
                model = profile.chatModel,
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "搜索")),
                cacheKey = "test"
            ) {}

            assertEquals("搜索结果", result.text)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(body.has("web_search_options"))
            assertFalse(body.has("tools"))
        } finally {
            server.shutdown()
        }
    }

    private fun sse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)
}
