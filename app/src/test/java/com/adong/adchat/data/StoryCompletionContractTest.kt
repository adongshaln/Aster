package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StoryCompletionContractTest {
    @Test fun geminiChatPreservesPartialTextButOnlyStopConfirmsCompletion() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatApiMode = "chat")
            for (reason in listOf("stop", "length", "content_filter", "unknown")) {
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"故事内容\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$reason\"}]}\n\n" + "data: [DONE]\n\n"))
                val result = ApiRepository().streamChat(profile, "gemini-test", "", listOf(ChatMessage(role = "user", content = "续写")), "test") {}
                assertEquals("故事内容", result.text)
                assertEquals(reason == "stop", result.outputComplete)
            }
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun doneWithoutFinishReasonDoesNotConfirmStoryCompletion() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"片段\"}}]}\n\ndata: [DONE]\n\n"))
            val result = ApiRepository().streamChat(ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test"),
                "gemini-test", "", listOf(ChatMessage(role = "user", content = "续写")), "test") {}
            assertEquals("片段", result.text)
            assertFalse(result.outputComplete)
        } finally { server.shutdown() }
    }

    @Test fun nonStreamingJsonRetainsCompletionStatusForBothProtocols() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test")
            for (complete in listOf(true, false)) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"choices":[{"message":{"content":"正文"},"finish_reason":"${if (complete) "stop" else "length"}"}]}"""))
                val chat = ApiRepository().streamChat(profile, "gemini-test", "", listOf(ChatMessage(role = "user", content = "续写")), "test") {}
                assertEquals(complete, chat.outputComplete)
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"id":"r1","status":"${if (complete) "completed" else "incomplete"}","output":[{"type":"message","content":[{"type":"output_text","text":"正文"}]}]}"""))
                val responses = ApiRepository().streamChat(profile, "gpt-test", "", listOf(ChatMessage(role = "user", content = "续写")), "test") {}
                assertEquals(complete, responses.outputComplete)
            }
        } finally { server.shutdown() }
    }
}
