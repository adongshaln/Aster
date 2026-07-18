package com.adong.adchat.media

import java.net.URI

data class DouyinLink(
    val sourceUrl: String,
    val videoId: String? = null
) {
    val resolverUrl: String
        get() = videoId?.let { "https://www.douyin.com/jingxuan?modal_id=$it" } ?: sourceUrl
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
        return extractVideoId(url)?.let { "https://www.douyin.com/jingxuan?modal_id=$it" } ?: url
    }

    private fun isDouyinHost(host: String): Boolean {
        return host == "douyin.com" ||
            host.endsWith(".douyin.com") ||
            host == "iesdouyin.com" ||
            host.endsWith(".iesdouyin.com")
    }
}

