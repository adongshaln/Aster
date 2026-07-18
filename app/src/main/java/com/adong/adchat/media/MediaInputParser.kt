package com.adong.adchat.media

import java.net.URI
import java.util.Locale

object MediaInputParser {
    private val urlPattern = Regex("https?://[^\\s<>\\\"']+|www\\.[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    private val directExtensions = setOf("mp4", "webm", "mov", "m4v", "m3u8")

    fun parse(input: String): MediaInput? {
        val url = normalizeUrl(input) ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null

        val platform = when {
            host == "douyin.com" || host.endsWith(".douyin.com") || host == "iesdouyin.com" || host.endsWith(".iesdouyin.com") -> MediaPlatform.Douyin
            host == "twitter.com" || host.endsWith(".twitter.com") || host == "x.com" || host.endsWith(".x.com") -> MediaPlatform.Twitter
            host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv" || host.endsWith(".b23.tv") -> MediaPlatform.Bilibili
            extensionOf(uri.path.orEmpty()) in directExtensions -> MediaPlatform.Direct
            else -> return null
        }

        val id = when (platform) {
            MediaPlatform.Douyin -> Regex("(?:video|note)/(\\d+)").find(url)?.groupValues?.getOrNull(1)
                ?: Regex("/(\\d{10,})").find(url)?.groupValues?.getOrNull(1)
            MediaPlatform.Twitter -> Regex("(?:status|statuses)/(\\d+)").find(url)?.groupValues?.getOrNull(1)
            MediaPlatform.Bilibili -> Regex("BV[0-9A-Za-z]{10}").find(url)?.value
                ?: Regex("(?:/video/)?av(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
            MediaPlatform.Direct -> null
        } ?: "${platform.key}-${url.hashCode().toUInt().toString(16)}"

        return MediaInput(platform = platform, sourceUrl = url, mediaId = id)
    }

    fun normalizeUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        val candidate = (urlPattern.find(trimmed)?.value ?: trimmed)
            .trimEnd(')', ']', '}', ',', '，', '。', '!', '！', '?', '？', ';', '；')
        return when {
            candidate.startsWith("http://", true) || candidate.startsWith("https://", true) -> candidate
            candidate.startsWith("www.", true) -> "https://$candidate"
            else -> null
        }
    }

    fun extensionOf(path: String): String = path.substringAfterLast('/', "")
        .substringBefore('?')
        .substringAfterLast('.', "")
        .lowercase(Locale.ROOT)
}
