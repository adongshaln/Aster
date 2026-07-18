package com.adong.adchat.data

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ConversationStore(context: Context) {
    private val prefs = context.getSharedPreferences("adchat_conversations", Context.MODE_PRIVATE)
    private val recoveryPrefs = context.getSharedPreferences("adchat_stream_recovery", Context.MODE_PRIVATE)

    @SuppressLint("ApplySharedPref")
    fun load(): List<Conversation> {
        val stored = decodeConversations(prefs.getString(KEY, "[]").orEmpty()).toMutableList()
        val recoveryRaw = recoveryPrefs.getString(RECOVERY_KEY, null)?.takeIf { it.isNotBlank() }
        val recovery = recoveryRaw
            ?.let { raw -> runCatching { decodeConversation(JSONObject(raw)) }.getOrNull() }
            ?.takeIf { it.messages.isNotEmpty() }
        if (recoveryRaw != null && recovery == null) {
            recoveryPrefs.edit().remove(RECOVERY_KEY).apply()
        }
        if (recovery != null) {
            val index = stored.indexOfFirst { it.id == recovery.id }
            val existing = stored.getOrNull(index)
            val restored = recovery.copy(
                title = existing?.title ?: recovery.title,
                createdAt = existing?.createdAt ?: recovery.createdAt
            )
            var recoveryMerged = false
            if (existing == null) {
                stored += restored
                recoveryMerged = true
            } else if (restored.updatedAt > existing.updatedAt) {
                stored[index] = restored
                recoveryMerged = true
            }
            if (recoveryMerged) {
                val committed = prefs.edit()
                    .putString(KEY, encodeConversations(stored).toString())
                    .commit()
                if (committed) recoveryPrefs.edit().remove(RECOVERY_KEY).commit()
            } else {
                recoveryPrefs.edit().remove(RECOVERY_KEY).apply()
            }
        }
        return stored.sortedByDescending { it.updatedAt }
    }

    fun save(conversations: List<Conversation>, clearRecoveryMessageId: Long? = null) {
        prefs.edit().putString(KEY, encodeConversations(conversations).toString()).apply()
        if (clearRecoveryMessageId != null && recoveryMessageId() == clearRecoveryMessageId) {
            recoveryPrefs.edit().remove(RECOVERY_KEY).apply()
        }
    }

    @SuppressLint("ApplySharedPref")
    fun saveStreamRecovery(conversation: Conversation) {
        // This runs on Dispatchers.IO and intentionally commits a paid partial response
        // before acknowledging the checkpoint to the streaming loop.
        recoveryPrefs.edit().putString(RECOVERY_KEY, encodeConversation(conversation, includeStreaming = true).toString()).commit()
    }

    private fun recoveryMessageId(): Long? {
        val raw = recoveryPrefs.getString(RECOVERY_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val messages = JSONObject(raw).optJSONArray("messages") ?: return@runCatching null
            messages.optJSONObject(messages.length() - 1)?.optLong("id")
        }.getOrNull()
    }

    private fun decodeConversations(raw: String): List<Conversation> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)
                    ?.let(::decodeConversation)
                    ?.takeIf { it.messages.isNotEmpty() }
                    ?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun decodeConversation(item: JSONObject): Conversation {
        val messagesJson = item.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (j in 0 until messagesJson.length()) {
                val message = messagesJson.optJSONObject(j) ?: continue
                add(ChatMessage(
                    id = message.optLong("id", System.nanoTime()),
                    role = message.optString("role"),
                    content = message.optString("content"),
                    isError = message.optBoolean("isError"),
                    isStreaming = false,
                    isInterrupted = message.optBoolean("isInterrupted"),
                    isStopped = message.optBoolean("isStopped"),
                    isRecovering = false,
                    streamRecoveryCount = message.optInt("streamRecoveryCount"),
                    profileName = message.optString("profileName"),
                    model = message.optString("model"),
                    usage = message.optJSONObject("usage")?.let(::decodeUsage)
                ))
            }
        }
        return Conversation(
            id = item.optString("id"),
            title = item.optString("title").ifBlank { "\u672a\u547d\u540d\u5bf9\u8bdd" },
            messages = messages,
            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun encodeConversations(conversations: List<Conversation>): JSONArray = JSONArray().apply {
        conversations.sortedByDescending { it.updatedAt }.take(MAX_CONVERSATIONS).forEach { conversation ->
            put(encodeConversation(conversation, includeStreaming = false))
        }
    }

    private fun encodeConversation(conversation: Conversation, includeStreaming: Boolean): JSONObject = JSONObject()
        .put("id", conversation.id)
        .put("title", conversation.title)
        .put("createdAt", conversation.createdAt)
        .put("updatedAt", conversation.updatedAt)
        .put("messages", JSONArray().apply {
            conversation.messages.filter { includeStreaming || !it.isStreaming }.forEach { message ->
                put(JSONObject()
                    .put("id", message.id)
                    .put("role", message.role)
                    .put("content", message.content)
                    .put("isError", message.isError)
                    .put("isInterrupted", message.isInterrupted)
                    .put("isStopped", message.isStopped)
                    .put("streamRecoveryCount", message.streamRecoveryCount)
                    .put("profileName", message.profileName)
                    .put("model", message.model)
                    .put("usage", message.usage?.let(::encodeUsage)))
            }
        })

    private fun encodeUsage(usage: TokenUsage): JSONObject = JSONObject()
        .put("inputTokens", usage.inputTokens)
        .put("cachedTokens", usage.cachedTokens)
        .put("cacheWriteTokens", usage.cacheWriteTokens)
        .put("outputTokens", usage.outputTokens)
        .put("reasoningTokens", usage.reasoningTokens)
        .put("totalTokens", usage.totalTokens)
        .put("timeToFirstTokenMs", usage.timeToFirstTokenMs)
        .put("durationMs", usage.durationMs)
        .put("cacheRequested", usage.cacheRequested)
        .put("cacheKey", usage.cacheKey)
        .put("cacheMetricsReported", usage.cacheMetricsReported)
        .put("cacheStrategy", usage.cacheStrategy)
        .put("streamRecoveryCount", usage.streamRecoveryCount)

    private fun decodeUsage(item: JSONObject): TokenUsage = TokenUsage(
        inputTokens = item.optInt("inputTokens"),
        cachedTokens = item.optInt("cachedTokens"),
        cacheWriteTokens = item.optInt("cacheWriteTokens"),
        outputTokens = item.optInt("outputTokens"),
        reasoningTokens = item.optInt("reasoningTokens"),
        totalTokens = item.optInt("totalTokens"),
        timeToFirstTokenMs = item.optLong("timeToFirstTokenMs").takeIf { item.has("timeToFirstTokenMs") && !item.isNull("timeToFirstTokenMs") },
        durationMs = item.optLong("durationMs").takeIf { item.has("durationMs") && !item.isNull("durationMs") },
        cacheRequested = item.optBoolean("cacheRequested"),
        cacheKey = item.optString("cacheKey"),
        cacheMetricsReported = item.optBoolean("cacheMetricsReported"),
        cacheStrategy = item.optString("cacheStrategy").ifBlank { if (item.optBoolean("cacheRequested")) "automatic" else "off" },
        streamRecoveryCount = item.optInt("streamRecoveryCount")
    )

    private companion object {
        const val KEY = "conversations_v1"
        const val RECOVERY_KEY = "active_stream_v1"
        const val MAX_CONVERSATIONS = 100
    }
}
