package com.adong.adchat.data.story

import com.adong.adchat.data.ChatGenerationOptions
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.TavernPreset
import com.adong.adchat.data.TavernPresetRuntime

/**
 * One request-preparation boundary for both a fresh story generation and regeneration.
 * The caller passes a frozen preset snapshot so the request cannot observe an asynchronous
 * UI/store transition halfway through preparation.
 */
data class StoryPreparedGenerationRequest(
    val systemPrompt: String,
    val history: List<ChatMessage>,
    val generationOptions: ChatGenerationOptions
)

object StoryGenerationPreset {
    fun prepare(
        workspace: StoryWorkspace,
        context: StoryContextResult,
        preset: TavernPreset?,
        regexEnabled: Boolean,
        cast: StoryCastContext = StoryCastContext()
    ): StoryPreparedGenerationRequest {
        val tavern = preset
            ?.takeIf { workspace == StoryWorkspace.Prose }
            ?.let {
                TavernPresetRuntime.prepare(
                    preset = it,
                    baseSystemPrompt = context.systemPrompt,
                    history = context.history,
                    regexEnabled = regexEnabled,
                    cast = com.adong.adchat.data.TavernCharacterContext(
                        name = cast.card?.name.orEmpty(),
                        description = cast.card?.description.orEmpty(),
                        personality = cast.card?.personality.orEmpty(),
                        scenario = cast.card?.scenario.orEmpty(),
                        persona = cast.userPersona,
                        mesExample = cast.card?.mesExample.orEmpty(),
                        wiBefore = context.worldBefore,
                        wiAfter = context.worldAfter
                    )
                )
            }
        return StoryPreparedGenerationRequest(
            systemPrompt = tavern?.systemPrompt ?: context.systemPrompt,
            history = tavern?.history ?: context.history,
            generationOptions = tavern?.generationOptions ?: ChatGenerationOptions()
        )
    }
}
