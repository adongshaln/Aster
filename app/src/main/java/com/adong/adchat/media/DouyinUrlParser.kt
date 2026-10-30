package com.adong.adchat.media

import java.net.URI

data class DouyinLink(
    val sourceUrl: String,
    val videoId: String? = null
) {
    /**
     * 移动端分享页（iesdouyin.com / m.douyin.com / /share/video/）内嵌 _ROUTER_DATA，
     * 含 video_id 及 /aweme/v1/playwm/ 地址，经 DouyinMediaSourceSelector 改写为
     * /aweme/v1/play/ 即可拿到真无水印源。若强行改写为 PC 端 www.douyin.com/jingxuan，
     * 在 Android WebView 的移动端 UA 下 detail API 会失败且页面播放器只会吐出带水印 CDN 直链。
     */
    val resolverUrl: String
        get() = videoId?.let { "https://www.iesdouyin.com/share/video/$it/" } ?: sourceUrl
}

object DouyinUrlParser {
    private val urlPattern = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    private val idPattern = Regex("(?:/video/|[?&](?:modal_id|aweme_id)=)(\\d{8,})", RegexOption.IGNORE_CASE)
    private val trailingPunctuation = Regex("[)\\]}>，。！？；、,.!?;:]+$")

    fun parse(input: String): DouyinLink? {
        val candidate = urlPattern.find(input.trim())?.value
            ?.replace(trailingPunctuation, "")
            ?: return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (!isDouyinHost(host)) return null
        return DouyinLink(
            sourceUrl = candidate,
            videoId = extractVideoId(candidate)
        )
    }

    fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return idPattern.find(url)?.groupValues?.getOrNull(1)
    }

    fun canonicalResolverUrl(url: String): String {
        return extractVideoId(url)?.let { "https://www.iesdouyin.com/share/video/$it/" } ?: url
    }

    private fun isDouyinHost(host: String): Boolean {
        return host == "douyin.com" ||
            host.endsWith(".douyin.com") ||
            host == "iesdouyin.com" ||
            host.endsWith(".iesdouyin.com")
    }
}

