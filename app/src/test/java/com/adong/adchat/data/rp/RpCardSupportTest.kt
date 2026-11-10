package com.adong.adchat.data.rp

import com.adong.adchat.data.rp.prompt.RpMacroContext
import com.adong.adchat.data.rp.prompt.RpPromptEngine
import com.adong.adchat.data.rp.prompt.RpRegex
import com.adong.adchat.data.TavernPreset
import com.adong.adchat.data.story.StoryCharacterCard
import com.adong.adchat.data.story.StoryDepthPrompt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpCardSupportTest {
    private fun baseCard(extensionsRaw: String = "{}") = StoryCharacterCard(
        name = "朝凪", description = "图书管理员", personality = "温和", scenario = "旧图书馆",
        imported = true, extensionsRaw = extensionsRaw
    )

    private fun regexExtensions(vararg scripts: JSONObject): String =
        JSONObject().put("regex_scripts", JSONArray(scripts.toList())).toString()

    private fun script(
        id: String, find: String, replace: String, placement: Int = 2,
        promptOnly: Boolean = false, markdownOnly: Boolean = false
    ): JSONObject = JSONObject()
        .put("id", id).put("scriptName", id).put("findRegex", find).put("replaceString", replace)
        .put("placement", JSONArray(listOf(placement))).put("promptOnly", promptOnly).put("markdownOnly", markdownOnly)

    private fun build(card: StoryCharacterCard, history: List<Pair<String, String>>, preset: TavernPreset? = null) =
        RpPromptEngine.build(
            RpPromptRequest(
                card = card,
                messages = history.mapIndexed { i, (role, text) -> RpPromptMessage(i + 1L, role, text) },
                persona = RpPersonaContext(name = "我"),
                preset = preset
            )
        )

    @Test
    fun cardRegexAppliesOnPromptWithoutPreset() {
        val extensions = regexExtensions(script("strip", "/<state>[\\s\\S]*?</state>/g", "", promptOnly = true))
        val result = build(baseCard(extensions), listOf("assistant" to "正文<state>hp=10</state>结尾"))
        assertTrue(result.messages.any { it.content == "正文结尾" })
    }

    @Test
    fun markdownOnlyCardRegexNeverTouchesPrompt() {
        val extensions = regexExtensions(
            script("panel", "/<choices>([\\s\\S]*?)</choices>/g", "<div class='panel'>$1</div>", markdownOnly = true)
        )
        val source = "正文<choices>A</choices>结尾"
        val result = build(baseCard(extensions), listOf("assistant" to source))
        assertTrue(result.messages.any { it.content == source })
        val display = RpRegex.display(source, "assistant", 0, RpRegex.scripts(null, baseCard(extensions)), RpMacroContext())
        assertTrue(display.text.contains("<div class='panel'>A</div>"))
    }

    @Test
    fun depthPromptInjectedAtFixedDepth() {
        val card = baseCard().copy(depthPrompt = StoryDepthPrompt(depth = 1, prompt = "保持口吻", role = "system"))
        val result = build(card, listOf("user" to "A", "assistant" to "B", "user" to "C"))
        val contents = result.messages.map { it.content }
        val note = contents.indexOf("保持口吻")
        assertTrue(note >= 0)
        assertEquals("C", contents[note + 1])
        assertEquals("B", contents[note - 1])
    }

    @Test
    fun userMacroResolvesToPersonaAtPromptTime() {
        val card = baseCard().copy(description = "{{user}} 是新来的读者")
        val result = build(card, listOf("user" to "你好"))
        assertTrue((result.systemPrompt + result.messages.joinToString { it.content }).contains("我 是新来的读者"))
    }

    @Test
    fun helperScriptsAreCountedButNotExecuted() {
        val extensions = JSONObject().put(
            "tavern_helper",
            JSONObject().put("scripts", JSONArray(listOf(JSONObject().put("name", "a.js"), JSONObject().put("name", "b.js"))))
        ).toString()
        assertEquals(2, RpCardExtensions.helperScriptCount(baseCard(extensions)))
        assertEquals(0, RpCardExtensions.helperScriptCount(baseCard()))
    }

    @Test
    fun cardRegexScriptsAreParsedFromExtensions() {
        val extensions = regexExtensions(
            script("r1", "/a/g", "b"),
            script("r2", "/c/g", "d", markdownOnly = true)
        )
        val scripts = RpCardExtensions.scripts(baseCard(extensions))
        assertEquals(2, scripts.size)
        assertEquals("r1", scripts[0].id)
        assertTrue(scripts[1].markdownOnly)
        assertTrue(RpCardExtensions.scripts(baseCard()).isEmpty())
    }
}