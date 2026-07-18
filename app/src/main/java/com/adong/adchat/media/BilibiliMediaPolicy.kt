package com.adong.adchat.media

internal object BilibiliMediaPolicy {
    fun normalizeThumbnail(value: String): String {
        val secure = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", ignoreCase = true) -> "https://${value.substringAfter("://")}" 
            else -> value
        }
        if (secure.isBlank() || "@" in secure || "/bfs/" !in secure) return secure
        return "$secure@672w_378h_1c.webp"
    }
}
