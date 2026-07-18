package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditMultipartTest {
    @Test
    fun keepsLegacyImageFieldForSingleReference() {
        val body = buildImageEditMultipart(
            model = "gpt-image-test",
            prompt = "test",
            size = "1024x1024",
            references = listOf(ReferenceImageInput(byteArrayOf(1), "image/png", "one.png"))
        )

        val imageParts = body.parts.mapNotNull { it.headers?.get("Content-Disposition") }
            .filter { "filename=" in it }
        assertEquals(1, imageParts.size)
        assertTrue(imageParts.single().contains("name=\"image\""))
    }

    @Test
    fun usesOpenAiArrayFieldForMultipleReferences() {
        val body = buildImageEditMultipart(
            model = "gpt-image-test",
            prompt = "test",
            size = "1024x1024",
            references = listOf(
                ReferenceImageInput(byteArrayOf(1), "image/png", "one.png"),
                ReferenceImageInput(byteArrayOf(2), "image/jpeg", "two.jpg")
            )
        )

        val imageParts = body.parts.mapNotNull { it.headers?.get("Content-Disposition") }
            .filter { "filename=" in it }
        assertEquals(2, imageParts.size)
        assertTrue(imageParts.all { it.contains("name=\"image[]\"") })
    }
}
