package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSkillsTest {
    private fun bundle(sourceUrl: String = "https://github.com/example/slides/tree/main/presentation") = SkillBundle(
        name = "presentation-design",
        sourceUrl = sourceUrl,
        resolvedSkillUrl = "https://raw.githubusercontent.com/example/slides/main/presentation/SKILL.md",
        skillMarkdown = "# Presentation skill",
        sha256 = "bundle-sha",
        fileCount = 2,
        zipBytes = "real-zip-payload".toByteArray()
    )

    @Test
    fun skillsEndpointIsDerivedNextToResponsesEndpoint() {
        assertEquals(
            "https://api.openai.com/v1/skills",
            NativeSkillsApi.skillsUrl(ApiProfile(baseUrl = "https://api.openai.com", responsesPath = "/v1/responses"))
        )
        assertEquals(
            "https://api.openai.com/v1/skills",
            NativeSkillsApi.skillsUrl(ApiProfile(baseUrl = "https://api.openai.com/v1", responsesPath = "/v1/responses"))
        )
        assertEquals(
            "https://gateway.example/v1/skills",
            NativeSkillsApi.skillsUrl(ApiProfile(baseUrl = "https://ignored.example", responsesPath = "https://gateway.example/v1/responses"))
        )
    }

    @Test
    fun nativeUploaderActuallyPostsZipToSkillsEndpointAndUsesReturnedId() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"skill_provider_123","object":"skill","default_version":"4","latest_version":"4","name":"presentation-design","description":"Slides"}"""
            ))
            val profile = ApiProfile(
                baseUrl = server.url("/").toString().trimEnd('/'),
                responsesPath = "/v1/responses",
                apiKey = "real-test-key",
                extraHeaders = "X-Aster-Test: skills"
            )

            val reference = NativeSkillsApi.upload(profile, bundle())
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals("/v1/skills", request.path)
            assertEquals("Bearer real-test-key", request.getHeader("Authorization"))
            assertEquals("skills", request.getHeader("X-Aster-Test"))
            assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data;"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"files\""))
            assertTrue(body.contains("filename=\"presentation-design.zip\""))
            assertTrue(body.contains("real-zip-payload"))
            assertEquals("skill_provider_123", reference.skillId)
            assertEquals("4", reference.version)
            assertEquals("presentation-design", reference.name)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unsupportedSkillsEndpointIsReportedAsUnsupportedInsteadOfPretendingSuccess() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody(
                """{"error":{"message":"unknown endpoint"}}"""
            ))
            val outcome = runCatching {
                NativeSkillsApi.upload(
                    ApiProfile(baseUrl = server.url("/").toString().trimEnd('/'), responsesPath = "/v1/responses"),
                    bundle()
                )
            }
            assertTrue(outcome.exceptionOrNull() is NativeSkillsUnsupportedException)
            assertEquals("/v1/skills", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun nativeShellToolCarriesProviderIssuedSkillReference() {
        val tool = nativeSkillShellTool(NativeSkillReference(
            skillId = "skill_123",
            version = "7",
            name = "presentation-design",
            bundleSha256 = "abc"
        ))

        assertEquals("shell", tool.getString("type"))
        val environment = tool.getJSONObject("environment")
        assertEquals("container_auto", environment.getString("type"))
        assertEquals("disabled", environment.getJSONObject("network_policy").getString("type"))
        val skill = environment.getJSONArray("skills").getJSONObject(0)
        assertEquals("skill_reference", skill.getString("type"))
        assertEquals("skill_123", skill.getString("skill_id"))
        assertEquals("7", skill.getString("version"))
    }

    @Test
    fun responsesRouteUsesActualNativeUploadedSkillReference() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val sourceUrl = "https://github.com/example/slides/tree/main/presentation"
            var bundled = 0
            var uploaded = 0
            val bundleLoader = SkillBundleLoader { url ->
                bundled++
                assertEquals(sourceUrl, url)
                bundle(url)
            }
            val uploader = NativeSkillUploader { _, skillBundle ->
                uploaded++
                assertEquals("bundle-sha", skillBundle.sha256)
                NativeSkillReference("skill_real_123", "3", skillBundle.name, skillBundle.sha256)
            }
            val repository = ApiRepository(
                skillLoader = SkillLoader { error("function fallback must not run on native success") },
                skillBundleLoader = bundleLoader,
                nativeSkillUploader = uploader
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"resp-native","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"已使用原生 Skill"}]}]}"""
            ))

            val result = repository.streamChat(
                profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatApiMode = "responses"),
                model = "gpt-test",
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "请加载并使用这个 skill：$sourceUrl")),
                cacheKey = "native-skill"
            ) {}

            assertEquals(1, bundled)
            assertEquals(1, uploaded)
            assertEquals("已使用原生 Skill", result.text)
            val request = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("shell", request.getJSONObject("tool_choice").getString("type"))
            assertTrue(request.getBoolean("store"))
            val shell = (0 until request.getJSONArray("tools").length())
                .map { request.getJSONArray("tools").getJSONObject(it) }
                .first { it.optString("type") == "shell" }
            val skill = shell.getJSONObject("environment").getJSONArray("skills").getJSONObject(0)
            assertEquals("skill_real_123", skill.getString("skill_id"))
            assertEquals("3", skill.getString("version"))
            assertFalse(request.getJSONArray("tools").toString().contains("\"name\":\"load_skill\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unsupportedNativeSkillsFallsBackToRealFunctionToolRoundTrip() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val sourceUrl = "https://github.com/example/slides/blob/main/SKILL.md"
            var fallbackLoads = 0
            val repository = ApiRepository(
                skillLoader = SkillLoader { url ->
                    fallbackLoads++
                    LoadedSkill(
                        name = "fallback-skill",
                        sourceUrl = url,
                        resolvedUrl = "https://raw.githubusercontent.com/example/slides/main/SKILL.md",
                        sha256 = "fallback-sha",
                        content = "# ACTUAL FALLBACK SKILL\nUse real loaded instructions."
                    )
                },
                skillBundleLoader = SkillBundleLoader { url ->
                    bundle(url)
                },
                nativeSkillUploader = NativeSkillUploader { _, _ ->
                    throw NativeSkillsUnsupportedException("unsupported")
                }
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"resp-1","status":"completed","output":[{"type":"function_call","id":"item-1","call_id":"skill-call","name":"load_skill","arguments":"{\"url\":\"$sourceUrl\"}"}]}"""
            ))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"resp-2","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"fallback complete"}]}]}"""
            ))

            val result = repository.streamChat(
                profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", chatApiMode = "responses"),
                model = "gpt-test",
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "加载 skill：$sourceUrl")),
                cacheKey = "fallback-skill"
            ) {}

            assertEquals(1, fallbackLoads)
            assertEquals("fallback complete", result.text)
            val first = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(LOAD_SKILL_TOOL, first.getJSONObject("tool_choice").getString("name"))
            val second = JSONObject(server.takeRequest().body.readUtf8())
            val output = JSONObject(second.getJSONArray("input").getJSONObject(0).getString("output"))
            assertEquals("# ACTUAL FALLBACK SKILL\nUse real loaded instructions.", output.getString("content"))
            assertEquals("fallback-sha", output.getString("sha256"))
        } finally {
            server.shutdown()
        }
    }
}
