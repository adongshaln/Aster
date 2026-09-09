from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    return text.replace(old, new, 1)


# SkillRuntime: keep explicitly supplied skill links available in the conversation.
p = Path("app/src/main/java/com/adong/adchat/data/SkillRuntime.kt")
s = p.read_text()
start = s.index("internal fun requestedGitHubSkillUrl")
end = s.index("\ninternal object GitHubSkillRuntime", start)
s = s[:start] + r'''private fun explicitGitHubSkillUrls(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val urls = Regex("https://(?:www\\.)?github\\.com/[^\\s<>()]+|https://raw\\.githubusercontent\\.com/[^\\s<>()]+", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.value.trimEnd('.', ',', ';', '，', '。', '；', ')', '）', ']', '】') }
        .toList()
    if (urls.isEmpty()) return emptyList()
    val lower = text.lowercase()
    val explicitlyRequested = lower.contains("skill") || lower.contains("技能") ||
        urls.any { it.contains("/SKILL.md", ignoreCase = true) || it.contains("/skills/", ignoreCase = true) }
    return if (explicitlyRequested) urls else emptyList()
}

internal fun availableGitHubSkillUrls(history: List<ChatMessage>): List<String> = history
    .asSequence()
    .filter { it.role == "user" }
    .flatMap { explicitGitHubSkillUrls(it.content).asSequence() }
    .distinct()
    .take(8)
    .toList()

internal fun requestedGitHubSkillUrl(history: List<ChatMessage>): String? = history
    .lastOrNull { it.role == "user" }
    ?.content
    ?.let(::explicitGitHubSkillUrls)
    ?.firstOrNull()

internal fun skillUrlsForTurn(history: List<ChatMessage>): List<String> {
    requestedGitHubSkillUrl(history)?.let { return listOf(it) }
    val latest = history.lastOrNull { it.role == "user" }?.content.orEmpty().lowercase()
    if (!latest.contains("skill") && !latest.contains("技能")) return emptyList()
    return availableGitHubSkillUrls(history)
}

internal fun shouldOfferSkillLoader(history: List<ChatMessage>): Boolean = skillUrlsForTurn(history).isNotEmpty()
''' + s[end:]
p.write_text(s)


