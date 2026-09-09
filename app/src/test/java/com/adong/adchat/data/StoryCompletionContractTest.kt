package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
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

    @Test fun imageAndImportedDocumentContentReachBothRequestProtocols() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val image=ChatImageAttachment(uri="file:///local.jpg",name="参考图",bytes=byteArrayOf(1,2,3))
            val text=DocumentImport.append("根据资料写作","[附件：设定.txt]\n北方终年积雪")
            for (model in listOf("gemini-test","gpt-test")) {
                val response=if(model.startsWith("gpt"))
                    """{"id":"r","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"收到"}]}]}"""
                else """{"choices":[{"message":{"content":"收到"},"finish_reason":"stop"}]}"""
                server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(response))
                ApiRepository().streamChat(ApiProfile(baseUrl=server.url("/").toString(),apiKey="test",chatApiMode="chat"),model,"",listOf(ChatMessage(role="user",content=text,attachments=listOf(image))),"test") {}
                val request=server.takeRequest().body.readUtf8()
                assertTrue(request.contains("北方终年积雪"))
                assertTrue(request.contains("data:image/jpeg;base64,AQID"))
                assertTrue(request.contains(if(model.startsWith("gpt")) "input_image" else "image_url"))
                assertFalse(request.contains("file:///local.jpg"))
            }
        } finally {server.shutdown()}
    }

    @Test fun chatSkillToolActuallyExecutesAndReturnsExactContentToModel() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val skillUrl = "https://github.com/example/presentation/blob/main/SKILL.md"
            var loads = 0
            val repository = ApiRepository(SkillLoader { url ->
                loads++
                assertEquals(skillUrl, url)
                LoadedSkill(
                    name = "presentation-design",
                    sourceUrl = url,
                    resolvedUrl = "https://raw.githubusercontent.com/example/presentation/main/SKILL.md",
                    sha256 = "chat-sha",
                    content = "# REAL CHAT SKILL\nUse cards, hierarchy and visual rhythm."
                )
            })
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"skill-call","type":"function","function":{"name":"load_skill","arguments":"{\"url\":\"$skillUrl\"}"}}]},"finish_reason":"tool_calls"}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"content":"已按真实 Skill 执行"},"finish_reason":"stop"}]}"""))

            val result = repository.streamChat(
                ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatApiMode = "chat"),
                "gemini-test",
                "",
                listOf(ChatMessage(role = "user", content = "请加载这个 skill：$skillUrl 并按它回答")),
                "skill-chat"
            ) {}

            assertEquals(1, loads)
            assertEquals("已按真实 Skill 执行", result.text)
            val first = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(LOAD_SKILL_TOOL, first.getJSONObject("tool_choice").getJSONObject("function").getString("name"))
            val toolNames = first.getJSONArray("tools").toString()
            assertTrue(toolNames.contains(LOAD_SKILL_TOOL))
            val second = JSONObject(server.takeRequest().body.readUtf8())
            val messages = second.getJSONArray("messages")
            val toolMessage = (0 until messages.length())
                .map { messages.getJSONObject(it) }
                .first { it.optString("role") == "tool" }
            val output = JSONObject(toolMessage.getString("content"))
            assertEquals("# REAL CHAT SKILL\nUse cards, hierarchy and visual rhythm.", output.getString("content"))
            assertEquals("chat-sha", output.getString("sha256"))
            assertTrue(result.toolActivities.any { it.name == LOAD_SKILL_TOOL && it.status == TOOL_STATUS_COMPLETED })
        } finally { server.shutdown() }
    }

    @Test fun responsesSkillToolActuallyExecutesAndReturnsExactContentToModel() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val skillUrl = "https://github.com/example/presentation/tree/main/skills/ppt"
            var loads = 0
            val repository = ApiRepository(SkillLoader { url ->
                loads++
                assertEquals(skillUrl, url)
                LoadedSkill(
                    name = "ppt",
                    sourceUrl = url,
                    resolvedUrl = "https://raw.githubusercontent.com/example/presentation/main/skills/ppt/SKILL.md",
                    sha256 = "responses-sha",
                    content = "# REAL RESPONSES SKILL\nPrefer visual storytelling over bullet walls."
                )
            })
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"resp-1","status":"completed","output":[{"type":"function_call","id":"item-1","call_id":"skill-call","name":"load_skill","arguments":"{\"url\":\"$skillUrl\"}"}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"resp-2","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Responses 已按真实 Skill 执行"}]}]}"""))

            val result = repository.streamChat(
                ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatApiMode = "responses"),
                "gpt-test",
                "",
                listOf(ChatMessage(role = "user", content = "使用这个 Skill：$skillUrl")),
                "skill-responses"
            ) {}

            assertEquals(1, loads)
            assertEquals("Responses 已按真实 Skill 执行", result.text)
            val first = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(LOAD_SKILL_TOOL, first.getJSONObject("tool_choice").getString("name"))
            assertTrue(first.getJSONArray("tools").toString().contains(LOAD_SKILL_TOOL))
            assertTrue(first.getBoolean("store"))
            val second = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("resp-1", second.getString("previous_response_id"))
            val output = JSONObject(second.getJSONArray("input").getJSONObject(0).getString("output"))
            assertEquals("# REAL RESPONSES SKILL\nPrefer visual storytelling over bullet walls.", output.getString("content"))
            assertEquals("responses-sha", output.getString("sha256"))
            assertTrue(result.toolActivities.any { it.name == LOAD_SKILL_TOOL && it.status == TOOL_STATUS_COMPLETED })
        } finally { server.shutdown() }
    }
}
