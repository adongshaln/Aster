package com.adong.adchat.media

internal data class DouyinPreferredMediaSource(
    val primaryUrl: String,
    val compatibilityUrl: String = "",
    val watermarkFreePreferred: Boolean = false
)

internal object DouyinMediaSourceSelector {
    /**
     * 无水印源选择。playwm → play 端点改写经实测（2026-10-15，真实 video_id）
     * 已恢复可用：/aweme/v1/play/ 对浏览器 UA 正常 302 到 douyinvod 直链，
     * 产物为无水印 MP4（体积比 playwm 小约 37%）。playwm 原样保留为兼容
     * 备选——若 play 端点再被收紧，probe 失败后回退到带水印源兜底。
     */
    fun preferWatermarkFree(url: String): DouyinPreferredMediaSource {
        val normalized = url.trim()
        val isPlaywm = normalized.contains("/aweme/v1/playwm/", ignoreCase = true)
        val isPlay = normalized.contains("/aweme/v1/play/", ignoreCase = true)
        val watermarkFree = if (isPlaywm) normalized.replace("/aweme/v1/playwm/", "/aweme/v1/play/", ignoreCase = true) else normalized
        return DouyinPreferredMediaSource(
            primaryUrl = watermarkFree,
            compatibilityUrl = if (isPlaywm) normalized else "",
            watermarkFreePreferred = isPlaywm || isPlay
        )
    }
}
