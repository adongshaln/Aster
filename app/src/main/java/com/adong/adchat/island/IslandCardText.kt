package com.adong.adchat.island

import com.adong.adchat.media.MediaPlatform

/** Display-only summaries. Original text and URLs remain intact in StarIslandContent. */
internal object IslandCardText {
    const val MAX_SUMMARY_CHARS = 120
    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    fun title(platform: MediaPlatform): String = when (platform) {
        MediaPlatform.Douyin -> "抖音视频"
        MediaPlatform.Bilibili -> "B 站视频"
        MediaPlatform.Twitter -> "X 媒体"
        MediaPlatform.Direct -> "媒体链接"
    }

    fun title(content: StarIslandContent): String = when (content.kind) {
        StarIslandKind.Text -> "剪贴板文字"
        StarIslandKind.Web -> "网页链接"
        StarIslandKind.Media -> title(checkNotNull(content.mediaLink).platform)
    }

    fun summary(content: StarIslandContent): String = if (content.kind == StarIslandKind.Text) {
        shorten(content.text.replace(Regex("[\\s\\u00A0]+"), " ").trim())
    } else {
        val url = content.mediaLink?.let { it.cleanedUrl.ifBlank { it.originalUrl } } ?: content.url.orEmpty()
        // A preview is not a place to expose credentials, signed queries or fragment state.
        val address = scheme.replaceFirst(url.trim(), "").substringBefore('?').substringBefore('#')
        val authority = address.substringBefore('/').substringAfterLast('@')
        val path = address.substringAfter('/', "").trimEnd('/')
        val host = if (authority.startsWith("www.", ignoreCase = true)) authority.substring(4) else authority
        shorten((host + if (path.isNotEmpty()) "/$path" else "").ifBlank { "网页链接" })
    }

    private fun shorten(text: String): String =
        if (text.length <= MAX_SUMMARY_CHARS) text else text.take(MAX_SUMMARY_CHARS - 1) + "…"

    /** Drops the scheme, a leading "www." and trailing slashes; the TextView middle-ellipsizes the rest. */
    fun summary(url: String): String {
        val trimmed = url.trim()
        var text = scheme.replaceFirst(trimmed, "")
        if (text.startsWith("www.", ignoreCase = true)) text = text.substring(4)
        text = text.trimEnd('/')
        if (text.isEmpty()) text = trimmed
        return shorten(text)
    }
}
