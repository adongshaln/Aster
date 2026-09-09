from pathlib import Path


def one(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing anchor: {label}")
    return text.replace(old, new, 1)

# Harden ToolProtocol: the model can only select a Skill explicitly allowed for this turn,
# and the executor independently checks the same allowlist.
p = Path("app/src/main/java/com/adong/adchat/data/ToolProtocol.kt")
s = p.read_text()
s = one(s,
'''internal fun buildChatTools(
    fileCreationEnabled: Boolean,
    skillLoadingEnabled: Boolean = false
): JSONArray = JSONArray().apply {''',
'''internal fun buildChatTools(
    fileCreationEnabled: Boolean,
    skillLoadingEnabled: Boolean = false,
    skillSelectors: List<String> = emptyList()
): JSONArray = JSONArray().apply {''', 'chat builder signature')
s = one(s,
'''            .put("function", loadSkillDefinition(responsesApi = false)))''',
'''            .put("function", loadSkillDefinition(responsesApi = false, skillSelectors = skillSelectors)))''', 'chat skill definition')
s = one(s,
'''internal fun buildResponsesTools(
    fileCreationEnabled: Boolean,
    webSearchEnabled: Boolean,
    skillLoadingEnabled: Boolean = false
): JSONArray = JSONArray().apply {''',
'''internal fun buildResponsesTools(
    fileCreationEnabled: Boolean,
    webSearchEnabled: Boolean,
    skillLoadingEnabled: Boolean = false,
    skillSelectors: List<String> = emptyList()
): JSONArray = JSONArray().apply {''', 'responses builder signature')
s = one(s,
'''    if (skillLoadingEnabled) put(loadSkillDefinition(responsesApi = true))''',
'''    if (skillLoadingEnabled) put(loadSkillDefinition(responsesApi = true, skillSelectors = skillSelectors))''', 'responses skill definition')
s = one(s,
'''private fun loadSkillDefinition(responsesApi: Boolean): JSONObject {
    val parameters = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("url", JSONObject()
                .put("type", "string")
                .put("description", "A public GitHub Skill URL to install/refresh, or the exact installed Skill name, SHA-256 or source URL to reuse.")))''',
'''private fun loadSkillDefinition(responsesApi: Boolean, skillSelectors: List<String>): JSONObject {
    require(skillSelectors.isNotEmpty()) { "load_skill requires at least one user-approved Skill selector" }
    val parameters = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("url", JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(skillSelectors.distinct()))
                .put("description", "The exact GitHub Skill URL supplied by the user, or the exact installed Skill selector explicitly requested for this turn.")))''', 'load skill schema')
s = one(s,
'''internal fun executeAppTool(
    call: PendingToolCall,
    skillLoader: SkillLoader = GitHubSkillRuntime
): ToolExecutionResult = when (call.name) {''',
'''internal fun executeAppTool(
    call: PendingToolCall,
    skillLoader: SkillLoader = GitHubSkillRuntime,
    allowedSkillSelectors: Set<String> = emptySet()
): ToolExecutionResult = when (call.name) {''', 'executor signature')
s = one(s,
'''        val sourceUrl = arguments.optString("url").trim()
        require(sourceUrl.isNotBlank()) { "load_skill 缺少 GitHub URL" }
        val skill = skillLoader.load(sourceUrl)''',
'''        val sourceUrl = arguments.optString("url").trim()
        require(sourceUrl.isNotBlank()) { "load_skill 缺少 Skill 选择器" }
        require(sourceUrl in allowedSkillSelectors) { "模型请求了用户本轮未授权的 Skill；Aster 已拒绝加载" }
        val skill = skillLoader.load(sourceUrl)''', 'executor allowlist')
p.write_text(s)

# Wire the exact selector through both protocol loops.
p = Path("app/src/main/java/com/adong/adchat/data/ApiRepository.kt")
s = p.read_text()
s = one(s,
'''        val requestedSkillUrl = requestedGitHubSkillUrl(history)
        val requestedInstalledSkill = requestedInstalledSkillName(history, skillLoader.listInstalled())
        var skillLoadingEnabled = requestedSkillUrl != null || requestedInstalledSkill != null
        var nativeSkillReference: NativeSkillReference? = null''',
'''        val requestedSkillUrl = requestedGitHubSkillUrl(history)
        val requestedInstalledSkill = requestedInstalledSkillName(history, skillLoader.listInstalled())
        val requestedSkillSelectors = listOfNotNull(requestedSkillUrl ?: requestedInstalledSkill)
        var skillLoadingEnabled = requestedSkillSelectors.isNotEmpty()
        var nativeSkillReference: NativeSkillReference? = null''', 'requested selectors')
s = one(s,
'''                streamResponses(profile, model, systemPrompt, prepared.history, cacheKey, skillLoadingEnabled, nativeSkillReference, onToolActivity, deltaSink)''',
'''                streamResponses(
                    profile, model, systemPrompt, prepared.history, cacheKey,
                    skillSelectors = if (skillLoadingEnabled) requestedSkillSelectors else emptyList(),
                    nativeSkillReference = nativeSkillReference,
                    onToolActivity = onToolActivity,
                    onDelta = deltaSink
                )''', 'responses call')
s = one(s,
'''                streamChatCompletions(profile, model, systemPrompt, prepared.history, cacheKey, explicitCache = false, skillLoadingEnabled = skillLoadingEnabled, onToolActivity = onToolActivity, onDelta = deltaSink)''',
'''                streamChatCompletions(
                    profile, model, systemPrompt, prepared.history, cacheKey, explicitCache = false,
                    skillSelectors = if (skillLoadingEnabled) requestedSkillSelectors else emptyList(),
                    onToolActivity = onToolActivity,
                    onDelta = deltaSink
                )''', 'chat call')
s = one(s,
'''        cacheKey: String,
        explicitCache: Boolean,
        skillLoadingEnabled: Boolean,
        onToolActivity: suspend (ChatToolActivity) -> Unit,''',
'''        cacheKey: String,
        explicitCache: Boolean,
        skillSelectors: List<String>,
        onToolActivity: suspend (ChatToolActivity) -> Unit,''', 'chat signature')
s = one(s,
'''    ): ChatCompletionResult {
        val messages = JSONArray()
        val effectiveSystemPrompt = listOf(systemPrompt, SKILL_RUNTIME_INSTRUCTION.takeIf { skillLoadingEnabled }.orEmpty())''',
'''    ): ChatCompletionResult {
        val skillLoadingEnabled = skillSelectors.isNotEmpty()
        val messages = JSONArray()
        val effectiveSystemPrompt = listOf(systemPrompt, SKILL_RUNTIME_INSTRUCTION.takeIf { skillLoadingEnabled }.orEmpty())''', 'chat derive flag')
s = one(s,
'''            val tools = buildChatTools(toolPolicy.fileCreationEnabled, skillLoadingEnabled)''',
'''            val tools = buildChatTools(toolPolicy.fileCreationEnabled, skillLoadingEnabled, skillSelectors)''', 'chat builder call')
s = one(s,
'''                val execution = executeAppTool(call, skillLoader)''',
'''                val execution = executeAppTool(call, skillLoader, skillSelectors.toSet())''', 'chat executor')
s = one(s,
'''        cacheKey: String,
        skillLoadingEnabled: Boolean,
        nativeSkillReference: NativeSkillReference?,''',
'''        cacheKey: String,
        skillSelectors: List<String>,
        nativeSkillReference: NativeSkillReference?,''', 'responses signature')
s = one(s,
'''    ): ChatCompletionResult {
        val initialInput = JSONArray()''',
'''    ): ChatCompletionResult {
        val skillLoadingEnabled = skillSelectors.isNotEmpty()
        val initialInput = JSONArray()''', 'responses derive flag')
s = one(s,
'''        val tools = buildResponsesTools(profile.fileCreationEnabled, profile.webSearchEnabled, skillLoadingEnabled).apply {''',
'''        val tools = buildResponsesTools(profile.fileCreationEnabled, profile.webSearchEnabled, skillLoadingEnabled, skillSelectors).apply {''', 'responses builder call')
s = one(s,
'''                val execution = executeAppTool(call, skillLoader)''',
'''                val execution = executeAppTool(call, skillLoader, skillSelectors.toSet())''', 'responses executor')
p.write_text(s)

# Update the direct runtime tests for the mandatory allowlist and schema enum.
p = Path("app/src/test/java/com/adong/adchat/data/SkillRuntimeTest.kt")
s = p.read_text()
s = one(s,
'''            ),
            loader
        )''',
'''            ),
            loader,
            setOf(source)
        )''', 'runtime direct executor')
s = one(s,
'''        val chatTools = buildChatTools(fileCreationEnabled = false, skillLoadingEnabled = true)''',
'''        val source = "https://github.com/example/repo/blob/main/SKILL.md"
        val chatTools = buildChatTools(fileCreationEnabled = false, skillLoadingEnabled = true, skillSelectors = listOf(source))''', 'chat schema test')
s = one(s,
'''        assertTrue(chatFunction.getJSONObject("parameters").getJSONObject("properties").has("url"))

        val responsesTools = buildResponsesTools(fileCreationEnabled = false, webSearchEnabled = false, skillLoadingEnabled = true)''',
'''        val chatUrl = chatFunction.getJSONObject("parameters").getJSONObject("properties").getJSONObject("url")
        assertEquals(source, chatUrl.getJSONArray("enum").getString(0))

        val responsesTools = buildResponsesTools(fileCreationEnabled = false, webSearchEnabled = false, skillLoadingEnabled = true, skillSelectors = listOf(source))''', 'responses schema test')
# Add a server-side refusal test before the last test.
anchor = '''    @Test
    fun bothProtocolsExposeTheSameLoadSkillFunction() {'''
insert = '''    @Test
    fun modelCannotInventAnUnapprovedSkillUrl() {
        val approved = "https://github.com/example/repo/blob/main/SKILL.md"
        val invented = "https://github.com/attacker/other/blob/main/SKILL.md"
        var loads = 0
        val result = executeAppTool(
            PendingToolCall("item", "call", LOAD_SKILL_TOOL, JSONObject().put("url", invented).toString()),
            SkillLoader { loads++; error("must not reach loader") },
            setOf(approved)
        )
        assertEquals(0, loads)
        assertEquals(TOOL_STATUS_FAILED, result.activity.status)
        assertFalse(JSONObject(result.output).getBoolean("ok"))
        assertTrue(JSONObject(result.output).getString("error").contains("未授权"))
    }

'''
if anchor not in s:
    raise SystemExit('missing test insertion anchor')
s = s.replace(anchor, insert + anchor, 1)
p.write_text(s)

# Protocol tests now also verify the exact user URL is present in the tool schema, not merely the function name.
p = Path("app/src/test/java/com/adong/adchat/data/StoryCompletionContractTest.kt")
s = p.read_text()
s = one(s,
'''            val toolNames = first.getJSONArray("tools").toString()
            assertTrue(toolNames.contains(LOAD_SKILL_TOOL))''',
'''            val toolNames = first.getJSONArray("tools").toString()
            assertTrue(toolNames.contains(LOAD_SKILL_TOOL))
            assertTrue(toolNames.contains(skillUrl))''', 'chat contract selector')
s = one(s,
'''            assertTrue(first.getJSONArray("tools").toString().contains(LOAD_SKILL_TOOL))
            assertTrue(first.getBoolean("store"))''',
'''            assertTrue(first.getJSONArray("tools").toString().contains(LOAD_SKILL_TOOL))
            assertTrue(first.getJSONArray("tools").toString().contains(skillUrl))
            assertTrue(first.getBoolean("store"))''', 'responses contract selector')
p.write_text(s)

# Native fallback contract also proves the compatibility schema is pinned to the supplied URL.
p = Path("app/src/test/java/com/adong/adchat/data/NativeSkillsTest.kt")
s = p.read_text()
s = one(s,
'''            assertEquals(LOAD_SKILL_TOOL, first.getJSONObject("tool_choice").getString("name"))
            val second = JSONObject(server.takeRequest().body.readUtf8())''',
'''            assertEquals(LOAD_SKILL_TOOL, first.getJSONObject("tool_choice").getString("name"))
            assertTrue(first.getJSONArray("tools").toString().contains(sourceUrl))
            val second = JSONObject(server.takeRequest().body.readUtf8())''', 'native fallback selector')
p.write_text(s)

# Make the documentation match the actual hardening and current state.
p = Path("docs/SKILLS.md")
s = p.read_text()
s = s.replace(
    "1. Aster 把 `load_skill` 作为真正的 function tool 注册给模型，并在明确的 Skill 加载请求首轮强制调用。",
    "1. Aster 把 `load_skill` 作为真正的 function tool 注册给模型，并在明确的 Skill 加载请求首轮强制调用；其参数 schema 使用 `enum` 只允许本轮用户明确给出的 GitHub URL，或本轮明确点名的已安装 Skill。"
)
s = s.replace(
    "5. 模型在下一轮基于 tool result 继续推理。",
    "5. 客户端执行工具时还会再次检查同一允许列表，因此即便兼容网关忽略 JSON Schema，模型也不能自行编造另一个 GitHub URL；校验通过后，模型才在下一轮基于 tool result 继续推理。"
)
p.write_text(s)

# Remove all one-shot implementation scaffolding from the final tree.
for name in [
    ".github/workflows/implement-skills-runtime.yml",
    ".github/workflows/wire-skills-runtime.yml",
    ".github/workflows/finalize-skills-runtime.yml",
    "tools/implement_skills_runtime.py",
    "tools/finalize_skills_runtime.py",
]:
    path = Path(name)
    if path.exists():
        path.unlink()
