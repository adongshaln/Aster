package com.adong.adchat.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserAndTitleTest {
    @Test
    fun askUserAlwaysEndsWithFreeTextAndDropsDuplicates() {
        val prompt = normalizeAskUserPrompt(
            " 写成多长？ ",
            listOf("短篇", "中篇", "描述您的其他想法", "短篇", " ")
        )
        assertEquals("写成多长？", prompt.question)
        assertEquals(listOf("短篇", "中篇", ASK_USER_CUSTOM_OPTION), prompt.options)
    }

    @Test
    fun webSearchSignalIgnoresOrdinaryDeltas() {
        val delta = JSONObject("""{"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}""")
        assertEquals(null, webSearchSignal(delta))
    }

    @Test
    fun webSearchSignalSeesAnActualSearchCall() {
        val searching = JSONObject("""{"type":"response.web_search_call.searching"}""")
        val completed = JSONObject("""{"type":"response.web_search_call.completed"}""")
        assertEquals(WebSearchSignal.Searching, webSearchSignal(searching))
        assertEquals(WebSearchSignal.Completed, webSearchSignal(completed))
    }

    @Test
    fun automaticTitleIsTheFirstUserLineButARenameIsKept() {
        val messages = listOf(
            ChatMessage(role = "user", content = "帮我写一个雨夜赶路的开头"),
            ChatMessage(role = "assistant", content = "雨下得很大。"),
            ChatMessage(role = "user", content = "再短一点")
        )
        assertTrue(isAutomaticTitle("帮我写一个雨夜赶路的开头", messages))
        assertFalse(isAutomaticTitle("雨夜", messages))
        assertEquals("雨夜赶路", sanitizeConversationTitle("「雨夜赶路」。\n不要解释"))
    }
}
