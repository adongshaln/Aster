package com.adong.adchat.media

import java.util.UUID

enum class MediaPlatform(val key: String, val displayName: String) {
    Douyin("douyin", "抖音"),
    Twitter("twitter", "X"),
    Bilibili("bilibili", "B站"),
    Direct("direct", "直链");

    companion object {
        fun fromKey(value: String): MediaPlatform = entries.firstOrNull { it.key == value } ?: Douyin
    }
}

data class MediaInput(
    val platform: MediaPlatform,
    val sourceUrl: String,
    val mediaId: String
)

data class MediaVariant(
    val id: String,
    val label: String,
    val mediaUrl: String,
    val mimeType: String = "video/mp4",
    val fileExtension: String = "mp4",
    val fileSize: Long = 0L,
    val fallbackMediaUrl: String = ""
)

data class ResolvedMedia(
    val videoId: String,
    val title: String,
    val author: String = "",
    val thumbnailUrl: String = "",
    val thumbnailFallbackUrl: String = "",
    val mediaUrl: String,
    val sourceUrl: String,
    val referer: String,
    val userAgent: String,
    val cookieHeader: String = "",
    val compatibilityMediaUrl: String = "",
    val prefersWatermarkFree: Boolean = false,
    val platform: MediaPlatform = MediaPlatform.Douyin,
    val qualityLabel: String = "",
    val mimeType: String = "video/mp4",
    val fileExtension: String = "mp4",
    val expectedFileSize: Long = 0L,
    val availableVariants: List<MediaVariant> = emptyList()
) {
    fun selectVariant(id: String): ResolvedMedia {
        val variant = availableVariants.firstOrNull { it.id == id } ?: return this
        return copy(
            mediaUrl = variant.mediaUrl,
            compatibilityMediaUrl = variant.fallbackMediaUrl,
            qualityLabel = variant.label,
            mimeType = variant.mimeType,
            fileExtension = variant.fileExtension,
            expectedFileSize = variant.fileSize
        )
    }
}

data class DouyinResolveRequest(
    val token: Long,
    val link: DouyinLink,
    val message: String = "正在建立安全解析环境"
)

data class MediaDownloadRecord(
    val id: String = UUID.randomUUID().toString(),
    val videoId: String,
    val title: String,
    val author: String = "",
    val thumbnailUrl: String = "",
    val thumbnailFallbackUrl: String = "",
    val sourceUrl: String,
    val filePath: String,
    val galleryUri: String = "",
    val fileSize: Long,
    val watermarkFree: Boolean = false,
    val platform: MediaPlatform = MediaPlatform.Douyin,
    val qualityLabel: String = "",
    val mimeType: String = "video/mp4",
    val fileExtension: String = "mp4",
    val createdAt: Long = System.currentTimeMillis()
)

data class MediaDownloadProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val connectionCount: Int = 1
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

class ExpiredMediaUrlException(message: String) : Exception(message)
