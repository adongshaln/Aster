package com.adong.adchat.media

import java.security.MessageDigest

internal object BilibiliMediaPolicy {
    private const val TV_APP_KEY = "4409e2ce8ffd12b8"
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
     * Build the signed Bilibili TV playurl query path (`/x/tv/playurl`).
     * Parameters are sorted lexicographically before appending [TV_APP_SECRET] for MD5 signing.
     */
    fun buildTvPlayurlPath(
        aid: Long,
        cid: Long,
        qn: Int = 80,
        fnval: Int = 4048,
        timestampSeconds: Long = System.currentTimeMillis() / 1000L
    ): String {
        val params = sortedMapOf(
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
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val sign = md5Hex(query + TV_APP_SECRET)
        return "/x/tv/playurl?$query&sign=$sign"
    }

    internal fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
