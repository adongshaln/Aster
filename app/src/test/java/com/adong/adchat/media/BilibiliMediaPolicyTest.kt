package com.adong.adchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliMediaPolicyTest {
    @Test
    fun normalizesCoverToSecureSizedWebp() {
        assertEquals(
            "https://i2.hdslb.com/bfs/archive/demo.jpg@672w_378h_1c.webp",
            BilibiliMediaPolicy.normalizeThumbnail("http://i2.hdslb.com/bfs/archive/demo.jpg")
        )
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/demo.jpg@bfs.webp".replace("@bfs.webp", "@672w_378h_1c.webp"),
            BilibiliMediaPolicy.normalizeThumbnail("//i0.hdslb.com/bfs/archive/demo.jpg")
        )
    }

    @Test
    fun keepsAlreadyProcessedOrEmptyCover() {
        val processed = "https://i0.hdslb.com/bfs/archive/demo.jpg@320w_180h.webp"
        assertEquals(processed, BilibiliMediaPolicy.normalizeThumbnail(processed))
        assertEquals("", BilibiliMediaPolicy.normalizeThumbnail(""))
    }

    @Test
    fun buildsSignedTvPlayurlPathWithLexicographicalParameterOrder() {
        val path = BilibiliMediaPolicy.buildTvPlayurlPath(
            aid = 117534648963255L,
            cid = 43205266609L,
            qn = 127,
            fnval = 4048,
            accessKey = "demo_token_123",
            timestampSeconds = 1761900000L
        )
        assertTrue(path.startsWith("/x/tv/playurl?access_key=demo_token_123&appkey=4409e2ce8ffd12b8&build=106500&cid=43205266609&device=android&fnval=4048&fnver=0&fourk=1&mid=0&mobi_app=android_tv_yst&object_id=117534648963255&platform=android&playurl_type=1&qn=127&ts=1761900000&sign="))
        val sign = path.substringAfter("&sign=")
        assertEquals(32, sign.length)
    }

    @Test
    fun qualityRankSorts4kAndHighFrameRateAbove1080pAnd720p() {
        val rank4k = BilibiliMediaPolicy.qualityRankFromIdOrLabel("bili-tv-120", "4K · 无水印")
        val rank1080p60 = BilibiliMediaPolicy.qualityRankFromIdOrLabel("bili-tv-116", "1080P60 · 无水印")
        val rank1080pPlus = BilibiliMediaPolicy.qualityRankFromIdOrLabel("bili-tv-112", "1080P+ · 无水印")
        val rank1080p = BilibiliMediaPolicy.qualityRankFromIdOrLabel("bili-dash-80", "1080P")
        val rank720p = BilibiliMediaPolicy.qualityRankFromIdOrLabel("bili-tv-64", "720P · 无水印")
        assertTrue(rank4k > rank1080p60)
        assertTrue(rank1080p60 > rank1080pPlus)
        assertTrue(rank1080pPlus > rank1080p)
        assertTrue(rank1080p > rank720p)
    }

    @Test
    fun selectVariantUpdatesAudioUrlRefererUserAgentAndWatermarkFreeFlag() {
        val tvVariant = MediaVariant(
            id = "bili-tv-64",
            label = "720P · 无水印",
            mediaUrl = "https://upos.bilivideo.com/tv-video.m4s",
            audioUrl = "https://upos.bilivideo.com/tv-audio.m4s",
            prefersWatermarkFree = true,
            variantReferer = "",
            variantUserAgent = "Mozilla/5.0"
        )
        val webVariant = MediaVariant(
            id = "bili-web-80",
            label = "1080P",
            mediaUrl = "https://upos.bilivideo.com/web.mp4",
            audioUrl = "",
            prefersWatermarkFree = false,
            variantReferer = "https://www.bilibili.com/",
            variantUserAgent = "Chrome Desktop"
        )
        val initial = ResolvedMedia(
            videoId = "BV1TxxC6vEo4",
            title = "测试视频",
            mediaUrl = tvVariant.mediaUrl,
            audioUrl = tvVariant.audioUrl,
            sourceUrl = "https://www.bilibili.com/video/BV1TxxC6vEo4",
            referer = "",
            userAgent = "Mozilla/5.0",
            prefersWatermarkFree = true,
            platform = MediaPlatform.Bilibili,
            qualityLabel = tvVariant.label,
            availableVariants = listOf(tvVariant, webVariant)
        )

        val switchedToWeb = initial.selectVariant("bili-web-80")
        assertFalse(switchedToWeb.prefersWatermarkFree)
        assertEquals("", switchedToWeb.audioUrl)
        assertEquals("https://www.bilibili.com/", switchedToWeb.referer)
        assertEquals("Chrome Desktop", switchedToWeb.userAgent)

        val switchedBackToTv = switchedToWeb.selectVariant("bili-tv-64")
        assertTrue(switchedBackToTv.prefersWatermarkFree)
        assertEquals("https://upos.bilivideo.com/tv-audio.m4s", switchedBackToTv.audioUrl)
        assertEquals("", switchedBackToTv.referer)
        assertEquals("Mozilla/5.0", switchedBackToTv.userAgent)
    }
}
