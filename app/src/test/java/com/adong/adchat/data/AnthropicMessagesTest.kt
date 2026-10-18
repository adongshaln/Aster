package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicMessagesTest {

    // ---- 纯映射 ----

    @Test fun routingFollowsApiFormatUnlessModelForcesResponses() {
        val anthropic = ApiProfile(apiFormat = API_FORMAT_ANTHROPIC)
        assertTrue(anthropic.usesAnthropicMessages("claude-sonnet-4.5"))
        assertTrue(anthropic.usesAnthropicMessages("step-3"))
        assertFalse(anthropic.usesAnthropicMessages("gpt-5.4"))
        assertFalse(anthropic.usesAnthropicMessages("grok-4"))
        assertFalse(ApiProfile(apiFormat = API_FORMAT_OPENAI).usesAnthropicMessages("claude-sonnet-4.5"))
    }

    @Test fun ensureMaxTokensKeepsExistingThenConfiguredThenDefault() {
        val body = JSONObject().put("max_tokens", 1234)
        AnthropicMessages.ensureMaxTokens(body, 4096)
        assertEquals(1234, body.getInt("max_tokens"))

        val fromConfig = JSONObject()
        AnthropicMessages.ensureMaxTokens(fromConfig, 4096)
        assertEquals(4096, fromConfig.getInt("max_tokens"))

        val fallback = JSONObject()
        AnthropicMessages.ensureMaxTokens(fallback, null)
        assertEquals(AnthropicMessages.DEFAULT_MAX_TOKENS, fallback.getInt("max_tokens"))
    }

    @Test fun imageMessageBecomesBase64SourceBlock() {
        val message = ChatMessage(
            role = "user",
            content = "看图",
            attachments = listOf(ChatImageAttachment(uri = "file://a.png", name = "a.png", mimeType = "image/png", bytes = byteArrayOf(1, 2, 3)))
        )
        val content = AnthropicMessages.messageContent(message) as JSONArray
        assertEquals("text", content.getJSONObject(0).getString("type"))
        val image = content.getJSONObject(1)
        assertEquals("image", image.getString("type"))
        assertEquals("base64", image.getJSONObject("source").getString("type"))
        assertEquals("image/png", image.getJSONObject("source").getString("media_type"))
    }

    @Test fun openAiToolDefinitionMapsToInputSchema() {
        val openAi = JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "create_file")
                .put("description", "创建文件")
                .put("parameters", JSONObject().put("type", "object")))
        val mapped = AnthropicMessages.toolDefinition(openAi)
        assertEquals("create_file", mapped.getString("name"))
        assertEquals("object", mapped.getJSONObject("input_schema").getString("type"))
        assertFalse(mapped.has("parameters"))
        assertFalse(mapped.has("type"))
    }

    @Test fun toolResultBlocksRideAUserMessage() {
        val message = AnthropicMessages.toolResultMessage(listOf("toolu_1" to "结果一", "toolu_2" to "结果二"))
        assertEquals("user", message.getString("role"))
        val content = message.getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("tool_result", content.getJSONObject(0).getString("type"))
        assertEquals("toolu_2", content.getJSONObject(1).getString("tool_use_id"))
    }

    @Test fun assistantToolUseMessageKeepsTextAndInputs() {
        val message = AnthropicMessages.assistantToolUseMessage(
            "先看下文件",
            listOf(PendingToolCall("toolu_9", "toolu_9", "create_file", "{\"filename\":\"a.md\"}"))
        )
        assertEquals("assistant", message.getString("role"))
        val content = message.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        val use = content.getJSONObject(1)
        assertEquals("tool_use", use.getString("type"))
        assertEquals("a.md", use.getJSONObject("input").getString("filename"))
    }

    // ---- SSE 解析 ----

    @Test fun sseStreamParsesTextThinkingAndToolUse() {
        val blocks = mutableMapOf<Int, AnthropicMessages.BlockState>()
        val events = listOf(
            """{"type":"message_start","message":{"usage":{"input_tokens":120,"cache_read_input_tokens":64,"cache_creation_input_tokens":32,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先想"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"你好"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"create_file","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"filename\":"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"a.md\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":40}}""",
            """{"type":"message_stop"}"""
        )
        val parsed = events.map { AnthropicMessages.parseEvent(it, blocks) }

        val start = parsed.filterIsInstance<AnthropicMessages.Event.Start>().single()
        assertEquals(120, start.usage.inputTokens)
        assertEquals(64, start.usage.cachedTokens)
        assertEquals(32, start.usage.cacheWriteTokens)

        assertEquals("先想", parsed.filterIsInstance<AnthropicMessages.Event.Thinking>().single().delta)
        assertEquals("你好", parsed.filterIsInstance<AnthropicMessages.Event.Text>().single().delta)

        val tool = parsed.filterIsInstance<AnthropicMessages.Event.ToolCall>().single().call
        assertEquals("toolu_1", tool.callId)
        assertEquals("create_file", tool.name)
        assertEquals("a.md", JSONObject(tool.arguments).getString("filename"))

        val done = parsed.filterIsInstance<AnthropicMessages.Event.MessageDone>().single()
        assertEquals("tool_use", done.stopReason)
        assertEquals(40, done.usage.outputTokens)
        assertTrue(blocks.isEmpty())
    }

    @Test fun pingAndUnknownEventsAreIgnored() {
        val blocks = mutableMapOf<Int, AnthropicMessages.BlockState>()
        assertTrue(AnthropicMessages.parseEvent("""{"type":"ping"}""", blocks) is AnthropicMessages.Event.Ignored)
        assertTrue(AnthropicMessages.parseEvent("""{"type":"content_block_delta","index":0,"delta":{"type":"citations_delta","citation":{}}}""", blocks) is AnthropicMessages.Event.Ignored)
        assertTrue(AnthropicMessages.parseEvent("not json", blocks) is AnthropicMessages.Event.Ignored)
    }

    @Test fun errorEventBecomesFailure() {
        val event = AnthropicMessages.parseEvent(
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            mutableMapOf()
        )
        assertEquals("Overloaded", (event as AnthropicMessages.Event.Failure).message)
    }

    // ---- 缓存 ----

    @Test fun claudeSystemBreakpointWrapsPlainString() {
        val marked = PromptCache.markClaudeSystem("你是 Aster") as JSONArray
        val block = marked.getJSONObject(0)
        assertEquals("text", block.getString("type"))
        assertEquals("你是 Aster", block.getString("text"))
        assertEquals("1h", block.getJSONObject("cache_control").getString("ttl"))
    }

    // ---- 端到端（MockWebServer） ----

    @Test
    fun anthropicChatStreamsTextAndReportsCacheUsage() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse("""
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":2100,"cache_read_input_tokens":1024,"cache_creation_input_tokens":1024,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好，"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"世界"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}

                event: message_stop
                data: {"type":"message_stop"}

            """.trimIndent()))

            val profile = ApiProfile(
                name = "claude",
                baseUrl = server.url("/").toString(),
                apiKey = "sk-ant-test",
                chatModel = "claude-sonnet-4-5",
                apiFormat = API_FORMAT_ANTHROPIC
            )
            val deltas = StringBuilder()
            val result = ApiRepository().streamChat(
                profile = profile,
                model = profile.chatModel,
                systemPrompt = "你是 Aster",
                history = listOf(ChatMessage(role = "user", content = "你好")),
                cacheKey = "conv-1",
                onDelta = { deltas.append(it) }
            )

            assertEquals("你好，世界", result.text)
            assertEquals("你好，世界", deltas.toString())
            assertEquals(2100, result.usage.inputTokens)
            assertEquals(1024, result.usage.cachedTokens)
            assertEquals(1024, result.usage.cacheWriteTokens)
            assertEquals(12, result.usage.outputTokens)
            assertEquals("claude-breakpoints", result.usage.cacheStrategy)
            assertTrue(result.outputComplete)

            val request = server.takeRequest()
            assertEquals("/v1/messages", request.path)
            assertEquals("sk-ant-test", request.getHeader("x-api-key"))
            assertEquals("2023-06-01", request.getHeader("anthropic-version"))
            assertTrue(request.getHeader("Authorization") == null)

            val body = JSONObject(request.body.readUtf8())
            // system 提升为顶层字段并打了缓存断点
            val system = body.getJSONArray("system")
            assertEquals("你是 Aster", system.getJSONObject(0).getString("text"))
            assertEquals("ephemeral", system.getJSONObject(0).getJSONObject("cache_control").getString("type"))
            // 用户消息也打了断点；不携带 OpenAI 采样/流式字段
            val messages = body.getJSONArray("messages")
            assertEquals("user", messages.getJSONObject(0).getString("role"))
            assertFalse(body.has("stream_options"))
            assertFalse(body.has("frequency_penalty"))
            assertTrue(body.getInt("max_tokens") > 0)
            assertTrue(body.getBoolean("stream"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anthropicToolRoundWritesToolResultAsUserMessage() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse("""
                data: {"type":"message_start","message":{"usage":{"input_tokens":50,"output_tokens":1}}}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"create_file","input":{}}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"filename\":\"notes.md\",\"mime_type\":\"text/markdown\",\"content\":\"# Notes\"}"}}

                data: {"type":"content_block_stop","index":0}

                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":30}}

            """.trimIndent()))
            server.enqueue(sse("""
                data: {"type":"message_start","message":{"usage":{"input_tokens":80,"output_tokens":1}}}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"文件已经创建。"}}

                data: {"type":"content_block_stop","index":0}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}

            """.trimIndent()))

            val profile = ApiProfile(
                name = "claude",
                baseUrl = server.url("/").toString(),
                apiKey = "sk-ant-test",
                chatModel = "claude-sonnet-4-5",
                apiFormat = API_FORMAT_ANTHROPIC,
                fileCreationEnabled = true
            )
            val result = ApiRepository().streamChat(
                profile = profile,
                model = profile.chatModel,
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "创建 md 文件")),
                cacheKey = "conv-2"
            ) {}

            assertEquals("文件已经创建。", result.text)
            assertEquals("notes.md", result.generatedFiles.single().name)
            assertEquals("# Notes", result.generatedFiles.single().content)
            assertEquals(TOOL_STATUS_COMPLETED, result.toolActivities.single().status)

            val firstBody = JSONObject(server.takeRequest().body.readUtf8())
            // 工具定义是 Anthropic 形状：name + input_schema，无 type:function 包装
            val tool = firstBody.getJSONArray("tools").getJSONObject(0)
            assertEquals("create_file", tool.getString("name"))
            assertTrue(tool.has("input_schema"))
            assertFalse(tool.has("function"))
            assertEquals("auto", firstBody.getJSONObject("tool_choice").getString("type"))

            val secondBody = JSONObject(server.takeRequest().body.readUtf8())
            val messages = secondBody.getJSONArray("messages")
            // 倒数第二条：assistant 带 tool_use 块；最后一条：user 带 tool_result 块
            val assistant = messages.getJSONObject(messages.length() - 2)
            assertEquals("assistant", assistant.getString("role"))
            val useBlock = (0 until assistant.getJSONArray("content").length())
                .map { assistant.getJSONArray("content").getJSONObject(it) }
                .single { it.getString("type") == "tool_use" }
            assertEquals("toolu_1", useBlock.getString("id"))

            val toolResult = messages.getJSONObject(messages.length() - 1)
            assertEquals("user", toolResult.getString("role"))
            val resultBlock = toolResult.getJSONArray("content").getJSONObject(0)
            assertEquals("tool_result", resultBlock.getString("type"))
            assertEquals("toolu_1", resultBlock.getString("tool_use_id"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun thinkingDeltaFeedsReasoningNotText() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(sse("""
                data: {"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1}}}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"思考一下"}}

                data: {"type":"content_block_stop","index":0}

                data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"答案"}}

                data: {"type":"content_block_stop","index":1}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

            """.trimIndent()))

            val profile = ApiProfile(
                name = "claude",
                baseUrl = server.url("/").toString(),
                apiKey = "sk-ant-test",
                chatModel = "claude-sonnet-4-5",
                apiFormat = API_FORMAT_ANTHROPIC
            )
            val result = ApiRepository().streamChat(
                profile = profile,
                model = profile.chatModel,
                systemPrompt = "",
                history = listOf(ChatMessage(role = "user", content = "q")),
                cacheKey = "conv-3"
            ) {}

            assertEquals("答案", result.text)
            assertEquals("思考一下", result.reasoning)
        } finally {
            server.shutdown()
        }
    }

    private fun sse(payload: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(payload)
}
