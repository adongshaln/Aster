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
        val blocked = "The prompt could not be submitted. Generative AI Prohibited Use policy."
        val rejection = runCatching { ChatIllustrationPlanner.parse(blocked) }.exceptionOrNull()!!
        assertTrue(rejection.message.orEmpty().contains("上游 Google 服务拒绝"))
        assertTrue(rejection.message.orEmpty().contains("尚未调用 NAI"))
        val oldCard = "画面提示词提炼失败（服务 · 模型）：提炼模型未返回有效画面数据。\n服务返回：$blocked"
        assertTrue(illustrationErrorMessage(oldCard).contains("上游 Google 服务拒绝"))
        val validPlan = JSONObject(json.toString()).put("prompt", "A person reading a notice: $blocked")
        assertEquals(validPlan.getString("prompt"), ChatIllustrationPlanner.parse(validPlan.toString()).prompt)
    }

    @Test fun thoughtExamplesAndTruncatedObjectsCannotBecomeAnImagePrompt() {
        val thoughtOnly = runCatching { ChatIllustrationPlanner.parse("<think>$json</think> No final scene.") }.exceptionOrNull()!!
        assertFalse(thoughtOnly.message.orEmpty().contains("warm glow"))
        assertTrue(thoughtOnly.message.orEmpty().contains("No final scene."))
        assertTrue(runCatching { ChatIllustrationPlanner.parse(json.toString().dropLast(1)) }.isFailure)
    }
}
