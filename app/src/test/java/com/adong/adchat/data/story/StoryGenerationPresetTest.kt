package com.adong.adchat.data.story

import com.adong.adchat.data.ChatGenerationOptions
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.TavernPreset
import com.adong.adchat.data.TavernPrompt
import com.adong.adchat.data.TavernPromptOrderEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryGenerationPresetTest {
    private val context = StoryContextResult(
        systemPrompt = "BASE_STORY_SYSTEM",
        history = listOf(ChatMessage(role = "user", content = "ORIGINAL_USER_TURN")),
        estimatedChars = 32,
        maxChars = 48_000,
        includedMemoryIds = emptySet(),
        includedProposalIds = emptySet(),
        truncations = emptyList()
    )

    private val preset = TavernPreset(
        id = "retry-preset",
        name = "Retry preset",
        builtIn = false,
        prompts = listOf(
            TavernPrompt(
                identifier = "custom-style",
                name = "Custom style",
                role = "system",
                content = "CUSTOM_PRESET_TOKEN",
                enabled = true,
                marker = false,
                injectionPosition = null,
                injectionDepth = null
            )
        ),
        promptOrder = listOf(
            TavernPromptOrderEntry("custom-style", true),
            TavernPromptOrderEntry("chatHistory", true)
        ),
        regexScripts = emptyList(),
        generationOptions = ChatGenerationOptions(temperature = 0.37, topP = 0.81),
        assistantPrefill = "ASSISTANT_PREFILL_TOKEN",
        helperScriptCount = 0
    )

    @Test fun prosePreparationIsIdenticalForFreshAndRegeneratedRequests() {
        val fresh = StoryGenerationPreset.prepare(StoryWorkspace.Prose, context, preset, true)
        val regenerated = StoryGenerationPreset.prepare(StoryWorkspace.Prose, context, preset, true)

        assertEquals(fresh.systemPrompt, regenerated.systemPrompt)
        assertEquals(
            fresh.history.map { it.role to it.content },
            regenerated.history.map { it.role to it.content }
        )
        assertEquals(fresh.generationOptions, regenerated.generationOptions)
        assertTrue(fresh.history.any { it.content.contains("CUSTOM_PRESET_TOKEN") })
        assertTrue(fresh.history.any { it.role == "user" && it.content == "ORIGINAL_USER_TURN" })
        assertTrue(fresh.history.any { it.role == "assistant" && it.content.contains("ASSISTANT_PREFILL_TOKEN") })
        assertEquals(0.37, fresh.generationOptions.temperature!!, 0.0001)
        assertEquals(0.81, fresh.generationOptions.topP!!, 0.0001)
    }

    @Test fun discussionDoesNotAccidentallyReceiveProsePreset() {
        val prepared = StoryGenerationPreset.prepare(StoryWorkspace.Discussion, context, preset, true)
        assertEquals(context.systemPrompt, prepared.systemPrompt)
        assertEquals(
            context.history.map { it.role to it.content },
            prepared.history.map { it.role to it.content }
        )
        assertFalse(prepared.history.any { it.content.contains("CUSTOM_PRESET_TOKEN") })
        assertEquals(ChatGenerationOptions(), prepared.generationOptions)
    }

    @Test fun presetMarkersAndMacrosReceiveTheCharacterCard() {
        val cardPreset = preset.copy(
            name = "4d00dc6a3ca485618f31cd11b97d9c6efa349f2fb6ba6f5489252aa492d185a6",
            prompts = listOf(
                TavernPrompt("charDescription", "描述", "system", "", true, true, null, null),
                TavernPrompt("scenario", "故事", "system", "", true, true, null, null),
                TavernPrompt("main", "主提示", "system", "对方是{{char}}。{{description}} {{scenario}}", true, false, null, null)
            ),
            promptOrder = listOf(
                TavernPromptOrderEntry("charDescription", true),
                TavernPromptOrderEntry("scenario", true),
                TavernPromptOrderEntry("main", true),
                TavernPromptOrderEntry("chatHistory", true)
            )
        )
        val prepared = StoryGenerationPreset.prepare(
            StoryWorkspace.Prose,
            context,
            cardPreset,
            regexEnabled = false,
            cast = StoryCastContext(
                card = StoryCharacterCard("梅尔文", "旧书店店主", "寡言", "Fate/strange Fake 同人"),
                userPersona = "旁白"
            )
        )
        val sent = prepared.history.joinToString("\n") { it.content }
        assertTrue(sent.contains("梅尔文"))
        assertTrue(sent.contains("旧书店店主"))
        assertTrue(sent.contains("Fate/strange Fake 同人"))
        assertFalse(sent.contains("{{"))
        assertFalse(sent.contains(cardPreset.name))
    }
}
