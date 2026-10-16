package com.adong.adchat.island

import com.adong.adchat.media.MediaPlatform

/** Card copy: a short platform title and a one-line link summary. Pure, so it is unit-testable. */
internal object IslandCardText {
    const val MAX_SUMMARY_CHARS = 120
    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    fun title(platform: MediaPlatform): String = when (platform) {
        MediaPlatform.Douyin -> "抖音视频"
        MediaPlatform.Bilibili -> "B 站视频"
        MediaPlatform.Twitter -> "X 媒体"
        MediaPlatform.Direct -> "媒体链接"
    }

    /** Drops the scheme, a leading "www." and trailing slashes; the TextView middle-ellipsizes the rest. */
    fun summary(url: String): String {
        val trimmed = url.trim()
        var text = scheme.replaceFirst(trimmed, "")
        if (text.startsWith("www.", ignoreCase = true)) text = text.substring(4)
        text = text.trimEnd('/')
        if (text.isEmpty()) text = trimmed
        return if (text.length <= MAX_SUMMARY_CHARS) text else text.take(MAX_SUMMARY_CHARS - 1) + "…"
    }
}
