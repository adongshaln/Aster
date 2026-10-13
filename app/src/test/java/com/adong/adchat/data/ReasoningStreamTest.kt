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
