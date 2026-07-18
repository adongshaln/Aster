package com.adong.adchat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ApiModel(val id: String, val ownedBy: String = "")

data class ApiProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新 API",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelsPath: String = "/v1/models",
    val chatPath: String = "/v1/chat/completions",
    val responsesPath: String = "/v1/responses",
    val chatApiMode: String = "chat",
    val reasoningEffort: String = "medium",
    val autoResumeStream: Boolean = true,
    val promptCacheEnabled: Boolean = true,
    val promptCacheMode: String = "adaptive",
    val imagePath: String = "/v1/images/generations",
    val imageEditPath: String = "/v1/images/edits",
    val chatModel: String = "",
    val imageModel: String = "",
    val extraHeaders: String = "",
    val cachedModels: List<ApiModel> = emptyList(),
    val lastLatencyMs: Long? = null
)

fun ApiProfile.normalized(): ApiProfile = copy(
    name = name.trim().ifBlank { "未命名 API" },
    baseUrl = baseUrl.trim().trimEnd('/'),
    apiKey = apiKey.trim(),
    modelsPath = modelsPath.trim().ifBlank { "/v1/models" },
    chatPath = chatPath.trim().ifBlank { "/v1/chat/completions" },
    responsesPath = responsesPath.trim().ifBlank { "/v1/responses" },
    chatApiMode = chatApiMode.takeIf { it == "responses" } ?: "chat",
    reasoningEffort = reasoningEffort.ifBlank { "medium" },
    promptCacheMode = promptCacheMode.takeIf { it in setOf("adaptive", "compatibility") } ?: "adaptive",
    imagePath = imagePath.trim().ifBlank { "/v1/images/generations" },
    imageEditPath = imageEditPath.trim().ifBlank { "/v1/images/edits" },
    chatModel = chatModel.trim(),
    imageModel = imageModel.trim(),
    extraHeaders = extraHeaders.lineSequence().map(String::trim).filter(String::isNotBlank).joinToString("\n")
)

fun ApiProfile.hasValidBaseUrl(): Boolean {
    val value = baseUrl.trim().trimEnd('/')
    val parsed = value.toHttpUrlOrNull() ?: return false
    return parsed.host.isNotBlank()
}

fun ApiProfile.invalidExtraHeaderLines(): List<String> = extraHeaders.lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .filter { line ->
        val separator = line.indexOf(':')
        separator <= 0 || line.substring(0, separator).trim().isBlank() || line.substring(separator + 1).trim().isBlank()
    }
    .toList()

