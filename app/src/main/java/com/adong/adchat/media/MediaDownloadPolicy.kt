package com.adong.adchat.media

internal object MediaDownloadPolicy {
    fun allowsParallel(platform: MediaPlatform): Boolean = platform != MediaPlatform.Bilibili

    fun forcesRangeRequest(platform: MediaPlatform): Boolean = platform == MediaPlatform.Bilibili

    fun originHeader(platform: MediaPlatform): String = when (platform) {
        MediaPlatform.Bilibili -> "https://www.bilibili.com"
        else -> ""
    }

    fun isRefreshableStatus(statusCode: Int): Boolean = statusCode == 402 || statusCode == 403 || statusCode == 410
}
