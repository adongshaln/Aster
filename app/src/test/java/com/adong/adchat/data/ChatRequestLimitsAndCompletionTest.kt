package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestLimitsAndCompletionTest {
    private val preset = PromptInjectionPreset(
        id = "response-status-test", name = "Style", description = "Neutral test fixture",
        pureMode = true, zeroWidthObfuscation = false,
        entries = listOf(PromptInjectionEntry(
            id = "style", name = "Style", position = "before_history", content = "Use concise wording."
        ))
    )

    private fun profile(server: MockWebServer, outputTokens: Int? = null) = ApiProfile(
        baseUrl = server.url("/").toString(), apiKey = "test", autoResumeStream = false,
        modelContexts = outputTokens?.let { mapOf("test-model" to ModelContextLimits(8192, it)) }.orEmpty()
    )

    private fun plainChoice(reason: String?) = JSONObject()
        .put("message", JSONObject().put("content", "The answer."))
        .put("finish_reason", reason ?: JSONObject.NULL)

    private fun jsonResponse(choice: JSONObject) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("choices", JSONArray().put(choice)).toString())

    private fun sseResponse(reason: String?) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(listOf("The ", "answer.").joinToString("") { chunk ->
            val choice = JSONObject().put("delta", JSONObject().put("content", chunk))
            "data: ${JSONObject().put("choices", JSONArray().put(choice))}\n\n"
        } + "data: ${JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("delta", JSONObject()).put("finish_reason", reason ?: JSONObject.NULL)))}\n\n" +
            "data: [DONE]\n\n")

    private suspend fun request(
        server: MockWebServer,
        outputTokens: Int? = null,
        options: ChatGenerationOptions = ChatGenerationOptions(),
        onDelta: suspend (String) -> Unit = {}
    ) = ApiRepository().streamChat(
        profile(server, outputTokens), "test-model", "",
        listOf(ChatMessage(role = "user", content = "Hello")), "status-test",
        skillsAllowed = false, generationOptions = options, injectionPreset = preset,
        onDelta = onDelta
    )

    @Test fun defaultRequestSettingsAndPlainResponseRemainUnchanged() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(jsonResponse(plainChoice("stop")))
            val result = request(server)
            val body = JSONObject(server.takeRequest().body.readUtf8())

            assertEquals("The answer.", result.text)
            assertTrue(result.outputComplete)
            assertEquals(20000, body.getInt("max_tokens"))
            assertEquals(1.35, body.getDouble("temperature"), 0.0)
            assertEquals(1.0, body.getDouble("top_p"), 0.0)
            assertTrue(body.getBoolean("stream"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun explicitModelOutputLimitIsRespected() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(jsonResponse(plainChoice("stop")))
            request(server, outputTokens = 512)

            assertEquals(512, JSONObject(server.takeRequest().body.readUtf8()).getInt("max_tokens"))
        } finally { server.shutdown() }
    }

    @Test fun requestOutputLimitCanFurtherRestrictModelLimit() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(jsonResponse(plainChoice("stop")))
            request(server, outputTokens = 512, options = ChatGenerationOptions(maxOutputTokens = 256))

            assertEquals(256, JSONObject(server.takeRequest().body.readUtf8()).getInt("max_tokens"))
        } finally { server.shutdown() }
    }

    @Test fun outputLimitDoesNotIncreaseTheExistingDefaultCeiling() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(jsonResponse(plainChoice("stop")))
            request(server, options = ChatGenerationOptions(maxOutputTokens = 30000))

            assertEquals(20000, JSONObject(server.takeRequest().body.readUtf8()).getInt("max_tokens"))
        } finally { server.shutdown() }
    }

    @Test fun normalStreamKeepsTextAndDeltaDelivery() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(sseResponse("stop"))
            val deltas = StringBuilder()
            val result = request(server, onDelta = { deltas.append(it) })

            assertEquals("The answer.", result.text)
            assertEquals(result.text, deltas.toString())
            assertTrue(result.outputComplete)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun truncationAndFilteringPreserveTextWithoutClaimingCompletion() = runBlocking {
        for (reason in listOf("length", "content_filter")) {
            for (streaming in listOf(false, true)) {
                val server = MockWebServer().apply { start() }
                try {
                    server.enqueue(if (streaming) sseResponse(reason) else jsonResponse(plainChoice(reason)))
                    val result = request(server)

                    assertEquals("The answer.", result.text)
                    assertFalse("reason=$reason, streaming=$streaming", result.outputComplete)
                    assertEquals(1, server.requestCount)
                } finally { server.shutdown() }
            }
        }
    }

    @Test fun transportDoneAloneDoesNotClaimContentCompletion() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(sseResponse(null))
            val result = request(server)

            assertEquals("The answer.", result.text)
            assertFalse(result.outputComplete)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun completedToolResponseKeepsItsTextAndCompletionState() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val arguments = JSONObject().put(PromptInjectionDefaults.NY_TUNNEL_PARAM_NAME, "The answer.")
            val call = JSONObject().put("id", "call-1").put("type", "function")
                .put("function", JSONObject().put("name", PromptInjectionDefaults.NY_TUNNEL_TOOL_NAME)
                    .put("arguments", arguments.toString()))
            val choice = JSONObject().put("message", JSONObject().put("tool_calls", JSONArray().put(call)))
                .put("finish_reason", "tool_calls")
            server.enqueue(jsonResponse(choice))
            val result = request(server)

            assertEquals("The answer.", result.text)
            assertTrue(result.outputComplete)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
