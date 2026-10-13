package com.adong.adchat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

class ApiRepository internal constructor(
    private val skillLoader: SkillLoader = GitHubSkillRuntime,
    private val skillBundleLoader: SkillBundleLoader = GitHubSkillBundleRuntime,
    private val nativeSkillUploader: NativeSkillUploader = NativeSkillsApi,
    private val preferNativeSkills: Boolean = false
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .applyNetworkTrust()
        .build()

    // Some OpenAI-compatible gateways terminate long HTTP/2 SSE streams with RST_STREAM CANCEL.
    // A dedicated HTTP/1.1 client avoids that transport failure and tolerates long reasoning pauses.
    private val streamingClient = client.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val paidImageDispatcher = longTaskDispatcher()

    private val imageClient = client.newBuilder()
        .dispatcher(paidImageDispatcher)
        .applyImageRequestPolicy()
        .build()

    private val mangaAnalysisClient = client.newBuilder()
        .dispatcher(paidImageDispatcher)
        .applyMangaAnalysisRequestPolicy()
        .build()

    suspend fun fetchModels(profile: ApiProfile): ConnectionResult = withContext(Dispatchers.IO) {
        validateProfile(profile)
        val url = resolveUrl(profile.baseUrl, profile.modelsPath)
        val started = System.nanoTime()
        val request = requestBuilder(profile, url).get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            ensureSuccess(response, text)
            val root = runCatching { JSONObject(text) }
                .getOrElse { throw IllegalStateException("模型接口返回的不是有效 JSON") }
            val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            val models = buildList {
                for (index in 0 until data.length()) {
                    when (val item = data.opt(index)) {
                        is JSONObject -> item.optString("id").takeIf { it.isNotBlank() }?.let { add(ApiModel(it, item.optString("owned_by"))) }
                        is String -> if (item.isNotBlank()) add(ApiModel(item))
                    }
                }
            }.distinctBy { it.id }.sortedBy { it.id.lowercase() }
            ConnectionResult(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), models, url)
        }
    }

    suspend fun streamChat(
        profile: ApiProfile,
        model: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        cacheKey: String,
        trimHistory: Boolean = true,
        skillsAllowed: Boolean = true,
        generationOptions: ChatGenerationOptions = ChatGenerationOptions(),
        searchBackend: SearchBackendConfig? = null,
        onContextTrim: suspend (Int) -> Unit = {},
        onRecovery: suspend (StreamRecoveryEvent) -> Unit = {},
        onToolActivity: suspend (ChatToolActivity) -> Unit = {},
        onReasoning: suspend (String) -> Unit = {},
        onAskUser: suspend (AskUserPrompt) -> String = { "" },
        onDelta: suspend (String) -> Unit
    ): ChatCompletionResult = withContext(Dispatchers.IO) {
        validateProfile(profile)
        require(model.isNotBlank()) { "Model is required" }
        val requestGenerationOptions = generationOptions.normalized()
        val requestedSkillUrl = if (skillsAllowed) requestedGitHubSkillUrl(history) else null
        val installed = if (skillsAllowed) skillLoader.listInstalled().filter { it.enabled } else emptyList()
        val requestedInstalledSkill = requestedInstalledSkillName(history, installed)
        val selected = if (skillsAllowed) skillLoader.selected(cacheKey) else emptyList()
        val available = (selected + installed.filter { it.name == requestedInstalledSkill || it.sourceUrl == requestedSkillUrl }.map { skillLoader.load(it.sourceUrl) }).distinctBy { it.sourceUrl }
        val requestedSkillSelectors = (listOfNotNull(requestedSkillUrl ?: requestedInstalledSkill) + available.flatMap {
            listOf(it.sha256, it.sourceUrl, it.resolvedUrl, it.name)
        }).map(String::trim).filter(String::isNotBlank).distinct()
        val requestSkillLoader = SkillSession(
            skillLoader,
            available,
            maxReadTokens = profile.contextLimits(model)?.inputTokens
        ) { loaded ->
            if (skillLoader is SkillRuntime && (requestedSkillUrl != null || requestedInstalledSkill != null)) {
                skillLoader.select(cacheKey, skillLoader.selection(cacheKey) + loaded.sourceUrl)
            }
        }
        val requireSkillLoad = requestedSkillUrl != null || requestedInstalledSkill != null
        var skillLoadingEnabled = requestedSkillSelectors.isNotEmpty()
        val delegatedSearchEnabled = profile.webSearchEnabled && !profile.usesResponses(model) && searchBackend != null
        require(!skillLoadingEnabled || !profile.webSearchEnabled || profile.usesResponses(model) || delegatedSearchEnabled) {
            "当前 Chat 服务的原生联网搜索与技能工具不能同时使用；请配置 Aster 联网搜索后端，或关闭联网搜索/Skill。"
        }
        var nativeSkillReference: NativeSkillReference? = null
        if (preferNativeSkills && requestedSkillUrl != null && profile.usesResponses(model)) {
            onToolActivity(ChatToolActivity("native_skill", LOAD_SKILL_TOOL, "正在从 GitHub 准备原生 Skill", TOOL_STATUS_RUNNING))
            try {
                val bundle = skillBundleLoader.loadBundle(requestedSkillUrl)
                nativeSkillReference = nativeSkillUploader.upload(profile, bundle)
                skillLoadingEnabled = false
                onToolActivity(ChatToolActivity(
                    "native_skill",
                    LOAD_SKILL_TOOL,
                    "已通过原生 Skills API 加载：${nativeSkillReference?.name ?: bundle.name}",
                    TOOL_STATUS_COMPLETED
                ))
            } catch (unsupported: NativeSkillsUnsupportedException) {
                skillLoadingEnabled = true
                onToolActivity(ChatToolActivity(
                    "native_skill",
                    LOAD_SKILL_TOOL,
                    "当前服务不支持原生 Skills，改用 GitHub 兼容加载",
                    TOOL_STATUS_COMPLETED
                ))
            } catch (error: Throwable) {
                onToolActivity(ChatToolActivity(
                    "native_skill",
                    LOAD_SKILL_TOOL,
                    error.message ?: "原生 Skill 加载失败",
                    TOOL_STATUS_FAILED
                ))
                throw error
            }
        }
        val askUserEnabled = skillsAllowed && BuiltInSkills.isEnabled(skillLoader)
        val toolsActive = profile.webSearchEnabled || profile.fileCreationEnabled || skillLoadingEnabled || askUserEnabled
        val initialContext = ModelContextPolicy.prepare(systemPrompt, history, profile.contextLimits(model), trimHistory)
        if (initialContext.omittedTurns > 0) onContextTrim(initialContext.omittedTurns)

        suspend fun executeAttempt(
            attemptHistory: List<ChatMessage>,
            deltaSink: suspend (String) -> Unit
        ): ChatCompletionResult {
            val prepared = ModelContextPolicy.prepare(systemPrompt, attemptHistory, profile.contextLimits(model), trimHistory = false)
            return if (profile.usesResponses(model)) {
                streamResponses(
                    profile, model, systemPrompt, prepared.history, cacheKey,
                    skillSelectors = if (skillLoadingEnabled) requestedSkillSelectors else emptyList(),
                    requestSkillLoader = requestSkillLoader, requireSkillLoad = requireSkillLoad,
                    nativeSkillReference = nativeSkillReference,
                    generationOptions = requestGenerationOptions,
                    askUserEnabled = askUserEnabled,
                    onToolActivity = onToolActivity,
                    onReasoning = onReasoning,
                    onAskUser = onAskUser,
                    onDelta = deltaSink
                )
            } else {
                streamChatCompletions(
                    profile, model, systemPrompt, prepared.history, cacheKey, explicitCache = false,
                    skillSelectors = if (skillLoadingEnabled) requestedSkillSelectors else emptyList(),
                    requestSkillLoader = requestSkillLoader, requireSkillLoad = requireSkillLoad,
                    searchBackend = searchBackend.takeIf { delegatedSearchEnabled },
                    generationOptions = requestGenerationOptions,
                    askUserEnabled = askUserEnabled,
                    onToolActivity = onToolActivity,
                    onReasoning = onReasoning,
                    onAskUser = onAskUser,
                    onDelta = deltaSink
                )
            }
        }

        suspend fun executeWithPreDeltaRetry(
            attemptHistory: List<ChatMessage>,
            deltaSink: suspend (String) -> Unit
        ): ChatCompletionResult {
            var emittedInAttempt = false
            val trackingSink: suspend (String) -> Unit = { delta ->
                emittedInAttempt = true
                deltaSink(delta)
            }
            return try {
                executeAttempt(attemptHistory, trackingSink)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                if (toolsActive || emittedInAttempt || !error.isRetryableStreamFailure()) throw error
                delay(PRE_DELTA_RETRY_DELAY_MS)
                executeAttempt(attemptHistory, deltaSink)
            }
        }

        val combined = StringBuilder()
        val initialSink: suspend (String) -> Unit = { delta ->
            combined.append(delta)
            onDelta(delta)
        }
        try {
            val result = executeWithPreDeltaRetry(initialContext.history, initialSink)
            return@withContext result.copy(text = combined.toString().ifBlank { result.text })
        } catch (initialError: Throwable) {
            if (initialError is CancellationException) throw initialError
            currentCoroutineContext().ensureActive()
            if (combined.isEmpty() && nativeSkillReference != null && initialError.isNativeSkillCompatibilityFailure()) {
                onToolActivity(ChatToolActivity(
                    "native_skill",
                    LOAD_SKILL_TOOL,
                    "当前模型不支持原生 Skill 环境，改用 GitHub 兼容加载",
                    TOOL_STATUS_COMPLETED
                ))
                nativeSkillReference = null
                skillLoadingEnabled = true
                val fallback = executeWithPreDeltaRetry(initialContext.history, initialSink)
                return@withContext fallback.copy(text = combined.toString().ifBlank { fallback.text })
            }
            if (combined.isEmpty() || toolsActive || !profile.autoResumeStream || !initialError.isRetryableStreamFailure()) throw initialError
        }

        val recoveryAttempt = 1
        onRecovery(StreamRecoveryEvent(recoveryAttempt, MAX_MID_STREAM_RECOVERY_ATTEMPTS, reconnecting = true))
        delay(MID_STREAM_RECOVERY_DELAY_MS)
        val partial = combined.toString()
        val resumeHistory = initialContext.history + listOf(
            ChatMessage(role = "assistant", content = partial),
            ChatMessage(role = "user", content = STREAM_RESUME_INSTRUCTION)
        )
        val deduplicator = ResumeDeltaDeduplicator(partial) { novel ->
            combined.append(novel)
            onDelta(novel)
        }
        var resumedConnectionAnnounced = false
        val resumedSink: suspend (String) -> Unit = { delta ->
            if (!resumedConnectionAnnounced) {
                resumedConnectionAnnounced = true
                onRecovery(StreamRecoveryEvent(recoveryAttempt, MAX_MID_STREAM_RECOVERY_ATTEMPTS, reconnecting = false))
            }
            deduplicator.accept(delta)
        }
        val resumedResult = try {
            executeWithPreDeltaRetry(resumeHistory, resumedSink)
        } catch (resumeError: Throwable) {
            if (resumeError is CancellationException) throw resumeError
            currentCoroutineContext().ensureActive()
            deduplicator.flush()
            onRecovery(StreamRecoveryEvent(recoveryAttempt, MAX_MID_STREAM_RECOVERY_ATTEMPTS, reconnecting = false))
            throw resumeError
        }
        deduplicator.flush()
        if (deduplicator.novelChars == 0) {
            throw IOException("Safe stream recovery returned no new text")
        }
        if (!resumedConnectionAnnounced) {
            onRecovery(StreamRecoveryEvent(recoveryAttempt, MAX_MID_STREAM_RECOVERY_ATTEMPTS, reconnecting = false))
        }
        resumedResult.copy(
            text = combined.toString(),
            usage = resumedResult.usage.copy(streamRecoveryCount = recoveryAttempt)
        )
    }

    private suspend fun streamChatCompletions(
        profile: ApiProfile,
        model: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        cacheKey: String,
        explicitCache: Boolean,
        skillSelectors: List<String>,
        requestSkillLoader: SkillLoader,
        requireSkillLoad: Boolean,
        searchBackend: SearchBackendConfig?,
        generationOptions: ChatGenerationOptions,
        askUserEnabled: Boolean,
        onToolActivity: suspend (ChatToolActivity) -> Unit,
        onReasoning: suspend (String) -> Unit,
        onAskUser: suspend (AskUserPrompt) -> String,
        onDelta: suspend (String) -> Unit
    ): ChatCompletionResult {
        val skillLoadingEnabled = skillSelectors.isNotEmpty()
        val delegatedSearchEnabled = profile.webSearchEnabled && searchBackend != null
        val messages = JSONArray()
        val effectiveSystemPrompt = listOf(
            systemPrompt,
            ASK_USER_INSTRUCTION.takeIf { askUserEnabled }.orEmpty(),
            SKILL_RUNTIME_INSTRUCTION.takeIf { skillLoadingEnabled }.orEmpty()
        ).filter(String::isNotBlank).joinToString("\n\n")
        if (effectiveSystemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", effectiveSystemPrompt))
        }
        val catalog = skillCatalog(requestSkillLoader.listInstalled())
        if (catalog.isNotBlank()) messages.put(JSONObject().put("role", "user").put("content", catalog))
        val stableHistory = history.filterNot { it.isError || it.isStreaming || it.isInterrupted || it.isStopped }
        stableHistory.forEachIndexed { index, message ->
            val content: Any = chatCompletionContent(
                message = message,
                explicitCacheBreakpoint = explicitCache && index == stableHistory.lastIndex
            )
            messages.put(JSONObject().put("role", message.role).put("content", content))
        }
        val started = System.nanoTime()
        var firstDeltaAt: Long? = null
        var usage = TokenUsage()
        val full = StringBuilder()
        var afterThought = false
        val reasoningTotal = StringBuilder()
        var outputComplete = false
        val generatedFiles = mutableListOf<GeneratedFileDraft>()
        val citations = linkedMapOf<String, ChatCitation>()
        val activities = linkedMapOf<String, ChatToolActivity>()

        suspend fun recordActivity(activity: ChatToolActivity) {
            activities[activity.id] = activity
            onToolActivity(activity)
        }

        suspend fun noteWebSearch(root: JSONObject) {
            when (webSearchSignal(root)) {
                WebSearchSignal.Searching -> {
                    recordActivity(ChatToolActivity("web_search", WEB_SEARCH_TOOL, "正在搜索网页", TOOL_STATUS_RUNNING))
                    if (full.isNotEmpty()) afterThought = true
                }
                WebSearchSignal.Completed -> recordActivity(ChatToolActivity("web_search", WEB_SEARCH_TOOL, "已完成网页搜索", TOOL_STATUS_COMPLETED))
                null -> Unit
            }
        }

        /** 思考内容单独累计，绝不混入正文；没有增量时保持静默避免打扰。 */
        suspend fun emitReasoning(text: String) {
            if (text.isBlank()) return
            reasoningTotal.append(text)
            onReasoning(text)
        }

        suspend fun executeRound(forceSkill: Boolean, forceNoTools: Boolean): ProtocolRoundResult {
            outputComplete = false
            val toolPolicy = resolveChatToolPolicy(profile.webSearchEnabled && !skillLoadingEnabled && !delegatedSearchEnabled, profile.fileCreationEnabled)
            val allowAskUser = askUserEnabled && !toolPolicy.webSearchEnabled
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("stream", true)
                .put("stream_options", JSONObject().put("include_usage", true))
            val tools = buildChatTools(
                toolPolicy.fileCreationEnabled,
                skillLoadingEnabled,
                skillSelectors,
                delegatedSearchEnabled = delegatedSearchEnabled,
                allowXSearch = searchBackend?.allowXSearch == true,
                askUserEnabled = allowAskUser
            )
            if (tools.length() > 0) {
                body.put("tools", tools)
                body.put("tool_choice", when {
                    forceSkill -> JSONObject().put("type", "function").put("function", JSONObject().put("name", LOAD_SKILL_TOOL))
                    forceNoTools -> "none"
                    else -> "auto"
                })
            }
            if (toolPolicy.webSearchEnabled) body.put("web_search_options", JSONObject())
            applyGptOptimizations(body, profile, model, cacheKey, responsesApi = false, explicitCache = explicitCache)
            ModelContextPolicy.applyToRequest(body, profile.contextLimits(model), responses = false)
            applyGenerationOptions(body, generationOptions, responsesApi = false, model = model)
            // 放在生成参数之后：预设可能压低 max_tokens，Claude 的思考预算必须按最终值收紧。
            applyReasoningPolicy(body, profile, model, responsesApi = false)
            val request = requestBuilder(profile, resolveUrl(profile.baseUrl, profile.chatPath))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .post(body.toString().toRequestBody(jsonMedia)).build()
            val call = streamingClient.newCall(request)
            val reasoningMark = reasoningTotal.length
            val cancellationWatcher = CoroutineScope(currentCoroutineContext()).launch {
                try { awaitCancellation() } finally { call.cancel() }
            }
            val roundText = StringBuilder()
            var roundUsage = TokenUsage()
            val roundCitations = linkedMapOf<String, ChatCitation>()
            val toolAccumulator = ChatToolCallAccumulator()
            suspend fun appendRound(delta: String) {
                if (delta.isEmpty()) return
                val piece = textAfterThought(full, delta, afterThought)
                afterThought = false
                roundText.append(piece)
                full.append(piece)
                onDelta(piece)
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) ensureSuccess(response, response.body?.string().orEmpty())
                    val contentType = response.header("Content-Type").orEmpty()
                    if (contentType.contains("text/event-stream", ignoreCase = true)) {
                        val source = response.body?.source() ?: throw IllegalStateException("Server returned an empty response")
                        var completed = false
                        readSsePayloads(source) { payload ->
                            if (payload.trim() == "[DONE]") {
                                completed = true
                                return@readSsePayloads false
                            }
                            val root = runCatching { JSONObject(payload) }.getOrNull() ?: return@readSsePayloads true
                            toolAccumulator.accept(root)
                            parseCitations(root).forEach { roundCitations[it.url] = it }
                            noteWebSearch(root)
                            val choices = root.optJSONArray("choices")
                            val choice = choices?.optJSONObject(0)
                            if (choice?.has("finish_reason") == true && !choice.isNull("finish_reason")) {
                                completed = true
                                outputComplete = choice.optString("finish_reason") == "stop"
                            }
                            if (root.has("usage") && !root.isNull("usage")) {
                                roundUsage = parseUsage(root)
                                if (choices != null && choices.length() == 0) completed = true
                            }
                            val delta = parseStreamDelta(root)
                            val reasoning = parseReasoningDelta(root)
                            if (delta.isNotEmpty()) {
                                if (firstDeltaAt == null) firstDeltaAt = System.nanoTime()
                                appendRound(delta)
                            }
                            if (reasoning.isNotBlank()) {
                                emitReasoning(reasoning)
                                if (delta.isEmpty() && full.isNotEmpty()) afterThought = true
                            }
                            true
                        }
                        if (!completed) throw IOException("Streaming connection ended before completion")
                    } else {
                        val text = response.body?.string().orEmpty()
                        if (text.isBlank()) throw IllegalStateException("Server returned an empty response")
                        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalStateException("Invalid JSON response") }
                        outputComplete = root.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason") == "stop"
                        toolAccumulator.accept(root)
                        parseCitations(root).forEach { roundCitations[it.url] = it }
                        noteWebSearch(root)
                        val result = runCatching { parseMessageContent(root) }.getOrDefault("")
                        if (result.isNotEmpty()) {
                            if (firstDeltaAt == null) firstDeltaAt = System.nanoTime()
                            appendRound(result)
                        }
                        val reasoning = parseReasoningDelta(root)
                        emitReasoning(reasoning)
                        if (reasoning.isNotBlank() && result.isEmpty() && full.isNotEmpty()) afterThought = true
                        roundUsage = parseUsage(root)
                    }
                }
            } finally {
                cancellationWatcher.cancel()
            }
            val calls = toolAccumulator.completedCalls()
            val producedReasoning = reasoningTotal.substring(reasoningMark)
            if (shouldContinueAfterReasoningOnly(roundText.toString(), calls.size, producedReasoning)) {
                return ProtocolRoundResult(
                    text = "",
                    usage = roundUsage,
                    toolCalls = calls,
                    citations = roundCitations.values.toList(),
                    reasoningOnly = true,
                    reasoningText = producedReasoning
                )
            }
            if (roundText.isBlank() && calls.isEmpty()) throw IllegalStateException("No recognizable message content or tool call in API response")
            return ProtocolRoundResult(roundText.toString(), roundUsage, calls, roundCitations.values.toList())
        }

        var completedNormally = false
        var forceNoToolsNextRound = false
        var executedToolCalls = 0
        var delegatedSearchCalls = 0
        val delegatedSearchCache = linkedMapOf<String, DelegatedSearchResult>()
        val skillToolReuseGuard = SkillToolReuseGuard()
        var reasoningContinuations = 0
        for (roundIndex in 0 until MAX_TOOL_ROUNDS) {
            skillToolReuseGuard.beginRound()
            val forceSkill = skillLoadingEnabled && requireSkillLoad && roundIndex == 0
            val forceNoTools = forceNoToolsNextRound
            forceNoToolsNextRound = false
            if (roundIndex > 0 && full.isNotEmpty()) afterThought = true
            val round = executeRound(forceSkill, forceNoTools)
            if (forceSkill && round.toolCalls.none { it.name == LOAD_SKILL_TOOL }) {
                throw IllegalStateException("模型未执行强制 load_skill 工具调用；Aster 不会伪装 Skill 已加载")
            }
            usage = usage + round.usage
            round.citations.forEach { citations[it.url] = it }
            if (round.reasoningOnly) {
                reasoningContinuations += 1
                if (reasoningContinuations > 2) {
                    val note = "思考内容过长，模型还没开始回答。可以降低思考强度后再试。"
                    val prefix = if (full.isNotEmpty() && !full.endsWith('\n')) "\n\n" else ""
                    full.append(prefix).append(note)
                    onDelta(prefix + note)
                    break
                }
                messages.put(JSONObject()
                    .put("role", "assistant")
                    .put("content", "")
                    .put("reasoning_content", round.reasoningText))
                messages.put(JSONObject().put("role", "user").put("content", REASONING_CONTINUE_PROMPT))
                continue
            }
            reasoningContinuations = 0
            if (round.toolCalls.isEmpty()) {
                completedNormally = true
                break
            }
            val orderedToolCalls = orderToolCalls(round.toolCalls)
            executedToolCalls += orderedToolCalls.size
            if (executedToolCalls > MAX_TOOL_CALLS) {
                throw IllegalStateException("工具调用已达到安全上限（${MAX_TOOL_CALLS} 次），请重试或减少需要读取的资料")
            }
            val assistantToolCalls = JSONArray()
            orderedToolCalls.forEach { call ->
                assistantToolCalls.put(JSONObject()
                    .put("id", call.callId)
                    .put("type", "function")
                    .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)))
            }
            messages.put(JSONObject()
                .put("role", "assistant")
                .put("content", round.text.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                .put("tool_calls", assistantToolCalls))
            var usedDelegatedSearchThisRound = false
            orderedToolCalls.forEach { call ->
                currentCoroutineContext().ensureActive()
                require(call.name != CREATE_FILE_TOOL || profile.fileCreationEnabled) { "当前会话未启用创建文件工具" }
                val execution = if (call.name == ASK_USER_TOOL) {
                    executeAskUser(call, onAskUser) { recordActivity(it) }
                } else if (call.name == DELEGATED_WEB_SEARCH_TOOL) {
                    usedDelegatedSearchThisRound = true
                    val backend = searchBackend ?: throw IllegalStateException("联网搜索后端尚未配置")
                    val args = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
                    val query = args.optString("query").trim()
                    val source = args.optString("source").ifBlank { "web" }.lowercase()
                    val cacheKey = source + "\n" + query.lowercase().replace(Regex("\\s+"), " ")
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
            forceNoToolsNextRound = !usedDelegatedSearchThisRound && skillToolReuseGuard.shouldForceNoToolsNextRound()
        }
        if (!completedNormally) {
            throw IllegalStateException("工具调用未在 ${MAX_TOOL_ROUNDS} 轮内完成（模型可能重复读取了同一份技能资料）")
        }
        if (activities["web_search"]?.status == TOOL_STATUS_RUNNING) {
            recordActivity(ChatToolActivity("web_search", WEB_SEARCH_TOOL, "已完成网页搜索", TOOL_STATUS_COMPLETED))
        }
        val duration = elapsedMs(started)
        val finalUsage = usage.copy(
            timeToFirstTokenMs = firstDeltaAt?.let { TimeUnit.NANOSECONDS.toMillis(it - started) },
            durationMs = duration,
            cacheRequested = PromptCache.requested(profile.promptCacheEnabled, model),
            cacheKey = cacheKey.take(64),
            cacheStrategy = PromptCache.strategy(profile.promptCacheEnabled, model, responsesApi = false)
        )
        val resultText = full.toString().ifBlank {
            generatedFiles.takeIf { it.isNotEmpty() }?.joinToString("\n") { "已创建文件：${it.name}" }
                ?: throw IllegalStateException("Streaming response contained no text")
        }
        return ChatCompletionResult(
            text = resultText,
            reasoning = reasoningTotal.toString(),
            usage = finalUsage,
            citations = citations.values.toList(),
            generatedFiles = generatedFiles,
            toolActivities = activities.values.toList(),
            outputComplete = outputComplete
        )
    }

    private suspend fun streamResponses(
        profile: ApiProfile,
        model: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        cacheKey: String,
        skillSelectors: List<String>,
        requestSkillLoader: SkillLoader,
        requireSkillLoad: Boolean,
        nativeSkillReference: NativeSkillReference?,
        generationOptions: ChatGenerationOptions,
        askUserEnabled: Boolean,
        onToolActivity: suspend (ChatToolActivity) -> Unit,
        onReasoning: suspend (String) -> Unit,
        onAskUser: suspend (AskUserPrompt) -> String,
        onDelta: suspend (String) -> Unit
    ): ChatCompletionResult {
        val skillLoadingEnabled = skillSelectors.isNotEmpty()
        val initialInput = JSONArray()
        val catalog = skillCatalog(requestSkillLoader.listInstalled())
        if (catalog.isNotBlank()) initialInput.put(JSONObject().put("role", "user").put("content", catalog))
        history.filterNot { it.isError || it.isStreaming || it.isInterrupted || it.isStopped }.forEach {
            initialInput.put(JSONObject()
                .put("role", it.role)
                .put("content", responsesMessageContent(it)))
        }
        val started = System.nanoTime()
        var firstDeltaAt: Long? = null
        var usage = TokenUsage()
        val full = StringBuilder()
        var afterThought = false
        val reasoningTotal = StringBuilder()
        var outputComplete = false
        val generatedFiles = mutableListOf<GeneratedFileDraft>()
        val citations = linkedMapOf<String, ChatCitation>()
        val activities = linkedMapOf<String, ChatToolActivity>()
        val tools = buildResponsesTools(
            profile.fileCreationEnabled,
            profile.webSearchEnabled,
            skillLoadingEnabled,
            skillSelectors,
            askUserEnabled = askUserEnabled
        ).apply {
            nativeSkillReference?.let { put(nativeSkillShellTool(it)) }
        }
        val effectiveSystemPrompt = listOf(
            systemPrompt,
            ASK_USER_INSTRUCTION.takeIf { askUserEnabled }.orEmpty(),
            SKILL_RUNTIME_INSTRUCTION.takeIf { skillLoadingEnabled }.orEmpty()
        ).filter(String::isNotBlank).joinToString("\n\n")

        suspend fun recordActivity(activity: ChatToolActivity) {
            activities[activity.id] = activity
            onToolActivity(activity)
        }

        /** 思考内容单独累计，绝不混入正文；没有增量时保持静默避免打扰。 */
        suspend fun emitReasoning(text: String) {
            if (text.isBlank()) return
            reasoningTotal.append(text)
            onReasoning(text)
        }

        var carriedContextTokens = 0L
        var lastRequestTokens = 0L
        suspend fun executeRound(
            requestInput: JSONArray,
            previousResponseId: String?,
            forceSkill: Boolean,
            forceNoTools: Boolean
        ): ProtocolRoundResult {
            outputComplete = false
            val body = JSONObject().put("model", model).put("input", requestInput).put("stream", true)
            if (tools.length() > 0) {
                body.put("tools", tools)
                body.put("tool_choice", when {
                    nativeSkillReference != null && previousResponseId == null -> JSONObject().put("type", "shell")
                    forceSkill -> JSONObject().put("type", "function").put("name", LOAD_SKILL_TOOL)
                    forceNoTools -> "none"
                    else -> "auto"
                })
            }
            if (profile.fileCreationEnabled || skillLoadingEnabled || nativeSkillReference != null) body.put("store", true)
            previousResponseId?.takeIf(String::isNotBlank)?.let { body.put("previous_response_id", it) }
            if (effectiveSystemPrompt.isNotBlank()) body.put("instructions", effectiveSystemPrompt)
            applyGptOptimizations(body, profile, model, cacheKey, responsesApi = true, explicitCache = false)
            lastRequestTokens = ModelContextPolicy.applyToRequest(body, profile.contextLimits(model), responses = true, carriedTokens = carriedContextTokens)
            applyGenerationOptions(body, generationOptions, responsesApi = true, model = model)
            applyReasoningPolicy(body, profile, model, responsesApi = true)
            val request = requestBuilder(profile, resolveUrl(profile.baseUrl, profile.responsesPath))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .post(body.toString().toRequestBody(jsonMedia)).build()
            val call = streamingClient.newCall(request)
            val cancellationWatcher = CoroutineScope(currentCoroutineContext()).launch {
                try { awaitCancellation() } finally { call.cancel() }
            }
            val roundText = StringBuilder()
            var roundUsage = TokenUsage()
            var responseId = ""
            var usedWebSearch = false
            val roundCitations = linkedMapOf<String, ChatCitation>()
            val toolAccumulator = ResponsesToolCallAccumulator()
            suspend fun appendRound(delta: String) {
                if (delta.isEmpty()) return
                val piece = textAfterThought(full, delta, afterThought)
                afterThought = false
                roundText.append(piece)
                full.append(piece)
                onDelta(piece)
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) ensureSuccess(response, response.body?.string().orEmpty())
                    val contentType = response.header("Content-Type").orEmpty()
                    if (contentType.contains("text/event-stream", ignoreCase = true)) {
                        val source = response.body?.source() ?: throw IllegalStateException("Server returned an empty response")
                        var completed = false
                        readSsePayloads(source) { payload ->
                            if (payload.trim() == "[DONE]") {
                                completed = true
                                return@readSsePayloads false
                            }
                            val root = runCatching { JSONObject(payload) }.getOrNull() ?: return@readSsePayloads true
                            toolAccumulator.accept(root)
                            parseCitations(root).forEach { roundCitations[it.url] = it }
                            when (root.optString("type")) {
                                "response.created" -> responseId = root.optJSONObject("response")?.optString("id").orEmpty()
                                "response.output_text.delta" -> {
                                    val delta = root.optString("delta")
                                    if (delta.isNotEmpty()) {
                                        if (firstDeltaAt == null) firstDeltaAt = System.nanoTime()
                                        appendRound(delta)
                                    }
                                }
                                // 思考增量：官方为 response.reasoning_summary_text.delta，
                                // 部分兼容网关用 response.reasoning_text.delta 或自定义 reasoning.*.delta。
                                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                                    val thought = root.optString("delta").ifBlank { reasoningEventText(root) }
                                    emitReasoning(thought)
                                    if (thought.isNotBlank() && full.isNotEmpty()) afterThought = true
                                }
                                "response.web_search_call.in_progress", "response.web_search_call.searching" -> {
                                    usedWebSearch = true
                                    recordActivity(ChatToolActivity("web_search", WEB_SEARCH_TOOL, "正在搜索网页", TOOL_STATUS_RUNNING))
                                    if (full.isNotEmpty()) afterThought = true
                                }
                                "response.web_search_call.completed" -> {
                                    usedWebSearch = true
                                    recordActivity(ChatToolActivity("web_search", WEB_SEARCH_TOOL, "已完成网页搜索", TOOL_STATUS_COMPLETED))
                                }
                                "response.completed" -> {
                                    val completedResponse = root.optJSONObject("response") ?: root
                                    outputComplete = completedResponse.optString("status").let { it.isBlank() || it == "completed" }
                                    responseId = completedResponse.optString("id").ifBlank { responseId }
                                    roundUsage = parseUsage(completedResponse)
                                    usedWebSearch = usedWebSearch || responseUsedWebSearch(root)
                                    completed = true
                                }
                                "response.failed", "response.incomplete" -> throw IllegalStateException(root.optJSONObject("response")?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Responses API failed" })
                                "error" -> throw IllegalStateException(root.optString("message").ifBlank { "Responses API failed" })
                                else -> {
                                    val type = root.optString("type")
                                    if (type.contains("reasoning", ignoreCase = true) && type.endsWith(".delta")) {
                                        val thought = reasoningEventText(root)
                                        emitReasoning(thought)
                                        if (thought.isNotBlank() && full.isNotEmpty()) afterThought = true
                                    }
                                }
                            }
                            !completed
                        }
                        if (!completed) throw IOException("Streaming connection ended before completion")
                    } else {
                        val text = response.body?.string().orEmpty()
                        if (text.isBlank()) throw IllegalStateException("Responses API returned an empty response")
                        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalStateException("Invalid JSON from Responses API") }
                        outputComplete = root.optString("status") == "completed"
                        toolAccumulator.acceptResponse(root)
                        parseCitations(root).forEach { roundCitations[it.url] = it }
                        responseId = root.optString("id")
                        usedWebSearch = responseUsedWebSearch(root)
                        val result = runCatching { parseResponsesText(root) }.getOrDefault("")
                        if (result.isNotEmpty()) {
                            if (firstDeltaAt == null) firstDeltaAt = System.nanoTime()
                            appendRound(result)
                        }
                        val reasoning = parseResponsesReasoning(root)
                        emitReasoning(reasoning)
                        if (reasoning.isNotBlank() && result.isEmpty() && full.isNotEmpty()) afterThought = true
                        roundUsage = parseUsage(root)
                    }
                }
            } finally {
                cancellationWatcher.cancel()
            }
            val calls = toolAccumulator.completedCalls()
            if (roundText.isBlank() && calls.isEmpty()) throw IllegalStateException("Responses API returned no text or tool call")
            return ProtocolRoundResult(roundText.toString(), roundUsage, calls, roundCitations.values.toList(), responseId, usedWebSearch)
        }

        var requestInput = initialInput
        var previousResponseId: String? = null
        var completedNormally = false
        var forceNoToolsNextRound = false
        var executedToolCalls = 0
        val skillToolReuseGuard = SkillToolReuseGuard()
        for (toolRound in 0 until MAX_TOOL_ROUNDS) {
            skillToolReuseGuard.beginRound()
            val forceSkill = skillLoadingEnabled && requireSkillLoad && toolRound == 0
            val forceNoTools = forceNoToolsNextRound
            forceNoToolsNextRound = false
            if (toolRound > 0 && full.isNotEmpty()) afterThought = true
            val round = executeRound(requestInput, previousResponseId, forceSkill, forceNoTools)
            if (forceSkill && round.toolCalls.none { it.name == LOAD_SKILL_TOOL }) {
                throw IllegalStateException("模型未执行强制 load_skill 工具调用；Aster 不会伪装 Skill 已加载")
            }
            usage = usage + round.usage
            round.citations.forEach { citations[it.url] = it }
            if (round.usedWebSearch) recordActivity(ChatToolActivity("web_search", WEB_SEARCH_TOOL, "已完成网页搜索", TOOL_STATUS_COMPLETED))
            if (round.toolCalls.isEmpty()) {
                completedNormally = true
                break
            }
            val orderedToolCalls = orderToolCalls(round.toolCalls)
            executedToolCalls += orderedToolCalls.size
            if (executedToolCalls > MAX_TOOL_CALLS) {
                throw IllegalStateException("工具调用已达到安全上限（${MAX_TOOL_CALLS} 次），请重试或减少需要读取的资料")
            }
            if (round.responseId.isBlank()) throw IllegalStateException("Responses API 未返回 response id，无法提交工具结果")
            carriedContextTokens = maxOf(lastRequestTokens, round.usage.inputTokens.toLong()) + maxOf(
                round.usage.outputTokens.toLong(), ContextTokenEstimate.text(round.text).toLong() + orderedToolCalls.sumOf { ContextTokenEstimate.text(it.arguments).toLong() + 32 })
            previousResponseId = round.responseId
            requestInput = JSONArray()
            orderedToolCalls.forEach { call ->
                currentCoroutineContext().ensureActive()
                require(call.name != CREATE_FILE_TOOL || profile.fileCreationEnabled) { "当前会话未启用创建文件工具" }
                if (call.name == ASK_USER_TOOL) {
                    val execution = executeAskUser(call, onAskUser) { recordActivity(it) }
                    requestInput.put(JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", call.callId)
                        .put("output", execution.output))
                    return@forEach
                }
                val runningLabel = when (call.name) { LOAD_SKILL_TOOL -> "正在读取技能说明"; READ_SKILL_FILE_TOOL -> "正在读取技能资料"; else -> "正在创建文件" }
                recordActivity(ChatToolActivity(call.callId, call.name, runningLabel, TOOL_STATUS_RUNNING))
                val execution = skillToolReuseGuard.execute(call, requestSkillLoader, skillSelectors.toSet())
                execution.generatedFile?.let(generatedFiles::add)
                recordActivity(execution.activity)
                if (call.name == LOAD_SKILL_TOOL && !runCatching { JSONObject(execution.output).optBoolean("ok") }.getOrDefault(false)) {
                    throw IllegalStateException(runCatching { JSONObject(execution.output).optString("error") }.getOrDefault("Skill 加载失败"))
                }
                requestInput.put(JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", call.callId)
                    .put("output", execution.output))
            }
            forceNoToolsNextRound = skillToolReuseGuard.shouldForceNoToolsNextRound()
        }
        if (!completedNormally) {
            throw IllegalStateException("工具调用未在 ${MAX_TOOL_ROUNDS} 轮内完成（模型可能重复读取了同一份技能资料）")
        }
        if (profile.webSearchEnabled && activities["web_search"]?.status == TOOL_STATUS_RUNNING) {
            recordActivity(ChatToolActivity("web_search", WEB_SEARCH_TOOL, "已完成网页搜索", TOOL_STATUS_COMPLETED))
        }
        val finalUsage = usage.copy(
            timeToFirstTokenMs = firstDeltaAt?.let { TimeUnit.NANOSECONDS.toMillis(it - started) },
            durationMs = elapsedMs(started),
            cacheRequested = PromptCache.requested(profile.promptCacheEnabled, model),
            cacheKey = cacheKey.take(64),
            cacheStrategy = PromptCache.strategy(profile.promptCacheEnabled, model, responsesApi = true)
        )
        val resultText = full.toString().ifBlank {
            generatedFiles.takeIf { it.isNotEmpty() }?.joinToString("\n") { "已创建文件：${it.name}" }
                ?: throw IllegalStateException("Responses API returned no text")
        }
        return ChatCompletionResult(
            text = resultText,
            reasoning = reasoningTotal.toString(),
            usage = finalUsage,
            citations = citations.values.toList(),
            generatedFiles = generatedFiles,
            toolActivities = activities.values.toList(),
            outputComplete = outputComplete
        )
    }

    private suspend fun executeAskUser(
        call: PendingToolCall,
        onAskUser: suspend (AskUserPrompt) -> String,
        recordActivity: suspend (ChatToolActivity) -> Unit
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
        val rawOptions = args.optJSONArray("options")
        val options = if (rawOptions == null) emptyList() else (0 until rawOptions.length()).map { rawOptions.optString(it) }
        val prompt = runCatching { normalizeAskUserPrompt(args.optString("question"), options) }.getOrElse { error ->
            val failed = ChatToolActivity(call.callId, ASK_USER_TOOL, error.message ?: "无法发起提问", TOOL_STATUS_FAILED)
            recordActivity(failed)
            return ToolExecutionResult(
                JSONObject().put("ok", false).put("error", error.message ?: "无法发起提问").toString(),
                activity = failed
            )
        }
        recordActivity(ChatToolActivity(call.callId, ASK_USER_TOOL, "正在等你选择", TOOL_STATUS_RUNNING))
        val answer = onAskUser(prompt).trim()
        val activity = if (answer.isBlank()) {
            ChatToolActivity(call.callId, ASK_USER_TOOL, "没有收到选择", TOOL_STATUS_FAILED)
        } else {
            ChatToolActivity(call.callId, ASK_USER_TOOL, "已收到你的选择", TOOL_STATUS_COMPLETED)
        }
        recordActivity(activity)
        val output = if (answer.isBlank()) {
            JSONObject().put("ok", false).put("error", "empty_answer").toString()
        } else {
            JSONObject().put("ok", true).put("question", prompt.question).put("answer", answer).toString()
        }
        return ToolExecutionResult(output, activity = activity)
    }

    suspend fun summarizeConversationTitle(profile: ApiProfile, model: String, transcript: String): String =
        withContext(Dispatchers.IO) {
            validateProfile(profile)
            require(model.isNotBlank()) { "Model is required" }
            val quietEffort = ReasoningPolicy.choices(model).firstOrNull { it.id != ReasoningPolicy.DEFAULT }?.id
                ?: profile.reasoningEffort
            val quietProfile = profile.copy(reasoningEffort = quietEffort)
            val instruction = "根据对话写一个不超过16个字的中文标题。只输出标题本身，不要引号、句号或解释。"
            val clipped = transcript.take(4000)
            val responsesApi = quietProfile.usesResponses(model)
            val body = if (responsesApi) {
                JSONObject().put("model", model).put("instructions", instruction).put("input", clipped).put("max_output_tokens", 2048)
            } else {
                JSONObject().put("model", model).put("max_tokens", 2048).put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", instruction))
                    .put(JSONObject().put("role", "user").put("content", clipped)))
            }
            applyReasoningPolicy(body, quietProfile, model, responsesApi)
            val path = if (responsesApi) quietProfile.responsesPath else quietProfile.chatPath
            val request = requestBuilder(quietProfile, resolveUrl(quietProfile.baseUrl, path))
                .post(body.toString().toRequestBody(jsonMedia)).build()
            val raw = executeTextCall(client.newCall(request))
            val root = runCatching { JSONObject(raw) }.getOrElse { throw IllegalStateException("标题接口返回的不是有效 JSON") }
            val text = if (responsesApi) parseResponsesText(root) else parseMessageContent(root)
            sanitizeConversationTitle(text).ifBlank { throw IllegalStateException("标题为空") }
        }

    suspend fun completePlain(profile: ApiProfile, model: String, instruction: String, user: String): String =
        withContext(Dispatchers.IO) {
            validateProfile(profile)
            require(model.isNotBlank()) { "Model is required" }
            val quietEffort = ReasoningPolicy.choices(model).firstOrNull { it.id == "low" || it.id == "none" || it.id == "off" }?.id
                ?: ReasoningPolicy.DEFAULT
            val quietProfile = profile.copy(reasoningEffort = quietEffort)
            val responsesApi = quietProfile.usesResponses(model)
            val body = if (responsesApi) {
                JSONObject().put("model", model).put("instructions", instruction).put("input", user).put("max_output_tokens", 4096)
            } else {
                JSONObject().put("model", model).put("max_tokens", 4096).put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", instruction))
                    .put(JSONObject().put("role", "user").put("content", user)))
            }
            applyReasoningPolicy(body, quietProfile, model, responsesApi)
            val path = if (responsesApi) quietProfile.responsesPath else quietProfile.chatPath
            val request = requestBuilder(quietProfile, resolveUrl(quietProfile.baseUrl, path))
                .post(body.toString().toRequestBody(jsonMedia)).build()
            val raw = executeTextCall(client.newCall(request))
            val root = runCatching { JSONObject(raw) }.getOrElse { throw IllegalStateException("设定接口返回的不是有效 JSON") }
            if (responsesApi) parseResponsesText(root) else parseMessageContent(root)
        }

    private suspend fun executeDelegatedSearch(
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
        val input = """You are Aster's retrieval worker, not the final-answer assistant. Use the required server-side search tool to research the query below. Return concise factual research notes. Preserve important dates, names, versions, numbers, disagreements, and uncertainty. Prefer primary/official sources where available. Do not assume access to the user's full conversation.

SEARCH QUERY:
$query"""
        val anthropic = backend.profile.apiFormat == API_FORMAT_ANTHROPIC
        if ((anthropic || backend.model.isStepModel()) && normalizedSource == "x") {
            throw IllegalStateException("当前搜索后端不支持 X Search")
        }
        if (backend.model.isStepModel()) {
            val searchRequest = requestBuilder(backend.profile, resolveUrl(backend.profile.baseUrl, "/v1/search"))
                .post(stepSearchRequest(query).toString().toRequestBody(jsonMedia))
                .build()
            val searchRaw = executeTextCall(client.newCall(searchRequest))
            val searchRoot = runCatching { JSONObject(searchRaw) }.getOrElse { throw IllegalStateException("Step 搜索返回的不是有效 JSON") }
            val (evidence, sources) = parseStepSearch(searchRoot)
            require(sources.isNotEmpty()) { "Step 搜索没有返回结果" }
            val research = runCatching {
                val summaryCall = requestBuilder(backend.profile, resolveUrl(backend.profile.baseUrl, backend.profile.messagesPath))
                if (backend.profile.apiKey.isNotBlank()) {
                    summaryCall.header("x-api-key", backend.profile.apiKey.trim())
                    summaryCall.header("anthropic-version", "2023-06-01")
                }
                val summaryRequest = summaryCall
                    .post(stepResearchRequest(backend.model, query, evidence).toString().toRequestBody(jsonMedia))
                    .build()
                val summaryRaw = executeTextCall(client.newCall(summaryRequest))
                val summaryRoot = JSONObject(summaryRaw)
                parseAnthropicSearch(summaryRoot).first
            }.getOrDefault("")
            return@runCatching DelegatedSearchResult(
                output = delegatedSearchToolOutput(query, normalizedSource, backend.model, research.ifBlank { evidence }, sources),
                citations = sources,
                activity = ChatToolActivity(callId, DELEGATED_WEB_SEARCH_TOOL,
                    "已通过 ${backend.model} 搜索 · ${sources.size} 个来源", TOOL_STATUS_COMPLETED)
            )
        }
        val body = if (anthropic) {
            anthropicWebSearchRequest(backend.model, input)
        } else {
            JSONObject()
                .put("model", backend.model)
                .put("input", input)
                .put("tools", JSONArray().put(JSONObject().put("type", toolType)))
                .apply {
                    if (normalizedSource == "web") put("include", JSONArray().put("web_search_call.action.sources"))
                }
        }
        val path = if (anthropic) backend.profile.messagesPath else backend.profile.responsesPath
        val searchCall = requestBuilder(backend.profile, resolveUrl(backend.profile.baseUrl, path))
        if (anthropic && backend.profile.apiKey.isNotBlank()) {
            searchCall.header("x-api-key", backend.profile.apiKey.trim())
            searchCall.header("anthropic-version", "2023-06-01")
        }
        val request = searchCall.post(body.toString().toRequestBody(jsonMedia)).build()
        val raw = executeTextCall(client.newCall(request))
        val root = runCatching { JSONObject(raw) }.getOrElse { throw IllegalStateException("联网搜索后端返回的不是有效 JSON") }
        val research: String
        val sources: List<ChatCitation>
        if (anthropic) {
            require(anthropicSearchPerformed(root)) { "联网搜索后端返回了响应，但未实际执行 Web Search" }
            val parsed = parseAnthropicSearch(root)
            research = parsed.first
            sources = parsed.second
        } else {
            require(responseUsedDelegatedSearch(root, normalizedSource)) {
                "联网搜索后端返回了响应，但未实际执行 ${if (normalizedSource == "x") "X Search" else "Web Search"}"
            }
            research = parseResponsesText(root)
            sources = parseServerSideSearchSources(root)
        }
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
        message: ChatMessage,
        explicitCacheBreakpoint: Boolean
    ): Any {
        if (message.attachments.isEmpty() && !explicitCacheBreakpoint) return message.content
        val content = JSONArray()
        if (message.content.isNotBlank() || message.attachments.isEmpty()) {
            content.put(JSONObject()
                .put("type", "text")
                .put("text", message.content)
                .apply {
                    if (explicitCacheBreakpoint) {
                        put("prompt_cache_breakpoint", JSONObject().put("mode", "explicit"))
                    }
                })
        }
        message.attachments.forEach { attachment ->
            content.put(JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", attachmentDataUrl(attachment))))
        }
        return content
    }

    private fun responsesMessageContent(message: ChatMessage): Any {
        if (message.attachments.isEmpty()) return message.content
        val content = JSONArray()
        if (message.content.isNotBlank()) {
            content.put(JSONObject().put("type", "input_text").put("text", message.content))
        }
        message.attachments.forEach { attachment ->
            content.put(JSONObject()
                .put("type", "input_image")
                .put("image_url", attachmentDataUrl(attachment)))
        }
        return content
    }

    private fun attachmentDataUrl(attachment: ChatImageAttachment): String {
        val bytes = attachment.bytes ?: throw IllegalStateException("无法读取对话图片：${attachment.name}")
        val mime = attachment.mimeType.ifBlank { "image/jpeg" }
        return "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    private fun applyGptOptimizations(
        body: JSONObject,
        profile: ApiProfile,
        model: String,
        cacheKey: String,
        responsesApi: Boolean,
        explicitCache: Boolean
    ) {
        if (!profile.promptCacheEnabled) return
        PromptCache.apply(body, enabled = true, model = model, cacheKey = cacheKey, responsesApi = responsesApi)
        if (explicitCache && !responsesApi && model.isGpt56Family()) {
            body.put("prompt_cache_options", JSONObject().put("mode", "explicit").put("ttl", "30m"))
        }
    }

    /**
     * 思考强度按模型家族写入：GPT/Grok 用 effort，Claude 用 thinking.budget_tokens，
     * GLM/Kimi/DeepSeek 用 thinking 开关加各自 effort。放在上下文上限与生成预设都写好之后调用，
     * Claude 的思考预算必须严格小于最终生效的 max_tokens。
     */
    private fun applyReasoningPolicy(
        body: JSONObject,
        profile: ApiProfile,
        model: String,
        responsesApi: Boolean
    ) {
        ReasoningPolicy.write(
            body = body,
            model = model,
            effort = profile.reasoningEffort,
            responsesApi = responsesApi,
            outputTokenLimit = body.optInt(if (responsesApi) "max_output_tokens" else "max_tokens", 0)
                .takeIf { body.has(if (responsesApi) "max_output_tokens" else "max_tokens") && it > 0 }
                ?: profile.contextLimits(model)?.outputTokens
                ?: 0
        )
    }

    /** Apply the portable part of a Tavern generation preset without overriding Aster's context safety cap. */
    private fun applyGenerationOptions(
        body: JSONObject,
        source: ChatGenerationOptions,
        responsesApi: Boolean,
        model: String
    ) {
        val options = source.normalized()
        // GPT reasoning models reject sampling controls on several Responses-compatible gateways.
        if (!responsesApi || !model.isGpt56Family()) {
            options.temperature?.let { body.put("temperature", it) }
            options.topP?.let { body.put("top_p", it) }
        }
        if (!responsesApi) {
            // xAI 的推理模型直接拒绝 presence_penalty / frequency_penalty / stop，带回就会 400。
            if (!ReasoningPolicy.forbidsSamplingControls(model)) {
                options.frequencyPenalty?.let { body.put("frequency_penalty", it) }
                options.presencePenalty?.let { body.put("presence_penalty", it) }
                options.seed?.let { body.put("seed", it) }
            }
        }
        options.maxOutputTokens?.let { requested ->
            val key = if (responsesApi) "max_output_tokens" else "max_tokens"
            val contextMaximum = body.optInt(key, 0).takeIf { body.has(key) && it > 0 }
            body.put(key, contextMaximum?.let { minOf(it, requested) } ?: requested)
        }
    }

    private fun Throwable.isNativeSkillCompatibilityFailure(): Boolean {
        val value = generateSequence(this) { it.cause }.joinToString(" ") { it.message.orEmpty() }.lowercase()
        if (!(value.contains("skill") || value.contains("shell") || value.contains("container"))) return false
        return listOf(
            "unsupported", "not supported", "does not support", "unknown tool",
            "invalid tool", "not available", "not allowed", "unrecognized",
            "unsupported parameter", "invalid_request_error"
        ).any(value::contains)
    }

    private fun Throwable.isCacheCompatibilityError(): Boolean {
        val value = message.orEmpty().lowercase()
        return value.contains("prompt_cache") || value.contains("cache breakpoint") ||
            value.contains("unsupported parameter") || value.contains("http 404") ||
            value.contains("接口不存在")
    }

    private suspend fun readSsePayloads(
        source: BufferedSource,
        onPayload: suspend (String) -> Boolean
    ) {
        val pending = StringBuilder()
        suspend fun flushPending(): Boolean {
            if (pending.isEmpty()) return true
            val payload = pending.toString()
            pending.clear()
            return onPayload(payload)
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> if (!flushPending()) return
                line.startsWith(":") -> Unit // SSE heartbeat/comment
                line.startsWith("data:") -> {
                    if (pending.isNotEmpty() && pending.isStandaloneSsePayload()) {
                        if (!flushPending()) return
                    }
                    if (pending.isNotEmpty()) pending.append('\n')
                    pending.append(line.substring(5).removePrefix(" "))
                    if (pending.length > SSE_MAX_EVENT_CHARS) {
                        throw IOException("SSE event exceeded the safety limit")
                    }
                }
                line.trimStart().startsWith("{") || line.trim() == "[DONE]" -> {
                    if (!flushPending()) return
                    if (!onPayload(line.trim())) return
                }
            }
        }
        flushPending()
    }

    private fun StringBuilder.isStandaloneSsePayload(): Boolean {
        val value = toString().trim()
        return value == "[DONE]" || runCatching { JSONObject(value) }.isSuccess
    }

    private fun Throwable.isRetryableStreamFailure(): Boolean {
        val causes = generateSequence(this) { it.cause }.toList()
        if (causes.any { it is CancellationException || it is SSLException }) return false
        if (causes.any { it is EOFException || it is ProtocolException || it is SocketTimeoutException }) return true
        val value = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return value.contains("stream was reset") || value.contains("unexpected end of stream") ||
            value.contains("connection reset") || value.contains("connection closed") ||
            value.contains("socket closed") || value.contains("connection shutdown") ||
            value.contains("broken pipe") || value.contains("refused_stream") ||
            value.contains("protocol_error") || value.contains("ended before completion") ||
            value.contains("premature eof") || value.contains("software caused connection abort") ||
            value.contains("timed out") || value.contains("timeout")
    }

    suspend fun generateImage(
        profile: ApiProfile,
        model: String,
        prompt: String,
        size: String,
        references: List<ReferenceImageInput> = emptyList(),
        requestKey: String = ""
    ): List<String> = withContext(Dispatchers.IO) {
        validateProfile(profile)
        require(model.isNotBlank()) { "Model is required" }
        if (profile.resolvedImageApiMode(model) == IMAGE_API_MODE_GEMINI) {
            return@withContext generateGeminiImage(profile, model, prompt, size, references, requestKey)
        }
        val responseText = if (references.isEmpty()) {
            val body = JSONObject().put("model", model).put("prompt", prompt).put("n", 1).put("size", size)
            val request = requestBuilder(profile, resolveUrl(profile.baseUrl, profile.imagePath))
                .applyIdempotencyKey(requestKey)
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            executeTextCall(imageClient.newCall(request))
        } else {
            val multipart = buildImageEditMultipart(model, prompt, size, references)
            val request = requestBuilder(profile, resolveUrl(profile.baseUrl, profile.imageEditPath))
                .applyIdempotencyKey(requestKey)
                .post(multipart)
                .build()
            executeTextCall(imageClient.newCall(request))
        }
        val data = JSONObject(responseText).optJSONArray("data") ?: throw IllegalStateException("API 返回中没有 data 数组")
        buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val url = item.optString("url")
                val b64 = item.optString("b64_json")
                when { url.isNotBlank() -> add(url); b64.isNotBlank() -> add("data:image/png;base64,$b64") }
            }
        }.ifEmpty { throw IllegalStateException("API 没有返回图片地址或图片数据") }
    }

    private suspend fun generateGeminiImage(
        profile: ApiProfile,
        model: String,
        prompt: String,
        size: String,
        references: List<ReferenceImageInput>,
        requestKey: String
    ): List<String> {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt)).apply {
            references.forEach { reference ->
                val dataUrl = "data:${reference.mimeType.ifBlank { "image/png" }};base64,${Base64.getEncoder().encodeToString(reference.bytes)}"
                put(JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl).put("detail", "high")))
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", if (references.isEmpty()) prompt else content)))
            .put("modalities", JSONArray().put("text").put("image"))
            .put("stream", true)
            .put("size", size)
        val request = requestBuilder(profile, resolveUrl(profile.baseUrl, profile.chatPath))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .applyIdempotencyKey(requestKey)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return executeGeminiImageCall(imageClient.newCall(request))
    }

    private suspend fun executeGeminiImageCall(call: Call): List<String> {
        val cancellationWatcher = CoroutineScope(currentCoroutineContext()).launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) ensureSuccess(response, response.body?.string().orEmpty())
                val body = response.body ?: throw IllegalStateException("Gemini 图片接口返回了空响应")
                if (response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)) {
                    val collector = GeminiImageResponseCollector()
                    readSsePayloads(body.source(), collector::accept)
                    collector.result()
                } else {
                    parseGeminiImageResponse(JSONObject(body.string()))
                }
            }
        } finally {
            cancellationWatcher.cancel()
        }
    }

    suspend fun analyzeMangaTranslation(
        profile: ApiProfile,
        model: String,
        target: MangaTranslationTarget,
        pages: List<ReferenceImageInput>,
        requestKey: String
    ): MangaTranslationAnalysis = withContext(Dispatchers.IO) {
        validateProfile(profile)
        require(model.isNotBlank()) { "辅助模型不能为空" }
        require(pages.isNotEmpty()) { "漫画分析至少需要一张图片" }
        val analysisPrompt = MangaTranslationAnalysisPrompt.build(target, pages.size)
        val pageData = pages.map { page ->
            "data:${page.mimeType.ifBlank { "image/png" }};base64,${Base64.getEncoder().encodeToString(page.bytes)}"
        }
        val body = if (profile.usesResponses(model)) {
            JSONObject()
                .put("model", model)
                .put("stream", true)
                .put("instructions", analysisPrompt)
                .put("input", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().apply {
                        put(JSONObject().put("type", "input_text").put("text", "以下图片按上传顺序编号为第 1 页到第 ${pages.size} 页。请严格按要求返回逐页 JSON。"))
                        pageData.forEach { data ->
                            put(JSONObject().put("type", "input_image").put("image_url", data).put("detail", "high"))
                        }
                    })))
        } else {
            JSONObject()
                .put("model", model)
                .put("stream", true)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", analysisPrompt))
                    .put(JSONObject().put("role", "user").put("content", JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", "以下图片按上传顺序编号为第 1 页到第 ${pages.size} 页。请严格按要求返回逐页 JSON。"))
                        pageData.forEach { data ->
                            put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", data).put("detail", "high")))
                        }
                    })))
        }
        val path = if (profile.usesResponses(model)) profile.responsesPath else profile.chatPath
        val request = requestBuilder(profile, resolveUrl(profile.baseUrl, path))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .applyIdempotencyKey(requestKey)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        val text = executeMangaAnalysisCall(
            call = mangaAnalysisClient.newCall(request),
            responsesApi = profile.usesResponses(model)
        )
        parseMangaTranslationAnalysis(text, pages.size)
    }

    private suspend fun executeMangaAnalysisCall(call: Call, responsesApi: Boolean): String {
        val cancellationWatcher = CoroutineScope(currentCoroutineContext()).launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) ensureSuccess(response, response.body?.string().orEmpty())
                val body = response.body ?: throw IllegalStateException("漫画辅助模型返回了空响应")
                if (response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)) {
                    val collector = MangaAnalysisStreamCollector(responsesApi)
                    readSsePayloads(body.source(), collector::accept)
                    collector.requireResult()
                } else {
                    val text = body.string()
                    val root = runCatching { JSONObject(text) }
                        .getOrElse { throw IllegalStateException("辅助模型返回的不是有效 JSON 响应") }
                    if (responsesApi) parseResponsesText(root) else parseMessageContent(root)
                }
            }
        } finally {
            cancellationWatcher.cancel()
        }
    }

    private suspend fun executeTextCall(call: Call): String {
        val cancellationWatcher = CoroutineScope(currentCoroutineContext()).launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        return try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                ensureSuccess(response, text)
                if (text.isBlank()) throw IllegalStateException("服务器返回了空响应")
                text
            }
        } finally {
            cancellationWatcher.cancel()
        }
    }

    private fun parseStreamDelta(root: JSONObject): String = runCatching {
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val content = choice?.optJSONObject("delta")?.opt("content")
        when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) append(content.optJSONObject(i)?.optString("text").orEmpty())
            }
            else -> choice?.optString("text").orEmpty()
        }
    }.getOrDefault("")

    /**
     * 推理模型的思考增量。各服务端字段不一：DeepSeek 系用 reasoning_content，
     * 部分网关用 reasoning（字符串或含 content/text 的对象），非流式响应放在 message 上。
     */
    private fun parseReasoningDelta(root: JSONObject): String = runCatching {
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return@runCatching ""
        val delta = choice.optJSONObject("delta")
        val fields = listOfNotNull(delta, choice.optJSONObject("message"))
        val raw = fields.firstNotNullOfOrNull { source ->
            when (val value = source.opt("reasoning_content") ?: source.opt("reasoning")) {
                is String -> value
                is JSONObject -> value.optString("content").ifBlank { value.optString("text") }
                else -> null
            }
        }.orEmpty()
        raw
    }.getOrDefault("")

    /** 兼容网关把思考文本放在 text 字段或 delta 对象内。 */
    private fun reasoningEventText(root: JSONObject): String {
        root.optString("delta").takeIf { it.isNotBlank() }?.let { return it }
        root.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        return root.optJSONObject("delta")?.optString("text").orEmpty()
    }

    /** 非流式 Responses：从 output 里 type=reasoning 的 item 提取思考摘要。 */
    private fun parseResponsesReasoning(root: JSONObject): String {
        val output = root.optJSONArray("output") ?: return ""
        return buildString {
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                if (!item.optString("type").equals("reasoning", ignoreCase = true)) continue
                item.optJSONArray("summary")?.let { summary ->
                    for (i in 0 until summary.length()) append(summary.optJSONObject(i)?.optString("text").orEmpty())
                }
                item.optJSONArray("content")?.let { content ->
                    for (i in 0 until content.length()) append(content.optJSONObject(i)?.optString("text").orEmpty())
                }
            }
        }.toString()
    }

    private fun parseMessageContent(root: JSONObject): String {
        val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        val parsed = when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) append(content.optJSONObject(i)?.optString("text").orEmpty())
            }
            else -> ""
        }
        return parsed.takeIf { it.isNotBlank() }
            ?: root.optString("response").takeIf { it.isNotBlank() }
            ?: root.optString("content").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No recognizable message content in API response")
    }

    private fun parseResponsesText(root: JSONObject): String {
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = root.optJSONArray("output") ?: JSONArray()
        return buildString {
            for (i in 0 until output.length()) {
                val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) append(content.optJSONObject(j)?.optString("text").orEmpty())
            }
        }.ifBlank { throw IllegalStateException("No output_text in Responses API response") }
    }

    private fun parseUsage(root: JSONObject): TokenUsage {
        val usage = root.optJSONObject("usage") ?: root.optJSONObject("response")?.optJSONObject("usage") ?: return TokenUsage()
        val inputDetails = usage.optJSONObject("prompt_tokens_details") ?: usage.optJSONObject("input_tokens_details")
        val outputDetails = usage.optJSONObject("completion_tokens_details") ?: usage.optJSONObject("output_tokens_details")
        val input = usage.optInt("prompt_tokens").takeIf { it > 0 } ?: usage.optInt("input_tokens")
        val output = usage.optInt("completion_tokens").takeIf { it > 0 } ?: usage.optInt("output_tokens")
        val cached = maxOf(inputDetails?.optInt("cached_tokens") ?: 0, usage.optInt("cache_read_input_tokens"), usage.optInt("cached_tokens"))
        val cacheWrite = maxOf(
            inputDetails?.optInt("cache_creation_tokens") ?: 0,
            inputDetails?.optInt("cache_write_tokens") ?: 0,
            usage.optInt("cache_creation_input_tokens"),
            usage.optInt("cache_write_input_tokens"),
            usage.optInt("claude_cache_creation_5_m_tokens"),
            usage.optInt("claude_cache_creation_1_h_tokens")
        )
        val cacheMetricsReported = inputDetails?.has("cached_tokens") == true ||
            inputDetails?.has("cache_creation_tokens") == true || inputDetails?.has("cache_write_tokens") == true ||
            usage.has("cache_read_input_tokens") || usage.has("cached_tokens") ||
            usage.has("cache_creation_input_tokens") || usage.has("cache_write_input_tokens") ||
            usage.has("claude_cache_creation_5_m_tokens") || usage.has("claude_cache_creation_1_h_tokens")
        val reasoning = maxOf(outputDetails?.optInt("reasoning_tokens") ?: 0, usage.optInt("reasoning_tokens"))
        val total = usage.optInt("total_tokens").takeIf { it > 0 } ?: input + output
        return TokenUsage(
            inputTokens = input,
            cachedTokens = cached.coerceAtMost(input),
            cacheWriteTokens = cacheWrite,
            outputTokens = output,
            reasoningTokens = reasoning,
            totalTokens = total,
            cacheMetricsReported = cacheMetricsReported,
            providerUsageReported = listOf("prompt_tokens", "input_tokens").any { usage.opt(it) is Number && usage.optDouble(it, -1.0) >= 0 } &&
                listOf("completion_tokens", "output_tokens").any { usage.opt(it) is Number && usage.optDouble(it, -1.0) >= 0 }
        )
    }

    private fun elapsedMs(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun ensureSuccess(response: Response, text: String) {
        if (response.isSuccessful) return
        val apiMessage = runCatching {
            val root = JSONObject(text)
            root.optJSONObject("error")?.optString("message") ?: root.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val hint = when (response.code) {
            401, 403 -> "认证失败，请检查此 API 配置的 Key 或请求头"
            404 -> "接口不存在，请检查此 API 配置的 URL 和路径"
            408, 504, 524 -> "供应商网关主动结束了长请求"
            429 -> "请求过于频繁或额度不足"
            in 500..599 -> "服务端暂时不可用"
            else -> "请求失败"
        }
        throw IllegalStateException("$hint（HTTP ${response.code}）${apiMessage?.let { "：$it" }.orEmpty()}")
    }

    private fun validateProfile(profile: ApiProfile) {
        require(profile.baseUrl.startsWith("http://") || profile.baseUrl.startsWith("https://")) { "Base URL 必须以 http:// 或 https:// 开头" }
    }

    private fun resolveUrl(baseUrl: String, path: String): String {
        require(path.isNotBlank()) { "接口路径不能为空" }
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = baseUrl.trim().trimEnd('/')
        val relative = path.trim().trimStart('/')
        return if (base.endsWith("/v1", ignoreCase = true) && relative.startsWith("v1/", ignoreCase = true)) {
            "$base/${relative.substring(3)}"
        } else {
            "$base/$relative"
        }
    }

    private fun requestBuilder(profile: ApiProfile, url: String): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (profile.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${profile.apiKey.trim()}")
        profile.extraHeaders.lineSequence().forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) {
                val name = line.substring(0, index).trim(); val value = line.substring(index + 1).trim()
                if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
            }
        }
        return builder
    }

    private fun Request.Builder.applyIdempotencyKey(requestKey: String): Request.Builder = apply {
        requestKey.trim().takeIf { it.isNotBlank() }?.let { key ->
            header("Idempotency-Key", key.take(128))
        }
    }

    private class ResumeDeltaDeduplicator(
        existing: String,
        private val emitNovel: suspend (String) -> Unit
    ) {
        private val existingTail = existing.takeLast(RESUME_OVERLAP_WINDOW_CHARS)
        private val pending = StringBuilder()
        private var resolved = false
        var novelChars: Int = 0
            private set

        suspend fun accept(delta: String) {
            if (delta.isEmpty()) return
            if (resolved) {
                novelChars += delta.length
                emitNovel(delta)
                return
            }
            pending.append(delta)
            if (pending.length < RESUME_MIN_PROBE_CHARS) return
            val overlap = longestSuffixPrefix(existingTail, pending)
            if (overlap < pending.length || pending.length >= RESUME_MAX_PROBE_CHARS) {
                resolve(overlap)
            }
        }

        suspend fun flush() {
            if (!resolved) resolve(longestSuffixPrefix(existingTail, pending))
        }

        private suspend fun resolve(overlap: Int) {
            if (resolved) return
            val novel = pending.substring(overlap.coerceIn(0, pending.length))
            pending.clear()
            resolved = true
            if (novel.isNotEmpty()) {
                novelChars += novel.length
                emitNovel(novel)
            }
        }

        private fun longestSuffixPrefix(existing: String, candidate: CharSequence): Int {
            val maxLength = minOf(existing.length, candidate.length)
            for (length in maxLength downTo 1) {
                var matches = true
                for (index in 0 until length) {
                    if (existing[existing.length - length + index] != candidate[index]) {
                        matches = false
                        break
                    }
                }
                if (matches) return length
            }
            return 0
        }
    }

    private companion object {
        const val MAX_MID_STREAM_RECOVERY_ATTEMPTS = 1
        const val PRE_DELTA_RETRY_DELAY_MS = 800L
        const val MID_STREAM_RECOVERY_DELAY_MS = 900L
        const val RESUME_MIN_PROBE_CHARS = 96
        const val RESUME_MAX_PROBE_CHARS = 1_200
        const val RESUME_OVERLAP_WINDOW_CHARS = 2_400
        const val SSE_MAX_EVENT_CHARS = 4 * 1024 * 1024
        const val STREAM_RESUME_INSTRUCTION = "[ADCHAT_STREAM_RESUME]\n\u4e0a\u4e00\u6b21\u6d41\u5f0f\u4f20\u8f93\u5728\u6b64\u5904\u4e2d\u65ad\u3002\u8bf7\u53ea\u4ece\u5df2\u8f93\u51fa\u5185\u5bb9\u7684\u6700\u540e\u4e00\u4e2a\u8bed\u4e49\u4f4d\u7f6e\u7ee7\u7eed\uff0c\u4e0d\u5f97\u91cd\u590d\u4efb\u4f55\u5df2\u8f93\u51fa\u6587\u672c\uff0c\u4e0d\u8981\u89e3\u91ca\u4e2d\u65ad\u539f\u56e0\u3002"
    }
}

internal fun buildImageEditMultipart(
    model: String,
    prompt: String,
    size: String,
    references: List<ReferenceImageInput>
): MultipartBody {
    require(references.isNotEmpty()) { "At least one reference image is required" }
    val imageField = if (references.size == 1) "image" else "image[]"
    return MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("model", model)
        .addFormDataPart("prompt", prompt)
        .addFormDataPart("size", size)
        .addFormDataPart("n", "1")
        .apply {
            references.forEach { reference ->
                val mediaType = runCatching { reference.mimeType.toMediaType() }
                    .getOrElse { "image/png".toMediaType() }
                addFormDataPart(
                    imageField,
                    reference.fileName,
                    reference.bytes.toRequestBody(mediaType)
                )
            }
        }
        .build()
}

private fun String.isGpt56Family(): Boolean {
    val value = lowercase()
    return value.contains("gpt-5.6") || value.contains("gpt-5_6")
}
