from pathlib import Path

ROOT = Path('.')

def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'anchor not found in {path}: {old[:100]!r}')
    if text.count(old) != 1:
        raise SystemExit(f'anchor not unique in {path}: {text.count(old)}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

# --- Models.kt: persistent search route/model configuration ---
path = 'app/src/main/java/com/adong/adchat/data/Models.kt'
replace_once(path,
'''    val mangaAnalysisModel: String = "",
    val extraHeaders: String = "",''',
'''    val mangaAnalysisModel: String = "",
    val searchModel: String = "",
    val extraHeaders: String = "",''')
replace_once(path,
'''    mangaAnalysisModel = mangaAnalysisModel.trim(),
    extraHeaders = extraHeaders.lineSequence()''',
'''    mangaAnalysisModel = mangaAnalysisModel.trim(),
    searchModel = searchModel.trim(),
    extraHeaders = extraHeaders.lineSequence()''')
replace_once(path,
'''    val activeMangaAnalysisProfileId: String = activeChatProfileId,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT''',
'''    val activeMangaAnalysisProfileId: String = activeChatProfileId,
    val activeSearchProfileId: String = activeChatProfileId,
    val allowXSearch: Boolean = false,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT''')
replace_once(path,
'''    fun mangaAnalysisProfile(): ApiProfile = profiles.firstOrNull { it.id == activeMangaAnalysisProfileId } ?: chatProfile()
}''',
'''    fun mangaAnalysisProfile(): ApiProfile = profiles.firstOrNull { it.id == activeMangaAnalysisProfileId } ?: chatProfile()
    fun searchProfile(): ApiProfile = profiles.firstOrNull { it.id == activeSearchProfileId } ?: chatProfile()
}''')
replace_once(path,
'''            activeMangaAnalysisProfileId = profile.id,
            systemPrompt = migrateSystemPrompt''',
'''            activeMangaAnalysisProfileId = profile.id,
            activeSearchProfileId = profile.id,
            allowXSearch = false,
            systemPrompt = migrateSystemPrompt''')
replace_once(path,
'''        .put("activeMangaAnalysisProfileId", config.activeMangaAnalysisProfileId)
        .put("systemPrompt", config.systemPrompt)''',
'''        .put("activeMangaAnalysisProfileId", config.activeMangaAnalysisProfileId)
        .put("activeSearchProfileId", config.activeSearchProfileId)
        .put("allowXSearch", config.allowXSearch)
        .put("systemPrompt", config.systemPrompt)''')
replace_once(path,
'''                    .put("mangaAnalysisModel", profile.mangaAnalysisModel)
                    .put("extraHeaders", profile.extraHeaders)''',
'''                    .put("mangaAnalysisModel", profile.mangaAnalysisModel)
                    .put("searchModel", profile.searchModel)
                    .put("extraHeaders", profile.extraHeaders)''')
replace_once(path,
'''                    mangaAnalysisModel = item.optString("mangaAnalysisModel"),
                    extraHeaders = item.optString("extraHeaders"),''',
'''                    mangaAnalysisModel = item.optString("mangaAnalysisModel"),
                    searchModel = item.optString("searchModel"),
                    extraHeaders = item.optString("extraHeaders"),''')
replace_once(path,
'''            activeMangaAnalysisProfileId = root.optString("activeMangaAnalysisProfileId")
                .takeIf { id -> profiles.any { it.id == id } }
                ?: root.optString("activeChatProfileId").takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.first().id,
            systemPrompt = migrateSystemPrompt''',
'''            activeMangaAnalysisProfileId = root.optString("activeMangaAnalysisProfileId")
                .takeIf { id -> profiles.any { it.id == id } }
                ?: root.optString("activeChatProfileId").takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.first().id,
            activeSearchProfileId = root.optString("activeSearchProfileId")
                .takeIf { id -> profiles.any { it.id == id } }
                ?: root.optString("activeChatProfileId").takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.first().id,
            allowXSearch = root.optBoolean("allowXSearch", false),
            systemPrompt = migrateSystemPrompt''')

# --- DelegatedSearch.kt: protocol types and source parsing ---
delegated = r'''package com.adong.adchat.data

import org.json.JSONArray
import org.json.JSONObject

const val DELEGATED_WEB_SEARCH_TOOL = "search_web"
const val MAX_DELEGATED_SEARCH_CALLS = 4

data class SearchBackendConfig(
    val profile: ApiProfile,
    val model: String,
    val allowXSearch: Boolean = false
)

data class DelegatedSearchResult(
    val output: String,
    val citations: List<ChatCitation>,
    val activity: ChatToolActivity
)

internal fun delegatedSearchDefinition(allowXSearch: Boolean): JSONObject {
    val sourceValues = mutableListOf("web")
    if (allowXSearch) sourceValues += "x"
    return JSONObject()
        .put("name", DELEGATED_WEB_SEARCH_TOOL)
        .put("description", "Search current internet information through Aster's configured search backend. Use this when the answer depends on recent, changing, external, or source-verifiable information. The backend performs real server-side search. Do not invent search results. Prefer one focused query; make another call only when materially different evidence is needed.")
        .put("parameters", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("query", JSONObject()
                    .put("type", "string")
                    .put("description", "A self-contained search query. Include names, dates, versions, or constraints needed to research the user's question without the full chat history."))
                .put("source", JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(sourceValues))
                    .put("description", if (allowXSearch) "Use web for normal internet research or x for public X posts." else "Internet source; only web is enabled.")))
            .put("required", JSONArray(listOf("query")))
            .put("additionalProperties", false))
}

internal fun parseServerSideSearchSources(root: JSONObject): List<ChatCitation> {
    val result = linkedMapOf<String, ChatCitation>()
    parseCitations(root).forEach { result[it.url] = it }
    val response = root.optJSONObject("response") ?: root
    val output = response.optJSONArray("output") ?: return result.values.toList()
    for (index in 0 until output.length()) {
        val item = output.optJSONObject(index) ?: continue
        if (item.optString("type") !in setOf("web_search_call", "x_search_call")) continue
        val action = item.optJSONObject("action") ?: continue
        val sources = action.optJSONArray("sources") ?: continue
        for (sourceIndex in 0 until sources.length()) {
            val source = sources.optJSONObject(sourceIndex) ?: continue
            val url = source.optString("url").trim()
            if (url.isBlank()) continue
            result[url] = ChatCitation(source.optString("title").ifBlank { url }, url)
        }
    }
    return result.values.toList()
}

internal fun delegatedSearchToolOutput(
    query: String,
    source: String,
    backendModel: String,
    research: String,
    citations: List<ChatCitation>,
    reused: Boolean = false
): String = JSONObject()
    .put("ok", true)
    .put("query", query)
    .put("source", source)
    .put("backend_model", backendModel)
    .put("reused", reused)
    .put("research", research)
    .put("sources", JSONArray().apply {
        citations.forEach { citation ->
            put(JSONObject().put("title", citation.title).put("url", citation.url))
        }
    })
    .put("instruction", "Use the research above as tool evidence. Cite or describe only claims supported by it. The search backend is not the final-answer model.")
    .toString()
'''
(ROOT / 'app/src/main/java/com/adong/adchat/data/DelegatedSearch.kt').write_text(delegated, encoding='utf-8')

# --- ToolProtocol.kt: expose search_web alongside normal Chat tools ---
path = 'app/src/main/java/com/adong/adchat/data/ToolProtocol.kt'
replace_once(path,
'''internal fun buildChatTools(
    fileCreationEnabled: Boolean,
    skillLoadingEnabled: Boolean = false,
    skillSelectors: List<String> = emptyList()
): JSONArray = JSONArray().apply {''',
'''internal fun buildChatTools(
    fileCreationEnabled: Boolean,
    skillLoadingEnabled: Boolean = false,
    skillSelectors: List<String> = emptyList(),
    delegatedSearchEnabled: Boolean = false,
    allowXSearch: Boolean = false
): JSONArray = JSONArray().apply {''')
replace_once(path,
'''    if (skillLoadingEnabled) {
        put(JSONObject()
            .put("type", "function")
            .put("function", loadSkillDefinition(responsesApi = false, skillSelectors = skillSelectors)))
        put(JSONObject().put("type", "function").put("function", readSkillDefinition(false, skillSelectors)))
    }
}''',
'''    if (skillLoadingEnabled) {
        put(JSONObject()
            .put("type", "function")
            .put("function", loadSkillDefinition(responsesApi = false, skillSelectors = skillSelectors)))
        put(JSONObject().put("type", "function").put("function", readSkillDefinition(false, skillSelectors)))
    }
    if (delegatedSearchEnabled) {
        put(JSONObject().put("type", "function").put("function", delegatedSearchDefinition(allowXSearch)))
    }
}''')

# --- ApiRepository.kt: route Chat search calls through configured Grok Responses backend ---
path = 'app/src/main/java/com/adong/adchat/data/ApiRepository.kt'
replace_once(path,
'''        trimHistory: Boolean = true,
        skillsAllowed: Boolean = true,
        onContextTrim:''',
'''        trimHistory: Boolean = true,
        skillsAllowed: Boolean = true,
        searchBackend: SearchBackendConfig? = null,
        onContextTrim:''')
replace_once(path,
'''        val requireSkillLoad = requestedSkillUrl != null || requestedInstalledSkill != null
        var skillLoadingEnabled = requestedSkillSelectors.isNotEmpty()
        require(!skillLoadingEnabled || !profile.webSearchEnabled || profile.usesResponses(model)) {
            "当前 Chat 服务的联网搜索与技能工具不能同时使用，请关闭联网搜索或取消本对话的技能选择。"
        }''',
'''        val requireSkillLoad = requestedSkillUrl != null || requestedInstalledSkill != null
        var skillLoadingEnabled = requestedSkillSelectors.isNotEmpty()
        val delegatedSearchEnabled = profile.webSearchEnabled && !profile.usesResponses(model) && searchBackend != null
        require(!skillLoadingEnabled || !profile.webSearchEnabled || profile.usesResponses(model) || delegatedSearchEnabled) {
            "当前 Chat 服务的原生联网搜索与技能工具不能同时使用；请配置 Aster 联网搜索后端，或关闭联网搜索/Skill。"
        }''')
replace_once(path,
'''                    requestSkillLoader = requestSkillLoader, requireSkillLoad = requireSkillLoad,
                    onToolActivity = onToolActivity,''',
'''                    requestSkillLoader = requestSkillLoader, requireSkillLoad = requireSkillLoad,
                    searchBackend = searchBackend.takeIf { delegatedSearchEnabled },
                    onToolActivity = onToolActivity,''')
replace_once(path,
'''        requestSkillLoader: SkillLoader,
        requireSkillLoad: Boolean,
        onToolActivity: suspend (ChatToolActivity) -> Unit,
        onDelta: suspend (String) -> Unit
    ): ChatCompletionResult {
        val skillLoadingEnabled = skillSelectors.isNotEmpty()''',
'''        requestSkillLoader: SkillLoader,
        requireSkillLoad: Boolean,
        searchBackend: SearchBackendConfig?,
        onToolActivity: suspend (ChatToolActivity) -> Unit,
        onDelta: suspend (String) -> Unit
    ): ChatCompletionResult {
        val skillLoadingEnabled = skillSelectors.isNotEmpty()
        val delegatedSearchEnabled = profile.webSearchEnabled && searchBackend != null''')
replace_once(path,
'''            val toolPolicy = resolveChatToolPolicy(profile.webSearchEnabled && !skillLoadingEnabled, profile.fileCreationEnabled)''',
'''            val toolPolicy = resolveChatToolPolicy(profile.webSearchEnabled && !skillLoadingEnabled && !delegatedSearchEnabled, profile.fileCreationEnabled)''')
replace_once(path,
'''            val tools = buildChatTools(toolPolicy.fileCreationEnabled, skillLoadingEnabled, skillSelectors)''',
'''            val tools = buildChatTools(
                toolPolicy.fileCreationEnabled,
                skillLoadingEnabled,
                skillSelectors,
                delegatedSearchEnabled = delegatedSearchEnabled,
                allowXSearch = searchBackend?.allowXSearch == true
            )''')
replace_once(path,
'''        var completedNormally = false
        var forceNoToolsNextRound = false
        var executedToolCalls = 0
        val skillToolReuseGuard = SkillToolReuseGuard()''',
'''        var completedNormally = false
        var forceNoToolsNextRound = false
        var executedToolCalls = 0
        var delegatedSearchCalls = 0
        val delegatedSearchCache = linkedMapOf<String, DelegatedSearchResult>()
        val skillToolReuseGuard = SkillToolReuseGuard()''')
replace_once(path,
'''            orderedToolCalls.forEach { call ->
                currentCoroutineContext().ensureActive()
                require(call.name != CREATE_FILE_TOOL || profile.fileCreationEnabled) { "当前会话未启用创建文件工具" }
                val runningLabel = when (call.name) { LOAD_SKILL_TOOL -> "正在读取技能说明"; READ_SKILL_FILE_TOOL -> "正在读取技能资料"; else -> "正在创建文件" }
                recordActivity(ChatToolActivity(call.callId, call.name, runningLabel, TOOL_STATUS_RUNNING))
                val execution = skillToolReuseGuard.execute(call, requestSkillLoader, skillSelectors.toSet())
                execution.generatedFile?.let(generatedFiles::add)
                recordActivity(execution.activity)
                if (call.name == LOAD_SKILL_TOOL && !runCatching { JSONObject(execution.output).optBoolean("ok") }.getOrDefault(false)) {
                    throw IllegalStateException(runCatching { JSONObject(execution.output).optString("error") }.getOrDefault("Skill 加载失败"))
                }
                messages.put(JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", call.callId)
                    .put("content", execution.output))
            }
            forceNoToolsNextRound = skillToolReuseGuard.shouldForceNoToolsNextRound()''',
'''            var usedDelegatedSearchThisRound = false
            orderedToolCalls.forEach { call ->
                currentCoroutineContext().ensureActive()
                require(call.name != CREATE_FILE_TOOL || profile.fileCreationEnabled) { "当前会话未启用创建文件工具" }
                val execution = if (call.name == DELEGATED_WEB_SEARCH_TOOL) {
                    usedDelegatedSearchThisRound = true
                    val backend = searchBackend ?: throw IllegalStateException("联网搜索后端尚未配置")
                    val args = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
                    val query = args.optString("query").trim()
                    val source = args.optString("source").ifBlank { "web" }.lowercase()
                    val cacheKey = source + "\\n" + query.lowercase().replace(Regex("\\s+"), " ")
                    val cached = delegatedSearchCache[cacheKey]
                    val search = when {
                        query.isBlank() -> DelegatedSearchResult(
                            JSONObject().put("ok", false).put("error", "search_query_empty").toString(),
                            emptyList(), ChatToolActivity(call.callId, call.name, "搜索查询为空", TOOL_STATUS_FAILED)
                        )
                        cached != null -> cached.copy(
                            output = delegatedSearchToolOutput(query, source, backend.model, JSONObject(cached.output).optString("research"), cached.citations, reused = true),
                            activity = ChatToolActivity(call.callId, call.name, "已复用联网搜索结果", TOOL_STATUS_COMPLETED)
                        )
                        delegatedSearchCalls >= MAX_DELEGATED_SEARCH_CALLS -> DelegatedSearchResult(
                            JSONObject().put("ok", false).put("error", "search_call_limit_reached").put("limit", MAX_DELEGATED_SEARCH_CALLS).toString(),
                            emptyList(), ChatToolActivity(call.callId, call.name, "联网搜索已达到本轮上限", TOOL_STATUS_FAILED)
                        )
                        else -> {
                            delegatedSearchCalls += 1
                            recordActivity(ChatToolActivity(call.callId, call.name, "正在通过 ${backend.model} 搜索", TOOL_STATUS_RUNNING))
                            executeDelegatedSearch(backend, call.callId, query, source)
                        }
                    }
                    if (cached == null && runCatching { JSONObject(search.output).optBoolean("ok") }.getOrDefault(false)) {
                        delegatedSearchCache[cacheKey] = search
                    }
                    search.citations.forEach { citations[it.url] = it }
                    ToolExecutionResult(search.output, activity = search.activity)
                } else {
                    val runningLabel = when (call.name) { LOAD_SKILL_TOOL -> "正在读取技能说明"; READ_SKILL_FILE_TOOL -> "正在读取技能资料"; else -> "正在创建文件" }
                    recordActivity(ChatToolActivity(call.callId, call.name, runningLabel, TOOL_STATUS_RUNNING))
                    skillToolReuseGuard.execute(call, requestSkillLoader, skillSelectors.toSet())
                }
                execution.generatedFile?.let(generatedFiles::add)
                recordActivity(execution.activity)
                if (call.name == LOAD_SKILL_TOOL && !runCatching { JSONObject(execution.output).optBoolean("ok") }.getOrDefault(false)) {
                    throw IllegalStateException(runCatching { JSONObject(execution.output).optString("error") }.getOrDefault("Skill 加载失败"))
                }
                messages.put(JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", call.callId)
                    .put("content", execution.output))
            }
            forceNoToolsNextRound = !usedDelegatedSearchThisRound && skillToolReuseGuard.shouldForceNoToolsNextRound()''')

# Insert non-stream Grok Responses search executor before message-content helpers.
replace_once(path,
'''    private fun chatCompletionContent(
        message: ChatMessage,''',
'''    private suspend fun executeDelegatedSearch(
        backend: SearchBackendConfig,
        callId: String,
        query: String,
        source: String
    ): DelegatedSearchResult = runCatching {
        validateProfile(backend.profile)
        require(backend.model.isNotBlank()) { "联网搜索模型不能为空" }
        val normalizedSource = when (source.lowercase()) {
            "x" -> {
                require(backend.allowXSearch) { "当前未启用 X Search" }
                "x"
            }
            else -> "web"
        }
        val toolType = if (normalizedSource == "x") "x_search" else "web_search"
        val input = """You are Aster's retrieval worker, not the final-answer assistant. Use the required server-side search tool to research the query below. Return concise factual research notes. Preserve important dates, names, versions, numbers, disagreements, and uncertainty. Prefer primary/official sources where available. Do not assume access to the user's full conversation.\n\nSEARCH QUERY:\n$query"""
        val body = JSONObject()
            .put("model", backend.model)
            .put("input", input)
            .put("tools", JSONArray().put(JSONObject().put("type", toolType)))
        if (normalizedSource == "web") {
            body.put("include", JSONArray().put("web_search_call.action.sources"))
        }
        val request = requestBuilder(backend.profile, resolveUrl(backend.profile.baseUrl, backend.profile.responsesPath))
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        val raw = executeTextCall(client.newCall(request))
        val root = runCatching { JSONObject(raw) }.getOrElse { throw IllegalStateException("联网搜索后端返回的不是有效 JSON") }
        val research = parseResponsesText(root)
        val sources = parseServerSideSearchSources(root)
        DelegatedSearchResult(
            output = delegatedSearchToolOutput(query, normalizedSource, backend.model, research, sources),
            citations = sources,
            activity = ChatToolActivity(callId, DELEGATED_WEB_SEARCH_TOOL,
                if (sources.isEmpty()) "已通过 ${backend.model} 完成搜索" else "已通过 ${backend.model} 搜索 · ${sources.size} 个来源",
                TOOL_STATUS_COMPLETED)
        )
    }.getOrElse { error ->
        DelegatedSearchResult(
            output = JSONObject()
                .put("ok", false)
                .put("error", "search_backend_unavailable")
                .put("message", error.message ?: "联网搜索失败")
                .toString(),
            citations = emptyList(),
            activity = ChatToolActivity(callId, DELEGATED_WEB_SEARCH_TOOL, error.message ?: "联网搜索失败", TOOL_STATUS_FAILED)
        )
    }

    private fun chatCompletionContent(
        message: ChatMessage,''')

# --- MainViewModel.kt: resolve/persist route and pass it to repository ---
path = 'app/src/main/java/com/adong/adchat/ui/MainViewModel.kt'
replace_once(path,
'''    val imageProfile: ApiProfile get() = appConfig.imageProfile()
    val mangaAnalysisProfile: ApiProfile''',
'''    val imageProfile: ApiProfile get() = appConfig.imageProfile()
    val searchProfile: ApiProfile get() = appConfig.searchProfile()
    val allowXSearch: Boolean get() = appConfig.allowXSearch
    private fun searchBackendConfig(): SearchBackendConfig? {
        val profile = appConfig.searchProfile()
        val model = profile.searchModel.trim()
        return model.takeIf(String::isNotBlank)?.let { SearchBackendConfig(profile, it, appConfig.allowXSearch) }
    }
    val mangaAnalysisProfile: ApiProfile''')
replace_once(path,
'''            activeMangaAnalysisProfileId = if (appConfig.activeMangaAnalysisProfileId == profileId) fallbackProfile.id else appConfig.activeMangaAnalysisProfileId
        )''',
'''            activeMangaAnalysisProfileId = if (appConfig.activeMangaAnalysisProfileId == profileId) fallbackProfile.id else appConfig.activeMangaAnalysisProfileId,
            activeSearchProfileId = if (appConfig.activeSearchProfileId == profileId) fallbackProfile.id else appConfig.activeSearchProfileId
        )''')
replace_once(path,
'''    fun selectImageProfile(profileId: String) {
        if (profiles.none { it.id == profileId }) return
        appConfig = appConfig.copy(activeImageProfileId = profileId)
        persist(); notice = "绘图已切换到 ${imageProfile.name}"
    }
''',
'''    fun selectImageProfile(profileId: String) {
        if (profiles.none { it.id == profileId }) return
        appConfig = appConfig.copy(activeImageProfileId = profileId)
        persist(); notice = "绘图已切换到 ${imageProfile.name}"
    }

    fun selectSearchProfile(profileId: String) {
        val profile = profiles.firstOrNull { it.id == profileId } ?: return
        appConfig = appConfig.copy(activeSearchProfileId = profileId)
        persist(); notice = "联网搜索后端已切换到 ${profile.name}"
    }

    fun selectSearchModel(profileId: String, model: String) {
        val selected = model.trim()
        if (selected.isBlank()) return
        updateProfile(profileId) { it.copy(searchModel = selected) }
        appConfig = appConfig.copy(activeSearchProfileId = profileId)
        persist(); notice = "联网搜索模型已设置为 $selected"
    }

    fun setAllowXSearch(enabled: Boolean) {
        appConfig = appConfig.copy(allowXSearch = enabled)
        persist(); notice = if (enabled) "已允许联网后端使用 X Search" else "已关闭 X Search"
    }
''')
replace_once(path,
'''        if (mangaAnalysisProfile.id != chatProfile.id && mangaAnalysisProfile.id != imageProfile.id) testProfile(mangaAnalysisProfile)
    }''',
'''        if (mangaAnalysisProfile.id != chatProfile.id && mangaAnalysisProfile.id != imageProfile.id) testProfile(mangaAnalysisProfile)
        if (searchProfile.searchModel.isNotBlank() && searchProfile.id !in setOf(chatProfile.id, imageProfile.id, mangaAnalysisProfile.id)) testProfile(searchProfile)
    }''')
replace_once(path,
'''                        .put("mangaAnalysisModel", profile.mangaAnalysisModel)
                        .put("extraHeaders", profile.extraHeaders)''',
'''                        .put("mangaAnalysisModel", profile.mangaAnalysisModel)
                        .put("searchModel", profile.searchModel)
                        .put("extraHeaders", profile.extraHeaders)''')
replace_once(path,
'''                    mangaAnalysisModel = item.optString("mangaAnalysisModel"),
                    extraHeaders = item.optString("extraHeaders")''',
'''                    mangaAnalysisModel = item.optString("mangaAnalysisModel"),
                    searchModel = item.optString("searchModel"),
                    extraHeaders = item.optString("extraHeaders")''')
replace_once(path,
'''                    cacheKey = "adchat-${activeConversationId ?: profile.id}",
                    onContextTrim =''',
'''                    cacheKey = "adchat-${activeConversationId ?: profile.id}",
                    searchBackend = searchBackendConfig(),
                    onContextTrim =''')

# --- Tests ---
test = r'''package com.adong.adchat.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegatedSearchTest {
    @Test
    fun delegatedDefinitionExposesXOnlyWhenEnabled() {
        val webOnly = delegatedSearchDefinition(false)
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("source").getJSONArray("enum")
        assertEquals(listOf("web"), webOnly.toStringList())

        val withX = delegatedSearchDefinition(true)
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("source").getJSONArray("enum")
        assertEquals(listOf("web", "x"), withX.toStringList())
    }

    @Test
    fun parsesServerSideSourcesAndFinalCitations() {
        val root = JSONObject()
            .put("output", JSONArray()
                .put(JSONObject()
                    .put("type", "web_search_call")
                    .put("action", JSONObject().put("sources", JSONArray()
                        .put(JSONObject().put("title", "Primary").put("url", "https://example.com/primary")))))
                .put(JSONObject()
                    .put("type", "message")
                    .put("content", JSONArray().put(JSONObject()
                        .put("type", "output_text")
                        .put("text", "answer")
                        .put("annotations", JSONArray().put(JSONObject()
                            .put("type", "url_citation")
                            .put("url", "https://example.com/cited")
                            .put("title", "Cited")))))))
        val citations = parseServerSideSearchSources(root)
        assertEquals(setOf("https://example.com/primary", "https://example.com/cited"), citations.map { it.url }.toSet())
    }

    @Test
    fun chatToolsCanMixSkillsFilesAndDelegatedSearch() {
        val tools = buildChatTools(
            fileCreationEnabled = true,
            skillLoadingEnabled = true,
            skillSelectors = listOf("demo-skill"),
            delegatedSearchEnabled = true,
            allowXSearch = true
        )
        val names = (0 until tools.length()).mapNotNull { index ->
            tools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
        }
        assertTrue(CREATE_FILE_TOOL in names)
        assertTrue(LOAD_SKILL_TOOL in names)
        assertTrue(READ_SKILL_FILE_TOOL in names)
        assertTrue(DELEGATED_WEB_SEARCH_TOOL in names)
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map(::getString)
}
'''
(ROOT / 'app/src/test/java/com/adong/adchat/data/DelegatedSearchTest.kt').write_text(test, encoding='utf-8')

# Documentation for the first implementation milestone.
doc = '''# Delegated Search Backend\n\nAster can delegate real-time search for Chat Completions models to a separately selected Responses model. The current implementation is designed for Grok-compatible gateways verified to support `web_search` and `x_search`.\n\n- Responses chat models keep their native `web_search` path.\n- Chat models receive an Aster `search_web` function when a search backend is configured.\n- Only the self-contained search query is sent to the search backend, not the full conversation.\n- Search results are returned to the original model as tool output; the original model remains the final-answer model.\n- Server-side sources and URL citations are preserved as `ChatCitation`.\n- Identical searches are reused within one user turn, and delegated search is capped at four real backend requests per turn.\n- Skills, file creation, and delegated search can coexist in Chat tool calls.\n\nConfiguration adds `activeSearchProfileId`, per-profile `searchModel`, and `allowXSearch`. Old configs migrate with search disabled until a search model is selected.\n'''
(ROOT / 'docs/DELEGATED_SEARCH.md').write_text(doc, encoding='utf-8')

print('Applied Grok delegated search backend core migration')
