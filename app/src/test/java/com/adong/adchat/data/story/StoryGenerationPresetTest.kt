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
        history = listOf(ChatMessage("user", "ORIGINAL_USER_TURN")),
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

        assertEquals(fresh, regenerated)
        assertTrue(fresh.history.any { it.content.contains("CUSTOM_PRESET_TOKEN") })
        assertTrue(fresh.history.any { it.role == "user" && it.content == "ORIGINAL_USER_TURN" })
        assertTrue(fresh.history.any { it.role == "assistant" && it.content.contains("ASSISTANT_PREFILL_TOKEN") })
        assertEquals(0.37, fresh.generationOptions.temperature!!, 0.0001)
        assertEquals(0.81, fresh.generationOptions.topP!!, 0.0001)
    }

    @Test fun discussionDoesNotAccidentallyReceiveProsePreset() {
        val prepared = StoryGenerationPreset.prepare(StoryWorkspace.Discussion, context, preset, true)
        assertEquals(context.systemPrompt, prepared.systemPrompt)
        assertEquals(context.history, prepared.history)
        assertFalse(prepared.history.any { it.content.contains("CUSTOM_PRESET_TOKEN") })
        assertEquals(ChatGenerationOptions(), prepared.generationOptions)
    }
}
