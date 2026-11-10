package com.adong.adchat.data.rp

import com.adong.adchat.data.rp.prompt.RpMacroContext
import com.adong.adchat.data.rp.prompt.RpRegex
import com.adong.adchat.data.story.StoryCharacterCard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpPresentationTest {
    private fun card(extensionsRaw: String = "{}") = StoryCharacterCard(
        name = "朝凪", description = "图书管理员", personality = "", scenario = "",
        imported = true, extensionsRaw = extensionsRaw
    )

    private fun build(extensionsRaw: String, content: String, regexEnabled: Boolean = true) = RpPresentation.build(
        content, "assistant", 0,
        if (regexEnabled) RpRegex.scripts(null, card(extensionsRaw)) else emptyList(),
        RpMacroContext(user = "我", char = "朝凪")
    )

    private fun markdownScript(find: String, replace: String): String = JSONObject().put(
        "regex_scripts",
        JSONArray(listOf(JSONObject()
            .put("id", "r").put("scriptName", "r").put("findRegex", find).put("replaceString", replace)
            .put("placement", JSONArray(listOf(2))).put("promptOnly", false).put("markdownOnly", true)))
    ).toString()

    @Test
    fun displayRegexTransformsIntoReadableBlocks() {
        val extensions = markdownScript("/<state>[\\s\\S]*?</state>/g", "")
        val presentation = build(extensions, "正文<state>hp=10</state>结尾", true)
        val text = presentation.blocks.joinToString("\n") { it.text }
        assertTrue(text.contains("正文"))
        assertTrue(text.contains("结尾"))
        assertFalse(text.contains("<state>"))
    }

    @Test
    fun rawHtmlGreetingRoutesToSandbox() {
        val raw = "<script>console.log('contained')</script><div class='panel'>你好，世界。</div>"
        val presentation = build("{}", raw, true)
        val html = presentation.blocks.single { it.html != null }.html!!
        assertTrue(html.contains("你好，世界。"))
    }

    @Test
    fun htmlFragmentsKeepBubblesAndGainMatchedStyles() {
        val extensions = markdownScript("/<bubble\\/>/g", "<style>.x-card{color:red}</style>")
        val content = "正文段落<bubble/>\n<div class=\"tw-item\">推文内容</div>\n<details class=\"x-card\" open><summary>Post</summary>气泡内容</details>"
        val presentation = build(extensions, content, true)
        assertTrue(presentation.blocks.any { it.text.startsWith("正文段落") })
        val html = presentation.blocks.single { it.html != null }.html!!
        assertTrue(html.contains(".x-card{color:red}"))
        assertTrue(html.contains("tw-item"))
        assertTrue(html.contains("气泡内容"))
        assertTrue(presentation.blocks.none { it.title != null })
    }

    @Test
    fun substantialFencedHtmlBecomesSandboxBlock() {
        val document = "<!DOCTYPE html><html><head><style>.x{color:red}</style></head><body><div class='panel'>面板内容</div></body></html>"
        val extensions = markdownScript("/<panel>/g", "```\n$document\n```")
        val presentation = build(extensions, "<panel>", true)
        assertTrue(presentation.blocks.any { it.html != null && it.html!!.contains("面板内容") })
    }

    @Test
    fun regexDisabledKeepsRawContent() {
        val extensions = markdownScript("/<state>[\\s\\S]*?</state>/g", "")
        val presentation = build(extensions, "正文<state>x</state>结尾", false)
        assertEquals("正文<state>x</state>结尾", presentation.blocks.joinToString("") { it.text })
    }

    @Test
    fun unmatchedScriptStylesStayOut() {
        val extensions = markdownScript("/never-match/", "<style>.leak{color:red}</style>")
        val presentation = build(extensions, "<div class=\"panel\">内容</div>")
        assertFalse(presentation.blocks.any { it.html?.contains(".leak") == true })
    }

    @Test
    fun panelTitledCardDocumentIsNotSwallowed() {
        val doc = "<!DOCTYPE html><html><head><title>状态面板</title></head><body><div>HP 10</div></body></html>"
        val presentation = build("{}", "```html\n$doc\n```")
        assertTrue(presentation.blocks.any { it.html?.contains("HP 10") == true })
    }

    @Test
    fun inlineTagLineStaysProse() {
        val presentation = build("{}", "<em>她轻声说</em>，然后离开了。")
        assertTrue(presentation.blocks.none { it.html != null })
    }
}
