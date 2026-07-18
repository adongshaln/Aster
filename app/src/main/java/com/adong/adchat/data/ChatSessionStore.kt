package com.adong.adchat.data

import android.content.Context
import org.json.JSONObject

class ChatSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("adchat_chat_session", Context.MODE_PRIVATE)

    fun load(): ChatSessionState = runCatching {
        val draftsJson = JSONObject(prefs.getString(KEY_DRAFTS, "{}") ?: "{}")
        val drafts = buildMap {
            draftsJson.keys().forEach { key ->
                val value = draftsJson.optString(key)
                if (value.isNotBlank()) put(key, value.take(MAX_DRAFT_LENGTH))
            }
        }
        ChatSessionState(
            drafts = drafts,
            lastActiveKey = prefs.getString(KEY_LAST_ACTIVE, NEW_CONVERSATION_KEY) ?: NEW_CONVERSATION_KEY,
            hasSavedSession = prefs.contains(KEY_LAST_ACTIVE)
        )
    }.getOrDefault(ChatSessionState())

    fun save(drafts: Map<String, String>, lastActiveKey: String) {
        val root = JSONObject()
        drafts.asSequence()
            .filter { it.value.isNotBlank() }
            .take(MAX_DRAFTS)
            .forEach { (key, value) -> root.put(key, value.take(MAX_DRAFT_LENGTH)) }
        prefs.edit()
            .putString(KEY_DRAFTS, root.toString())
            .putString(KEY_LAST_ACTIVE, lastActiveKey)
            .apply()
    }

    companion object {
        const val NEW_CONVERSATION_KEY = "__new_conversation__"
        private const val KEY_DRAFTS = "drafts_v1"
        private const val KEY_LAST_ACTIVE = "last_active_v1"
        private const val MAX_DRAFTS = 100
        private const val MAX_DRAFT_LENGTH = 30_000
    }
}

data class ChatSessionState(
    val drafts: Map<String, String> = emptyMap(),
    val lastActiveKey: String = ChatSessionStore.NEW_CONVERSATION_KEY,
    val hasSavedSession: Boolean = false
)
