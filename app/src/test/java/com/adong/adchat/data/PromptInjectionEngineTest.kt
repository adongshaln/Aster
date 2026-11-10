package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptInjectionEngineTest {
    @Test
    fun regexesCompileAndObfuscateCleanly() {
        val out = PromptInjectionRuntime.applyFilter("朝凪 asanagi she is 17", zeroWidth = true)
        assertTrue(out.contains("\u2060"))
        val body = PromptInjectionRuntime.buildTunnelChatBody(
            model = "gemini-4-argon-high",
            history = listOf(ChatMessage(role = "user", content = "测试")),
            preset = PromptInjectionDefaults.builtInNyGeminiPreset,
            cacheKey = "test-session"
        )
        assertTrue(body.getString("model") == "gemini-4-argon-high")
    }

    @Test
    fun expandMacrosPreservesDollarAndBackslashInUserContent() {
        val user = "Price " + '$' + "100 path C:" + '\\' + "temp" + '\\' + "next " + '$' + "1"
        val history = listOf(ChatMessage(role = "user", content = user))
        val out = PromptInjectionRuntime.expandMacros("Echo: {{lastUserMessage}}", history, zeroWidth = false)
        assertEquals("Echo: $user", out)
    }

    @Test
    fun decoderSkipsBracesInsideNestedStringValues() {
        val decoder = NyDisplayStreamDecoder()
        val out = decoder.feed("""{"status":{"note":"a}b{c"},"display_stream":"target text"}""") + decoder.finish()
        assertEquals("target text", out)
    }

    @Test
    fun decoderBuffersControlMarkersSplitAcrossChunks() {
        val decoder = NyDisplayStreamDecoder()
        val out = decoder.feed("""{"display_stream":"hello<ctr""") +
            decoder.feed("""l94>world"}""") +
            decoder.finish()
        assertEquals("hello<thinking>\nworld", out)
    }

    @Test
    fun injectedHistoryRetainsImageAttachments() {
        val message = ChatMessage(
            role = "user",
            content = "看图",
            attachments = listOf(ChatImageAttachment(uri = "content://img/1", name = "pic.png"))
        )
        val preset = PromptInjectionPreset(
            id = "t", name = "t", description = "",
            pureMode = false, zeroWidthObfuscation = false, entries = emptyList()
        )
        val slot = PromptInjectionRuntime.buildInjectedMessagesList("", listOf(message), preset)
            .single { it.source != null }
        assertEquals("看图", slot.text)
        assertEquals(1, slot.source!!.attachments.size)
    }
}
