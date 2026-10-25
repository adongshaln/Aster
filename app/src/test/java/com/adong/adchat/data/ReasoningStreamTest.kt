package com.adong.adchat.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject

/**
 * 推理模型的思考流必须与正文分流：用户要能看见模型在推进，而正文不能被思考内容污染。
 */
class ReasoningStreamTest {
    @Test fun singleActionChatStopsAfterReasoningWithoutSendingAnotherRequest() = runBlocking {
        val server = MockWebServer()
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(sse(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"仍在思考\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5}}\n\n" +
                    "data: [DONE]\n\n"
            ))
            server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"不应续轮\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatModel = "deepseek-r1")
            val result = ApiRepository().streamChat(profile, "deepseek-r1", "",
                listOf(ChatMessage(role = "user", content = "解释")), "test", skillsAllowed = false,
                allowReasoningContinuation = false) {}
            assertEquals("", result.text)
            assertEquals("仍在思考", result.reasoning)
            assertFalse(result.outputComplete)
            assertEquals(3, result.usage.inputTokens)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun singleActionAnthropicStopsAfterReasoningWithoutSendingAnotherRequest() = runBlocking {
        val server = MockWebServer()
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(sse(
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":0}}}\n\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"仍在思考\"}}\n\n" +
                    "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":5}}\n\n" +
                    "data: {\"type\":\"message_stop\"}\n\n"
            ))
            server.enqueue(sse(
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"不应续轮\"}}\n\n" +
                    "data: {\"type\":\"message_stop\"}\n\n"
            ))
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", apiFormat = API_FORMAT_ANTHROPIC)
            val result = ApiRepository().streamChat(profile, "claude-test", "",
                listOf(ChatMessage(role = "user", content = "解释")), "test", skillsAllowed = false,
                allowReasoningContinuation = false) {}
            assertEquals("", result.text)
            assertEquals("仍在思考", result.reasoning)
            assertFalse(result.outputComplete)
            assertEquals(3, result.usage.inputTokens)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun reasoningWithoutTextOrToolsContinuesInsteadOfFailing() {
        assertTrue(shouldContinueAfterReasoningOnly("", 0, "想了两万字"))
        assertFalse(shouldContinueAfterReasoningOnly("回答", 0, "想了两万字"))
        assertFalse(shouldContinueAfterReasoningOnly("", 1, "想了两万字"))
        assertFalse(shouldContinueAfterReasoningOnly("", 0, "   "))
    }

    @Test fun chatCompletionsStreamsReasoningSeparatelyFromContent() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"一半\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            ))
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatModel = "deepseek-r1")
            val reasoning = StringBuilder()
            val result = ApiRepository().streamChat(profile, "deepseek-r1", "",
                listOf(ChatMessage(role = "user", content = "hello")), "test",
                onReasoning = { reasoning.append(it) }) { }
            assertEquals("答案", result.text)
            assertEquals("先想一半", result.reasoning)
            assertEquals("先想一半", reasoning.toString())
        } finally { server.shutdown() }
    }

    @Test fun chatCompletionsAcceptsReasoningObjectField() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse(
                "data: {\"choices\":[{\"delta\":{\"reasoning\":{\"content\":\"思路\"}}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"正文\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            ))
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatModel = "gateway-model")
            val result = ApiRepository().streamChat(profile, "gateway-model", "",
                listOf(ChatMessage(role = "user", content = "hello")), "test") {}
            assertEquals("正文", result.text)
            assertEquals("思路", result.reasoning)
        } finally { server.shutdown() }
    }

    @Test fun responsesApiStreamsReasoningSummaryBeforeAnswer() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse(
                "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"分析中\"}\n\n" +
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"结论\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{}}}\n\n"
            ))
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatModel = "gpt-5.6-sol")
            val reasoning = StringBuilder()
            val result = ApiRepository().streamChat(profile, "gpt-5.6-sol", "",
                listOf(ChatMessage(role = "user", content = "hello")), "test",
                onReasoning = { reasoning.append(it) }) { }
            assertEquals("结论", result.text)
            assertEquals("分析中", result.reasoning)
            assertEquals("分析中", reasoning.toString())
        } finally { server.shutdown() }
    }

    @Test fun responsesApiAcceptsVendorReasoningTextEvent() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse(
                "data: {\"type\":\"response.reasoning_text.delta\",\"text\":\"琢磨\"}\n\n" +
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"好的\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{}}}\n\n"
            ))
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatModel = "grok-4.6")
            val result = ApiRepository().streamChat(profile, "grok-4.6", "",
                listOf(ChatMessage(role = "user", content = "hello")), "test") {}
            assertEquals("好的", result.text)
            assertEquals("琢磨", result.reasoning)
        } finally { server.shutdown() }
    }

    @Test fun reasoningNeverLeaksIntoRequestHistory() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"第一轮\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{}}}\n\n"
            ))
            server.enqueue(sse(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"第二轮\"}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{}}}\n\n"
            ))
            val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatModel = "gpt-5.6-sol")
            var reply = ApiRepository().streamChat(profile, "gpt-5.6-sol", "",
                listOf(ChatMessage(role = "user", content = "hello")), "test") {}
            reply = ApiRepository().streamChat(profile, "gpt-5.6-sol", "",
                listOf(
                    ChatMessage(role = "user", content = "hello"),
                    ChatMessage(role = "assistant", content = reply.text, reasoning = "内部思考不应外传")
                ),
                "test") {}
            val second = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(second.toString().contains("内部思考不应外传"))
            assertEquals("第二轮", reply.text)
        } finally { server.shutdown() }
    }

    private fun sse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)
}