# ToolProtocol: expose and execute real load_skill calls.
p = Path("app/src/main/java/com/adong/adchat/data/ToolProtocol.kt")
s = p.read_text()
s = replace_once(
    s,
    '''internal fun buildChatTools(fileCreationEnabled: Boolean): JSONArray = JSONArray().apply {
    if (fileCreationEnabled) {
        put(JSONObject()
            .put("type", "function")
            .put("function", createFileDefinition(responsesApi = false)))
    }
}

internal fun buildResponsesTools(
    fileCreationEnabled: Boolean,
    webSearchEnabled: Boolean
): JSONArray = JSONArray().apply {
    if (webSearchEnabled) put(JSONObject().put("type", WEB_SEARCH_TOOL))
    if (fileCreationEnabled) put(createFileDefinition(responsesApi = true))
}

private fun createFileDefinition(responsesApi: Boolean): JSONObject {''',
    '''internal fun buildChatTools(fileCreationEnabled: Boolean, skillUrls: List<String> = emptyList()): JSONArray = JSONArray().apply {
    if (skillUrls.isNotEmpty()) {
        put(JSONObject()
            .put("type", "function")
            .put("function", loadSkillDefinition(responsesApi = false, skillUrls)))
    }
    if (fileCreationEnabled) {
        put(JSONObject()
            .put("type", "function")
            .put("function", createFileDefinition(responsesApi = false)))
    }
}

internal fun buildResponsesTools(
    fileCreationEnabled: Boolean,
    webSearchEnabled: Boolean,
    skillUrls: List<String> = emptyList()
): JSONArray = JSONArray().apply {
    if (webSearchEnabled) put(JSONObject().put("type", WEB_SEARCH_TOOL))
    if (skillUrls.isNotEmpty()) put(loadSkillDefinition(responsesApi = true, skillUrls))
    if (fileCreationEnabled) put(createFileDefinition(responsesApi = true))
}

private fun loadSkillDefinition(responsesApi: Boolean, skillUrls: List<String>): JSONObject {
    require(skillUrls.isNotEmpty())
    val parameters = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("source_url", JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(skillUrls.distinct()))
                .put("description", "The exact GitHub Skill URL supplied by the user.")))
        .put("required", JSONArray(listOf("source_url")))
        .put("additionalProperties", false)
    val definition = JSONObject()
        .put("name", LOAD_SKILL_TOOL)
        .put("description", "Load the actual SKILL.md bytes from a GitHub URL explicitly supplied by the user. You must call this tool before claiming that a skill was loaded or before applying that skill's instructions. The result contains the verified URL, SHA-256 and complete UTF-8 SKILL.md content.")
        .put("parameters", parameters)
    return if (responsesApi) definition.put("type", "function").put("strict", true) else definition
}

internal fun toolRunningLabel(name: String): String = when (name) {
    LOAD_SKILL_TOOL -> "正在加载 Skill"
    CREATE_FILE_TOOL -> "正在创建文件"
    else -> "正在调用工具"
}

private fun createFileDefinition(responsesApi: Boolean): JSONObject {''',
    "tool builders",
)
start = s.index("internal fun executeAppTool")
end = s.index("\ninternal fun parseCitations", start)
s = s[:start] + r'''internal fun executeAppTool(
    call: PendingToolCall,
    skillLoader: SkillLoader = GitHubSkillRuntime,
    allowedSkillUrls: Set<String> = emptySet()
): ToolExecutionResult = when (call.name) {
    CREATE_FILE_TOOL -> runCatching {
        val arguments = JSONObject(call.arguments)
        val mimeType = arguments.optString("mime_type")
        require(mimeType in MIME_EXTENSIONS) { "不支持的文件类型" }
        val content = arguments.optString("content")
        val filename = sanitizeFileName(arguments.optString("filename"), mimeType)
        val file = if (mimeType in DocumentFiles.extensions) DocumentFiles.create(filename, mimeType, content)
            else GeneratedFileDraft(name = filename, mimeType = mimeType, content = content)
        ToolExecutionResult(
            output = JSONObject()
                .put("ok", true)
                .put("filename", file.name)
                .put("mime_type", file.mimeType)
                .put("size_bytes", ChatFileAttachment(name = file.name, mimeType = file.mimeType, content = file.content, encoding = file.encoding).sizeBytes)
                .toString(),
            generatedFile = file,
            activity = ChatToolActivity(call.callId, CREATE_FILE_TOOL, "已创建 ${file.name}", TOOL_STATUS_COMPLETED)
        )
    }.getOrElse { error ->
        ToolExecutionResult(
            output = JSONObject().put("ok", false).put("error", error.message ?: "文件创建失败").toString(),
            activity = ChatToolActivity(call.callId, CREATE_FILE_TOOL, error.message ?: "文件创建失败", TOOL_STATUS_FAILED)
        )
    }
    LOAD_SKILL_TOOL -> runCatching {
        val sourceUrl = JSONObject(call.arguments).getString("source_url").trim()
        require(sourceUrl in allowedSkillUrls) { "该 Skill 链接未由用户在当前对话中提供" }
        val loaded = skillLoader.load(sourceUrl)
        ToolExecutionResult(
            output = JSONObject()
                .put("ok", true)
                .put("type", "aster_skill")
                .put("name", loaded.name)
                .put("source_url", loaded.sourceUrl)
                .put("resolved_url", loaded.resolvedUrl)
                .put("sha256", loaded.sha256)
                .put("content", loaded.content)
                .toString(),
            activity = ChatToolActivity(call.callId, LOAD_SKILL_TOOL, "已加载 Skill：${loaded.name}", TOOL_STATUS_COMPLETED)
        )
    }.getOrElse { error ->
        ToolExecutionResult(
            output = JSONObject().put("ok", false).put("error", error.message ?: "Skill 加载失败").toString(),
            activity = ChatToolActivity(call.callId, LOAD_SKILL_TOOL, error.message ?: "Skill 加载失败", TOOL_STATUS_FAILED)
        )
    }
    else -> ToolExecutionResult(
        output = JSONObject().put("ok", false).put("error", "Unsupported tool: ${call.name}").toString(),
        activity = ChatToolActivity(call.callId, call.name, "工具 ${call.name} 不受支持", TOOL_STATUS_FAILED)
    )
}
''' + s[end:]
p.write_text(s)


