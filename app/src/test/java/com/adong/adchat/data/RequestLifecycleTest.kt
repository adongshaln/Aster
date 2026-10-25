package com.adong.adchat.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class RequestLifecycleTest {
    @Test fun cancellationDoesNotNeedTheBlockedCallDispatcher() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val job = launch(dispatcher) { call.withCancellableResponse { it.body?.string() } }
            try {
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(call.isCanceled())
            } finally {
                call.cancel()
                job.cancelAndJoin()
                dispatcher.close()
            }
        }
    }

    @Test fun modelListCanBeCanceledWhileWaitingForHeaders() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { ApiRepository().fetchModels(profile(server)) }
            try {
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(1, server.requestCount)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test fun cancelClosesAStalledStreamAfterFirstDelta() = runBlocking {
        withServer { server ->
            val body = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
            server.enqueue(sse(body).setHeader("Content-Length", body.toByteArray().size + 1000))
            val delta = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                chat(server, onDelta = { delta.complete(Unit) })
            }
            try {
                withTimeout(3_000) { delta.await() }
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(1, server.requestCount)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test fun interruptedReasoningDoesNotResendThePaidRequest() = runBlocking {
        withServer { server ->
            server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n"))
            server.enqueue(chatSuccess()) // Would be consumed by the old pre-text retry.
            val reasoning = StringBuilder()
            val failure = runCatching { chat(server, onReasoning = { reasoning.append(it) }) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals("thinking", reasoning.toString())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun explicitProviderErrorIsReportedWithoutAutomaticResume() = runBlocking {
        withServer { server ->
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"content":"partial"}}]}

                data: {"error":{"message":"upstream timeout; quota exhausted"}}

            """.trimIndent()))
            server.enqueue(chatSuccess())
            val text = StringBuilder()
            val failure = runCatching { chat(server, onDelta = { text.append(it) }) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("quota exhausted"))
            assertEquals("partial", text.toString())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun usageFrameDoesNotTurnTruncatedTextIntoSuccess() = runBlocking {
        withServer { server ->
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"content":"partial"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}

            """.trimIndent()))
            val failure = runCatching { chat(server, profile(server).copy(autoResumeStream = false)) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun anthropicMessageStopCompletesWithoutWaitingForSocketEof() = runBlocking {
        withServer { server ->
            val body = """
                data: {"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":100,"cache_creation_input_tokens":20,"output_tokens":1}}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"answer"}}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}

                data: {"type":"message_stop"}

            """.trimIndent() + "\n\n"
            server.enqueue(sse(body).setHeader("Content-Length", body.toByteArray().size + 1000))
            val result = withTimeout(3_000) {
                chat(server, profile(server).copy(apiFormat = API_FORMAT_ANTHROPIC))
            }
            assertEquals("answer", result.text)
            assertEquals(130, result.usage.inputTokens)
            assertEquals(100, result.usage.cachedTokens)
            assertEquals(7, result.usage.outputTokens)
            assertEquals(137, result.usage.totalTokens)
            assertTrue(result.outputComplete)
        }
    }

    @Test fun unrelatedAnthropicHttpErrorDoesNotRetryWithoutCache() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":{\"message\":\"unknown model\"}}"))
            server.enqueue(anthropicSuccess())
            val failure = runCatching {
                chat(server, profile(server).copy(apiFormat = API_FORMAT_ANTHROPIC))
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("HTTP 404"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun explicitUnsupportedCacheControlRetriesOnceWithoutCache() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"unsupported cache_control\"}}"))
            server.enqueue(anthropicSuccess())
            val result = chat(server, profile(server).copy(apiFormat = API_FORMAT_ANTHROPIC))
            assertEquals("answer", result.text)
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest().body.readUtf8().contains("cache_control"))
            assertFalse(server.takeRequest().body.readUtf8().contains("cache_control"))
        }
    }

    @Test fun errorsIncludeRequestIdForProviderSupport() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(429)
                .setHeader("x-request-id", "req-example")
                .setBody("{\"error\":{\"message\":\"rate limited\"}}"))
            val failure = runCatching { chat(server) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("HTTP 429"))
            assertTrue(failure?.message.orEmpty().contains("req-example"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun successfulHttpStatusWithAnErrorBodyIsStillAnError() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"upstream unavailable\"}}"))
            val failure = runCatching { chat(server) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("upstream unavailable"))
        }
    }

    @Test fun corruptTextEventsCannotBecomeSuccessfulPartialAnswersInAnyChatProtocol() = runBlocking {
        withServer { server ->
            val protocols = listOf(
                Triple(profile(server),
                    "{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}",
                    "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"),
                Triple(profile(server).copy(chatModel = "gpt-test"),
                    "{\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}",
                    "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}"),
                Triple(profile(server).copy(apiFormat = API_FORMAT_ANTHROPIC),
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}",
                    "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}")
            )
            protocols.forEachIndexed { index, (profile, text, terminal) ->
                server.enqueue(sse("data: $text\n\ndata: {not-json\n\ndata: $terminal\n\ndata: [DONE]\n\n"))
                val received = StringBuilder()
                val failure = runCatching { chat(server, profile, onDelta = { received.append(it) }) }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertTrue(failure?.message.orEmpty().contains("JSON"))
                assertEquals("partial", received.toString())
                assertEquals(index + 1, server.requestCount)
            }
        }
    }

    private fun profile(server: MockWebServer) = ApiProfile(
        baseUrl = server.url("/").toString(), apiKey = "test", chatModel = "test-model"
    )

    private suspend fun chat(
        server: MockWebServer,
        profile: ApiProfile = profile(server),
        onReasoning: suspend (String) -> Unit = {},
        onDelta: suspend (String) -> Unit = {}
    ): ChatCompletionResult = ApiRepository().streamChat(
        profile, profile.chatModel, "system",
        listOf(ChatMessage(role = "user", content = "hello")), "test",
        skillsAllowed = false, onReasoning = onReasoning, onDelta = onDelta
    )

    private fun chatSuccess() = sse("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")

    private fun anthropicSuccess() = sse("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}\n\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\ndata: {\"type\":\"message_stop\"}\n\n")

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try { block(server) } finally { server.shutdown() }
    }
}
