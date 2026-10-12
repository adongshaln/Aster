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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TavernPresetTest {
    @Test fun promptContentPersistsAndChangesActualRequestWithoutChangingDefaults() {
        val context = RuntimeEnvironment.getApplication()
        val store = TavernPresetStore(context)
        val preset = store.importPreset(ByteArrayInputStream(samplePreset().toByteArray()), "Editable.json")
        val edited = "  {{setvar::tone::冷静}}\n\n"
        store.setPromptContent(preset.id, "init", edited)
        store.setPromptContent(preset.id, "disabled", "用户自己的要求")
        store.select(preset.id)
        val reopened = TavernPresetStore(context)
        val config = reopened.activeConfiguration()!!
        assertEquals(edited, config.prompts.first { it.identifier == "init" }.content)
        assertTrue(config.prompts.first { it.identifier == "init" }.contentModified)
        assertFalse(config.prompts.first { it.identifier == "disabled" }.enabled)
        assertEquals(2, config.modifiedCount)
        val request = TavernPresetRuntime.prepare(reopened.active()!!, "BASE", listOf(ChatMessage(role = "user", content = "继续")))
        assertTrue(request.history.any { it.content == "冷静|继续|1|x" })
        assertFalse(request.history.any { it.content == "用户自己的要求" })
        reopened.setPromptEnabled(preset.id, "disabled", true)
        assertTrue(TavernPresetRuntime.prepare(reopened.active()!!, "BASE", emptyList()).history.any { it.content == "用户自己的要求" })
        val defaultContent = config.prompts.first { it.identifier == "init" }.defaultContent
        reopened.setPromptContent(preset.id, "init", defaultContent)
        assertFalse(reopened.activeConfiguration()!!.prompts.first { it.identifier == "init" }.contentModified)
        assertTrue(reopened.activeConfiguration()!!.prompts.first { it.identifier == "disabled" }.contentModified)
        reopened.resetConfiguration(preset.id)
        assertEquals(0, reopened.activeConfiguration()!!.modifiedCount)
        assertEquals("不能出现", reopened.activeConfiguration()!!.prompts.first { it.identifier == "disabled" }.content)
    }

    @Test fun builtinContentSupportsEmptyAndLongEditsAndIsIsolatedFromImports() {
        val store = TavernPresetStore(RuntimeEnvironment.getApplication())
        val id = TavernPresetStore.BUILT_IN_ID
        val prompt = store.configuration(id)!!.prompts.first { !it.marker && it.enabled }
        val longText = "长文本与换行\n".repeat(4000)
        store.setPromptContent(id, prompt.identifier, longText)
        assertEquals(longText, TavernPresetStore(RuntimeEnvironment.getApplication()).active()!!.prompts.first { it.identifier == prompt.identifier }.content)
        val imported = store.importPreset(ByteArrayInputStream(samplePreset().toByteArray()), "Other.json")
        assertEquals(0, store.configuration(imported.id)!!.modifiedCount)
        store.setPromptContent(id, prompt.identifier, "")
        assertEquals("", store.configuration(id)!!.prompts.first { it.identifier == prompt.identifier }.content)
        assertTrue(store.configuration(id)!!.prompts.first { it.identifier == prompt.identifier }.contentModified)
        assertTrue(runCatching { store.setPromptContent(id, "chatHistory", "不能变成普通提示") }.isFailure)
        assertTrue(runCatching { store.setPromptContent(id, prompt.identifier, "x".repeat(MAX_TAVERN_PROMPT_CHARS + 1)) }.isFailure)
        assertEquals("", store.configuration(id)!!.prompts.first { it.identifier == prompt.identifier }.content)
    }

    @Test fun builtinNativeProseKeepsPlanningAcrossRegexCleanupAndDepth() {
        val preset = TavernPresetStore(RuntimeEnvironment.getApplication()).list().first { it.builtIn }
        val raw = "<konatan_planning~>核对设定</konatan_planning~>\n正文保留\n<details><summary>摘要</summary>到达城门</details>"
        for (depth in listOf(0, 3, 12)) for (enabled in listOf(true, false)) {
            val view = com.adong.adchat.data.story.StoryProsePresenter.present(raw, preset, "assistant", depth, enabled, false)
            assertEquals("核对设定", view.planning)
            assertTrue(view.blocks.any { "正文保留" in it.text })
            assertTrue(view.blocks.any { "到达城门" in it.text })
        }
    }
    @Before
    fun setUpStoredPresets() = clearStoredPresets()

    @After
    fun tearDownStoredPresets() = clearStoredPresets()

    private fun clearStoredPresets() {
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
        assertEquals("温柔|最后输入|1|x", prepared.history[3].content)
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
    fun oversizedCaptureExpansionKeepsOriginalAndContinuesFollowingRules() {
        val base = TavernPresetParser.parse(samplePreset(), "test", "Sample.json", false)
        val expansion = base.regexScripts.first().copy(
            name = "oversized", findRegex = "/([\\s\\S]+)/", replaceString = "$1".repeat(2_000),
            disabled = false, placement = setOf(2), markdownOnly = true, promptOnly = false,
            minDepth = null, maxDepth = null
        )
        val following = expansion.copy(name = "following", findRegex = "/END$/", replaceString = "DONE")
        val source = "正文".repeat(32_000) + "END"
        val output = TavernPresetRuntime.display(
            base.copy(regexScripts = listOf(expansion, following)), source, "assistant", 0, true
        )
        assertEquals(source.removeSuffix("END") + "DONE", output.text)
        assertEquals(listOf("oversized"), output.skippedScripts)
        assertEquals(1, output.appliedScripts)
    }

    @Test
    fun optionalPresetMacrosDoNotLeakAsRawPlaceholders() {
        val root = JSONObject(samplePreset())
        root.getJSONArray("prompts").put(
            prompt("optional-macros", "optional macros", "system", "{{date}}|{{time}}|{{trim}}|{{random::唯一选项}}", enabled = true)
        )
        root.getJSONArray("prompt_order").getJSONObject(0).getJSONArray("order")
            .put(JSONObject().put("identifier", "optional-macros").put("enabled", true))
        val prepared = TavernPresetRuntime.prepare(
            TavernPresetParser.parse(root.toString(), "test", "Sample.json", false),
            "ASTER BASE",
            emptyList(),
            random = Random(7)
        )

        val content = prepared.history.first { it.role == "system" }.content
        assertFalse(content.contains("{{"))
        assertTrue(content.endsWith("||唯一选项"))
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

    @Test
    fun importWithoutDeclaredNameUsesFileNameAndRenamePersists() {
        val context = RuntimeEnvironment.getApplication()
        val store = TavernPresetStore(context)
        val imported = store.importPreset(ByteArrayInputStream(samplePreset().toByteArray()), "我的故事预设.json")
        assertEquals("我的故事预设", store.list().first { it.id == imported.id }.name)

        store.renamePreset(imported.id, "改名后的预设")
        val reopened = TavernPresetStore(context)
        assertEquals("改名后的预设", reopened.list().first { it.id == imported.id }.name)
        assertEquals("改名后的预设", reopened.configuration(imported.id)!!.name)
        assertTrue(runCatching { store.renamePreset(imported.id, "  ") }.isFailure)
        assertTrue(runCatching { store.renamePreset(TavernPresetStore.BUILT_IN_ID, "x") }.isFailure)
    }

    @Test
    fun promptOnlyTagStripperHidesBlockOnDisplayAtAnyDepth() {
        val preset = presetWith(hider())
        val source = "正文<choices>A\nB</choices>结尾"
        val display = TavernPresetRuntime.display(preset, source, "assistant", 5, true)
        assertEquals("正文结尾", display.text)
        val prompt = TavernRegexEngine.apply(
            preset, source, "assistant", 5, TavernRegexSurface.Prompt
        )
        assertEquals("正文结尾", prompt.text)
    }

    @Test
    fun promptOnlyExpansionToHtmlDocumentIsNotTreatedAsHider() {
        // linux_1.2-style: promptOnly script that EXPANDS tags into a full HTML document
        val expander = TavernRegexScript(
            id = "expander", name = "选项美化",
            findRegex = "/<choices>([\\s\\S]*?)<\\/choices>/g",
            replaceString = "```\n<!DOCTYPE html><html><body>choices panel</body></html>\n```",
            trimStrings = emptyList(), placement = setOf(2), disabled = false,
            promptOnly = true, markdownOnly = false, runOnEdit = false, substituteRegex = 0,
            minDepth = null, maxDepth = 1
        )
        val hiderScript = hider()
        val preset = presetWith(expander, hiderScript)
        // depth 5: expander has maxDepth=1 so it does not run; hider strips the raw tags
        val older = TavernPresetRuntime.display(preset, "正文<choices>A</choices>结尾", "assistant", 5, true)
        assertFalse("<choices>" in older.text)
        assertFalse("choices panel" in older.text)
        // depth 1: expander runs and its HTML survives (NOT stripped as a hider)
        val latest = TavernPresetRuntime.display(preset, "正文<choices>A</choices>结尾", "assistant", 1, true)
        assertTrue("choices panel" in latest.text)
        assertFalse("<choices>" in latest.text)
    }

    @Test
    fun wholeMessageBlankerDoesNotHideDisplay() {
        val blanker = TavernRegexScript(
            id = "blanker", name = "blanker", findRegex = "/^([\\s\\S]*?)$/", replaceString = "",
            trimStrings = emptyList(), placement = setOf(1, 2), disabled = false,
            promptOnly = true, markdownOnly = false, runOnEdit = false, substituteRegex = 0,
            minDepth = 120, maxDepth = null
        )
        val preset = presetWith(blanker)
        val display = TavernPresetRuntime.display(preset, "正文", "assistant", 200, true)
        assertEquals("正文", display.text)
        val prompt = TavernRegexEngine.apply(
            preset, "正文", "assistant", 200, TavernRegexSurface.Prompt
        )
        assertEquals("", prompt.text)
    }

    @Test
    fun displayHiderIgnoresDepthLimits() {
        val preset = presetWith(hider().copy(minDepth = 2, maxDepth = 40))
        val source = "正文<choices>A</choices>结尾"
        for (depth in listOf(0, 1, 41, 99)) {
            val output = TavernPresetRuntime.display(preset, source, "assistant", depth, true)
            assertEquals("正文结尾", output.text)
        }
    }

    @Test
    fun promptOnlyTransformDoesNotLeakToDisplay() {
        val transform = hider().copy(findRegex = "/^([\\s\\S]*?)$/", replaceString = "【MARK】\$1")
        val preset = presetWith(transform)
        val output = TavernPresetRuntime.display(preset, "正文", "assistant", 5, true)
        assertEquals("正文", output.text)
    }

    @Test
    fun hiderRunsAfterBeautifier() {
        val beautifier = TavernRegexScript(
            id = "beautifier", name = "beautifier", findRegex = "/<choices>([\\s\\S]*?)<\\/choices>/g",
            replaceString = "```html\nPANEL(\$1)\n```",
            trimStrings = emptyList(), placement = setOf(2), disabled = false,
            promptOnly = false, markdownOnly = true, runOnEdit = false, substituteRegex = 0,
            minDepth = null, maxDepth = 1
        )
        val preset = presetWith(beautifier, hider())
        val latest = TavernPresetRuntime.display(preset, "正文<choices>A\nB</choices>结尾", "assistant", 1, true)
        assertTrue("PANEL(" in latest.text)
        assertFalse("<choices>" in latest.text)
        val older = TavernPresetRuntime.display(preset, "正文<choices>A\nB</choices>结尾", "assistant", 5, true)
        assertFalse("<choices>" in older.text)
        assertFalse("PANEL(" in older.text)
        assertTrue("正文" in older.text && "结尾" in older.text)
    }

    @Test
    fun afterHistoryUserAndAssistantModulesMoveBeforeChatHistorySystemStaysAfter() {
        val prompts = JSONArray()
            .put(prompt("sys-a", "A", "system", "SYS_A", enabled = true))
            .put(prompt("user-b", "B", "user", "USER_B", enabled = true))
            .put(prompt("chatHistory", "history", "system", "", enabled = true, marker = true))
            .put(prompt("user-c", "C", "user", "USER_C", enabled = true))
            .put(prompt("assistant-d", "D", "assistant", "ASSISTANT_D", enabled = true))
            .put(prompt("sys-e", "E", "system", "SYS_E", enabled = true))
        val order = JSONArray()
            .put(JSONObject().put("identifier", "sys-a").put("enabled", true))
            .put(JSONObject().put("identifier", "user-b").put("enabled", true))
            .put(JSONObject().put("identifier", "chatHistory").put("enabled", true))
            .put(JSONObject().put("identifier", "user-c").put("enabled", true))
            .put(JSONObject().put("identifier", "assistant-d").put("enabled", true))
            .put(JSONObject().put("identifier", "sys-e").put("enabled", true))
        val root = JSONObject(samplePreset())
            .put("prompts", prompts)
            .put("prompt_order", JSONArray().put(JSONObject().put("character_id", 1).put("order", order)))
        val prepared = TavernPresetRuntime.prepare(
            TavernPresetParser.parse(root.toString(), "test", "Sample.json", false),
            "BASE",
            listOf(
                ChatMessage(role = "user", content = "真实用户"),
                ChatMessage(role = "assistant", content = "真实回复")
            )
        )

        assertEquals(
            listOf(
                "system" to "SYS_A",
                "user" to "USER_B",
                "user" to "真实用户",
                "assistant" to "真实回复",
                "user" to "USER_C",
                "assistant" to "ASSISTANT_D",
                "system" to "SYS_E"
            ),
            prepared.history.map { it.role to it.content }
        )
        val last = prepared.history.last()
        assertEquals("system", last.role)
    }

    @Test
    fun assistantPrefillStaysLastAfterRelocation() {
        val prompts = JSONArray()
            .put(prompt("chatHistory", "history", "system", "", enabled = true, marker = true))
            .put(prompt("assistant-d", "D", "assistant", "ASSISTANT_D", enabled = true))
        val order = JSONArray()
            .put(JSONObject().put("identifier", "chatHistory").put("enabled", true))
            .put(JSONObject().put("identifier", "assistant-d").put("enabled", true))
        val root = JSONObject(samplePreset())
            .put("prompts", prompts)
            .put("prompt_order", JSONArray().put(JSONObject().put("character_id", 1).put("order", order)))
            .put("assistant_prefill", "PREFILL")
        val prepared = TavernPresetRuntime.prepare(
            TavernPresetParser.parse(root.toString(), "test", "Sample.json", false),
            "BASE",
            listOf(ChatMessage(role = "user", content = "真实用户"))
        )

        assertEquals("assistant" to "ASSISTANT_D", prepared.history.first().let { it.role to it.content })
        assertEquals("user" to "真实用户", prepared.history[1].let { it.role to it.content })
        assertEquals("assistant" to "PREFILL", prepared.history.last().let { it.role to it.content })
    }

    private fun hider(): TavernRegexScript = TavernRegexScript(
        id = "hider", name = "hider", findRegex = "/<choices>([\\s\\S]*?)<\\/choices>/g", replaceString = "",
        trimStrings = emptyList(), placement = setOf(2), disabled = false,
        promptOnly = true, markdownOnly = false, runOnEdit = false, substituteRegex = 0,
        minDepth = null, maxDepth = null
    )

    private fun presetWith(vararg scripts: TavernRegexScript): TavernPreset =
        TavernPresetParser.parse(samplePreset(), "test", "Sample.json", false)
            .copy(regexScripts = scripts.toList())

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
