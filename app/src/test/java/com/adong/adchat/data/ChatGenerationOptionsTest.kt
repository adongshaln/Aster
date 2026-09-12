package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatGenerationOptionsTest {
    @Test
    fun tavernCompatibleSettingsReachChatAndResponsesRequests() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val options = ChatGenerationOptions(
                temperature = 0.72,
                topP = 0.91,
                frequencyPenalty = 0.2,
                presencePenalty = -0.1,
                seed = 42,
                maxOutputTokens = 3456
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""
            ))
            ApiRepository().streamChat(
                ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test"),
                "gemini-test", "", listOf(ChatMessage(role = "user", content = "go")), "chat",
                generationOptions = options
            ) {}
            val chat = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(0.72, chat.getDouble("temperature"), 0.0)
            assertEquals(0.91, chat.getDouble("top_p"), 0.0)
            assertEquals(0.2, chat.getDouble("frequency_penalty"), 0.0)
            assertEquals(-0.1, chat.getDouble("presence_penalty"), 0.0)
            assertEquals(42, chat.getInt("seed"))
            assertEquals(3456, chat.getInt("max_tokens"))

            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"r","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}"""
            ))
            ApiRepository().streamChat(
                ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatApiMode = "responses"),
                "gpt-test", "", listOf(ChatMessage(role = "user", content = "go")), "responses",
                generationOptions = options
            ) {}
            val responses = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(0.72, responses.getDouble("temperature"), 0.0)
            assertEquals(0.91, responses.getDouble("top_p"), 0.0)
            assertEquals(3456, responses.getInt("max_output_tokens"))
            assertFalse(responses.has("frequency_penalty"))
            assertFalse(responses.has("presence_penalty"))
            assertFalse(responses.has("seed"))
        } finally {
            server.shutdown()
        }
    }
}
