package com.adong.adchat.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Messages API 的请求映射与 SSE 事件解析。
 *
 * 覆盖 Aster 需要的基础面：system 提升为顶层字段、text/image 内容块、
 * 客户端工具的 tool_use/tool_result 回写、思考块、缓存断点。
 * 不处理：服务端工具的交错块（web_search_tool_result 等由委托搜索后端自行解析）、
 * citations 块、mcp。解析时未知块类型一律跳过，不因网关新增块而崩。
 */
internal object AnthropicMessages {

    const val VERSION_HEADER = "anthropic-version"
    const val VERSION = "2023-06-01"
    const val DEFAULT_MAX_TOKENS = 8192

    /** Messages 必填 max_tokens；优先沿用请求体已写入的上下文上限，其次配置上限，最后兜底。 */
    fun ensureMaxTokens(body: JSONObject, configuredOutputLimit: Int?) {
        if (body.optInt("max_tokens", 0) > 0) return
        body.put("max_tokens", configuredOutputLimit?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS)
    }

    /** 单条历史消息 → Messages 内容。无附件时保持纯字符串，有附件用块数组。 */
    fun messageContent(message: ChatMessage): Any {
        if (message.attachments.isEmpty()) return message.content
        val content = JSONArray()
        if (message.content.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", message.content))
        }
        message.attachments.forEach { attachment ->
            val bytes = attachment.bytes ?: throw IllegalStateException("无法读取对话图片：${attachment.name}")
            content.put(JSONObject()
                .put("type", "image")
                .put("source", JSONObject()
                    .put("type", "base64")
                    .put("media_type", attachment.mimeType.ifBlank { "image/jpeg" })
                    .put("data", java.util.Base64.getEncoder().encodeToString(bytes))))
        }
        return content
    }

    /**
     * 工具结果以 user 角色的 tool_result 块回写，多条调用结果合并在同一条 user 消息里
     * （Anthropic 要求 tool_result 紧跟在对应 tool_use 的助手消息之后）。
     */
    fun toolResultMessage(results: List<Pair<String, String>>): JSONObject {
        val content = JSONArray()
        results.forEach { (toolUseId, output) ->
            content.put(JSONObject()
                .put("type", "tool_result")
                .put("tool_use_id", toolUseId)
                .put("content", output))
        }
        return JSONObject().put("role", "user").put("content", content)
    }

    /** 助手回合回写：正文文本块 + 工具调用块，先文后调用。 */
    fun assistantToolUseMessage(text: String, calls: List<PendingToolCall>): JSONObject {
        val content = JSONArray()
        if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
        calls.forEach { call ->
            val input = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
            content.put(JSONObject()
                .put("type", "tool_use")
                .put("id", call.callId)
                .put("name", call.name)
                .put("input", input))
        }
        return JSONObject().put("role", "assistant").put("content", content)
    }

    fun toolChoice(forceSkill: Boolean, forceNoTools: Boolean): Any = when {
        forceSkill -> JSONObject().put("type", "tool").put("name", LOAD_SKILL_TOOL)
        forceNoTools -> JSONObject().put("type", "none")
        else -> JSONObject().put("type", "auto")
    }

    /** OpenAI 工具定义 {type:"function", function:{…}} → Anthropic {name, description, input_schema}。 */
    fun toolDefinition(openAiTool: JSONObject): JSONObject {
        val function = openAiTool.optJSONObject("function") ?: openAiTool
        return JSONObject()
            .put("name", function.optString("name"))
            .put("description", function.optString("description"))
            .put("input_schema", function.optJSONObject("parameters")
                ?: JSONObject().put("type", "object").put("properties", JSONObject()))
    }

    fun tools(openAiTools: JSONArray): JSONArray = JSONArray().apply {
        for (index in 0 until openAiTools.length()) {
            openAiTools.optJSONObject(index)?.let { put(toolDefinition(it)) }
        }
    }

    // ---- SSE 事件解析 ----

    /** 一个 content block 的在途状态：tool_use 需要累计参数与 id/name。 */
    internal class BlockState(val type: String, val index: Int) {
        val json = StringBuilder()
        var toolId: String = ""
        var toolName: String = ""
    }

