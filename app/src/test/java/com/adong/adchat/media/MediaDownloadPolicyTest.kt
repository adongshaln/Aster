package com.adong.adchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDownloadPolicyTest {
    @Test
    fun bilibiliUsesStableSingleRangeConnection() {
        assertFalse(MediaDownloadPolicy.allowsParallel(MediaPlatform.Bilibili))
        assertTrue(MediaDownloadPolicy.forcesRangeRequest(MediaPlatform.Bilibili))
        assertEquals("https://www.bilibili.com", MediaDownloadPolicy.originHeader(MediaPlatform.Bilibili))
    }

    @Test
    fun bilibiliCdnRejectionStatusesRefreshSource() {
        assertTrue(MediaDownloadPolicy.isRefreshableStatus(402))
        assertTrue(MediaDownloadPolicy.isRefreshableStatus(403))
        assertTrue(MediaDownloadPolicy.isRefreshableStatus(410))
        assertFalse(MediaDownloadPolicy.isRefreshableStatus(404))
    }

    @Test
    fun otherPlatformsKeepParallelAcceleration() {
        assertTrue(MediaDownloadPolicy.allowsParallel(MediaPlatform.Douyin))
        assertTrue(MediaDownloadPolicy.allowsParallel(MediaPlatform.Twitter))
        assertTrue(MediaDownloadPolicy.allowsParallel(MediaPlatform.Direct))
        assertFalse(MediaDownloadPolicy.forcesRangeRequest(MediaPlatform.Direct))
    }
}
