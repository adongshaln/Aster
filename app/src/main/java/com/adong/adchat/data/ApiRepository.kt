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

class ApiRepository {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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
        onRecovery: suspend (StreamRecoveryEvent) -> Unit = {},
        onDelta: suspend (String) -> Unit
    ): ChatCompletionResult = withContext(Dispatchers.IO) {
        validateProfile(profile)
        require(model.isNotBlank()) { "Model is required" }

        suspend fun executeAttempt(
            attemptHistory: List<ChatMessage>,
            deltaSink: suspend (String) -> Unit
        ): ChatCompletionResult {
            val adaptiveCache = profile.promptCacheEnabled && model.isGpt56Family() && profile.promptCacheMode != "compatibility"
            return if (adaptiveCache) {
                try {
                    streamChatCompletions(profile, model, systemPrompt, attemptHistory, cacheKey, explicitCache = true, onDelta = deltaSink)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    currentCoroutineContext().ensureActive()
                    if (!error.isCacheCompatibilityError()) throw error
                    if (profile.chatApiMode == "responses") {
                        streamResponses(profile, model, systemPrompt, attemptHistory, cacheKey, deltaSink)
                    } else {
                        streamChatCompletions(profile, model, systemPrompt, attemptHistory, cacheKey, explicitCache = false, onDelta = deltaSink)
                    }
                }
            } else if (profile.chatApiMode == "responses") {
                streamResponses(profile, model, systemPrompt, attemptHistory, cacheKey, deltaSink)
            } else {
                streamChatCompletions(profile, model, systemPrompt, attemptHistory, cacheKey, explicitCache = false, onDelta = deltaSink)
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
                if (emittedInAttempt || !error.isRetryableStreamFailure()) throw error
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
            val result = executeWithPreDeltaRetry(history, initialSink)
            return@withContext result.copy(text = combined.toString().ifBlank { result.text })
        } catch (initialError: Throwable) {
            if (initialError is CancellationException) throw initialError
            currentCoroutineContext().ensureActive()
            if (combined.isEmpty() || !profile.autoResumeStream || !initialError.isRetryableStreamFailure()) throw initialError
        }

        val recoveryAttempt = 1
        onRecovery(StreamRecoveryEvent(recoveryAttempt, MAX_MID_STREAM_RECOVERY_ATTEMPTS, reconnecting = true))
        delay(MID_STREAM_RECOVERY_DELAY_MS)
        val partial = combined.toString()
        val resumeHistory = history + listOf(
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
        onDelta: suspend (String) -> Unit
    ): ChatCompletionResult {
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        val stableHistory = history.filterNot { it.isError || it.isStreaming || it.isInterrupted || it.isStopped }
        stableHistory.forEachIndexed { index, message ->
            val content: Any = if (explicitCache && index == stableHistory.lastIndex) {
                JSONArray().put(JSONObject()
                    .put("type", "text")
                    .put("text", message.content)
                    .put("prompt_cache_breakpoint", JSONObject().put("mode", "explicit")))
            } else {
                message.content
            }
            messages.put(JSONObject().put("role", message.role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
        applyGptOptimizations(body, profile, model, cacheKey, responsesApi = false, explicitCache = explicitCache)
        val request = requestBuilder(profile, resolveUrl(profile.baseUrl, profile.chatPath))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .post(body.toString().toRequestBody(jsonMedia)).build()
        val started = System.nanoTime()
        var firstDeltaAt: Long? = null
        var usage = TokenUsage()
        val full = StringBuilder()

        val call = streamingClient.newCall(request)
        val cancellationWatcher = CoroutineScope(currentCoroutineContext()).launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
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
                    val choices = root.optJSONArray("choices")
                    val choice = choices?.optJSONObject(0)
                    if (choice?.has("finish_reason") == true && !choice.isNull("finish_reason")) completed = true
                    if (root.has("usage") && !root.isNull("usage")) {
                        usage = parseUsage(root)
                        if (choices != null && choices.length() == 0) completed = true
                    }
                    val delta = parseStreamDelta(root)
                    if (delta.isNotEmpty()) {
                        if (firstDeltaAt == null) firstDeltaAt = System.nanoTime()
                        full.append(delta)
                        onDelta(delta)
                    }
                    true
                }
                if (!completed) throw IOException("Streaming connection ended before completion")
            } else {
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) throw IllegalStateException("Server returned an empty response")
                val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalStateException("Invalid JSON response") }
                val result = parseMessageContent(root)
                firstDeltaAt = System.nanoTime()
                full.append(result); onDelta(result)
                usage = parseUsage(root)
                }
            }
        } finally {
            cancellationWatcher.cancel()
        }
        val duration = elapsedMs(started)
        val finalUsage = usage.copy(
            timeToFirstTokenMs = firstDeltaAt?.let { TimeUnit.NANOSECONDS.toMillis(it - started) },
            durationMs = duration,
            cacheRequested = profile.promptCacheEnabled && model.isGpt56Family(),
            cacheKey = cacheKey.take(64),
            cacheStrategy = when {
                !profile.promptCacheEnabled || !model.isGpt56Family() -> "off"
                explicitCache -> "explicit-chat"
                else -> "automatic"
            }
        )
        return ChatCompletionResult(full.toString().ifBlank { throw IllegalStateException("Streaming response contained no text") }, finalUsage)
    }

    private suspend fun streamResponses(
        profile: ApiProfile,
        model: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        cacheKey: String,
        onDelta: suspend (String) -> Unit
    ): ChatCompletionResult {
        val input = JSONArray()
        history.filterNot { it.isError || it.isStreaming || it.isInterrupted || it.isStopped }.forEach {
            input.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        val body = JSONObject().put("model", model).put("input", input).put("stream", true)
        if (systemPrompt.isNotBlank()) body.put("instructions", systemPrompt)
        applyGptOptimizations(body, profile, model, cacheKey, responsesApi = true, explicitCache = false)
        val request = requestBuilder(profile, resolveUrl(profile.baseUrl, profile.responsesPath))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .post(body.toString().toRequestBody(jsonMedia)).build()
        val started = System.nanoTime()
        var firstDeltaAt: Long? = null
        var usage = TokenUsage()
        val full = StringBuilder()

        val call = streamingClient.newCall(request)
        val cancellationWatcher = CoroutineScope(currentCoroutineContext()).launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
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
                    when (root.optString("type")) {
                        "response.output_text.delta" -> {
                            val delta = root.optString("delta")
                            if (delta.isNotEmpty()) {
                                if (firstDeltaAt == null) firstDeltaAt = System.nanoTime()
                                full.append(delta); onDelta(delta)
                            }
                        }
                        "response.completed" -> {
                            usage = parseUsage(root.optJSONObject("response") ?: root)
                            completed = true
                        }
                        "response.failed", "response.incomplete" -> throw IllegalStateException(root.optJSONObject("response")?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Responses API failed" })
                    }
                    !completed
                }
                if (!completed) throw IOException("Streaming connection ended before completion")
            } else {
                val text = response.body?.string().orEmpty()
                val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalStateException("Invalid JSON from Responses API") }
                val result = parseResponsesText(root)
                firstDeltaAt = System.nanoTime()
                full.append(result); onDelta(result)
                usage = parseUsage(root)
                }
            }
        } finally {
            cancellationWatcher.cancel()
        }
        val finalUsage = usage.copy(
            timeToFirstTokenMs = firstDeltaAt?.let { TimeUnit.NANOSECONDS.toMillis(it - started) },
            durationMs = elapsedMs(started),
            cacheRequested = profile.promptCacheEnabled && model.isGpt56Family(),
            cacheKey = cacheKey.take(64),
            cacheStrategy = if (profile.promptCacheEnabled && model.isGpt56Family()) "automatic" else "off"
        )
        return ChatCompletionResult(full.toString().ifBlank { throw IllegalStateException("Responses API returned no text") }, finalUsage)
    }

    private fun applyGptOptimizations(
        body: JSONObject,
        profile: ApiProfile,
        model: String,
        cacheKey: String,
        responsesApi: Boolean,
        explicitCache: Boolean
    ) {
        if (!model.isGpt56Family()) return
        if (profile.reasoningEffort.isNotBlank() && profile.reasoningEffort != "default") {
            if (responsesApi) body.put("reasoning", JSONObject().put("effort", profile.reasoningEffort))
            else body.put("reasoning_effort", profile.reasoningEffort)
        }
        if (profile.promptCacheEnabled && cacheKey.isNotBlank()) {
            body.put("prompt_cache_key", cacheKey.take(64))
            if (explicitCache && !responsesApi) {
                body.put("prompt_cache_options", JSONObject().put("mode", "explicit").put("ttl", "30m"))
            }
        }
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
        val body = if (profile.chatApiMode == "responses") {
            JSONObject()
                .put("model", model)
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
                .put("stream", false)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", analysisPrompt))
                    .put(JSONObject().put("role", "user").put("content", JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", "以下图片按上传顺序编号为第 1 页到第 ${pages.size} 页。请严格按要求返回逐页 JSON。"))
                        pageData.forEach { data ->
                            put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", data).put("detail", "high")))
                        }
                    })))
        }
        val path = if (profile.chatApiMode == "responses") profile.responsesPath else profile.chatPath
        val request = requestBuilder(profile, resolveUrl(profile.baseUrl, path))
            .applyIdempotencyKey(requestKey)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        val root = runCatching { JSONObject(executeTextCall(mangaAnalysisClient.newCall(request))) }
            .getOrElse { throw IllegalStateException("辅助模型返回的不是有效 JSON 响应") }
        val text = if (profile.chatApiMode == "responses") parseResponsesText(root) else parseMessageContent(root)
        parseMangaTranslationAnalysis(text, pages.size)
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
            cacheMetricsReported = cacheMetricsReported
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

