package com.adong.adchat.media

import java.net.URI

data class DouyinLink(
    val sourceUrl: String,
    val videoId: String? = null,
    val isNote: Boolean = false
) {
    /**
     * 移动端分享页（iesdouyin.com / m.douyin.com / /share/video/ 或 /share/note/）内嵌 _ROUTER_DATA，
     * 含 video_id 及 /aweme/v1/playwm/ 地址或图文 images[] + music，经 DouyinMediaSourceSelector 改写为
     * /aweme/v1/play/ 即可拿到真无水印源。
     */
    val resolverUrl: String
        get() = videoId?.let { id ->
            if (isNote) "https://www.douyin.com/note/$id"
            else "https://www.iesdouyin.com/share/video/$id/"
        } ?: sourceUrl
}

object DouyinUrlParser {
    private val urlPattern = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    private val idPattern = Regex("(?:/(?:video|note|slides)/|[?&](?:modal_id|aweme_id)=)(\\d{8,})", RegexOption.IGNORE_CASE)
    private val notePathPattern = Regex("/(?:share/)?(?:note|slides)/", RegexOption.IGNORE_CASE)
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
            videoId = extractVideoId(candidate),
            isNote = notePathPattern.containsMatchIn(candidate)
        )
    }

    fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return idPattern.find(url)?.groupValues?.getOrNull(1)
    }

    fun canonicalResolverUrl(url: String): String {
        val isNote = notePathPattern.containsMatchIn(url)
        return extractVideoId(url)?.let { id ->
            if (isNote) "https://www.douyin.com/note/$id"
            else "https://www.iesdouyin.com/share/video/$id/"
        } ?: url
    }

    private fun isDouyinHost(host: String): Boolean {
        return host == "douyin.com" ||
            host.endsWith(".douyin.com") ||
            host == "iesdouyin.com" ||
            host.endsWith(".iesdouyin.com")
    }
}

