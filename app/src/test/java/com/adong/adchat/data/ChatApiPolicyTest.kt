package com.adong.adchat.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

class ChatApiPolicyTest {
    @Test fun gptModelsIgnoreLegacyChatPreference() {
        for (model in listOf("gpt-5.6-sol", "gpt-6-astra", "GPT-4.1", "openai/gpt-5", "gpt5")) {
            val profile = ApiProfile(chatModel = model, chatApiMode = "chat")
            assertTrue(profile.usesResponses())
            assertEquals("responses", profile.normalized().chatApiMode)
        }
    }

    @Test fun otherModelsKeepTheirSelectedProtocol() {
        assertFalse(ApiProfile(chatModel = "claude-test").usesResponses())
        assertTrue(ApiProfile(chatModel = "claude-test", chatApiMode = "responses").usesResponses())
        assertFalse("not-gpt-model".isGptModel())
    }

    @Test fun actualRequestModelOverridesProfileDefault() {
        assertTrue(ApiProfile(chatModel = "claude-test").usesResponses("openai/gpt-6-astra"))
    }

    @Test fun adaptiveCacheNeverRoutesGptThroughChat() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{}}}\n\n"
            ))
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test",
                chatApiMode = "chat", promptCacheMode = "adaptive", responsesPath = "/custom/responses")
            val result = ApiRepository().streamChat(profile, "gpt-5.6-sol", "",
                listOf(ChatMessage(role = "user", content = "hello")), "test") {}
            assertEquals("OK", result.text)
            assertEquals("/custom/responses", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
