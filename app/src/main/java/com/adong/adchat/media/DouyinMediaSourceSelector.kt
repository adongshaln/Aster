package com.adong.adchat.media

import java.net.URI

internal data class DouyinPreferredMediaSource(
    val primaryUrl: String,
    val compatibilityUrl: String = "",
    val watermarkFreePreferred: Boolean = false
)

internal object DouyinMediaSourceSelector {
    fun preferWatermarkFree(url: String): DouyinPreferredMediaSource {
        val normalized = url.trim()
        if (!normalized.contains("/aweme/v1/playwm/", ignoreCase = true)) {
            return DouyinPreferredMediaSource(primaryUrl = normalized)
        }
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: return DouyinPreferredMediaSource(primaryUrl = normalized)
        val originalHost = uri.host?.lowercase().orEmpty()
        val cleanHost = when {
            originalHost == "iesdouyin.com" || originalHost.endsWith(".iesdouyin.com") -> originalHost
            else -> "aweme.snssdk.com"
        }
        val cleanPath = uri.path.replace("/playwm/", "/play/", ignoreCase = true)
        var ratioFound = false
        val cleanQuery = buildList {
            uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).forEach { item ->
                if (item.substringBefore('=').equals("ratio", ignoreCase = true)) {
                    add("ratio=1080p")
                    ratioFound = true
                } else {
                    add(item)
                }
            }
            if (!ratioFound) add("ratio=1080p")
        }.joinToString("&")
        val cleanUri = runCatching {
            URI(
                "https",
                uri.userInfo,
                cleanHost,
                -1,
                cleanPath,
                cleanQuery,
                uri.fragment
            ).toASCIIString()
        }.getOrNull().orEmpty()
        if (cleanUri.isBlank() || cleanUri == normalized) {
            return DouyinPreferredMediaSource(primaryUrl = normalized)
        }
        return DouyinPreferredMediaSource(
            primaryUrl = cleanUri,
            compatibilityUrl = normalized,
            watermarkFreePreferred = true
        )
    }
}
