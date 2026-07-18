package com.adong.adchat.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adong.adchat.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class ConnectionPhase { Idle, Testing, Success, Error }
enum class ImageGenerationPhase { Idle, UploadingReference, Rendering, Saving }
enum class ImageWorkflow { Standard, MangaTranslation }

data class ConnectionUiState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val title: String = "尚未验证",
    val detail: String = "测试连接后读取模型列表",
    val latencyMs: Long? = null,
    val modelCount: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ConfigStore(application)
    private val repository = ApiRepository()
    private val conversationStore = ConversationStore(application)
    private val chatSessionStore = ChatSessionStore(application)
    private val artworkStore = ArtworkStore(application)
    private val conversationSaveMutex = Mutex()
    private var conversationSaveRevision = 0L
    @Volatile private var pendingStreamRecoveryClearId: Long? = null
    private val chatDrafts = linkedMapOf<String, String>()
    private var lastActiveChatKey = ChatSessionStore.NEW_CONVERSATION_KEY
    private var sessionSaveJob: Job? = null

    val messages = mutableStateListOf<ChatMessage>()
    val images = mutableStateListOf<GeneratedImage>()
    val conversations = mutableStateListOf<Conversation>()
    var activeConversationId by mutableStateOf<String?>(null)
        private set

    var appConfig by mutableStateOf(store.load())
        private set
    val modelCache = mutableStateMapOf<String, List<ApiModel>>()
    val connectionStates = mutableStateMapOf<String, ConnectionUiState>()
    var chatInput by mutableStateOf("")
        private set

    init {
        appConfig.profiles.forEach { profile ->
            if (profile.cachedModels.isNotEmpty()) {
                modelCache[profile.id] = profile.cachedModels
                connectionStates[profile.id] = ConnectionUiState(
                    phase = ConnectionPhase.Idle,
                    title = "已缓存 ${profile.cachedModels.size} 个模型",
                    detail = "模型列表来自上次同步，可随时重新测试",
                    latencyMs = profile.lastLatencyMs,
                    modelCount = profile.cachedModels.size
                )
            }
        }
        conversations.addAll(conversationStore.load())
        val session = chatSessionStore.load()
        chatDrafts.putAll(session.drafts)
        val restoredConversation = session.lastActiveKey
            .takeUnless { it == ChatSessionStore.NEW_CONVERSATION_KEY }
            ?.let { id -> conversations.firstOrNull { it.id == id } }
        val initialConversation = when {
            !session.hasSavedSession -> conversations.firstOrNull()
            restoredConversation != null -> restoredConversation
            session.lastActiveKey == ChatSessionStore.NEW_CONVERSATION_KEY -> null
            else -> conversations.firstOrNull()
        }
        initialConversation?.let { conversation ->
            activeConversationId = conversation.id
            messages.addAll(conversation.messages)
        }
        lastActiveChatKey = activeConversationId ?: ChatSessionStore.NEW_CONVERSATION_KEY
        chatInput = chatDrafts[lastActiveChatKey].orEmpty()
        images.addAll(artworkStore.load())
    }

    var drawPrompt by mutableStateOf("")
    var imageSize by mutableStateOf("1024x1024")
    var imageStyle by mutableStateOf("原始")
    var imageWorkflow by mutableStateOf(ImageWorkflow.Standard)
        private set
    var mangaTranslationTarget by mutableStateOf(MangaTranslationTarget.SimplifiedChinese)
        private set
    val referenceImages = mutableStateListOf<ReferenceImageAttachment>()
    private val referenceImageInputs = linkedMapOf<String, ReferenceImageInput>()
    var isReferenceLoading by mutableStateOf(false)
        private set
    var isChatLoading by mutableStateOf(false)
        private set
    private var chatJob: Job? = null
    private var chatStopRequested = false
    var isImageLoading by mutableStateOf(false)
        private set
    var imageGenerationPhase by mutableStateOf(ImageGenerationPhase.Idle)
        private set
    var imageGenerationStartedAt by mutableLongStateOf(0L)
        private set
    var imageBatchCompleted by mutableIntStateOf(0)
        private set
    var imageBatchTotal by mutableIntStateOf(0)
        private set
    private var imageJob: Job? = null
    private var imageStopRequested = false
    var imageError by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    val profiles: List<ApiProfile> get() = appConfig.profiles
    val chatProfile: ApiProfile get() = appConfig.chatProfile()
    val imageProfile: ApiProfile get() = appConfig.imageProfile()

    val activeMangaTranslationPrompt: String
        get() = MangaTranslationPrompt.build(mangaTranslationTarget)

    fun activateMangaTranslation(target: MangaTranslationTarget = mangaTranslationTarget) {
        mangaTranslationTarget = target
        imageWorkflow = ImageWorkflow.MangaTranslation
        imageStyle = "原始"
        imageSize = referenceImages.firstOrNull()?.let { canvasSizeForReference(it.width, it.height) } ?: "1024x1536"
        drawPrompt = MangaTranslationPrompt.build(target)
        imageError = null
        notice = if (referenceImages.isEmpty()) {
            "漫画翻译模式已启用，请添加 1–4 张漫画原图"
        } else {
            "漫画翻译模式已启用，可并发处理 ${referenceImages.size} 张"
        }
    }

    fun selectMangaTranslationTarget(target: MangaTranslationTarget) {
        activateMangaTranslation(target)
    }

    fun exitMangaTranslation() {
        val preset = MangaTranslationPrompt.build(mangaTranslationTarget)
        if (drawPrompt.trim() == preset.trim()) drawPrompt = ""
        if (referenceImages.size > MAX_REFERENCE_IMAGES) {
            referenceImages.drop(MAX_REFERENCE_IMAGES).forEach { referenceImageInputs.remove(it.id) }
            while (referenceImages.size > MAX_REFERENCE_IMAGES) referenceImages.removeAt(referenceImages.lastIndex)
        }
        imageWorkflow = ImageWorkflow.Standard
        imageStyle = "原始"
        imageError = null
        notice = "已返回普通绘图"
    }

    fun modelsFor(profileId: String): List<ApiModel> = modelCache[profileId] ?: profiles.firstOrNull { it.id == profileId }?.cachedModels.orEmpty()
    fun connectionFor(profileId: String): ConnectionUiState = connectionStates[profileId] ?: ConnectionUiState()

    fun createBlankProfile(): ApiProfile = ApiProfile(
        id = UUID.randomUUID().toString(), name = "新 API", baseUrl = "https://", chatModel = "", imageModel = ""
    )

    fun createOpenAiProfile(): ApiProfile = ApiProfile(
        id = UUID.randomUUID().toString(), name = "OpenAI GPT-5.6", baseUrl = "https://api.openai.com",
        chatModel = "gpt-5.6-sol", imageModel = "gpt-image-1", chatApiMode = "responses",
        reasoningEffort = "high", promptCacheEnabled = true, promptCacheMode = "adaptive"
    )

    fun createLocalProfile(): ApiProfile = ApiProfile(
        id = UUID.randomUUID().toString(), name = "本地服务", baseUrl = "http://10.0.2.2:8000",
        chatModel = "", imageModel = ""
    )

    fun saveProfile(profile: ApiProfile) {
        val cleaned = profile.normalized()
        val previous = appConfig.profiles.firstOrNull { it.id == cleaned.id }
        val exists = previous != null
        val connectionChanged = previous != null && (previous.baseUrl != cleaned.baseUrl || previous.apiKey != cleaned.apiKey || previous.modelsPath != cleaned.modelsPath || previous.extraHeaders != cleaned.extraHeaders)
        val profiles = if (exists) appConfig.profiles.map { if (it.id == cleaned.id) cleaned else it } else appConfig.profiles + cleaned
        if (cleaned.cachedModels.isNotEmpty()) modelCache[cleaned.id] = cleaned.cachedModels else modelCache.remove(cleaned.id)
        if (connectionChanged && cleaned.cachedModels.isEmpty()) connectionStates[cleaned.id] = ConnectionUiState(title = "等待重新测试", detail = "连接参数已更新")
        appConfig = appConfig.copy(profiles = profiles)
        persist()
        notice = if (exists) "已更新 ${cleaned.name}" else "已添加 ${cleaned.name}"
    }

    fun duplicateProfile(profileId: String) {
        val source = profiles.firstOrNull { it.id == profileId } ?: return
        saveProfile(source.copy(id = UUID.randomUUID().toString(), name = "${source.name} 副本"))
    }

    fun deleteProfile(profileId: String) {
        if (profiles.size <= 1) { notice = "至少需要保留一个 API 配置"; return }
        val remaining = profiles.filterNot { it.id == profileId }
        val fallback = remaining.first().id
        appConfig = appConfig.copy(
            profiles = remaining,
            activeChatProfileId = if (appConfig.activeChatProfileId == profileId) fallback else appConfig.activeChatProfileId,
            activeImageProfileId = if (appConfig.activeImageProfileId == profileId) fallback else appConfig.activeImageProfileId
        )
        modelCache.remove(profileId); connectionStates.remove(profileId)
        persist(); notice = "API 配置已删除"
    }

    fun selectChatProfile(profileId: String) {
        if (profiles.none { it.id == profileId }) return
        appConfig = appConfig.copy(activeChatProfileId = profileId)
        persist(); notice = "对话已切换到 ${chatProfile.name}"
    }

    fun selectImageProfile(profileId: String) {
        if (profiles.none { it.id == profileId }) return
        appConfig = appConfig.copy(activeImageProfileId = profileId)
        persist(); notice = "绘图已切换到 ${imageProfile.name}"
    }

    fun selectChatModel(profileId: String, model: String) {
        updateProfile(profileId) { it.copy(chatModel = model) }
        if (appConfig.activeChatProfileId != profileId) selectChatProfile(profileId) else persist()
    }

    fun selectImageModel(profileId: String, model: String) {
        updateProfile(profileId) { it.copy(imageModel = model) }
        if (appConfig.activeImageProfileId != profileId) selectImageProfile(profileId) else persist()
    }

    fun setChatReasoningEffort(effort: String) {
        val normalized = effort.takeIf { it in REASONING_EFFORTS } ?: "medium"
        updateProfile(chatProfile.id) { it.copy(reasoningEffort = normalized) }
        persist()
    }

    fun updateSystemPrompt(value: String) {
        appConfig = appConfig.copy(systemPrompt = value); persist()
    }

    fun testProfile(profile: ApiProfile, onUpdatedDraft: ((ApiProfile) -> Unit)? = null) {
        val tested = profile.normalized()
        val invalidHeaders = tested.invalidExtraHeaderLines()
        val validationError = when {
            !tested.hasValidBaseUrl() -> "Base URL 需要包含完整的 http:// 或 https:// 主机地址"
            invalidHeaders.isNotEmpty() -> "额外请求头格式错误：${invalidHeaders.first()}"
            else -> null
        }
        onUpdatedDraft?.invoke(tested)
        if (validationError != null) {
            connectionStates[tested.id] = ConnectionUiState(ConnectionPhase.Error, "配置不完整", validationError)
            return
        }
        connectionStates[tested.id] = ConnectionUiState(ConnectionPhase.Testing, "正在连接", "验证认证并获取模型列表…")
        val startedAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            runCatching { repository.fetchModels(tested) }
                .onSuccess { result ->
                    modelCache[tested.id] = result.models
                    connectionStates[tested.id] = ConnectionUiState(
                        ConnectionPhase.Success,
                        "连接成功",
                        if (result.models.isEmpty()) "服务可访问，但模型列表为空" else "已同步 ${result.models.size} 个模型",
                        result.latencyMs,
                        result.models.size
                    )
                    val chatCandidate = result.models.firstOrNull { !it.id.isImageLike() }?.id
                    val imageCandidate = result.models.firstOrNull { it.id.isImageLike() }?.id
                    var updated = tested.copy(cachedModels = result.models, lastLatencyMs = result.latencyMs)
                    if (tested.chatModel.isBlank() && chatCandidate != null) updated = updated.copy(chatModel = chatCandidate)
                    if (tested.imageModel.isBlank() && imageCandidate != null) updated = updated.copy(imageModel = imageCandidate)
                    if (onUpdatedDraft == null && profiles.any { it.id == updated.id }) {
                        updateProfile(updated.id) { updated }
                        persist()
                    }
                    onUpdatedDraft?.invoke(updated)
                    notice = "${tested.name} 已获取 ${result.models.size} 个模型"
                }
                .onFailure { error ->
                    val raw = generateSequence(error) { it.cause }.joinToString(" ") { it.message.orEmpty() }
                    val modelsEndpointUnavailable = raw.contains("HTTP 404", true) || raw.contains("HTTP 405", true)
                    if (modelsEndpointUnavailable) {
                        val latency = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                        val updated = tested.copy(lastLatencyMs = latency)
                        connectionStates[tested.id] = ConnectionUiState(
                            ConnectionPhase.Success,
                            "服务可访问",
                            "模型列表接口不可用，请手动填写模型 ID",
                            latency,
                            0
                        )
                        if (onUpdatedDraft == null && profiles.any { it.id == updated.id }) {
                            updateProfile(updated.id) { updated }
                            persist()
                        }
                        onUpdatedDraft?.invoke(updated)
                        notice = "${tested.name} 已连通，需手动填写模型"
                    } else {
                        connectionStates[tested.id] = ConnectionUiState(ConnectionPhase.Error, "连接失败", friendlyError(error))
                    }
                }
        }
    }


    fun discardProfileDraft(profileId: String) {
        val saved = profiles.firstOrNull { it.id == profileId }
        if (saved == null) {
            modelCache.remove(profileId)
            connectionStates.remove(profileId)
            return
        }
        if (saved.cachedModels.isNotEmpty()) {
            modelCache[profileId] = saved.cachedModels
            connectionStates[profileId] = ConnectionUiState(
                phase = ConnectionPhase.Idle,
                title = "已缓存 ${saved.cachedModels.size} 个模型",
                detail = "已恢复保存前的连接信息",
                latencyMs = saved.lastLatencyMs,
                modelCount = saved.cachedModels.size
            )
        } else {
            modelCache.remove(profileId)
            connectionStates[profileId] = ConnectionUiState()
        }
    }

    fun testActiveRoutes() {
        testProfile(chatProfile)
        if (imageProfile.id != chatProfile.id) testProfile(imageProfile)
    }

    fun exportProfiles(includeApiKeys: Boolean): String {
        return JSONObject()
            .put("format", "adchat-profiles-v1")
            .put("exportedAt", System.currentTimeMillis())
            .put("profiles", JSONArray().apply {
                profiles.forEach { profile ->
                    put(JSONObject()
                        .put("name", profile.name)
                        .put("baseUrl", profile.baseUrl)
                        .put("apiKey", if (includeApiKeys) profile.apiKey else "")
                        .put("modelsPath", profile.modelsPath)
                        .put("chatPath", profile.chatPath)
                        .put("responsesPath", profile.responsesPath)
                        .put("chatApiMode", profile.chatApiMode)
                        .put("reasoningEffort", profile.reasoningEffort)
                        .put("autoResumeStream", profile.autoResumeStream)
                        .put("promptCacheEnabled", profile.promptCacheEnabled)
                        .put("promptCacheMode", profile.promptCacheMode)
                        .put("imagePath", profile.imagePath)
                        .put("imageEditPath", profile.imageEditPath)
                        .put("chatModel", profile.chatModel)
                        .put("imageModel", profile.imageModel)
                        .put("extraHeaders", profile.extraHeaders)
                    )
                }
            })
            .toString(2)
    }

    fun importProfiles(json: String): Result<Int> = runCatching {
        val root = JSONObject(json)
        require(root.optString("format") == "adchat-profiles-v1") { "不是有效的 ADChat 配置文件" }
        val array = root.optJSONArray("profiles") ?: throw IllegalArgumentException("配置中没有 profiles")
        val imported = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val baseUrl = item.optString("baseUrl").trim().trimEnd('/')
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) continue
                add(ApiProfile(
                    id = UUID.randomUUID().toString(),
                    name = item.optString("name").ifBlank { "导入的 API" },
                    baseUrl = baseUrl,
                    apiKey = item.optString("apiKey"),
                    modelsPath = item.optString("modelsPath").ifBlank { "/v1/models" },
                    chatPath = item.optString("chatPath").ifBlank { "/v1/chat/completions" },
                    responsesPath = item.optString("responsesPath").ifBlank { "/v1/responses" },
                    chatApiMode = item.optString("chatApiMode").ifBlank { "chat" },
                    reasoningEffort = item.optString("reasoningEffort").ifBlank { "medium" },
                    autoResumeStream = item.optBoolean("autoResumeStream", true),
                    promptCacheEnabled = item.optBoolean("promptCacheEnabled", true),
                    promptCacheMode = item.optString("promptCacheMode").ifBlank { "implicit" },
                    imagePath = item.optString("imagePath").ifBlank { "/v1/images/generations" },
                    imageEditPath = item.optString("imageEditPath").ifBlank { "/v1/images/edits" },
                    chatModel = item.optString("chatModel"),
                    imageModel = item.optString("imageModel"),
                    extraHeaders = item.optString("extraHeaders")
                ))
            }
        }
        require(imported.isNotEmpty()) { "没有找到可导入的有效 API 配置" }
        appConfig = appConfig.copy(profiles = profiles + imported)
        persist()
        notice = "已导入 ${imported.size} 个 API 配置"
        imported.size
    }
    fun updateChatInput(value: String) {
        chatInput = value
        val key = currentChatKey()
        if (value.isBlank()) chatDrafts.remove(key) else chatDrafts[key] = value
        scheduleSessionSave()
    }

    fun sendMessage(textOverride: String? = null) {
        val fromComposer = textOverride == null
        val text = (textOverride ?: chatInput).trim()
        if (text.isEmpty() || isChatLoading) return
        val profile = chatProfile
        val model = profile.chatModel
        if (fromComposer) clearCurrentDraft()
        messages += ChatMessage(role = "user", content = text)
        persistCurrentConversation()
        val history = messages.toList()
        val assistantId = System.nanoTime()
        val streamingMessage = ChatMessage(
            id = assistantId, role = "assistant", content = "", isStreaming = true,
            profileName = profile.name, model = model
        )
        messages += streamingMessage
        val recoveryConversationId = activeConversationId ?: profile.id
        val recoveryBase = conversations.firstOrNull { it.id == recoveryConversationId }
        val recoveryTitle = recoveryBase?.title
            ?: history.firstOrNull { it.role == "user" }?.content
                ?.replace(Regex("\\s+"), " ")?.trim()?.take(24)?.ifBlank { null }
            ?: "\u65b0\u5bf9\u8bdd"
        val recoveryCreatedAt = recoveryBase?.createdAt ?: System.currentTimeMillis()
        isChatLoading = true
        chatStopRequested = false
        chatJob = viewModelScope.launch {
            val streamed = StringBuilder()
            var lastUiPushAt = 0L
            var lastRecoveryAt = 0L
            var automaticRecoveryCount = 0
            try {
                val result = repository.streamChat(
                    profile = profile,
                    model = model,
                    systemPrompt = appConfig.systemPrompt,
                    history = history,
                    cacheKey = "adchat-${activeConversationId ?: profile.id}",
                    onRecovery = { event ->
                        automaticRecoveryCount = maxOf(automaticRecoveryCount, event.attempt)
                        withContext(Dispatchers.Main.immediate) {
                            replaceMessage(assistantId) {
                                it.copy(
                                    isRecovering = event.reconnecting,
                                    streamRecoveryCount = automaticRecoveryCount
                                )
                            }
                        }
                    }
                ) { delta ->
                    streamed.append(delta)
                    val now = SystemClock.elapsedRealtime()
                    val uiDue = lastUiPushAt == 0L || now - lastUiPushAt >= streamUiIntervalMs(streamed.length)
                    val recoveryDue = lastRecoveryAt == 0L || now - lastRecoveryAt >= STREAM_RECOVERY_INTERVAL_MS
                    if (uiDue || recoveryDue) {
                        val snapshot = streamed.toString()
                        if (uiDue) {
                            lastUiPushAt = now
                            withContext(Dispatchers.Main.immediate) {
                                replaceMessage(assistantId) { it.copy(content = snapshot, isStreaming = true) }
                            }
                        }
                        if (recoveryDue && snapshot.isNotBlank()) {
                            lastRecoveryAt = now
                            // Store a recoverable interrupted copy without touching Compose state.
                            // A force-stop or process reclaim can then restore paid partial output.
                            conversationStore.saveStreamRecovery(
                                Conversation(
                                    id = recoveryConversationId,
                                    title = recoveryTitle,
                                    messages = history + streamingMessage.copy(
                                        content = snapshot,
                                        isStreaming = false,
                                        isInterrupted = true,
                                        isRecovering = false,
                                        streamRecoveryCount = automaticRecoveryCount
                                    ),
                                    createdAt = recoveryCreatedAt,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
                replaceMessage(assistantId) {
                    it.copy(
                        content = result.text,
                        isStreaming = false,
                        isInterrupted = false,
                        isStopped = false,
                        isRecovering = false,
                        streamRecoveryCount = maxOf(automaticRecoveryCount, result.usage.streamRecoveryCount),
                        usage = result.usage
                    )
                }
            } catch (error: Throwable) {
                val partial = streamed.toString().trimEnd()
                when {
                    chatStopRequested -> {
                        if (partial.isBlank()) {
                            messages.indexOfFirst { it.id == assistantId }
                                .takeIf { it >= 0 }
                                ?.let(messages::removeAt)
                            notice = "\u5df2\u505c\u6b62\u751f\u6210"
                        } else {
                            replaceMessage(assistantId) {
                                it.copy(
                                    content = partial,
                                    isError = false,
                                    isStreaming = false,
                                    isInterrupted = false,
                                    isStopped = true,
                                    isRecovering = false,
                                    streamRecoveryCount = automaticRecoveryCount
                                )
                            }
                            notice = "\u5df2\u505c\u6b62\u751f\u6210\uff0c\u5e76\u4fdd\u7559\u5168\u90e8\u5df2\u751f\u6210\u5185\u5bb9"
                        }
                    }
                    error is CancellationException -> {
                        if (partial.isNotBlank()) {
                            replaceMessage(assistantId) {
                                it.copy(
                                    content = partial,
                                    isError = false,
                                    isStreaming = false,
                                    isInterrupted = true,
                                    isStopped = false,
                                    isRecovering = false,
                                    streamRecoveryCount = automaticRecoveryCount
                                )
                            }
                        }
                        throw error
                    }
                    partial.isNotBlank() -> {
                        replaceMessage(assistantId) {
                            it.copy(
                                content = partial,
                                isError = false,
                                isStreaming = false,
                                isInterrupted = true,
                                isStopped = false,
                                isRecovering = false,
                                streamRecoveryCount = automaticRecoveryCount
                            )
                        }
                        notice = if (automaticRecoveryCount > 0) {
                            "\u5b89\u5168\u7eed\u4f20\u540e\u4ecd\u4e2d\u65ad\uff0c\u5df2\u4fdd\u7559\u5168\u90e8\u5df2\u751f\u6210\u5185\u5bb9"
                        } else {
                            "\u6d41\u5f0f\u8fde\u63a5\u4e2d\u65ad\uff0c\u5df2\u4fdd\u7559\u5168\u90e8\u5df2\u751f\u6210\u5185\u5bb9"
                        }
                    }
                    else -> {
                        replaceMessage(assistantId) {
                            it.copy(
                                content = friendlyError(error),
                                isError = true,
                                isStreaming = false,
                                isInterrupted = false,
                                isStopped = false,
                                isRecovering = false,
                                streamRecoveryCount = automaticRecoveryCount
                            )
                        }
                    }
                }
            } finally {
                isChatLoading = false
                chatStopRequested = false
                chatJob = null
                persistCurrentConversation(clearRecoveryMessageId = assistantId)
            }
        }
    }

    fun stopGeneration() {
        if (!isChatLoading) return
        chatStopRequested = true
        chatJob?.cancel(CancellationException("User stopped generation"))
    }

    fun regenerateMessage(messageId: Long) {
        if (isChatLoading) return
        val assistantIndex = messages.indexOfFirst { it.id == messageId }
        if (assistantIndex < 0 || assistantIndex != messages.lastIndex) return
        val assistant = messages[assistantIndex]
        if (assistant.role != "assistant" || assistant.isStreaming || assistant.isInterrupted || assistant.isStopped) return
        val userIndex = (assistantIndex - 1 downTo 0).firstOrNull { messages[it].role == "user" } ?: return
        val prompt = messages[userIndex].content
        messages.removeAt(assistantIndex)
        messages.removeAt(userIndex)
        persistCurrentConversation()
        sendMessage(prompt)
    }

    fun retryMessage(messageId: Long) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        if (messages[index].isInterrupted || messages[index].isStopped) {
            messages[index] = messages[index].copy(isInterrupted = false, isStopped = false)
            persistCurrentConversation()
            sendMessage("请从上一条回复中断的位置继续，直接续写，不要重复已生成的内容。")
            return
        }
        if (!messages[index].isError) return
        val previousUser = messages.take(index).lastOrNull { it.role == "user" } ?: return
        messages.removeAt(index)
        messages.indexOfLast { it.id == previousUser.id }.takeIf { it >= 0 }?.let(messages::removeAt)
        sendMessage(previousUser.content)
    }

    fun newConversation() {
        if (isChatLoading) {
            notice = "\u5f53\u524d\u56de\u590d\u4ecd\u5728\u751f\u6210\uff0c\u8bf7\u5148\u505c\u6b62\u6216\u7b49\u5f85\u5b8c\u6210\u540e\u518d\u65b0\u5efa\u5bf9\u8bdd"
            return
        }
        val alreadyOnNewConversation = activeConversationId == null
        stashCurrentDraft()
        activeConversationId = null
        lastActiveChatKey = ChatSessionStore.NEW_CONVERSATION_KEY
        messages.clear()
        if (alreadyOnNewConversation) {
            chatDrafts.remove(lastActiveChatKey)
            chatInput = ""
        } else {
            chatInput = chatDrafts[lastActiveChatKey].orEmpty()
        }
        scheduleSessionSave(immediate = true)
        notice = if (chatInput.isBlank()) "已开始新对话" else "已恢复新对话草稿"
    }

    fun clearChat() = newConversation()

    fun selectConversation(id: String) {
        if (id == activeConversationId) return
        if (isChatLoading) {
            notice = "\u5f53\u524d\u56de\u590d\u4ecd\u5728\u751f\u6210\uff0c\u8bf7\u5148\u505c\u6b62\u6216\u7b49\u5f85\u5b8c\u6210\u540e\u518d\u5207\u6362\u4efb\u52a1"
            return
        }
        conversations.firstOrNull { it.id == id }?.let { conversation ->
            stashCurrentDraft()
            activeConversationId = id
            lastActiveChatKey = id
            messages.clear()
            messages.addAll(conversation.messages)
            chatInput = chatDrafts[id].orEmpty()
            scheduleSessionSave(immediate = true)
        }
    }

    fun deleteConversation(id: String) {
        if (isChatLoading && id == activeConversationId) {
            notice = "\u5f53\u524d\u4efb\u52a1\u6b63\u5728\u751f\u6210\uff0c\u8bf7\u5148\u505c\u6b62\u540e\u518d\u5220\u9664"
            return
        }
        val index = conversations.indexOfFirst { it.id == id }
        if (index < 0) return
        conversations.removeAt(index)
        chatDrafts.remove(id)
        if (activeConversationId == id) {
            activeConversationId = null
            lastActiveChatKey = ChatSessionStore.NEW_CONVERSATION_KEY
            messages.clear()
            chatInput = ""
        }
        scheduleConversationSave(conversations.toList())
        scheduleSessionSave(immediate = true)
        notice = "已删除对话"
    }

    fun renameConversation(id: String, title: String) {
        val index = conversations.indexOfFirst { it.id == id }
        if (index < 0 || title.isBlank()) return
        conversations[index] = conversations[index].copy(title = title.trim(), updatedAt = System.currentTimeMillis())
        sortAndSaveConversations()
    }

    fun generateImage() {
        val prompt = drawPrompt.trim()
        if (prompt.isEmpty() || isImageLoading) return
        if (isReferenceLoading) {
            notice = "参考图仍在读取，请稍候"
            return
        }
        val profile = imageProfile
        val model = profile.imageModel
        val selectedPages = referenceImages.mapNotNull { attachment ->
            referenceImageInputs[attachment.id]?.let { input -> attachment to input }
        }
        val mangaTranslation = imageWorkflow == ImageWorkflow.MangaTranslation
        if (mangaTranslation && selectedPages.size !in 1..MAX_MANGA_IMAGES) {
            imageError = "漫画翻译需要添加 1–$MAX_MANGA_IMAGES 张清晰原图"
            return
        }
        val selectedReferenceInputs = selectedPages.map { it.second }
        val targetSnapshot = mangaTranslationTarget
        imageError = null
        isImageLoading = true
        imageStopRequested = false
        imageGenerationStartedAt = SystemClock.elapsedRealtime()
        imageBatchCompleted = 0
        imageBatchTotal = if (mangaTranslation) selectedPages.size else 0
        imageGenerationPhase = if (selectedReferenceInputs.isNotEmpty()) {
            ImageGenerationPhase.UploadingReference
        } else {
            ImageGenerationPhase.Rendering
        }
        val apiPrompt = if (mangaTranslation) {
            prompt
        } else {
            when (imageStyle) {
                "摄影" -> "$prompt，专业摄影，真实光影，高细节"
                "插画" -> "$prompt，精致数字插画，清晰构图"
                "电影" -> "$prompt，电影感画面，戏剧性光影，宽容度丰富"
                "动漫" -> "$prompt，精致动漫风格，细腻线条与色彩"
                else -> prompt
            }
        }
        val generatedStyle = if (mangaTranslation) "漫画翻译" else imageStyle
        imageJob = viewModelScope.launch {
            try {
                if (mangaTranslation) {
                    val pageResults = coroutineScope {
                        selectedPages.mapIndexed { index, (attachment, input) ->
                            async {
                                val pageSize = canvasSizeForReference(attachment.width, attachment.height)
                                try {
                                    MangaTranslationPageResult(
                                        index = index,
                                        name = attachment.name,
                                        size = pageSize,
                                        sources = repository.generateImage(
                                            profile = profile,
                                            model = model,
                                            prompt = apiPrompt,
                                            size = pageSize,
                                            references = listOf(input)
                                        )
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    MangaTranslationPageResult(
                                        index = index,
                                        name = attachment.name,
                                        size = pageSize,
                                        errorMessage = safeMangaError(error)
                                    )
                                } finally {
                                    imageBatchCompleted += 1
                                }
                            }
                        }.awaitAll()
                    }
                    val orderedSuccesses = orderedMangaSuccesses(pageResults)
                    val failureSummary = mangaFailureSummary(pageResults)
                    val successfulPages = pageResults.count(MangaTranslationPageResult::successful)
                    if (orderedSuccesses.isEmpty()) {
                        imageError = failureSummary.ifBlank { "所有漫画页均翻译失败" }
                        notice = "本次 ${pageResults.size} 张漫画均未完成"
                        return@launch
                    }
                    imageGenerationPhase = ImageGenerationPhase.Saving
                    val cachedSuccesses = withContext(Dispatchers.IO) {
                        orderedSuccesses.map { (page, source) ->
                            page to runCatching { artworkStore.cacheSource(source) }.getOrDefault(source)
                        }
                    }
                    val generatedImages = cachedSuccesses.map { (page, source) ->
                        GeneratedImage(
                            prompt = "漫画翻译 · ${targetSnapshot.shortLabel} · 第 ${page.index + 1} 张",
                            source = source,
                            size = page.size,
                            style = generatedStyle,
                            profileName = profile.name,
                            model = model
                        )
                    }
                    images.addAll(0, generatedImages)
                    artworkStore.save(images)
                    if (failureSummary.isBlank()) {
                        notice = "${successfulPages} 张漫画已按顺序翻译完成"
                    } else {
                        notice = "已完成 $successfulPages/${pageResults.size} 张，失败页已说明"
                        imageError = "以下页面翻译失败，成功结果已保留：\n$failureSummary"
                    }
                } else {
                    val sources = withContext(Dispatchers.IO) {
                        repository.generateImage(profile, model, apiPrompt, imageSize, selectedReferenceInputs)
                    }
                    imageGenerationPhase = ImageGenerationPhase.Saving
                    val cachedSources = withContext(Dispatchers.IO) {
                        sources.map { source -> runCatching { artworkStore.cacheSource(source) }.getOrDefault(source) }
                    }
                    val generatedImages = cachedSources.map { source ->
                        GeneratedImage(
                            prompt = prompt,
                            source = source,
                            size = imageSize,
                            style = generatedStyle,
                            profileName = profile.name,
                            model = model
                        )
                    }
                    images.addAll(0, generatedImages)
                    artworkStore.save(images)
                    notice = "图片已生成并保存在作品记录中"
                }
            } catch (error: Throwable) {
                when {
                    imageStopRequested -> notice = "已停止本次图片生成"
                    error is CancellationException -> throw error
                    else -> imageError = friendlyError(error)
                }
            } finally {
                isImageLoading = false
                imageStopRequested = false
                imageGenerationPhase = ImageGenerationPhase.Idle
                imageBatchCompleted = 0
                imageBatchTotal = 0
                imageJob = null
            }
        }
    }

    fun stopImageGeneration() {
        if (!isImageLoading) return
        imageStopRequested = true
        imageJob?.cancel(CancellationException("User stopped image generation"))
    }

    fun deleteImage(imageId: Long) {
        val index = images.indexOfFirst { it.id == imageId }
        if (index < 0) return
        val image = images.removeAt(index)
        artworkStore.delete(image)
        artworkStore.save(images)
        notice = "\u5df2\u5220\u9664\u4f5c\u54c1"
    }

    fun reuseImagePrompt(image: GeneratedImage) {
        imageSize = image.size
        if (image.style == "漫画翻译") {
            imageWorkflow = ImageWorkflow.MangaTranslation
            mangaTranslationTarget = MangaTranslationPrompt.detectTarget(image.prompt)
            drawPrompt = MangaTranslationPrompt.build(mangaTranslationTarget)
            imageStyle = "原始"
        } else {
            imageWorkflow = ImageWorkflow.Standard
            drawPrompt = image.prompt
            imageStyle = image.style
        }
        notice = "\u5df2\u8f7d\u5165\u8fd9\u5f20\u4f5c\u54c1\u7684\u521b\u4f5c\u53c2\u6570"
    }

    fun saveImageToGallery(image: GeneratedImage) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { artworkStore.saveToGallery(image) } }
                .onSuccess { notice = "\u5df2\u4fdd\u5b58\u5230\u7cfb\u7edf\u76f8\u518c\uff1a$it" }
                .onFailure { imageError = friendlyError(it) }
        }
    }

    fun attachReferenceImages(uris: List<Uri>) {
        val maximum = if (imageWorkflow == ImageWorkflow.MangaTranslation) MAX_MANGA_IMAGES else MAX_REFERENCE_IMAGES
        val candidates = uris.distinctBy(Uri::toString).take(maximum)
        if (candidates.isEmpty()) return
        loadReferenceImages(candidates) { loaded, failureCount ->
            referenceImages.clear()
            referenceImageInputs.clear()
            loaded.forEach(::storeReferenceImage)
            notice = when {
                failureCount > 0 -> "已添加 ${loaded.size} 张参考图，$failureCount 张读取失败"
                imageWorkflow == ImageWorkflow.MangaTranslation && loaded.size > 1 -> "${loaded.size} 张漫画原图已就绪"
                loaded.size > 1 -> "${loaded.size} 张参考图已就绪"
                else -> "参考图已就绪"
            }
        }
    }

    fun addReferenceImage(uri: Uri) {
        val maximum = if (imageWorkflow == ImageWorkflow.MangaTranslation) MAX_MANGA_IMAGES else MAX_REFERENCE_IMAGES
        if (referenceImages.size >= maximum) {
            notice = if (imageWorkflow == ImageWorkflow.MangaTranslation) {
                "漫画翻译最多同时处理 $MAX_MANGA_IMAGES 张原图"
            } else {
                "当前最多添加 $MAX_REFERENCE_IMAGES 张参考图"
            }
            return
        }
        if (referenceImages.any { it.uri == uri.toString() }) {
            notice = "这张参考图已经添加"
            return
        }
        loadReferenceImages(listOf(uri)) { loaded, _ ->
            loaded.firstOrNull()?.let(::storeReferenceImage)
            notice = if (imageWorkflow == ImageWorkflow.MangaTranslation) {
                "已添加第 ${referenceImages.size} 张漫画原图"
            } else {
                "已添加第 ${referenceImages.size} 张参考图"
            }
        }
    }

    fun replaceReferenceImage(id: String, uri: Uri) {
        val index = referenceImages.indexOfFirst { it.id == id }
        if (index < 0) return
        loadReferenceImages(listOf(uri)) { loaded, _ ->
            val replacement = loaded.firstOrNull() ?: return@loadReferenceImages
            referenceImageInputs.remove(id)
            referenceImages[index] = replacement.attachment.copy(id = id)
            referenceImageInputs[id] = replacement.input
            syncMangaCanvasToReference()
            notice = "参考图已替换"
        }
    }

    fun removeReferenceImage(id: String) {
        val index = referenceImages.indexOfFirst { it.id == id }
        if (index < 0) return
        referenceImages.removeAt(index)
        referenceImageInputs.remove(id)
        syncMangaCanvasToReference()
        notice = if (referenceImages.isEmpty()) "已移除参考图" else "已移除一张参考图"
    }

    fun clearReferenceImages() {
        referenceImages.clear()
        referenceImageInputs.clear()
        syncMangaCanvasToReference()
    }

    private fun loadReferenceImages(
        uris: List<Uri>,
        onLoaded: (List<LoadedReferenceImage>, failureCount: Int) -> Unit
    ) {
        if (isImageLoading || isReferenceLoading) return
        imageError = null
        isReferenceLoading = true
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    uris.map { uri -> runCatching { loadReferenceImage(uri) } }
                }
                val loaded = results.mapNotNull(Result<LoadedReferenceImage>::getOrNull)
                val failures = results.count(Result<LoadedReferenceImage>::isFailure)
                if (loaded.isEmpty()) {
                    imageError = friendlyError(results.firstNotNullOfOrNull { it.exceptionOrNull() }
                        ?: IllegalArgumentException("无法读取所选图片"))
                } else {
                    onLoaded(loaded, failures)
                }
            } finally {
                isReferenceLoading = false
            }
        }
    }

    private fun loadReferenceImage(uri: Uri): LoadedReferenceImage {
        val resolver = getApplication<Application>().contentResolver
        var name = "reference-image"
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                    name = cursor.getString(it) ?: name
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let {
                    declaredSize = cursor.getLong(it)
                }
            }
        }
        if (declaredSize != null && declaredSize!! > MAX_REFERENCE_BYTES) {
            throw IllegalArgumentException("单张参考图不能超过 20 MB")
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取所选图片")
        if (bytes.size > MAX_REFERENCE_BYTES) throw IllegalArgumentException("单张参考图不能超过 20 MB")
        val mime = resolver.getType(uri) ?: "image/png"
        if (!mime.startsWith("image/", ignoreCase = true)) throw IllegalArgumentException("所选文件不是图片")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val attachment = ReferenceImageAttachment(
            uri = uri.toString(),
            name = name,
            size = bytes.size.toLong(),
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0)
        )
        return LoadedReferenceImage(attachment, ReferenceImageInput(bytes, mime, name))
    }

    private fun storeReferenceImage(loaded: LoadedReferenceImage) {
        val maximum = if (imageWorkflow == ImageWorkflow.MangaTranslation) MAX_MANGA_IMAGES else MAX_REFERENCE_IMAGES
        if (referenceImages.size >= maximum) return
        referenceImages += loaded.attachment
        referenceImageInputs[loaded.attachment.id] = loaded.input
        syncMangaCanvasToReference()
    }

    private fun syncMangaCanvasToReference() {
        if (imageWorkflow != ImageWorkflow.MangaTranslation) return
        val reference = referenceImages.firstOrNull()
        imageSize = if (reference == null) "1024x1536" else canvasSizeForReference(reference.width, reference.height)
    }

    fun dismissNotice() { notice = null }
    fun dismissImageError() { imageError = null }

    private fun persistCurrentConversation(clearRecoveryMessageId: Long? = null) {
        val stableMessages = messages.filterNot { it.isStreaming }
        if (stableMessages.isEmpty()) return
        val now = System.currentTimeMillis()
        val wasNewConversation = activeConversationId == null
        val id = activeConversationId ?: UUID.randomUUID().toString().also { activeConversationId = it }
        if (wasNewConversation) {
            lastActiveChatKey = id
            chatDrafts.remove(ChatSessionStore.NEW_CONVERSATION_KEY)
            scheduleSessionSave(immediate = true)
        }
        val index = conversations.indexOfFirst { it.id == id }
        val title = stableMessages.firstOrNull { it.role == "user" }?.content
            ?.replace(Regex("\\s+"), " ")?.trim()?.take(24)?.ifBlank { null } ?: "\u65b0\u5bf9\u8bdd"
        val createdAt = conversations.getOrNull(index)?.createdAt ?: now
        val conversation = Conversation(id, conversations.getOrNull(index)?.title ?: title, stableMessages, createdAt, now)
        if (index >= 0) conversations[index] = conversation else conversations.add(conversation)
        sortAndSaveConversations(clearRecoveryMessageId)
    }

    private fun sortAndSaveConversations(clearRecoveryMessageId: Long? = null) {
        val sorted = conversations.sortedByDescending { it.updatedAt }
        conversations.clear()
        conversations.addAll(sorted)
        scheduleConversationSave(sorted, clearRecoveryMessageId)
    }

    private fun scheduleConversationSave(snapshot: List<Conversation>, clearRecoveryMessageId: Long? = null) {
        if (clearRecoveryMessageId != null) pendingStreamRecoveryClearId = clearRecoveryMessageId
        val revision = ++conversationSaveRevision
        viewModelScope.launch(Dispatchers.IO) {
            conversationSaveMutex.withLock {
                if (revision == conversationSaveRevision) {
                    val pendingClear = pendingStreamRecoveryClearId
                    conversationStore.save(snapshot, pendingClear)
                    if (pendingStreamRecoveryClearId == pendingClear) pendingStreamRecoveryClearId = null
                }
            }
        }
    }

    private fun currentChatKey(): String = activeConversationId ?: ChatSessionStore.NEW_CONVERSATION_KEY

    private fun stashCurrentDraft() {
        val key = currentChatKey()
        if (chatInput.isBlank()) chatDrafts.remove(key) else chatDrafts[key] = chatInput
    }

    private fun clearCurrentDraft() {
        chatInput = ""
        chatDrafts.remove(currentChatKey())
        scheduleSessionSave(immediate = true)
    }

    private fun scheduleSessionSave(immediate: Boolean = false) {
        lastActiveChatKey = currentChatKey()
        sessionSaveJob?.cancel()
        val draftsSnapshot = chatDrafts.toMap()
        val activeKeySnapshot = lastActiveChatKey
        sessionSaveJob = viewModelScope.launch(Dispatchers.IO) {
            if (!immediate) delay(220)
            chatSessionStore.save(draftsSnapshot, activeKeySnapshot)
        }
    }

    override fun onCleared() {
        stashCurrentDraft()
        chatSessionStore.save(chatDrafts.toMap(), currentChatKey())
        super.onCleared()
    }

    private fun updateProfile(id: String, transform: (ApiProfile) -> ApiProfile) {
        appConfig = appConfig.copy(profiles = profiles.map { if (it.id == id) transform(it) else it })
    }

    private fun persist() = store.save(appConfig)

    private fun friendlyError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("stream was reset", true) || raw.contains("ended before completion", true) -> "连接在首字返回前中断，已尝试重新建立连接"
            raw.contains("failed to connect", true) -> "无法连接服务器，请检查网络、URL 与服务是否已启动"
            raw.contains("timeout", true) || raw.contains("timed out", true) -> "连接超时，请检查网络或稍后重试"
            raw.contains("unable to resolve host", true) -> "无法解析服务器地址，请检查 Base URL"
            raw.contains("certificate", true) || raw.contains("ssl", true) -> "TLS 证书验证失败，请检查 HTTPS 配置"
            raw.isNotBlank() -> raw
            else -> "发生未知错误，请检查 API 配置"
        }
    }

    private fun safeMangaError(error: Throwable): String {
        val value = friendlyError(error)
        return if (
            value.contains("最高优先级规则") ||
            value.contains("将参考图视为不可改动") ||
            value.contains("原本没有文字的位置绝不出现新文字")
        ) {
            "绘图服务拒绝了该页的翻译请求"
        } else {
            value.take(220)
        }
    }

    private fun replaceMessage(id: Long, transform: (ChatMessage) -> ChatMessage) {
        messages.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { messages[it] = transform(messages[it]) }
    }

    private companion object {
        const val MAX_REFERENCE_BYTES = 20 * 1024 * 1024
        const val MAX_REFERENCE_IMAGES = 2
        const val MAX_MANGA_IMAGES = 4
        const val STREAM_RECOVERY_INTERVAL_MS = 1_400L

        fun streamUiIntervalMs(contentLength: Int): Long = when {
            contentLength < 2_000 -> 58L
            contentLength < 8_000 -> 82L
            contentLength < 24_000 -> 108L
            else -> 136L
        }
        val REASONING_EFFORTS = setOf("low", "medium", "high", "xhigh", "max")
    }
}

private data class LoadedReferenceImage(
    val attachment: ReferenceImageAttachment,
    val input: ReferenceImageInput
)

private fun String.isImageLike(): Boolean {
    val id = lowercase()
    return listOf("image", "dall", "flux", "stable-diffusion", "sdxl", "ideogram", "recraft").any(id::contains)
}




