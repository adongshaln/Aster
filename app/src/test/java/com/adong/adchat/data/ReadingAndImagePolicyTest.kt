package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingAndImagePolicyTest {
    @Test
    fun thoughtGapStartsANewParagraphOnlyAfterExistingText() {
        assertEquals("继续", textAfterThought("", "继续", afterThought = true))
        assertEquals("继续", textAfterThought("上一段", "继续", afterThought = false))
        assertEquals("\n\n继续", textAfterThought("上一段", "继续", afterThought = true))
        assertEquals("\n已经换行", textAfterThought("上一段", "\n已经换行", afterThought = true))
    }

    @Test
    fun pixivImagesSendTheSiteReferer() {
        assertEquals("https://www.pixiv.net/", refererForImage("https://i.pximg.net/img-original/img/a.png"))
        assertNull(refererForImage("https://cdn.example.com/a.png"))
        assertTrue(chatImageHeaders("https://i.pximg.net/a.png").any { it.first == "Referer" })
    }

    @Test
    fun htmlIsNotTreatedAsAnImage() {
        assertFalse(looksLikeImage("<html></html>".toByteArray(), "text/html"))
        assertTrue(looksLikeImage(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()), null))
    }

    @Test
    fun pixivAjaxPrefersTheRegularPreview() {
        val json = """{"error":false,"body":{"urls":{"thumb":"https://i.pximg.net/thumb.jpg","regular":"https://i.pximg.net/regular.jpg"}}}"""
        assertEquals("https://i.pximg.net/regular.jpg", parsePixivPreviewUrl(json))
        assertEquals(null, parsePixivPreviewUrl("""{"error":true}"""))
    }
}
