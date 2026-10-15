package com.adong.adchat.media

internal data class DouyinPreferredMediaSource(
    val primaryUrl: String,
    val compatibilityUrl: String = "",
    val watermarkFreePreferred: Boolean = false
)

internal object DouyinMediaSourceSelector {
    /**
     * 无水印源选择。aweme.snssdk.com / aweme/v1/play 老接口已对客户端请求失效
     * （伪造 ID 返回 200 text/plain 空内容，真 ID 不再 302），重写只会把 WebView
     * 抓到的有效 CDN URL 换成死域名。现在只做安全归一：原样返回，playwm URL
     * 作为兼容备选（下载器 probe 失败时回退）。
     */
    fun preferWatermarkFree(url: String): DouyinPreferredMediaSource {
        val normalized = url.trim()
        val isPlaywm = normalized.contains("/aweme/v1/playwm/", ignoreCase = true)
        return DouyinPreferredMediaSource(
            primaryUrl = normalized,
            compatibilityUrl = if (isPlaywm) normalized else "",
            watermarkFreePreferred = false
        )
    }
}
