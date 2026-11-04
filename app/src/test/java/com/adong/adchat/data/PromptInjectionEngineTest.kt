package com.adong.adchat.data

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
}
