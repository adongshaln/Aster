package com.adong.adchat.data.rp.prompt

import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.TavernPreset
import com.adong.adchat.data.TavernPresetParser
import com.adong.adchat.data.rp.RpAuthorsNote
import com.adong.adchat.data.rp.RpGenerationType
import com.adong.adchat.data.rp.RpNotePosition
import com.adong.adchat.data.rp.RpPersonaContext
import com.adong.adchat.data.rp.RpPersonaPosition
import com.adong.adchat.data.rp.RpPromptMessage
import com.adong.adchat.data.rp.RpPromptRequest
import com.adong.adchat.data.story.StoryCharacterCard
import com.adong.adchat.data.story.StoryDepthPrompt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpPromptEngineTest {
    private val card = StoryCharacterCard(
        name = "朝凪", description = "{{char}}是图书管理员", personality = "温和", scenario = "深夜图书馆"
    )
    private val persona = RpPersonaContext(name = "旅人", description = "一位访客")

    private fun msgs(vararg pairs: Pair<String, String>) = pairs.mapIndexed { i, (r, c) -> RpPromptMessage(i + 1L, r, c) }

    private fun prompt(id: String, content: String = "", extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("identifier", id).put("name", id).put("role", "system").put("content", content).apply(extra)

    private fun preset(prompts: List<JSONObject>, orders: JSONArray, root: JSONObject.() -> Unit = {}): TavernPreset {
        val json = JSONObject().put("prompts", JSONArray(prompts)).put("prompt_order", orders).apply(root)
        return TavernPresetParser.parse(json.toString(), "p", "p", false)
    }

    private val markers = listOf("worldInfoBefore", "worldInfoAfter", "charDescription", "charPersonality", "scenario", "personaDescription", "dialogueExamples")

    /** Order with [ids] enabled; markers not listed are present but disabled (as in real ST presets). */
    private fun order(characterId: Int, vararg ids: String) = JSONObject().put("character_id", characterId)
        .put("order", JSONArray(ids.map { JSONObject().put("identifier", it).put("enabled", true) } +
            markers.filter { it !in ids }.map { JSONObject().put("identifier", it).put("enabled", false) }))

    private fun all(result: com.adong.adchat.data.rp.RpPromptResult): List<Pair<String, String>> =
        (if (result.systemPrompt.isEmpty()) emptyList() else listOf("system" to result.systemPrompt)) +
            result.messages.map { it.role to it.content }

    private val standard = listOf(
        prompt("main", "主提示"), prompt("jailbreak", "后置指令 {{char}}"), prompt("nsfw", "辅助"),
        prompt("chatHistory") { put("marker", true) }, prompt("charDescription") { put("marker", true) }
    )

    @Test
    fun promptOrder100001IsPreferredAndJailbreakKept() {
        val orders = JSONArray().put(order(100000, "main", "chatHistory")).put(order(100001, "main", "nsfw", "charDescription", "chatHistory", "jailbreak"))
        val p = preset(standard, orders)
        assertEquals(listOf("main", "nsfw", "charDescription", "chatHistory", "jailbreak"), p.promptOrderRp.filter { it.enabled }.map { it.identifier })
        assertEquals(listOf("main", "chatHistory"), p.promptOrder.filter { it.enabled }.map { it.identifier })
        val r = RpPromptEngine.build(RpPromptRequest(card, msgs("user" to "你好"), persona, preset = p))
        assertEquals("主提示\n\n辅助\n\n朝凪是图书管理员\n\n[Start a new Chat]", r.systemPrompt)
        assertEquals(listOf("user" to "你好", "system" to "后置指令 朝凪"), r.messages.map { it.role to it.content })
    }

    @Test
    fun markersMissingFromOrderAreAppended() {
        val bare = JSONObject().put("character_id", 100001)
            .put("order", JSONArray(listOf("main", "chatHistory").map { JSONObject().put("identifier", it).put("enabled", true) }))
        val r = RpPromptEngine.build(RpPromptRequest(card, msgs("user" to "你好"), persona, preset = preset(standard, JSONArray().put(bare))))
        assertEquals(listOf("user" to "你好", "system" to "朝凪是图书管理员", "system" to "温和", "system" to "深夜图书馆", "system" to "一位访客"),
            r.messages.map { it.role to it.content })
    }

    @Test
    fun cardOverridesUseOriginalAndRespectForbid() {
        val orders = JSONArray().put(order(100001, "main", "chatHistory", "jailbreak"))
        val withCard = card.copy(systemPrompt = "卡片主提示 + {{original}}", postHistory = "卡片后置")
        val r = RpPromptEngine.build(RpPromptRequest(withCard, msgs("user" to "嗨"), persona, preset = preset(standard, orders)))
        assertTrue(r.systemPrompt.startsWith("卡片主提示 + 主提示"))
        assertEquals("卡片后置", r.messages.last().content)

        val forbid = standard.map { if (it.getString("identifier") == "main") JSONObject(it.toString()).put("forbid_overrides", true) else it }
        val r2 = RpPromptEngine.build(RpPromptRequest(withCard, msgs("user" to "嗨"), persona, preset = preset(forbid, orders)))
        assertTrue(r2.systemPrompt.startsWith("主提示"))
    }

    @Test
    fun defaultOrderWithoutPreset() {
        val c = card.copy(mesExample = "<START>\n{{user}}: 问\n{{char}}: 答", postHistory = "PHI")
        val r = RpPromptEngine.build(RpPromptRequest(c, msgs("assistant" to "欢迎光临", "user" to "你好"), persona))
        assertTrue(r.systemPrompt.startsWith(RpPromptEngine.DEFAULT_MAIN_PROMPT.replace("{{char}}", "朝凪").replace("{{user}}", "旅人")))
        assertTrue(r.systemPrompt.contains("一位访客\n\n朝凪是图书管理员\n\n温和\n\n深夜图书馆\n\n[Example Chat]"))
        assertEquals(
            listOf("user" to "问", "assistant" to "答", "system" to "[Start a new Chat]", "assistant" to "欢迎光临", "user" to "你好", "system" to "PHI"),
            r.messages.map { it.role to it.content }
        )
    }

    @Test
    fun inChatInjectionFollowsSillyTavernAlgorithm() {
        val prompts = standard + listOf(
            prompt("d0", "D0") { put("injection_position", 1).put("injection_depth", 0) },
            prompt("d1u", "D1用户") { put("injection_position", 1).put("injection_depth", 1).put("role", "user") },
            prompt("d1s", "D1系统") { put("injection_position", 1).put("injection_depth", 1) },
            prompt("d1hi", "D1高序") { put("injection_position", 1).put("injection_depth", 1).put("injection_order", 200) },
            prompt("d2", "D2") { put("injection_position", 1).put("injection_depth", 2) }
        )
        val orders = JSONArray().put(order(100001, "chatHistory", "d0", "d1u", "d1s", "d1hi", "d2"))
        val c = card.copy(depthPrompt = StoryDepthPrompt(depth = 1, prompt = "角色深度", role = "system"))
        val r = RpPromptEngine.build(
            RpPromptRequest(c, msgs("user" to "u1", "assistant" to "a1", "user" to "u2"), persona.copy(description = ""), preset = preset(prompts, orders))
        )
        assertEquals("[Start a new Chat]", r.systemPrompt)
        assertEquals(
            listOf(
                "user" to "u1", "system" to "D2", "assistant" to "a1",
                "user" to "D1用户", "system" to "D1系统\n角色深度", "system" to "D1高序",
                "user" to "u2", "system" to "D0"
            ),
            r.messages.map { it.role to it.content }
        )
    }

    @Test
    fun authorsNoteIntervalCountsUserMessages() {
        val note = RpAuthorsNote(text = "作者注释", position = RpNotePosition.InChat, depth = 0, interval = 2)
        val one = RpPromptEngine.build(RpPromptRequest(card, msgs("user" to "1"), persona, authorsNote = note))
        assertFalse(all(one).any { it.second.contains("作者注释") })
        val two = RpPromptEngine.build(RpPromptRequest(card, msgs("user" to "1", "assistant" to "a", "user" to "2"), persona, authorsNote = note))
        assertEquals("作者注释", two.messages.dropLast(0).last { it.role == "system" && it.content.contains("作者注释") }.content)
        val three = RpPromptEngine.build(RpPromptRequest(card, msgs("user" to "1", "user" to "2", "user" to "3"), persona, authorsNote = note))
        assertFalse(all(three).any { it.second.contains("作者注释") })
        val topPersona = RpPromptEngine.build(RpPromptRequest(card, msgs("user" to "1", "user" to "2"), persona.copy(position = RpPersonaPosition.TopAuthorsNote), authorsNote = note))
        assertTrue(topPersona.messages.any { it.content == "一位访客\n作者注释" })
        assertFalse(topPersona.systemPrompt.contains("一位访客"))
    }

    @Test
    fun examplesParseIntoBlocks() {
        val blocks = RpExamples.parseMesExamples("{{user}}: hi\n<START>\nA: x\nB: y\nmore")
        assertEquals(2, blocks.size)
        val parsed = RpExamples.parseBlock(blocks[1].replace("<START>", "{Example Dialogue:}"), "A", "B")
        assertEquals(listOf(RpExampleMessage("example_user", "x"), RpExampleMessage("example_assistant", "y\nmore")), parsed)
        assertTrue(RpExamples.parseMesExamples("<START>").isEmpty())
    }

    @Test
    fun continueAndImpersonate() {
        val chat = msgs("user" to "你好", "assistant" to "她抬起头")
        val nudge = RpPromptEngine.build(RpPromptRequest(card, chat, persona, type = RpGenerationType.Continue))
        val tail = nudge.messages.takeLast(2).map { it.role to it.content }
        assertEquals("assistant" to "她抬起头", tail[0])
        assertEquals("system", tail[1].first)

        val p = preset(standard, JSONArray().put(order(100001, "main", "chatHistory"))) {
            put("continue_prefill", true); put("continue_nudge_prompt", "续写：{{lastChatMessage}}")
        }
        val prefill = RpPromptEngine.build(RpPromptRequest(card, chat, persona, preset = p, type = RpGenerationType.Continue))
        assertEquals("assistant" to "她抬起头", prefill.messages.last().let { it.role to it.content })
        val p2 = preset(standard, JSONArray().put(order(100001, "main", "chatHistory"))) { put("continue_nudge_prompt", "续写：{{lastChatMessage}}") }
        val custom = RpPromptEngine.build(RpPromptRequest(card, chat, persona, preset = p2, type = RpGenerationType.Continue))
        assertEquals("续写：她抬起头", custom.messages.last().content)

        val imp = RpPromptEngine.build(RpPromptRequest(card, chat, persona, type = RpGenerationType.Impersonate, quietPrompt = "最后"))
        assertTrue(imp.messages[imp.messages.size - 2].content.contains("point of view of 旅人"))
        assertEquals("最后", imp.messages.last().content)
    }

    @Test
    fun assistantPrefillOnlyForNonImpersonate() {
        val p = preset(standard, JSONArray().put(order(100001, "main", "chatHistory"))) { put("assistant_prefill", "好的，") }
        val normal = RpPromptEngine.build(RpPromptRequest(card, msgs("user" to "嗨"), persona, preset = p))
        assertEquals("assistant" to "好的，", normal.messages.last().let { it.role to it.content })
        val imp = RpPromptEngine.build(RpPromptRequest(card, msgs("user" to "嗨"), persona, preset = p, type = RpGenerationType.Impersonate))
        assertTrue(imp.messages.none { it.content == "好的，" })
    }

    @Test
    fun budgetKeepsNewestAndTrimsGreetingOnlyWhenNeeded() {
        val chat = msgs("assistant" to "开场白".repeat(50), "user" to "第一句", "assistant" to "回应", "user" to "最新")
        val p = preset(standard, JSONArray().put(order(100001, "main", "chatHistory")))
        val roomy = RpPromptEngine.build(RpPromptRequest(card, chat, persona, preset = p, inputBudgetTokens = 10_000))
        assertEquals(0, roomy.omittedMessages)
        assertEquals("开场白".repeat(50), roomy.messages.first().content)
        val tight = RpPromptEngine.build(RpPromptRequest(card, chat, persona, preset = p, inputBudgetTokens = 200))
        assertEquals(1, tight.omittedMessages)
        assertEquals(listOf("第一句", "回应", "最新"), tight.messages.map(ChatMessage::content))
    }

    @Test
    fun memorySummaryReplacesCoveredMessages() {
        val chat = msgs("user" to "旧1", "assistant" to "旧2", "user" to "新")
        val r = RpPromptEngine.build(RpPromptRequest(card, chat, persona, memorySummary = "摘要", coveredUntilId = 2))
        assertTrue(r.systemPrompt.endsWith("[前情提要]\n摘要\n\n[Start a new Chat]"))
        assertEquals(listOf("新"), r.messages.map(ChatMessage::content))
    }

    @Test
    fun injectionTriggerAndRegexAndMacrosOnHistory() {
        val prompts = standard + prompt("onlyImp", "仅扮演") { put("injection_trigger", JSONArray().put("impersonate")) }
        val p = preset(prompts, JSONArray().put(order(100001, "main", "onlyImp", "chatHistory")))
        val normal = RpPromptEngine.build(RpPromptRequest(card, msgs("user" to "{{char}}在吗"), persona, preset = p))
        assertFalse(normal.systemPrompt.contains("仅扮演"))
        assertEquals("朝凪在吗", normal.messages.single().content)
        val imp = RpPromptEngine.build(RpPromptRequest(card, msgs("user" to "x"), persona, preset = p, type = RpGenerationType.Impersonate))
        assertTrue(imp.systemPrompt.contains("仅扮演"))
    }
}
