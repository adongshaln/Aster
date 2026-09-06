package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StoryUsageReportingTest {
    @Test fun chatUsageDistinguishesMissingPartialAndExplicitZero() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val profile=ApiProfile(baseUrl=server.url("/").toString(),apiKey="test",chatApiMode="chat")
            for((usage,reported) in listOf("null" to false,"{}" to false,
                "{\"total_tokens\":20}" to false,"{\"prompt_tokens\":10}" to false,
                "{\"prompt_tokens\":0,\"completion_tokens\":0}" to true,
                "{\"prompt_tokens\":10,\"completion_tokens\":5}" to true)) {
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"故事\"},\"finish_reason\":\"stop\"}]}\n\n"+
                    "data: {\"choices\":[],\"usage\":$usage}\n\ndata: [DONE]\n\n"))
                val response=ApiRepository().streamChat(profile,"gemini-test","",listOf(ChatMessage(role="user",content="继续")),"test") {}
                assertEquals(reported,response.usage.providerUsageReported)
            }
        } finally { server.shutdown() }
    }

    @Test fun responsesReportsInputAndOutputFromJson() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(
                """{"id":"r1","status":"completed","usage":{"input_tokens":21,"output_tokens":8},"output":[{"type":"message","content":[{"type":"output_text","text":"正文"}]}]}"""))
            val response=ApiRepository().streamChat(ApiProfile(baseUrl=server.url("/").toString(),apiKey="test"),
                "gpt-test","",listOf(ChatMessage(role="user",content="继续")),"test") {}
            assertTrue(response.usage.providerUsageReported)
            assertEquals(21,response.usage.inputTokens);assertEquals(8,response.usage.outputTokens)
        } finally { server.shutdown() }
    }

    @Test fun tokenAggregationPreservesReportPresence() {
        val value=TokenUsage()+TokenUsage(inputTokens=12,outputTokens=3,providerUsageReported=true)+TokenUsage()
        assertTrue(value.providerUsageReported);assertEquals(12,value.inputTokens)
        assertFalse((TokenUsage()+TokenUsage()).providerUsageReported)
    }
}