# ApiRepository: wire skill URLs, forced first load, actual tool output, and test injection.
p = Path("app/src/main/java/com/adong/adchat/data/ApiRepository.kt")
s = p.read_text()
s = replace_once(s, "class ApiRepository {", "class ApiRepository(private val skillLoader: SkillLoader = GitHubSkillRuntime) {", "repository constructor")
s = replace_once(
    s,
    "        val toolsActive = profile.webSearchEnabled || profile.fileCreationEnabled\n",
    "        val skillUrls = skillUrlsForTurn(history)\n        val forcedSkillUrl = requestedGitHubSkillUrl(history)\n        val toolsActive = profile.webSearchEnabled || profile.fileCreationEnabled || skillUrls.isNotEmpty()\n",
    "stream tool state",
)
s = replace_once(
    s,
    "                streamResponses(profile, model, systemPrompt, prepared.history, cacheKey, onToolActivity, deltaSink)\n",
    "                streamResponses(profile, model, systemPrompt, prepared.history, cacheKey, skillUrls, forcedSkillUrl, onToolActivity, deltaSink)\n",
    "responses call",
)
s = replace_once(
    s,
    "                streamChatCompletions(profile, model, systemPrompt, prepared.history, cacheKey, explicitCache = false, onToolActivity = onToolActivity, onDelta = deltaSink)\n",
    "                streamChatCompletions(profile, model, systemPrompt, prepared.history, cacheKey, explicitCache = false, skillUrls = skillUrls, forcedSkillUrl = forcedSkillUrl, onToolActivity = onToolActivity, onDelta = deltaSink)\n",
    "chat call",
)
s = replace_once(
    s,
    "        cacheKey: String,\n        explicitCache: Boolean,\n        onToolActivity: suspend (ChatToolActivity) -> Unit,\n",
    "        cacheKey: String,\n        explicitCache: Boolean,\n        skillUrls: List<String>,\n        forcedSkillUrl: String?,\n        onToolActivity: suspend (ChatToolActivity) -> Unit,\n",
    "chat signature",
)
s = replace_once(
    s,
    "        val stableHistory = history.filterNot { it.isError || it.isStreaming || it.isInterrupted || it.isStopped }\n",
    "        val stableHistory = history.filterNot { it.isError || it.isStreaming || it.isInterrupted || it.isStopped }\n        var forcedSkillHandled = forcedSkillUrl == null\n",
    "chat force state",
)
s = replace_once(
    s,
    "            val toolPolicy = resolveChatToolPolicy(profile.webSearchEnabled, profile.fileCreationEnabled)\n",
    "            val toolPolicy = if (skillUrls.isNotEmpty()) ChatToolPolicy(webSearchEnabled = false, fileCreationEnabled = profile.fileCreationEnabled)\n                else resolveChatToolPolicy(profile.webSearchEnabled, profile.fileCreationEnabled)\n",
    "chat policy",
)
s = replace_once(
    s,
    '''            val tools = buildChatTools(toolPolicy.fileCreationEnabled)
            if (tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
''',
    '''            val tools = buildChatTools(toolPolicy.fileCreationEnabled, skillUrls)
            if (tools.length() > 0) {
                body.put("tools", tools)
                body.put("tool_choice", if (!forcedSkillHandled) {
                    JSONObject().put("type", "function").put("function", JSONObject().put("name", LOAD_SKILL_TOOL))
                } else "auto")
            }
''',
    "chat tools",
)

marker = "    private suspend fun streamResponses("
idx = s.index(marker)
tail = s[idx:]
tail = replace_once(
    tail,
    "        cacheKey: String,\n        onToolActivity: suspend (ChatToolActivity) -> Unit,\n",
    "        cacheKey: String,\n        skillUrls: List<String>,\n        forcedSkillUrl: String?,\n        onToolActivity: suspend (ChatToolActivity) -> Unit,\n",
    "responses signature",
)
s = s[:idx] + tail
s = replace_once(
    s,
    "        val tools = buildResponsesTools(profile.fileCreationEnabled, profile.webSearchEnabled)\n",
    "        val tools = buildResponsesTools(profile.fileCreationEnabled, profile.webSearchEnabled, skillUrls)\n        var forcedSkillHandled = forcedSkillUrl == null\n",
    "responses tools state",
)
s = replace_once(
    s,
    '''            if (tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
            if (profile.fileCreationEnabled) body.put("store", true)
''',
    '''            if (tools.length() > 0) {
                body.put("tools", tools)
                body.put("tool_choice", if (!forcedSkillHandled) {
                    JSONObject().put("type", "function").put("name", LOAD_SKILL_TOOL)
                } else "auto")
            }
            if (profile.fileCreationEnabled || skillUrls.isNotEmpty()) body.put("store", true)
''',
    "responses forced tool",
)
old_exec = '''                recordActivity(ChatToolActivity(call.callId, call.name, "正在创建文件", TOOL_STATUS_RUNNING))
                val execution = executeAppTool(call)
'''
new_exec = '''                recordActivity(ChatToolActivity(call.callId, call.name, toolRunningLabel(call.name), TOOL_STATUS_RUNNING))
                val execution = executeAppTool(call, skillLoader, skillUrls.toSet())
                if (call.name == LOAD_SKILL_TOOL) forcedSkillHandled = true
'''
if s.count(old_exec) != 2:
    raise SystemExit(f"expected 2 tool execution anchors, got {s.count(old_exec)}")
