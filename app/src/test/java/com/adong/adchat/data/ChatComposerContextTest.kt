package com.adong.adchat.data

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatComposerContextTest {
    private val document = ChatDocumentAttachment(name = "notes.txt", uri = "file:///notes.txt",
        mimeType = "text/plain", size = 123, text = "完整的附件内容\nDOCUMENT_END")
    private val quote = ChatQuote(messageId = 7, role = "assistant",
        text = "这段原文不会被截断。".repeat(40) + "\nQUOTE_END", partial = true)

    @Test fun allProtocolsCarryFullContextWithAndWithoutImages() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            for ((model, format, response) in listOf(
                Triple("deepseek-test", API_FORMAT_OPENAI, """{"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}]}"""),
                Triple("gpt-5-test", API_FORMAT_OPENAI, """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"OK"}]}]}"""),
                Triple("claude-test", API_FORMAT_ANTHROPIC, """{"content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn"}""")
            )) for (withImage in listOf(false, true)) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response))
                val message = ChatMessage(role = "user", content = "请分析", documents = listOf(document), quotes = listOf(quote),
                    attachments = if (withImage) listOf(ChatImageAttachment(uri = "file:///image.png", name = "image.png",
                        mimeType = "image/png", bytes = byteArrayOf(1, 2, 3))) else emptyList())
                val profile = ApiProfile(baseUrl = server.url("/").toString(), apiKey = "test", apiFormat = format,
                    promptCacheEnabled = false, autoResumeStream = false)
                val result = ApiRepository().streamChat(profile, model, "", listOf(message), "context-test") {}
                assertEquals("OK", result.text)
                val request = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                val messages = request.optJSONArray("input") ?: request.getJSONArray("messages")
                val user = (0 until messages.length()).map(messages::getJSONObject).first { it.optString("role") == "user" }
                val content = user.get("content")
                val text = if (content is String) content else user.getJSONArray("content").getJSONObject(0).getString("text")
                assertEquals(message.requestContent(), text)
                assertTrue(text.contains("QUOTE_END"))
                assertTrue(text.contains("DOCUMENT_END"))
                assertEquals("请分析", message.content)
            }
            assertEquals(6, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun sourceSnapshotsSurviveHistoryAndDraftRoundTrips() {
        val application: Application = RuntimeEnvironment.getApplication()
        val message = ChatMessage(role = "user", content = "", documents = listOf(document), quotes = listOf(quote))
        val conversation = Conversation(id = "context-roundtrip", title = "附件", messages = listOf(message))
        ConversationStore(application).save(listOf(conversation))
        val restored = ConversationStore(application).load().single().messages.single()
        assertEquals(message, restored)
        val context = ChatComposerContext(listOf(document), listOf(quote))
        ChatSessionStore(application).save(mapOf("context-roundtrip" to "自己的文字"), "context-roundtrip",
            contexts = mapOf("context-roundtrip" to context))
        val draft = ChatSessionStore(application).load()
        assertEquals(context, draft.contexts["context-roundtrip"])
        assertEquals("自己的文字", draft.drafts["context-roundtrip"])
        assertEquals("", restored.content)
        assertTrue(ContextTokenEstimate.message(restored) > ContextTokenEstimate.message(ChatMessage(role = "user", content = "")))
    }

    @Test fun enterPreferencePersistsAndOldConfigurationsKeepTheirBehavior() {
        val application: Application = RuntimeEnvironment.getApplication()
        val store = ConfigStore(application)
        val profile = ApiProfile(id = "enter-test")
        store.save(AppConfig(listOf(profile), profile.id, profile.id, sendOnEnter = false))
        assertFalse(ConfigStore(application).load().sendOnEnter)
        val prefs = application.getSharedPreferences("adchat_api_config", Context.MODE_PRIVATE)
        val legacy = JSONObject(prefs.getString("appConfigV2", "{}")!!).apply { remove("sendOnEnter") }
        prefs.edit().putString("appConfigV2", legacy.toString()).commit()
        assertTrue(ConfigStore(application).load().sendOnEnter)
        assertEquals("普通消息", ChatMessage(role = "user", content = "普通消息").requestContent())
    }
}
