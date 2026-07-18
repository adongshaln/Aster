package com.adong.adchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaInputParserTest {
    @Test
    fun parsesPlatformsFromShareText() {
        assertEquals(MediaPlatform.Douyin, MediaInputParser.parse("复制打开 https://v.douyin.com/abc123/")?.platform)
        assertEquals(MediaPlatform.Twitter, MediaInputParser.parse("https://x.com/user/status/1234567890")?.platform)
        assertEquals(MediaPlatform.Bilibili, MediaInputParser.parse("https://www.bilibili.com/video/BV1xx411c7mD?p=2")?.platform)
        assertEquals(MediaPlatform.Bilibili, MediaInputParser.parse("https://b23.tv/abcdef")?.platform)
    }

    @Test
    fun acceptsStaticVideoLinksAndRejectsPages() {
        val direct = MediaInputParser.parse("下载：https://cdn.example.com/path/demo.webm?token=123")
        assertEquals(MediaPlatform.Direct, direct?.platform)
        assertTrue(direct?.mediaId?.startsWith("direct-") == true)
        assertNull(MediaInputParser.parse("https://example.com/watch/123"))
        assertEquals(MediaPlatform.Direct, MediaInputParser.parse("https://cdn.example.com/live/index.m3u8")?.platform)
    }

    @Test
    fun selectedVariantUpdatesDownloadMetadata() {
        val variants = listOf(
            MediaVariant("high", "1080p", "https://cdn.example.com/high.mp4"),
            MediaVariant("low", "720p", "https://cdn.example.com/low.mp4", fallbackMediaUrl = "https://backup.example.com/low.mp4")
        )
        val media = ResolvedMedia(
            videoId = "1",
            title = "test",
            mediaUrl = variants.first().mediaUrl,
            sourceUrl = "https://example.com/1",
            referer = "",
            userAgent = "",
            qualityLabel = variants.first().label,
            availableVariants = variants
        )
        val selected = media.selectVariant("low")
        assertEquals("720p", selected.qualityLabel)
        assertEquals(variants.last().mediaUrl, selected.mediaUrl)
        assertEquals(variants.last().fallbackMediaUrl, selected.compatibilityMediaUrl)
    }
}
