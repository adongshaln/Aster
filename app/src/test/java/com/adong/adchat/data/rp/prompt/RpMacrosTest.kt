package com.adong.adchat.data.rp.prompt

import com.adong.adchat.data.rp.InMemoryRpVariableStore
import com.adong.adchat.data.rp.RpPromptMessage
import com.adong.adchat.data.rp.RpVarScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RpMacrosTest {
    private val store = InMemoryRpVariableStore()
    private val ctx = RpMacroContext(
        user = "旅人", char = "朝凪", persona = "{{user}}是访客", description = "{{char}}是图书管理员",
        scenario = "深夜", mesExamples = "<START>\n{{user}}: 你好\n{{char}}: 嗯",
        messages = listOf(RpPromptMessage(1, "assistant", "欢迎"), RpPromptMessage(2, "user", "我来了")),
        variables = store, chatId = "chat-1", random = Random(7)
    )

    private fun x(text: String, c: RpMacroContext = ctx) = RpMacros.expand(text, c)

    @Test
    fun namesAndCardFieldsResolveNestedNames() {
        assertEquals("旅人 朝凪 朝凪是图书管理员 旅人是访客", x("{{user}} {{Char}} {{description}} {{persona}}"))
        assertEquals("旅人朝凪朝凪", x("<USER><BOT><char>"))
        assertEquals("深夜", x("{{scenario}}"))
        assertEquals("<START>\n旅人: 你好\n朝凪: 嗯\n", x("{{mesExamples}}"))
    }

    @Test
    fun messagesAndUtilityMacros() {
        assertEquals("我来了|我来了|欢迎|1", x("{{lastMessage}}|{{lastUserMessage}}|{{lastCharMessage}}|{{lastMessageId}}"))
        assertEquals("a\nb", x("a{{newline}}b"))
        assertEquals("ab", x("a\n{{trim}}\nb"))
        assertEquals("ab", x("a{{noop}}{{// 注释\n多行}}b"))
        assertEquals("cba", x("{{reverse:abc}}"))
        assertEquals("x", x("{{model}}x"))
        assertEquals("orig|", x("{{original}}|{{original}}".let { RpMacros.expand(it, ctx, "orig") }))
    }

    @Test
    fun variablesRunSetBeforeGetRegardlessOfPosition() {
        assertEquals("1", x("{{getvar::hp}}{{setvar::hp::1}}"))
        assertEquals("", x("{{addvar::hp::4}}"))
        assertEquals("5", x("{{getvar::hp}}"))
        assertEquals("6", x("{{incvar::hp}}"))
        assertEquals("5", x("{{decvar::hp}}"))
        x("{{setvar::name::阿}}{{addvar::name::伟}}")
        assertEquals("阿伟", x("{{getvar::name}}"))
        x("{{setglobalvar::g::3}}")
        assertEquals("3", store.table(RpVarScope.Global).optString("g"))
        assertEquals("3", x("{{getglobalvar::g}}"))
        // Comments are removed after variable macros ran.
        x("{{// {{setvar::c::9}} }}")
        assertEquals("9", x("{{getvar::c}}"))
    }

    @Test
    fun pickIsDeterministicAndRandomSplits() {
        val text = "{{pick::甲::乙::丙::丁}} {{pick:a,b,c,d}}"
        val first = x(text)
        repeat(5) { assertEquals(first, x(text, ctx.copy(random = Random(it)))) }
        val other = (1..20).map { x(text, ctx.copy(chatId = "chat-$it")) }.toSet()
        assertTrue(other.size > 1)
        assertTrue(x("{{random:a\\,b,c}}") in setOf("a,b", "c"))
        assertTrue(x("{{random::x::y}}") in setOf("x", "y"))
        val roll = x("{{roll:2d6}}").toInt()
        assertTrue(roll in 2..12)
        assertTrue(x("{{roll 20}}").toInt() in 1..20)
    }

    @Test
    fun tavernHelperVariableMacros() {
        store.replace(RpVarScope.Chat, JSONObject("""{"stat":{"hp":10,"${'$'}meta":1},"who":{"bag":["剑"]}}"""))
        val withFloor = ctx.copy(messageVariables = { JSONObject("""{"位置":"图书馆","list":[1,2]}""") })
        assertEquals("10", x("{{get_chat_variable::stat.hp}}", withFloor))
        assertEquals("""{"hp":10}""", x("{{get_chat_variable::stat}}", withFloor))
        assertEquals("图书馆", x("{{get_message_variable::位置}}", withFloor))
        assertEquals("状态: bag:\n      - \"剑\"", x("状态: {{format_chat_variable::who}}", withFloor))
        assertEquals("  - \"剑\"", x("  {{format_chat_variable::who.bag}}", withFloor))
        assertEquals("null", x("{{get_global_variable::none}}", withFloor))
    }

    @Test
    fun outletsAndShortCircuit() {
        assertEquals("规则", x("{{outlet::rules}}", ctx.copy(outlets = mapOf("rules" to "规则"))))
        assertEquals("plain", x("plain"))
    }
}
