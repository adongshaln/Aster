package com.adong.adchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinMediaSourceSelectorTest {
    @Test
    fun playwmRewrittenToWatermarkFreePrimaryWithFallback() {
        // playwm → play 改写经实测（2026-10-15）已恢复可用：play 端点对浏览器
        // UA 正常 302 到 douyinvod 直链，产物无水印（体积小约 37%）。
        // playwm 原样保留为兼容备选，play 再失效时 probe 回退兜底。
        val source = DouyinMediaSourceSelector.preferWatermarkFree(
            "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=abc123&ratio=720p&line=0"
        )

        assertTrue(source.watermarkFreePreferred)
        assertEquals(
            "https://aweme.snssdk.com/aweme/v1/play/?video_id=abc123&ratio=720p&line=0",
            source.primaryUrl
        )
        assertEquals(
            "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=abc123&ratio=720p&line=0",
            source.compatibilityUrl
        )
    }

    @Test
    fun leavesDirectCdnUrlUntouched() {
        val direct = "https://v26.douyinvod.com/video/tos/example.mp4"
        val source = DouyinMediaSourceSelector.preferWatermarkFree(direct)

        assertEquals(direct, source.primaryUrl)
        assertFalse(source.watermarkFreePreferred)
        assertEquals("", source.compatibilityUrl)
    }

    @Test
    fun playUrlAlreadyWatermarkFreeNeedsNoRewrite() {
        val play = "https://aweme.snssdk.com/aweme/v1/play/?video_id=abc123&ratio=720p"
        val source = DouyinMediaSourceSelector.preferWatermarkFree(play)

        assertEquals(play, source.primaryUrl)
        assertFalse(source.watermarkFreePreferred)
        assertEquals("", source.compatibilityUrl)
    }
}