data class AppConfig(
    val profiles: List<ApiProfile>,
    val activeChatProfileId: String,
    val activeImageProfileId: String,
    val systemPrompt: String = "你是 ADChat 中可靠、友好的 AI 助手。请使用清晰、自然的中文回答。"
) {
    fun chatProfile(): ApiProfile = profiles.firstOrNull { it.id == activeChatProfileId } ?: profiles.first()
    fun imageProfile(): ApiProfile = profiles.firstOrNull { it.id == activeImageProfileId } ?: profiles.first()
}

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("adchat_api_config", Context.MODE_PRIVATE)

    fun load(): AppConfig {
        prefs.getString("appConfigV2", null)?.let { json ->
            runCatching {
                val config = decode(JSONObject(json))
                if (config.profiles.any { it.apiKey.isNotBlank() } && !json.contains("enc:v1:")) save(config)
                return config
            }
        }
        return migrateLegacy()
    }

    fun save(config: AppConfig) {
        prefs.edit().putString("appConfigV2", encode(config).toString()).apply()
    }

    private fun migrateLegacy(): AppConfig {
        val profile = ApiProfile(
            id = "default",
            name = prefs.getString("profileName", null) ?: "默认连接",
            baseUrl = prefs.getString("baseUrl", null) ?: "https://api.openai.com",
            apiKey = prefs.getString("apiKey", null) ?: "",
            modelsPath = prefs.getString("modelsPath", null) ?: "/v1/models",
            chatPath = prefs.getString("chatPath", null) ?: "/v1/chat/completions",
            responsesPath = prefs.getString("responsesPath", null) ?: "/v1/responses",
            chatApiMode = prefs.getString("chatApiMode", null) ?: "chat",
            reasoningEffort = prefs.getString("reasoningEffort", null) ?: "medium",
            autoResumeStream = true,
            promptCacheEnabled = prefs.getBoolean("promptCacheEnabled", true),
            promptCacheMode = "adaptive",
            imagePath = prefs.getString("imagePath", null) ?: "/v1/images/generations",
            imageEditPath = prefs.getString("imageEditPath", null) ?: "/v1/images/edits",
            chatModel = prefs.getString("chatModel", null) ?: "gpt-4.1-mini",
            imageModel = prefs.getString("imageModel", null) ?: "gpt-image-1",
            extraHeaders = prefs.getString("extraHeaders", null) ?: ""
        )
        return AppConfig(
            profiles = listOf(profile),
            activeChatProfileId = profile.id,
            activeImageProfileId = profile.id,
            systemPrompt = prefs.getString("systemPrompt", null)
                ?: "你是 ADChat 中可靠、友好的 AI 助手。请使用清晰、自然的中文回答。"
        ).also(::save)
    }

    private fun encode(config: AppConfig): JSONObject = JSONObject()
        .put("activeChatProfileId", config.activeChatProfileId)
        .put("activeImageProfileId", config.activeImageProfileId)
        .put("systemPrompt", config.systemPrompt)
        .put("profiles", JSONArray().apply {
            config.profiles.forEach { profile ->
                put(JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("baseUrl", profile.baseUrl)
                    .put("apiKey", encryptSecret(profile.apiKey))
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
                    .put("lastLatencyMs", profile.lastLatencyMs)
                    .put("cachedModels", JSONArray().apply {
                        profile.cachedModels.forEach { model -> put(JSONObject().put("id", model.id).put("ownedBy", model.ownedBy)) }
                    })
                )
            }
        })

    private fun decode(root: JSONObject): AppConfig {
        val array = root.optJSONArray("profiles") ?: JSONArray()
        val profiles = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(ApiProfile(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = item.optString("name").ifBlank { "未命名 API" },
                    baseUrl = item.optString("baseUrl"),
                    apiKey = decryptSecret(item.optString("apiKey")),
                    modelsPath = item.optString("modelsPath").ifBlank { "/v1/models" },
                    chatPath = item.optString("chatPath").ifBlank { "/v1/chat/completions" },
                    responsesPath = item.optString("responsesPath").ifBlank { "/v1/responses" },
                    chatApiMode = item.optString("chatApiMode").ifBlank { "chat" },
                    reasoningEffort = item.optString("reasoningEffort").ifBlank { "medium" },
                    autoResumeStream = item.optBoolean("autoResumeStream", true),
                    promptCacheEnabled = item.optBoolean("promptCacheEnabled", true),
                    promptCacheMode = when (item.optString("promptCacheMode")) {
                        "compatibility" -> "compatibility"
                        else -> "adaptive"
                    },
                    imagePath = item.optString("imagePath").ifBlank { "/v1/images/generations" },
                    imageEditPath = item.optString("imageEditPath").ifBlank { "/v1/images/edits" },
                    chatModel = item.optString("chatModel"),
                    imageModel = item.optString("imageModel"),
                    extraHeaders = item.optString("extraHeaders"),
                    cachedModels = decodeModels(item.optJSONArray("cachedModels")),
                    lastLatencyMs = item.optLong("lastLatencyMs").takeIf { item.has("lastLatencyMs") && !item.isNull("lastLatencyMs") }
                ))
            }
        }.ifEmpty { listOf(ApiProfile(id = "default", name = "默认连接", baseUrl = "https://api.openai.com")) }
        return AppConfig(
            profiles = profiles,
            activeChatProfileId = root.optString("activeChatProfileId").takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id,
            activeImageProfileId = root.optString("activeImageProfileId").takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id,
            systemPrompt = root.optString("systemPrompt").ifBlank { "你是 ADChat 中可靠、友好的 AI 助手。请使用清晰、自然的中文回答。" }
        )
    }


    private fun encryptSecret(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            "enc:v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
        }.getOrElse { value }
    }

    private fun decryptSecret(value: String): String {
        if (!value.startsWith("enc:v1:")) return value
        return runCatching {
            val parts = value.split(':', limit = 4)
            val iv = Base64.decode(parts[2], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[3], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "adchat_api_config_key_v1"
    }    private fun decodeModels(array: JSONArray?): List<ApiModel> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            item.optString("id").takeIf { it.isNotBlank() }?.let { add(ApiModel(it, item.optString("ownedBy"))) }
        }
    }
}

data class ConnectionResult(val latencyMs: Long, val models: List<ApiModel>, val endpoint: String)

data class ChatCompletionResult(val text: String, val usage: TokenUsage)

data class StreamRecoveryEvent(
    val attempt: Int,
    val maxAttempts: Int,
    val reconnecting: Boolean
)

data class TokenUsage(
    val inputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val totalTokens: Int = 0,
    val timeToFirstTokenMs: Long? = null,
    val durationMs: Long? = null,
    val cacheRequested: Boolean = false,
    val cacheKey: String = "",
    val cacheMetricsReported: Boolean = false,
    val cacheStrategy: String = "off",
    val streamRecoveryCount: Int = 0
) {
    val uncachedInputTokens: Int get() = (inputTokens - cachedTokens - cacheWriteTokens).coerceAtLeast(0)
    val cacheHitRate: Float get() = if (inputTokens > 0) cachedTokens.toFloat() / inputTokens else 0f
    val cacheEligible: Boolean get() = inputTokens >= 1024
}

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val role: String,
    val content: String,
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
    val isInterrupted: Boolean = false,
    val isStopped: Boolean = false,
    val isRecovering: Boolean = false,
    val streamRecoveryCount: Int = 0,
    val profileName: String = "",
    val model: String = "",
    val usage: TokenUsage? = null
)

data class GeneratedImage(
    val id: Long = System.nanoTime(),
    val prompt: String,
    val source: String,
    val size: String = "1024x1024",
    val style: String = "原始",
    val profileName: String = "",
    val model: String = "",
    val seriesId: String = "",
    val seriesIndex: Int = 0,
    val seriesTotal: Int = 0,
    val seriesTitle: String = ""
)



data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<ChatMessage>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val profileId: String = "",
    val model: String = ""
)

data class ReferenceImageInput(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String
)

data class ReferenceImageAttachment(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val name: String,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0
)
