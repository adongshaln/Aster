package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatIllustrationCompletionTest {
    private val model = "gemini-4-argon-high"
    private val history = listOf(ChatMessage(role = "assistant", content = "The knight lights a lantern."))
    private val plan = JSONObject().put("title", "灯火").put("focus", "骑士点亮灯笼")
        .put("prompt", "A knight lights a lantern in a dark courtyard, close-up, warm light.")

    @Test fun argonHasThinkingHeadroomAndKeepsTheOriginalModelAndSingleRequest() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(response(JSONObject().put("content", plan.toString()).put("reasoning_content", "private planning"), "stop"))
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "fixture", reasoningEffort = "high")
            val result = ApiRepository().planChatIllustration(profile, model, history, "")
            assertEquals(plan.getString("prompt"), result.prompt)
            val request = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(model, request.getString("model"))
            assertTrue(request.getBoolean("stream"))
            assertEquals(32_768, request.getInt("max_tokens"))
            assertEquals("high", request.getString("reasoning_effort"))
            assertFalse(request.has("tools"))
            assertEquals("high", profile.reasoningEffort)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun thinkingOnlyLimitResponseKeepsTheExplicitBudgetAndNeverRetriesAutomatically() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(response(JSONObject().put("content", JSONObject.NULL).put("reasoning_content", "private planning"), "length"))
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "fixture",
                modelContexts = mapOf(model to ModelContextLimits(131_072, 2_048)))
            val error = runCatching { ApiRepository().planChatIllustration(profile, model, history, "") }.exceptionOrNull()!!
            assertTrue(error.message.orEmpty().contains("耗尽输出预算"))
            assertTrue(error.message.orEmpty().contains("2048"))
            assertFalse(error.message.orEmpty().contains("private planning"))
            assertEquals(2_048, JSONObject(server.takeRequest().body.readUtf8()).getInt("max_tokens"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun providerErrorsAndTruncatedJsonDoNotBecomeDrawingPrompts() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "fixture")
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"upstream busy"}}"""))
            val providerError = runCatching { ApiRepository().planChatIllustration(profile, model, history, "") }.exceptionOrNull()!!
            assertEquals("upstream busy", providerError.message)
            server.enqueue(response(JSONObject().put("content", plan.toString()), "length"))
            val truncated = runCatching { ApiRepository().planChatIllustration(profile, model, history, "") }.exceptionOrNull()!!
            assertTrue(truncated.message.orEmpty().contains("耗尽输出预算"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun streamedPlannerCollectsOnlyAnswerTextAndRejectsAnUnfinishedStream() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "current-chat-key")
            val reasoned = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta",
                JSONObject().put("reasoning_content", "private planning"))))
            val answer = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta",
                JSONObject().put("content", plan.toString()))))
            val stop = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "stop")))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $reasoned\n\ndata: $answer\n\ndata: $stop\n\ndata: [DONE]\n\n"))
            val result = ApiRepository().planChatIllustration(profile, model, history, "")
            assertEquals(plan.getString("prompt"), result.prompt)
            val request = server.takeRequest()
            assertEquals("Bearer current-chat-key", request.getHeader("Authorization"))
            assertTrue(JSONObject(request.body.readUtf8()).getBoolean("stream"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $answer\n\n"))
            val error = runCatching { ApiRepository().planChatIllustration(profile, model, history, "") }.exceptionOrNull()!!
            assertTrue(error.message.orEmpty().contains("未完整结束"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun response(message: JSONObject, finish: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", finish))).toString())
}
