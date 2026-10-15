package com.adong.adchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinMediaSourceSelectorTest {
    @Test
    fun playwmKeptAsIsAndBecomesCompatibilityFallback() {
        // aweme.snssdk.com 重写已移除：该老接口对客户端请求失效（假 ID 返回 200
        // text/plain 空内容），重写只会把 WebView 抓到的有效 CDN URL 换成死域名。
        // playwm URL 原样作为主源，同时保留为兼容备选。
        val source = DouyinMediaSourceSelector.preferWatermarkFree(
            "https://m.douyin.com/aweme/v1/playwm/?video_id=abc123&ratio=720p&line=0"
        )

        assertFalse(source.watermarkFreePreferred)
        assertEquals(
            "https://m.douyin.com/aweme/v1/playwm/?video_id=abc123&ratio=720p&line=0",
            source.primaryUrl
        )
        assertTrue(source.compatibilityUrl.contains("/playwm/"))
    }

    @Test
    fun leavesDirectCdnUrlUntouched() {
        val direct = "https://v26.douyinvod.com/video/tos/example.mp4"
        val source = DouyinMediaSourceSelector.preferWatermarkFree(direct)

        assertEquals(direct, source.primaryUrl)
        assertFalse(source.watermarkFreePreferred)
        assertEquals("", source.compatibilityUrl)
    }
}
