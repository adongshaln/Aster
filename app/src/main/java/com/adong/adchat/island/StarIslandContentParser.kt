package com.adong.adchat.island

import com.adong.adchat.media.MediaInputParser
import com.adong.adchat.media.MediaPlatform
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Recognition is local. Keep the original text and URL for the action the user chooses. */
object StarIslandContentParser {
    const val MAX_CONTENT_CHARS = 16_384
    private val urlPattern = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun parse(text: String): StarIslandContent? {
        require(text.length <= MAX_CONTENT_CHARS) { "内容过长，星岛最多处理 16,384 个字符，请缩短后重试" }
        val content = text.trim().takeIf(String::isNotEmpty) ?: return null
        val match = urlPattern.find(content) ?: return StarIslandContent(text = content)
        val url = trimSharePunctuation(match.value)
        checkedHttpUrl(url)
        val mediaLink = IslandLinkParser.parse(url) ?: MediaInputParser.parse(url)
            ?.takeIf { it.platform == MediaPlatform.Direct }
            ?.let { IslandLink(it, url, url, url) }
        return StarIslandContent(text = content, url = url, mediaLink = mediaLink)
    }

    private fun trimSharePunctuation(value: String): String {
        var result = value.trimEnd('。', '，', '；', '！', '？')
        // Keep balanced brackets and query punctuation: both can be part of a signed URL.
        for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
            while (result.endsWith(close) && result.count { it == close } > result.count { it == open }) {
                result = result.dropLast(1)
            }
        }
        return result
    }
}

internal fun checkedHttpUrl(value: String): HttpUrl {
    require(value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) {
        "链接格式不正确，请使用完整的 http 或 https 网址"
    }
    require(!value.contains('\\') && value.none { it.isISOControl() }) { "链接格式不正确，请使用完整的 http 或 https 网址" }
    val url = value.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("链接格式不正确，请使用完整的 http 或 https 网址")
    val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    require('@' !in authority && url.username.isEmpty() && url.password.isEmpty()) {
        "星岛不处理包含账号或密码的网址，请先移除链接中的登录信息"
    }
    return url
}
