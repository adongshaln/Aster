package com.adong.adchat.island

import android.net.Uri
import com.adong.adchat.media.MediaInput
import com.adong.adchat.media.MediaInputParser
import com.adong.adchat.media.MediaPlatform
import java.util.Locale

/**
 * 灵动岛展示用的链接信息：原文短链、清洗后的规范链接、以及可直接跳转 App 的 deep link。
 */
data class IslandLink(
    val input: MediaInput,
    val originalUrl: String,
    val cleanedUrl: String,
    val deepLink: String
) {
    val platform: MediaPlatform get() = input.platform
    val displayName: String get() = input.platform.displayName
}

object IslandLinkParser {

    private val douyinHosts = hashSetOf("douyin.com", "iesdouyin.com")
    private val biliHosts = hashSetOf("bilibili.com", "b23.tv")
    private val twitterHosts = hashSetOf("twitter.com", "x.com")

    private val trackingKeys = hashSetOf(
        "share_medium", "share_source", "share_token", "tt_from",
        "utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term",
        "from", "from_spm", "spm_id_from", "referfrom", "refer_flag", "is_from_webapp",
        "with_sec_did", "mstoken", "a_biaoti", "a_type", "chgd_source"
    )

    /** 从分享文本中解析出可展示的链接；不识别的文本返回 null。 */
    fun parse(sharedText: String): IslandLink? {
        val input = MediaInputParser.parse(sharedText) ?: return null
        if (input.platform == MediaPlatform.Direct) return null
        val url = input.sourceUrl
        val cleaned = if (isShortLink(url)) url else clean(url) ?: url
        return IslandLink(
            input = input,
            originalUrl = url,
            cleanedUrl = cleaned,
            deepLink = deepLinkFor(input, cleaned)
        )
    }

    fun isShortLink(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "v.douyin.com" || host == "b23.tv" || host == "t.co"
    }

    fun isSupported(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host.endsWithAnyOf(douyinHosts) || host.endsWithAnyOf(biliHosts) || host.endsWithAnyOf(twitterHosts)
    }

    /** 短链 302 后落到真实页面时，重新构造清洗结果。 */
    fun resolveExpanded(original: IslandLink, expandedUrl: String): IslandLink? {
        if (!isSupported(expandedUrl)) return null
        val cleaned = clean(expandedUrl) ?: expandedUrl
        val input = MediaInputParser.parse(cleaned) ?: return null
        return original.copy(input = input, cleanedUrl = cleaned, deepLink = deepLinkFor(input, cleaned))
    }

    /** 去掉追踪参数并统一为适合直接访问的规范形式。 */
    fun clean(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val builder = uri.buildUpon().clearQuery()
        var kept = 0
        uri.queryParameterNames
            ?.filter { it.lowercase(Locale.ROOT) !in trackingKeys }
            ?.forEach { name ->
                uri.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                    kept++
                }
            }
        if (kept == 0) builder.clearQuery()
        return builder.build().toString()
    }

    private fun deepLinkFor(input: MediaInput, cleanedUrl: String): String = when (input.platform) {
        MediaPlatform.Douyin -> "snssdk1128://aweme/detail/${input.mediaId}?needQueryLog=0"
        MediaPlatform.Bilibili -> "bilibili://video/${input.mediaId}"
        MediaPlatform.Twitter -> cleanedUrl
        MediaPlatform.Direct -> cleanedUrl
    }

    private fun hostOf(url: String): String? = runCatching { checkedHttpUrl(url).host }.getOrNull()

    private fun String.endsWithAnyOf(suffixes: Set<String>): Boolean =
        suffixes.any { this == it || this.endsWith(".$it") }
}
