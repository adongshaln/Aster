package com.adong.adchat.data.rp.prompt

import com.adong.adchat.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpPromptPostProcessTest {
    private fun m(role: String, text: String) = ChatMessage(role = role, content = text)

    @Test
    fun strictFoldsInChatSystemAndEndsOnUser() {
        val r = RpPromptPostProcess.apply(
            "", listOf(m("system", "规则"), m("assistant", "开场"), m("user", "你好"), m("system", "格式要求"), m("system", "思维链")),
            RpPromptPostProcess.Mode.Strict, keepAssistantPrefill = false
        )
        assertEquals("规则", r.systemPrompt)
        assertEquals(listOf("user", "assistant", "user"), r.messages.map { it.role })
        assertTrue(r.messages.last().content.contains("你好") && r.messages.last().content.contains("思维链"))
        assertTrue(r.messages.zipWithNext().none { (a, b) -> a.role == b.role })
    }

    @Test
    fun lenientKeepsSystemInPlace() {
        val r = RpPromptPostProcess.apply("", listOf(m("user", "你好"), m("system", "提示")), RpPromptPostProcess.Mode.Lenient, false)
        assertEquals(listOf("user", "system"), r.messages.map { it.role })
    }
}
