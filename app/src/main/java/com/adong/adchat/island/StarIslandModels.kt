package com.adong.adchat.island

/** Content stays local until the user chooses an action. */
data class StarIslandContent(
    val text: String,
    val url: String? = null,
    val mediaLink: IslandLink? = null,
) {
    val kind: StarIslandKind get() = when {
        mediaLink != null -> StarIslandKind.Media
        url != null -> StarIslandKind.Web
        else -> StarIslandKind.Text
    }
    val key: String get() = url ?: text
}

enum class StarIslandKind { Text, Web, Media }

enum class StarIslandAction(val label: String) {
    Translate("翻译"), Polish("润色"), Explain("解释"),
    ReadWeb("阅读网页"), Download("解析下载"), OpenLink("打开原页"), CopyLink("复制链接"),
}

enum class StarIslandPhase { Working, Complete, Stopped, Error }

data class StarIslandTaskState(
    val action: StarIslandAction,
    val phase: StarIslandPhase,
    val output: String = "",
    val detail: String = "",
    val needsConfiguration: Boolean = false,
)