s = s.replace(old_exec, new_exec)
p.write_text(s)


# Unit tests.
Path("app/src/test/java/com/adong/adchat/data/SkillRuntimeTest.kt").write_text(r'''package com.adong.adchat.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRuntimeTest {
    private val url = "https://github.com/example/skills/blob/main/presentation/SKILL.md"

    @Test fun githubTargetsResolveOnlyToSkillMarkdown() {
        val blob = resolveGitHubSkillTarget(url) { _, _ -> "main" }
        assertEquals("https://raw.githubusercontent.com/example/skills/main/presentation/SKILL.md", blob.rawUrl)
        val tree = resolveGitHubSkillTarget("https://github.com/example/skills/tree/main/presentation") { _, _ -> "main" }
        assertEquals("presentation/SKILL.md", tree.path)
        val root = resolveGitHubSkillTarget("https://github.com/example/skills") { _, _ -> "stable" }
        assertEquals("stable", root.ref)
        assertEquals("SKILL.md", root.path)
    }

    @Test fun nonGithubAndNonSkillBlobAreRejected() {
        assertTrue(runCatching { resolveGitHubSkillTarget("https://example.com/SKILL.md") { _, _ -> "main" } }.isFailure)
        assertTrue(runCatching { resolveGitHubSkillTarget("https://github.com/a/b/blob/main/README.md") { _, _ -> "main" } }.isFailure)
    }

    @Test fun conversationKeepsExplicitSkillsButDoesNotTreatOrdinaryGithubLinksAsSkills() {
        val history = listOf(
            ChatMessage(role = "user", content = "加载这个 skill：$url"),
            ChatMessage(role = "assistant", content = "好"),
            ChatMessage(role = "user", content = "普通项目 https://github.com/example/project")
        )
        assertEquals(listOf(url), availableGitHubSkillUrls(history))
        assertFalse(shouldOfferSkillLoader(history))
        val reuse = history + ChatMessage(role = "user", content = "继续使用刚才的技能")
        assertEquals(listOf(url), skillUrlsForTurn(reuse))
    }

    @Test fun loadSkillToolReturnsRealLoaderContentAndRejectsUnapprovedUrl() {
        val loader = SkillLoader { source -> LoadedSkill("presentation", source, "https://raw.githubusercontent.com/example/skills/main/presentation/SKILL.md", "abc123", "# REAL SKILL\nFollow this procedure") }
        val call = PendingToolCall("i", "c", LOAD_SKILL_TOOL, JSONObject().put("source_url", url).toString())
        val ok = executeAppTool(call, loader, setOf(url))
        val body = JSONObject(ok.output)
        assertTrue(body.getBoolean("ok"))
        assertEquals("# REAL SKILL\nFollow this procedure", body.getString("content"))
        assertEquals("abc123", body.getString("sha256"))
        val denied = executeAppTool(call, loader, emptySet())
        assertFalse(JSONObject(denied.output).getBoolean("ok"))
    }

    @Test fun toolSchemasConstrainTheModelToUserSuppliedUrl() {
        val chat = buildChatTools(false, listOf(url)).getJSONObject(0).getJSONObject("function")
        assertEquals(LOAD_SKILL_TOOL, chat.getString("name"))
        assertEquals(url, chat.getJSONObject("parameters").getJSONObject("properties").getJSONObject("source_url").getJSONArray("enum").getString(0))
        val responses = buildResponsesTools(false, false, listOf(url)).getJSONObject(0)
        assertEquals(LOAD_SKILL_TOOL, responses.getString("name"))
        assertTrue(responses.getBoolean("strict"))
    }
}
''')

