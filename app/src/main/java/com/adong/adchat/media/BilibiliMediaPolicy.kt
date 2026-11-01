package com.adong.adchat.media

import java.security.MessageDigest

internal object BilibiliMediaPolicy {
    internal const val TV_APP_KEY = "4409e2ce8ffd12b8"
    private const val TV_APP_SECRET = "59b43e04ad6965f34319062b478f83dd"

    fun normalizeThumbnail(value: String): String {
        val secure = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", ignoreCase = true) -> "https://${value.substringAfter("://")}" 
            else -> value
        }
        if (secure.isBlank() || "@" in secure || "/bfs/" !in secure) return secure
        return "$secure@672w_378h_1c.webp"
    }

    /**
     * Sort parameters lexicographically and append the TV MD5 `sign` parameter.
     */
    fun signTvParams(params: Map<String, String>): Map<String, String> {
        val sorted = params.toSortedMap()
        val query = sorted.entries.joinToString("&") { "${it.key}=${it.value}" }
        val sign = md5Hex(query + TV_APP_SECRET)
        return LinkedHashMap<String, String>(sorted.size + 1).apply {
            putAll(sorted)
            put("sign", sign)
        }
    }

    /**
     * Build the signed Bilibili TV playurl query path (`/x/tv/playurl`).
     * When [accessKey] is supplied, Bilibili unlocks 1080P (free account) and 1080P+ / 1080P60 / 4K / 8K (VIP)
     * in completely watermark-free DASH streams.
     */
    fun buildTvPlayurlPath(
        aid: Long,
        cid: Long,
        qn: Int = 127,
        fnval: Int = 4048,
        accessKey: String = "",
        timestampSeconds: Long = System.currentTimeMillis() / 1000L
    ): String {
        val raw = mutableMapOf(
            "appkey" to TV_APP_KEY,
            "build" to "106500",
            "cid" to cid.toString(),
            "device" to "android",
            "fnval" to fnval.toString(),
            "fnver" to "0",
            "fourk" to "1",
            "mid" to "0",
            "mobi_app" to "android_tv_yst",
            "object_id" to aid.toString(),
            "platform" to "android",
            "playurl_type" to "1",
            "qn" to qn.toString(),
            "ts" to timestampSeconds.toString()
        )
        if (accessKey.isNotBlank()) {
            raw["access_key"] = accessKey.trim()
        }
        val signed = signTvParams(raw)
        val query = signed.entries.joinToString("&") { "${it.key}=${it.value}" }
        return "/x/tv/playurl?$query"
    }

    /** Monotonic quality rank for Bilibili `qn` codes and labels (ensures 8K/4K/1080P60/1080P+ sort above 1080P/720P). */
    fun qualityRankFromIdOrLabel(variantId: String, label: String): Int {
        val qnFromId = variantId.substringAfterLast('-').toIntOrNull()
        if (qnFromId != null && qnFromId > 0) return qnFromId
        val upper = label.uppercase()
        return when {
            "8K" in upper -> 127
            "杜比" in upper -> 126
            "HDR" in upper -> 125
            "4K" in upper -> 120
            "1080P60" in upper -> 116
            "1080P+" in upper -> 112
            "1080P" in upper -> 80
            "720P60" in upper -> 74
            "720P" in upper -> 64
            "480P" in upper -> 32
            "360P" in upper -> 16
            else -> Regex("(\\d+)").find(upper)?.value?.toIntOrNull() ?: 0
        }
    }

    internal fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
