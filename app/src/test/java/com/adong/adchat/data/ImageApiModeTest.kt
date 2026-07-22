package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageApiModeTest {
    @Test
    fun naiDiffusionUsesOpenAiCompatibleImagesProtocolAutomatically() {
        val profile = ApiProfile(imageApiMode = IMAGE_API_MODE_AUTO)

        assertEquals(
            IMAGE_API_MODE_OPENAI,
            profile.resolvedImageApiMode("nai-diffusion-4-5-curated")
        )
    }

    @Test
    fun geminiImageStillUsesItsDedicatedProtocol() {
        val profile = ApiProfile(imageApiMode = IMAGE_API_MODE_AUTO)

        assertEquals(
            IMAGE_API_MODE_GEMINI,
            profile.resolvedImageApiMode("gemini-3.1-flash-image")
        )
    }
}