Path("app/src/test/java/com/adong/adchat/data/SkillProtocolContractTest.kt").write_text(r'''package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillProtocolContractTest {
    private val url = "https://github.com/example/skills/blob/main/presentation/SKILL.md"
    private val loader = SkillLoader { source ->
        LoadedSkill("presentation", source, "https://raw.githubusercontent.com/example/skills/main/presentation/SKILL.md", "sha-real", "# REAL_SKILL_BODY\nUse cards, not bullet walls.")
    }

    @Test fun chatCompletionsForcesLoadSkillAndFeedsRealContentBack() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call-skill","type":"function","function":{"name":"load_skill","arguments":"{\"source_url\":\"$url\"}"}}]},"finish_reason":"tool_calls"}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"content":"已按真实 Skill 执行"},"finish_reason":"stop"}]}"""))
            val result = ApiRepository(loader).streamChat(
                ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test"),
                "gemini-test", "", listOf(ChatMessage(role = "user", content = "加载这个 skill 并使用：$url")), "skill-test"
            ) {}
            assertEquals("已按真实 Skill 执行", result.text)
            val first = server.takeRequest().body.readUtf8()
            val second = server.takeRequest().body.readUtf8()
            assertTrue(first.contains("load_skill"))
            assertTrue(first.contains("tool_choice"))
            assertTrue(second.contains("REAL_SKILL_BODY"))
            assertTrue(second.contains("sha-real"))
            assertTrue(second.contains("\"role\":\"tool\""))
        } finally { server.shutdown() }
    }

    @Test fun responsesForcesLoadSkillAndSendsFunctionCallOutput() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"resp-1","status":"completed","output":[{"id":"fc-1","type":"function_call","call_id":"call-skill","name":"load_skill","arguments":"{\"source_url\":\"$url\"}"}],"usage":{"input_tokens":10,"output_tokens":5}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"resp-2","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"已执行"}]}],"usage":{"input_tokens":20,"output_tokens":3}}"""))
            val result = ApiRepository(loader).streamChat(
                ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test"),
                "gpt-test", "", listOf(ChatMessage(role = "user", content = "加载这个技能：$url")), "skill-test"
            ) {}
            assertEquals("已执行", result.text)
            val first = server.takeRequest().body.readUtf8()
            val second = server.takeRequest().body.readUtf8()
            assertTrue(first.contains("load_skill"))
            assertTrue(first.contains("tool_choice"))
            assertTrue(second.contains("function_call_output"))
            assertTrue(second.contains("REAL_SKILL_BODY"))
            assertTrue(second.contains("resp-1"))
        } finally { server.shutdown() }
    }
}
''')

Path("docs/SKILLS_RUNTIME.md").write_text(r'''# Aster Skills Runtime

Development branch: `feature/skills-runtime`.

Aster implements Skills as real model function calls, not as a UI label or hidden prompt substitution.

## Current contract

1. A user supplies an explicit public GitHub Skill URL in a chat message. Repository roots, `tree/<ref>/<dir>`, `blob/<ref>/.../SKILL.md`, and raw `SKILL.md` URLs are supported.
2. Aster exposes `load_skill(source_url)` to the model. For a newly supplied URL, `tool_choice` forces the first custom-tool round to call `load_skill`; the schema constrains `source_url` to the exact URL supplied by the user.
3. The app resolves the GitHub target, downloads the real `SKILL.md` over HTTPS, rejects non-GitHub targets and unexpected redirects, enforces a 256 KiB limit and strict UTF-8, and computes SHA-256 over the downloaded bytes.
4. The complete verified Skill text, source/resolved URLs and SHA-256 are returned as the actual tool result. That result is sent to the provider in the next Chat Completions `role=tool` message or Responses `function_call_output` input.
5. The model only continues after receiving that result. A failed download is returned as a failed tool result; Aster does not claim the Skill was loaded.

Skill links remain discoverable from persisted user messages in the same conversation. A later message that explicitly asks to use a Skill can offer those previously supplied links again. This checkpoint does not yet create a global cross-conversation Skill library or execute arbitrary code/scripts referenced by a Skill.

GitHub Skill content is treated as instructions only. Aster never executes shell commands, scripts, binaries, macros, or arbitrary repository files as part of `load_skill`.
''')

# One-shot implementation helper must not remain in the product tree.
Path("tools/implement_skills_runtime.py").unlink()
Path(".github/workflows/implement-skills-runtime.yml").unlink()
