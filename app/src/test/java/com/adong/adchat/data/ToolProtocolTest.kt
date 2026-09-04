package com.adong.adchat.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProtocolTest {
    @Test
    fun chatSearchTakesPriorityOverCustomFileTool() {
        val policy = resolveChatToolPolicy(webSearchEnabled = true, fileCreationEnabled = true)

        assertTrue(policy.webSearchEnabled)
        assertFalse(policy.fileCreationEnabled)
    }

    @Test
    fun chatFileToolUsesFunctionEnvelope() {
        val tools = buildChatTools(fileCreationEnabled = true)
        val tool = tools.getJSONObject(0)

        assertEquals("function", tool.getString("type"))
        assertEquals(CREATE_FILE_TOOL, tool.getJSONObject("function").getString("name"))
    }

    @Test
    fun responsesCanOfferSearchAndFileTogether() {
        val tools = buildResponsesTools(fileCreationEnabled = true, webSearchEnabled = true)

        assertEquals(WEB_SEARCH_TOOL, tools.getJSONObject(0).getString("type"))
        assertEquals(CREATE_FILE_TOOL, tools.getJSONObject(1).getString("name"))
    }

    @Test
    fun createFileNormalizesNameAndKeepsUtf8Content() {
        val call = PendingToolCall(
            itemId = "item-1",
            callId = "call-1",
            name = CREATE_FILE_TOOL,
            arguments = JSONObject()
                .put("filename", "../测试?.txt")
                .put("mime_type", "text/markdown")
                .put("content", "# 标题\n正文")
                .toString()
        )

        val result = executeAppTool(call)

        assertEquals(TOOL_STATUS_COMPLETED, result.activity.status)
        assertEquals("测试_.md", result.generatedFile?.name)
        assertEquals("# 标题\n正文", result.generatedFile?.content)
        assertTrue(JSONObject(result.output).getBoolean("ok"))
    }

    @Test
    fun unsupportedMimeTypeFailsWithoutCreatingFile() {
        val result = executeAppTool(PendingToolCall(
            itemId = "item-2",
            callId = "call-2",
            name = CREATE_FILE_TOOL,
            arguments = """{"filename":"bad.bin","mime_type":"application/octet-stream","content":"x"}"""
        ))

        assertEquals(TOOL_STATUS_FAILED, result.activity.status)
        assertEquals(null, result.generatedFile)
        assertFalse(JSONObject(result.output).getBoolean("ok"))
    }

    @Test
    fun chatAccumulatorCombinesStreamingArguments() {
        val accumulator = ChatToolCallAccumulator()
        accumulator.accept(JSONObject("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-3","function":{"name":"create_file","arguments":"{\"filename\":\"a.md\","}}]}}]}"""))
        accumulator.accept(JSONObject("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"mime_type\":\"text/markdown\",\"content\":\"ok\"}"}}]}}]}"""))

        val call = accumulator.completedCalls().single()
        assertEquals("call-3", call.callId)
        assertEquals("a.md", JSONObject(call.arguments).getString("filename"))
    }

    @Test
    fun responsesAccumulatorReadsCompletedFunctionCall() {
        val accumulator = ResponsesToolCallAccumulator()
        accumulator.accept(JSONObject("""{"type":"response.output_item.done","item":{"id":"item-4","type":"function_call","call_id":"call-4","name":"create_file","arguments":"{\"filename\":\"a.md\"}"}}"""))

        val call = accumulator.completedCalls().single()
        assertEquals("item-4", call.itemId)
        assertEquals("call-4", call.callId)
    }

    @Test
    fun citationsAreDeduplicatedByUrl() {
        val root = JSONObject()
            .put("sources", JSONArray()
                .put(JSONObject().put("url", "https://example.com/a").put("title", "A"))
                .put(JSONObject().put("url", "https://example.com/a").put("title", "A2")))

        val citations = parseCitations(root)

        assertEquals(1, citations.size)
        assertEquals("A2", citations.single().title)
    }
}
