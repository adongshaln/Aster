package com.adong.adchat.data.rp.template

import com.adong.adchat.data.rp.InMemoryRpVariableStore
import com.adong.adchat.data.rp.RpPromptMessage
import com.adong.adchat.data.rp.RpVarScope
import com.adong.adchat.data.story.StoryWorldBook
import com.adong.adchat.data.story.StoryWorldBookEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpTemplateTest {
    private val store = InMemoryRpVariableStore()
    private fun ctx(book: StoryWorldBook = StoryWorldBook(), stage: RpTplStage = RpTplStage.Generate) = RpTplContext(
        stage = stage, variables = store, messages = listOf(RpPromptMessage(1, "user", "你好")), worldBook = book,
        userName = "我", charName = "花音", chatId = "c", messageFloor = 1L to 0
    )
    private fun render(src: String, escape: Boolean = false) = RpTemplate.render(src, RpTplHost(ctx()), escape)

    @Test
    fun outputsAndControlFlow() {
        assertEquals("hello world!", render("<% print('hello world!') %>"))
        assertEquals("1,2,3", render("<% const xs = [1, 2, 3] %><%- xs.join(',') %>"))
        assertEquals("偶奇偶", render("<% for (const n of [2, 3, 4]) { %><%- n % 2 === 0 ? '偶' : '奇' %><% } %>"))
        assertEquals("B", render("<% if (1 > 2) { %>A<% } else { %>B<% } %>"))
        assertEquals("a-b", render("<%- `${'a'}-${'b'}` %>"))
        assertEquals("2", render("<%- [1, 2, 3].filter(x => x > 1).length %>"))
    }

    @Test
    fun whitespaceControlAndEscaping() {
        assertEquals("x\ny", render("x\n<% const a = 1 -%>\ny"))
        assertEquals("&lt;b&gt;", render("<%= '<b>' %>", escape = true))
        assertEquals("<b>", render("<%- '<b>' %>", escape = true))
        assertEquals("<%= 1 %>", render("<#escape-ejs><%= 1 %><#/escape-ejs>"))
    }

    @Test
    fun variablesFollowExtensionSemantics() {
        store.replace(RpVarScope.Message, JSONObject().put("好感度", 50), 1L, 0)
        assertEquals("当前好感度：50/100", render("当前好感度：<%- variables.好感度 %>/100"))
        assertEquals("新的好感度: 60/100", render("<% setvar('好感度', 60) -%>\n新的好感度: <%- getvar('好感度') %>/100"))
        render("<% setvar('a', 1, 'nx'); setvar('a', 2, 'nx') %>")
        assertEquals("1", render("<%- getvar('a') %>"))
        render("<% incvar('a') %>")
        assertEquals("2", render("<%- getvar('a') %>"))
    }

    @Test
    fun injectPromptAcrossTemplates() {
        val c = ctx()
        RpTemplate.render("<% injectPrompt('CoT', 'Q: 好感度？') %>", RpTplHost(c))
        assertEquals("<thinking>\nQ: 好感度？\n</thinking>", RpTemplate.render("<thinking>\n<%- getPromptsInjected('CoT') %>\n</thinking>", RpTplHost(c)))
    }

    @Test
    fun brokenTemplateKeepsRaw() {
        assertNull(render("<% if ( %>"))
    }

    @Test
    fun generateEntriesInjectAndIfDecoratorGates() {
        val book = StoryWorldBook(entries = listOf(
            StoryWorldBookEntry(keys = emptyList(), comment = "[GENERATE:BEFORE]", content = "开头"),
            StoryWorldBookEntry(keys = emptyList(), comment = "[GENERATE:AFTER]", content = "@@if getvar('阶段') === 2\n结尾"),
            StoryWorldBookEntry(keys = listOf("王都"), comment = "普通条目", content = "王都在北方")
        ))
        val (system, messages) = RpPromptTemplate.generate("系统", listOf(com.adong.adchat.data.ChatMessage(role = "user", content = "你好")), ctx(book))
        assertEquals("开头\n系统", system)
        assertEquals("你好", messages.single().content)
        assertEquals(1, RpPromptTemplate.scanBook(book).entries.size)
    }

    @Test
    fun initialVariablesFromYaml() {
        val book = StoryWorldBook(entries = listOf(StoryWorldBookEntry(keys = emptyList(), comment = "[InitialVariables]", content = "哈基米:\n  好感度: 0")))
        val vars = RpPromptTemplate.initialVariables(book) { it }
        assertTrue(vars!!.getJSONObject("哈基米").has("好感度"))
    }
}
