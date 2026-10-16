package com.adong.adchat.island

import com.adong.adchat.media.MediaPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandCardTextTest {
    @Test fun titleNamesThePlatform() {
        assertEquals("抖音视频", IslandCardText.title(MediaPlatform.Douyin))
        assertEquals("B 站视频", IslandCardText.title(MediaPlatform.Bilibili))
        assertEquals("X 媒体", IslandCardText.title(MediaPlatform.Twitter))
        assertEquals("媒体链接", IslandCardText.title(MediaPlatform.Direct))
    }

    @Test fun summaryDropsSchemeWwwAndTrailingSlash() {
        assertEquals("v.douyin.com/one", IslandCardText.summary("https://v.douyin.com/one/"))
        assertEquals(
            "bilibili.com/video/BV1xx411c7mD?p=2",
            IslandCardText.summary("https://www.bilibili.com/video/BV1xx411c7mD?p=2")
        )
        // The scheme is stripped case-insensitively; "www." is not present here.
        assertEquals("X.com/a/status/1", IslandCardText.summary("HTTP://X.com/a/status/1"))
        assertEquals("b23.tv/abc", IslandCardText.summary("  https://b23.tv/abc  "))
    }

    @Test fun summaryFallsBackToTheRawLinkWhenNothingIsLeftToStrip() {
        // A scheme with no host would otherwise collapse to an empty caption.
        assertEquals("https://", IslandCardText.summary("https://"))
    }

    @Test fun summaryEllipsizesPastTheCaptionBudget() {
        val summary = IslandCardText.summary("https://x.com/" + "a".repeat(300))
        assertEquals(IslandCardText.MAX_SUMMARY_CHARS, summary.length)
        assertTrue("over-long captions must end in an ellipsis; summary=$summary", summary.endsWith("…"))
    }
}