    sealed class Event {
        /** 正文增量。 */
        data class Text(val delta: String) : Event()
        /** 思考增量。 */
        data class Thinking(val delta: String) : Event()
        /** 一个 tool_use 块收齐，可以执行。 */
        data class ToolCall(val call: PendingToolCall) : Event()
        /** 回合结束；stopReason 原文保留（end_turn / tool_use / max_tokens / stop_sequence）。 */
        data class MessageDone(val stopReason: String, val usage: TokenUsage) : Event()
        /** message_start 携带的输入侧 usage（含缓存读/写）。 */
        data class Start(val usage: TokenUsage) : Event()
        /** 服务端报错事件，按失败处理。 */
        data class Failure(val message: String) : Event()
        /** ping、未知块、未知事件：安全忽略。 */
        object Ignored : Event()
    }

    fun parseEvent(payload: String, blocks: MutableMap<Int, BlockState>): Event {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return Event.Ignored
        return when (root.optString("type")) {
            "message_start" -> {
                val usage = root.optJSONObject("message")?.optJSONObject("usage")
                Event.Start(usage?.let(::parseUsage) ?: TokenUsage())
            }
            "content_block_start" -> {
                val index = root.optInt("index", -1)
                val block = root.optJSONObject("content_block") ?: return Event.Ignored
                val type = block.optString("type")
                if (index < 0) return Event.Ignored
                val state = BlockState(type, index)
                if (type == "tool_use") {
                    state.toolId = block.optString("id")
                    state.toolName = block.optString("name")
                    // input 在 start 里通常是空对象 {}，参数由后续 input_json_delta 给出；
                    // 只在网关确实于 start 里带上非空 input 时才收下（不自拼 "{}"）。
                    block.optJSONObject("input")?.takeIf { it.length() > 0 }
                        ?.let { state.json.append(it.toString()) }
                }
                blocks[index] = state
                Event.Ignored
            }
            "content_block_delta" -> {
                val index = root.optInt("index", -1)
                val delta = root.optJSONObject("delta") ?: return Event.Ignored
                when (delta.optString("type")) {
                    "text_delta" -> Event.Text(delta.optString("text"))
                    "thinking_delta" -> Event.Thinking(delta.optString("thinking"))
                    "input_json_delta" -> {
                        blocks[index]?.json?.append(delta.optString("partial_json"))
                        Event.Ignored
                    }
                    else -> Event.Ignored
                }
            }
            "content_block_stop" -> {
                val index = root.optInt("index", -1)
                val block = blocks.remove(index) ?: return Event.Ignored
                if (block.type != "tool_use") return Event.Ignored
                if (block.toolId.isBlank() || block.toolName.isBlank()) return Event.Ignored
                Event.ToolCall(PendingToolCall(block.toolId, block.toolId, block.toolName, block.json.toString()))
            }
            "message_delta" -> {
                val stopReason = root.optJSONObject("delta")?.optString("stop_reason").orEmpty()
                val usage = root.optJSONObject("usage")?.let(::parseUsage) ?: TokenUsage()
                Event.MessageDone(stopReason, usage)
            }
            // message_delta 已携带 stop_reason；裸 message_stop 无更多信息。
            "message_stop" -> Event.Ignored
            "error" -> Event.Failure(
                root.optJSONObject("error")?.optString("message")?.ifBlank { null }
                    ?: "Anthropic 流式响应返回错误事件"
            )
            else -> Event.Ignored
        }
    }

    private fun parseUsage(usage: JSONObject): TokenUsage {
        val input = usage.optInt("input_tokens", 0)
        val output = usage.optInt("output_tokens", 0)
        val cached = usage.optInt("cache_read_input_tokens", 0)
        val cacheWrite = usage.optInt("cache_creation_input_tokens", 0)
        return TokenUsage(
            inputTokens = input,
            cachedTokens = cached.coerceAtMost(input),
            cacheWriteTokens = cacheWrite,
            outputTokens = output,
            totalTokens = input + output,
            cacheMetricsReported = usage.has("cache_read_input_tokens") || usage.has("cache_creation_input_tokens"),
            providerUsageReported = usage.opt("input_tokens") is Number && usage.opt("output_tokens") is Number
        )
    }

    /** 非流式整包解析（小请求用）：正文拼接 + 全部 tool_use。 */
    fun parseContent(root: JSONObject): Pair<String, List<PendingToolCall>> {
        val content = root.optJSONArray("content") ?: return "" to emptyList()
        val text = StringBuilder()
        val calls = mutableListOf<PendingToolCall>()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> {
                    val id = block.optString("id")
                    val name = block.optString("name")
                    if (id.isNotBlank() && name.isNotBlank()) {
                        calls += PendingToolCall(id, id, name,
                            block.optJSONObject("input")?.toString().orEmpty())
                    }
                }
            }
        }
        return text.toString() to calls
    }

    fun stopReason(root: JSONObject): String = root.optString("stop_reason")
}
