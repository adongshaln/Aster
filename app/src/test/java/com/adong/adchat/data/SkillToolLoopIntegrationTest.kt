package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillToolLoopIntegrationTest {
    private val source = "https://github.com/example/story-editor"
    private val resolved = "https://raw.githubusercontent.com/example/story-editor/main/SKILL.md"

    @Test
    fun chatCompletesLoadReadThenFinalAnswer() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val skill = installedSkill()
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-load","function":{"name":"load_skill","arguments":"{\"url\":\"story-editor\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

            """.trimIndent()))
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-read","function":{"name":"read_skill_file","arguments":"{\"skill\":\"$resolved\",\"path\":\"references/rules.md\",\"offset\":0}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

            """.trimIndent()))
            server.enqueue(sse("""
                data: {"choices":[{"delta":{"content":"已按技能资料完成。"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25}}

                data: [DONE]

            """.trimIndent()))

            val result = repository(skill).streamChat(
                profile = ApiProfile(
                    name = "chat-test",
                    baseUrl = server.url("/").toString(),
                    apiKey = "test",
                    chatModel = "gemini-test",
                    chatPath = "/v1/chat/completions",
                    chatApiMode = "chat"
                ),
                model = "gemini-test",
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "请使用 story-editor 技能处理，来源 $source")),
                cacheKey = "chat-skill"
            ) {}

            assertEquals("已按技能资料完成。", result.text)
            val first = JSONObject(server.takeRequest().body.readUtf8())
            val second = JSONObject(server.takeRequest().body.readUtf8())
            val third = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(LOAD_SKILL_TOOL, first.getJSONObject("tool_choice").getJSONObject("function").getString("name"))
            assertTrue(second.getJSONArray("messages").toString().contains("load_skill"))
            assertTrue(third.getJSONArray("messages").toString().contains("角色不知道秘密"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun responsesCompletesLoadReadThenFinalAnswer() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val skill = installedSkill()
            server.enqueue(sse("""
                data: {"type":"response.created","response":{"id":"resp-1"}}

                data: {"type":"response.output_item.done","item":{"id":"item-load","type":"function_call","call_id":"call-load","name":"load_skill","arguments":"{\"url\":\"$source\"}"}}

                data: {"type":"response.completed","response":{"id":"resp-1","status":"completed","usage":{"input_tokens":10,"output_tokens":1},"output":[{"id":"item-load","type":"function_call","call_id":"call-load","name":"load_skill","arguments":"{\"url\":\"$source\"}"}]}}

            """.trimIndent()))
            server.enqueue(sse("""
                data: {"type":"response.created","response":{"id":"resp-2"}}

                data: {"type":"response.output_item.done","item":{"id":"item-read","type":"function_call","call_id":"call-read","name":"read_skill_file","arguments":"{\"skill\":\"${skill.sha256}\",\"path\":\"references/rules.md\",\"offset\":0}"}}

                data: {"type":"response.completed","response":{"id":"resp-2","status":"completed","usage":{"input_tokens":12,"output_tokens":1},"output":[{"id":"item-read","type":"function_call","call_id":"call-read","name":"read_skill_file","arguments":"{\"skill\":\"${skill.sha256}\",\"path\":\"references/rules.md\",\"offset\":0}"}]}}

            """.trimIndent()))
            server.enqueue(sse("""
                data: {"type":"response.created","response":{"id":"resp-3"}}

                data: {"type":"response.output_text.delta","delta":"已按 Responses 技能资料完成。"}

                data: {"type":"response.completed","response":{"id":"resp-3","status":"completed","usage":{"input_tokens":14,"output_tokens":5},"output":[]}}

            """.trimIndent()))

            val result = repository(skill).streamChat(
                profile = ApiProfile(
                    name = "responses-test",
                    baseUrl = server.url("/").toString(),
                    apiKey = "test",
                    chatModel = "gpt-5.6-test",
                    responsesPath = "/v1/responses",
                    chatApiMode = "responses"
                ),
                model = "gpt-5.6-test",
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "请使用 story-editor 技能处理，来源 $source")),
                cacheKey = "responses-skill"
            ) {}

            assertEquals("已按 Responses 技能资料完成。", result.text)
            val first = JSONObject(server.takeRequest().body.readUtf8())
            val second = JSONObject(server.takeRequest().body.readUtf8())
            val third = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(LOAD_SKILL_TOOL, first.getJSONObject("tool_choice").getString("name"))
            assertEquals("resp-1", second.getString("previous_response_id"))
            assertEquals("resp-2", third.getString("previous_response_id"))
            assertTrue(second.getJSONArray("input").toString().contains("function_call_output"))
            assertTrue(third.getJSONArray("input").toString().contains("角色不知道秘密"))
        } finally {
            server.shutdown()
        }
    }

    private fun repository(skill: LoadedSkill): ApiRepository {
        val library = MemorySkillLibrary()
        library.save(skill)
        return ApiRepository(skillLoader = SkillRuntime(library, SkillLoader { error("unexpected network") }))
    }

    private fun installedSkill(): LoadedSkill = SkillPackages.importZip(
        skillZip(
            "SKILL.md" to "---\nname: story-editor\ndescription: test\n---\nRead references/rules.md.",
            "references/rules.md" to "角色不知道秘密"
        ),
        source
    ).copy(resolvedUrl = resolved)

    private fun sse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)
}
