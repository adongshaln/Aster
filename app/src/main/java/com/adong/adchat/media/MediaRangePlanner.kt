package com.adong.adchat.media

internal data class MediaByteRange(
    val start: Long,
    val end: Long
) {
    val length: Long get() = end - start + 1L
}

internal object MediaRangePlanner {
    fun connectionCount(remainingBytes: Long): Int = when {
        remainingBytes < 512L * 1024L -> 1
        remainingBytes < 2L * 1024L * 1024L -> 3
        remainingBytes < 8L * 1024L * 1024L -> 4
        else -> 6
    }

    fun ranges(existingBytes: Long, totalBytes: Long, connectionCount: Int): List<MediaByteRange> {
        if (existingBytes >= totalBytes || totalBytes <= 0L) return emptyList()
        val safeConnections = connectionCount.coerceAtLeast(1)
        val remaining = totalBytes - existingBytes
        val chunkSize = (remaining + safeConnections - 1L) / safeConnections
        return buildList {
            var start = existingBytes
            while (start < totalBytes) {
                val end = minOf(totalBytes - 1L, start + chunkSize - 1L)
                add(MediaByteRange(start, end))
                start = end + 1L
            }
        }
    }
}
