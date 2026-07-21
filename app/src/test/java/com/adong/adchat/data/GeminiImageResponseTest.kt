package com.adong.adchat.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiImageResponseTest {
    @Test
    fun parsesNonStreamingImagesReturnedByChatCompletion() {
        val root = JSONObject("""
            {"choices":[{"message":{"images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AAA"}}]}}]}
        """)

        assertEquals(listOf("data:image/png;base64,AAA"), parseGeminiImageResponse(root))
    }

    @Test
    fun parsesInlineDataAndStreamingImageChunks() {
        val collector = GeminiImageResponseCollector()

        assertTrue(collector.accept("""{"choices":[{"delta":{"images":[{"type":"inline_data","inline_data":{"mime_type":"image/jpeg","data":"BBB"}}]}}]}"""))
        assertFalse(collector.accept("[DONE]"))

        assertEquals(listOf("data:image/jpeg;base64,BBB"), collector.result())
    }

    @Test
    fun autoModeRoutesOnlyGeminiImageModelsToGeminiProtocol() {
        val profile = ApiProfile(imageApiMode = IMAGE_API_MODE_AUTO)

        assertEquals(IMAGE_API_MODE_GEMINI, profile.resolvedImageApiMode("gemini-3.1-flash-image"))
        assertEquals(IMAGE_API_MODE_OPENAI, profile.resolvedImageApiMode("gpt-image-1"))
        assertEquals(IMAGE_API_MODE_OPENAI, profile.copy(imageApiMode = IMAGE_API_MODE_OPENAI).resolvedImageApiMode("gemini-3.1-flash-image"))
    }
}
