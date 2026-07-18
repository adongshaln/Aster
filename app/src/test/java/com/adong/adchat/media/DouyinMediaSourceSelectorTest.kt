package com.adong.adchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinMediaSourceSelectorTest {
    @Test
    fun convertsMobilePlaywmToCleanAwemeEndpoint() {
        val source = DouyinMediaSourceSelector.preferWatermarkFree(
            "https://m.douyin.com/aweme/v1/playwm/?video_id=abc123&ratio=720p&line=0"
        )

        assertTrue(source.watermarkFreePreferred)
        assertEquals(
            "https://aweme.snssdk.com/aweme/v1/play/?video_id=abc123&ratio=1080p&line=0",
            source.primaryUrl
        )
        assertTrue(source.compatibilityUrl.contains("/playwm/"))
    }

    @Test
    fun keepsIesdouyinHostWhenItSupportsCleanPlay() {
        val source = DouyinMediaSourceSelector.preferWatermarkFree(
            "https://www.iesdouyin.com/aweme/v1/playwm/?line=0&video_id=abc123"
        )

        assertEquals("www.iesdouyin.com", URI(source.primaryUrl).host)
        assertTrue(source.primaryUrl.contains("/aweme/v1/play/"))
        assertTrue(source.primaryUrl.contains("ratio=1080p"))
    }

    @Test
    fun leavesDirectCdnUrlUntouched() {
        val direct = "https://v26.douyinvod.com/video/tos/example.mp4"
        val source = DouyinMediaSourceSelector.preferWatermarkFree(direct)

        assertEquals(direct, source.primaryUrl)
        assertFalse(source.watermarkFreePreferred)
        assertEquals("", source.compatibilityUrl)
    }

    private fun URI(value: String) = java.net.URI(value)
}
