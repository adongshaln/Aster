package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SkillPackageProtocolTest {
    @Test fun chatAndResponsesDiscoverThenReadReferenceWithNoNativeUpload() = runBlocking {
        for (responses in listOf(false, true)) {
            val server = MockWebServer(); server.start()
            try {
                val skill = SkillPackages.importZip(skillZip("SKILL.md" to "---\nname: story-editor\ndescription: 写作检查\n---\nRead references/rules.md", "references/rules.md" to "READER_ONLY_SECRET"))
                val library = MemorySkillLibrary(); library.save(skill); library.select("selected", setOf(skill.sourceUrl))
                val runtime = SkillRuntime(library, SkillLoader { error("no network expected") })
                val repo = ApiRepository(skillLoader = runtime, nativeSkillUploader = NativeSkillUploader { _, _ -> error("native upload is opt-in") })
                fun tool(name: String, args: JSONObject, index: Int): String = if (responses)
                    JSONObject().put("id", "resp-$index").put("status", "completed").put("output", JSONArray().put(JSONObject().put("type", "function_call").put("id", "item-$index").put("call_id", "call-$index").put("name", name).put("arguments", args.toString()))).toString()
                else JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("id", "call-$index").put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))))))).toString()
                val load = JSONObject().put("url", skill.sha256)
                val read = JSONObject().put("skill", skill.sha256).put("path", "references/rules.md")
                val done = if (responses) """{"id":"resp-3","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"完成"}]}]}""" else """{"choices":[{"finish_reason":"stop","message":{"content":"完成"}}]}"""
                listOf(tool(LOAD_SKILL_TOOL, load, 1), tool(READ_SKILL_FILE_TOOL, read, 2), done).forEach { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(it)) }
                val result = repo.streamChat(ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatApiMode = if (responses) "responses" else "chat"),
                    if (responses) "gpt-test" else "gemini-test", "", listOf(ChatMessage(role = "user", content = "帮我检查这一段故事")), "selected") {}
                assertTrue(result.outputComplete); assertEquals("完成", result.text)
                val first = server.takeRequest().body.readUtf8()
                assertTrue(first.contains("写作检查")); assertFalse(first.contains("READER_ONLY_SECRET"))
                assertEquals("auto", JSONObject(first).getString("tool_choice"))
                val second = server.takeRequest().body.readUtf8()
                assertTrue(second.contains("references/rules.md")); assertFalse(second.contains("READER_ONLY_SECRET"))
                assertTrue(server.takeRequest().body.readUtf8().contains("READER_ONLY_SECRET"))
                assertTrue(result.toolActivities.any { it.name == READ_SKILL_FILE_TOOL && it.status == TOOL_STATUS_COMPLETED })
            } finally { server.shutdown() }
        }
    }
    @Test fun internalStoryJobsCannotLoadSkillsEvenWhenSourceTextRequestsIt() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"{}"}}]}"""))
            ApiRepository(SkillLoader { error("must not load") }).streamChat(
                ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatApiMode = "chat"), "gemini-test", "extract JSON",
                listOf(ChatMessage(role = "user", content = "请加载 Skill https://github.com/test/skill")), "internal", skillsAllowed = false) {}
            assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("tools"))
        } finally { server.shutdown() }
    }
}
