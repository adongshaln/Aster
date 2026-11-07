package com.adong.adchat.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatIllustrationPlanFormatTest {
    private val json = JSONObject().put("title", "灯火").put("focus", "骑士举灯")
        .put("prompt", "A knight raises a lantern {warm glow}, \"soft light\", cinematic portrait.")

    @Test fun markdownAndExplanatoryWrappersKeepTheExactImagePrompt() {
        val result = ChatIllustrationPlanner.parse("Here is the scene:\n```JSON\n$json\n```\nEnd.")
        assertEquals(json.getString("prompt"), result.prompt)
        val doubleEncoded = JSONObject.quote(json.toString())
        assertEquals(result, ChatIllustrationPlanner.parse(doubleEncoded))
    }

    @Test fun proseExplanationsRemainVisibleAndAreNeverSentToDrawing() {
        val explanation = "I cannot produce that request. Please revise the scene."
        val error = runCatching { ChatIllustrationPlanner.parse(explanation) }.exceptionOrNull()!!
        assertTrue(error.message.orEmpty().contains(explanation))
        val credential = "sk-abcdefghijklmnop"
        val redacted = runCatching { ChatIllustrationPlanner.parse("Service explanation. Bearer $credential") }.exceptionOrNull()!!
        assertFalse(redacted.message.orEmpty().contains(credential))
    }

    @Test fun thoughtExamplesAndTruncatedObjectsCannotBecomeAnImagePrompt() {
        val thoughtOnly = runCatching { ChatIllustrationPlanner.parse("<think>$json</think> No final scene.") }.exceptionOrNull()!!
        assertFalse(thoughtOnly.message.orEmpty().contains("warm glow"))
        assertTrue(thoughtOnly.message.orEmpty().contains("No final scene."))
        assertTrue(runCatching { ChatIllustrationPlanner.parse(json.toString().dropLast(1)) }.isFailure)
    }
}
