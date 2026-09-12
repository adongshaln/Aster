package com.adong.adchat.data

import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TavernPresetTest {
    @Before
    @After
    fun clearStoredPresets() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aster_tavern_presets", 0).edit().clear().commit()
        File(context.filesDir, "tavern_presets").deleteRecursively()
    }

    @Test
    fun promptOrderMacrosAndChatHistoryArePreserved() {
        val preset = TavernPresetParser.parse(samplePreset(), "test", "Sample.json", false)
        val prepared = TavernPresetRuntime.prepare(
            preset = preset,
            baseSystemPrompt = "ASTER BASE",
            history = listOf(
                ChatMessage(role = "user", content = "第一问"),
                ChatMessage(role = "assistant", content = "旧 secret"),
                ChatMessage(role = "user", content = "最后输入")
            ),
            random = Random(7)
        )

        assertTrue(prepared.systemPrompt.startsWith("ASTER BASE"))
        assertTrue(prepared.systemPrompt.contains("不能覆盖 Aster"))
        assertEquals(listOf("user", "assistant", "user", "user"), prepared.history.map { it.role })
        assertEquals("旧 hidden", prepared.history[1].content)
        assertEquals("温柔|最后输入|1|x", prepared.history.last().content)
        assertFalse(prepared.history.joinToString { it.content }.contains("{{"))
        assertEquals(0.7, prepared.generationOptions.temperature!!, 0.0)
        assertEquals(4096, prepared.generationOptions.maxOutputTokens)
    }

    @Test
    fun regexHonorsSurfaceRoleDepthGlobalCaptureAndTrim() {
        val preset = TavernPresetParser.parse(samplePreset(), "test", "Sample.json", false)
        val visible = TavernPresetRuntime.display(
            preset, "<tag>one</tag> <tag>two</tag>", "assistant", depth = 0, regexEnabled = true
        )
        assertEquals("one/one two/two", visible.text)
        assertEquals(1, visible.appliedScripts)

        val tooDeep = TavernPresetRuntime.display(
            preset, "<tag>one</tag>", "assistant", depth = 2, regexEnabled = true
        )
        assertEquals("<tag>one</tag>", tooDeep.text)
        val user = TavernPresetRuntime.display(
            preset, "<tag>one</tag>", "user", depth = 0, regexEnabled = true
        )
        assertEquals("<tag>one</tag>", user.text)
    }

    @Test
    fun htmlRegexOutputIsWrappedForRestrictedPreview() {
        val output = TavernRegexOutput("正文<style>.x{color:red}</style><span class=x>卡片</span>", 1, emptyList())
        assertTrue(output.containsHtml)
        assertTrue(output.structuredText().startsWith("```html"))
        assertTrue(output.structuredText().contains("正文<style>"))
    }

    @Test
    fun promptAndRegexChoicesUseFileDefaultsPersistAndReset() {
        val context = RuntimeEnvironment.getApplication()
        val store = TavernPresetStore(context)
        val imported = store.importPreset(ByteArrayInputStream(samplePreset().toByteArray()), "Sample.json")
        store.select(imported.id)

        val defaults = store.activeConfiguration()!!
        assertEquals(3, defaults.enabledPromptCount)
        assertEquals(2, defaults.enabledRegexCount)
        assertFalse(defaults.prompts.first { it.identifier == "disabled" }.defaultEnabled)
        assertFalse(defaults.prompts.first { it.identifier == "outside-order" }.defaultEnabled)
        assertEquals(0, defaults.modifiedCount)

        store.setPromptEnabled(imported.id, "disabled", true)
        store.setPromptEnabled(imported.id, "outside-order", true)
        store.setRegexScriptEnabled(imported.id, 0, false)

        val restoredStore = TavernPresetStore(context)
        val configured = restoredStore.activeConfiguration()!!
        assertTrue(configured.prompts.first { it.identifier == "disabled" }.enabled)
        assertTrue(configured.prompts.first { it.identifier == "outside-order" }.enabled)
        assertFalse(configured.regexScripts[0].enabled)
        assertEquals(3, configured.modifiedCount)
        val active = restoredStore.active()!!
        assertEquals(5, active.enabledPromptCount)
        assertEquals(1, active.enabledRegexCount)
        val prepared = TavernPresetRuntime.prepare(
            preset = active,
            baseSystemPrompt = "ASTER BASE",
            history = listOf(ChatMessage(role = "assistant", content = "old secret")),
            random = Random(7)
        )
        assertTrue(prepared.history.any { it.content == "不能出现" })
        assertTrue(prepared.history.any { it.content == "顺序外候选" })
        assertTrue(prepared.history.any { it.content == "old secret" })

        restoredStore.resetConfiguration(imported.id)
        val reset = restoredStore.activeConfiguration()!!
        assertEquals(0, reset.modifiedCount)
        assertFalse(reset.prompts.first { it.identifier == "disabled" }.enabled)
        assertTrue(reset.regexScripts[0].enabled)
    }

    @Test
    fun builtInPresetExposesItsExactDefaultChoices() {
        val configuration = TavernPresetStore(RuntimeEnvironment.getApplication()).activeConfiguration()!!

        assertEquals(TavernPresetStore.BUILT_IN_ID, configuration.id)
        assertEquals(223, configuration.prompts.size)
        assertEquals(60, configuration.enabledPromptCount)
        assertEquals(30, configuration.regexScripts.size)
        assertEquals(19, configuration.enabledRegexCount)
        assertTrue(configuration.prompts.first { it.name == "⚡️字数加强" }.defaultEnabled.not())
        assertTrue(configuration.prompts.first { it.name == "🚢文风-顺眼舒服" }.defaultEnabled)
    }

    private fun samplePreset(): String {
        val prompts = JSONArray()
            .put(prompt("init", "init", "system", "{{setvar::tone::温柔}}", enabled = true))
            .put(prompt("chatHistory", "history", "system", "", enabled = true, marker = true))
            .put(prompt("tail", "tail", "user", "{{getvar::tone}}|{{lastUserMessage}}|{{roll 1d1}}|{{random::x::x}}", enabled = true))
            .put(prompt("disabled", "disabled", "system", "不能出现", enabled = true))
            .put(prompt("outside-order", "outside-order", "system", "顺序外候选", enabled = true))
        val order = JSONArray()
            .put(JSONObject().put("identifier", "init").put("enabled", true))
            .put(JSONObject().put("identifier", "chatHistory").put("enabled", true))
            .put(JSONObject().put("identifier", "tail").put("enabled", true))
            .put(JSONObject().put("identifier", "disabled").put("enabled", false))
        val regex = JSONArray()
            .put(
                JSONObject()
                    .put("id", "prompt")
                    .put("scriptName", "prompt cleanup")
                    .put("findRegex", "/secret/g")
                    .put("replaceString", "hidden")
                    .put("placement", JSONArray().put(2))
                    .put("promptOnly", true)
                    .put("markdownOnly", false)
                    .put("disabled", false)
            )
            .put(
                JSONObject()
                    .put("id", "display")
                    .put("scriptName", "display")
                    .put("findRegex", "/<tag>([\\s\\S]*?)<\\/tag>/g")
                    .put("replaceString", "$1/{{match}}")
                    .put("trimStrings", JSONArray().put("<tag>").put("</tag>"))
                    .put("placement", JSONArray().put(2))
                    .put("promptOnly", false)
                    .put("markdownOnly", true)
                    .put("maxDepth", 1)
                    .put("disabled", false)
            )
        return JSONObject()
            .put("temperature", 0.7)
            .put("openai_max_tokens", 4096)
            .put("prompts", prompts)
            .put("prompt_order", JSONArray().put(JSONObject().put("character_id", 1).put("order", order)))
            .put("extensions", JSONObject().put("regex_scripts", regex))
            .toString()
    }

    private fun prompt(
        id: String,
        name: String,
        role: String,
        content: String,
        enabled: Boolean,
        marker: Boolean = false
    ): JSONObject = JSONObject()
        .put("identifier", id)
        .put("name", name)
        .put("role", role)
        .put("content", content)
        .put("enabled", enabled)
        .put("marker", marker)
}
