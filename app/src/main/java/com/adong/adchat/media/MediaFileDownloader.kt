package com.adong.adchat.media

import android.content.Context
import android.os.storage.StorageManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

class MediaFileDownloader(context: Context) {
    private val outputDirectory = File(context.filesDir, "media_downloads").apply { mkdirs() }
    private val storageManager = context.getSystemService(StorageManager::class.java)
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 8
        })
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        cancelActiveCalls()
    }

    suspend fun download(
        media: ResolvedMedia,
        onProgress: suspend (MediaDownloadProgress) -> Unit
    ): MediaDownloadRecord = withContext(Dispatchers.IO) {
        cancelled = false
        val extension = media.fileExtension.lowercase().filter(Char::isLetterOrDigit).take(5).ifBlank { "mp4" }
        val qualityKey = media.qualityLabel.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(18)
        val safeId = buildString {
            append(media.platform.key)
            append('-')
            append(media.videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(72))
            if (qualityKey.isNotBlank()) append("-$qualityKey")
        }.trim('-').ifBlank { System.currentTimeMillis().toString() }
        val finalFile = File(outputDirectory, "$safeId.$extension")
        val partialSuffix = if (media.prefersWatermarkFree) ".clean.$extension.part" else ".$extension.part"
        val partialFile = File(outputDirectory, "$safeId$partialSuffix")

        if (finalFile.exists()) {
            if (!media.prefersWatermarkFree && isLikelyMedia(finalFile, media.mimeType, extension)) {
                return@withContext media.toRecord(finalFile)
            }
            finalFile.delete()
        }

        var activeMedia = media
        val probe = try {
            probe(activeMedia)
        } catch (error: Exception) {
            val canUseCompatibility = media.compatibilityMediaUrl.isNotBlank() &&
                (error is MediaSourceUnavailableException || error is ExpiredMediaUrlException)
            if (!canUseCompatibility) throw error
            activeMedia = media.copy(
                mediaUrl = media.compatibilityMediaUrl,
                compatibilityMediaUrl = "",
                prefersWatermarkFree = false
            )
            probe(activeMedia)
        }

        if (probe.totalBytes > 0L && partialFile.length() > probe.totalBytes) {
            partialFile.delete()
        }
        if (probe.totalBytes > 0L && partialFile.length() == probe.totalBytes && isLikelyMedia(partialFile, activeMedia.mimeType, extension)) {
            moveIntoPlace(partialFile, finalFile)
            return@withContext activeMedia.toRecord(finalFile)
        }

        val existingBytes = partialFile.length().coerceAtLeast(0L)
        val remainingBytes = (probe.totalBytes - existingBytes).coerceAtLeast(0L)
        val canAccelerate = MediaDownloadPolicy.allowsParallel(activeMedia.platform) &&
            probe.supportsRanges &&
            probe.totalBytes > 0L &&
            remainingBytes >= PARALLEL_MIN_BYTES

        if (canAccelerate) {
            try {
                downloadParallel(
                    media = activeMedia,
                    partialFile = partialFile,
                    totalBytes = probe.totalBytes,
                    existingBytes = existingBytes,
                    onProgress = onProgress
                )
            } catch (_: RangeUnsupportedException) {
                cancelActiveCalls()
                cleanupSegments(partialFile)
                downloadSingle(activeMedia, partialFile, onProgress)
            }
        } else {
            downloadSingle(activeMedia, partialFile, onProgress)
        }

        if (!isLikelyMedia(partialFile, activeMedia.mimeType, extension)) {
            partialFile.delete()
            cleanupSegments(partialFile)
            throw ExpiredMediaUrlException("解析结果不是有效视频，正在重新获取")
        }
        moveIntoPlace(partialFile, finalFile)
        activeMedia.toRecord(finalFile)
    }
    private suspend fun probe(media: ResolvedMedia): DownloadProbe {
        val request = requestBuilder(media)
            .header("Range", "bytes=0-0")
            .build()
        val call = client.newCall(request)
        activeCalls += call
        try {
            call.execute().use { response ->
                throwForStatus(response)
                if (!response.isSuccessful) throw MediaSourceUnavailableException(response.code)
                rejectNonVideoResponse(response)
                val total = parseContentRangeTotal(response)
                return DownloadProbe(
                    totalBytes = total,
                    supportsRanges = response.code == 206 && total > 0L
                )
            }
        } catch (error: IOException) {
            translateCancellation(call, error)
        } finally {
            activeCalls -= call
        }
    }

    private suspend fun downloadParallel(
        media: ResolvedMedia,
        partialFile: File,
        totalBytes: Long,
        existingBytes: Long,
        onProgress: suspend (MediaDownloadProgress) -> Unit
    ) {
        val remaining = totalBytes - existingBytes
        val connectionCount = MediaRangePlanner.connectionCount(remaining)
        val segments = buildSegments(partialFile, existingBytes, totalBytes, connectionCount)
        cleanupSegments(partialFile, segments.mapTo(HashSet()) { it.file.name })

        segments.forEach { segment ->
            if (segment.file.length() > segment.length) segment.file.delete()
        }
        val segmentBytes = segments.sumOf { it.file.length().coerceAtMost(it.length) }
        val downloadedBytes = AtomicLong(existingBytes + segmentBytes)
        val missingBytes = (totalBytes - downloadedBytes.get()).coerceAtLeast(0L)
        val largestSegment = segments.maxOfOrNull(Segment::length) ?: 0L
        if (availableBytes(partialFile) < missingBytes + largestSegment + MIN_FREE_SPACE) {
            throw IOException("设备剩余空间不足，无法保存该视频")
        }

        coroutineScope {
            val reporter = launch {
                var lastBytes = downloadedBytes.get()
                var lastTime = System.nanoTime()
                var smoothedSpeed = 0L
                while (isActive) {
                    delay(UI_UPDATE_MS)
                    val now = System.nanoTime()
                    val current = downloadedBytes.get()
                    val elapsed = (now - lastTime).coerceAtLeast(1L)
                    val instant = (current - lastBytes).coerceAtLeast(0L) * 1_000_000_000L / elapsed
                    smoothedSpeed = if (smoothedSpeed == 0L) instant else (smoothedSpeed * 3L + instant) / 4L
                    onProgress(MediaDownloadProgress(current, totalBytes, smoothedSpeed, connectionCount))
                    lastBytes = current
                    lastTime = now
                }
            }
            try {
                segments.map { segment ->
                    async { downloadSegment(media, segment, downloadedBytes) }
                }.awaitAll()
            } finally {
                reporter.cancel()
                reporter.join()
            }
        }

        mergeSegments(partialFile, segments)
        onProgress(MediaDownloadProgress(totalBytes, totalBytes, 0L, connectionCount))
    }

    private suspend fun downloadSegment(
        media: ResolvedMedia,
        segment: Segment,
        downloadedBytes: AtomicLong
    ) {
        var attempt = 0
        while (segment.file.length() < segment.length) {
            coroutineContext.ensureActive()
            if (cancelled) throw CancellationException("下载已取消")
            val localBytes = segment.file.length()
            val rangeStart = segment.start + localBytes
            val request = requestBuilder(media)
                .header("Range", "bytes=$rangeStart-${segment.end}")
                .build()
            val call = client.newCall(request)
            activeCalls += call
            try {
                call.execute().use { response ->
                    throwForStatus(response)
                    if (response.code == 200) {
                        cancelActiveCalls()
                        throw RangeUnsupportedException()
                    }
                    if (response.code != 206) {
                        throw IOException("分段下载失败：HTTP ${response.code}")
                    }
                    rejectNonVideoResponse(response)
                    val body = response.body ?: throw IOException("服务器没有返回视频内容")
                    body.byteStream().use { input ->
                        FileOutputStream(segment.file, true).use { output ->
                            val buffer = ByteArray(PARALLEL_BUFFER_SIZE)
                            var remaining = segment.length - localBytes
                            while (remaining > 0L) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                remaining -= read
                                downloadedBytes.addAndGet(read.toLong())
                            }
                        }
                    }
                }
                attempt = 0
            } catch (error: RangeUnsupportedException) {
                throw error
            } catch (error: IOException) {
                translateCancellation(call, error)
                attempt += 1
                if (attempt >= SEGMENT_RETRIES) throw error
                delay(250L * attempt)
            } finally {
                activeCalls -= call
            }
        }
        if (segment.file.length() != segment.length) {
            throw IOException("视频分段不完整，请重试")
        }
    }

    private suspend fun downloadSingle(
        media: ResolvedMedia,
        partialFile: File,
        onProgress: suspend (MediaDownloadProgress) -> Unit
    ) {
        try {
            downloadSingleSource(media, partialFile, onProgress)
        } catch (error: ExpiredMediaUrlException) {
            val canSwitchMirror = media.platform == MediaPlatform.Bilibili &&
                media.compatibilityMediaUrl.isNotBlank() &&
                media.compatibilityMediaUrl != media.mediaUrl
            if (!canSwitchMirror) throw error
            cancelActiveCalls()
            downloadSingleSource(
                media.copy(mediaUrl = media.compatibilityMediaUrl, compatibilityMediaUrl = ""),
                partialFile,
                onProgress
            )
        }
    }

    private suspend fun downloadSingleSource(
        media: ResolvedMedia,
        partialFile: File,
        onProgress: suspend (MediaDownloadProgress) -> Unit
    ) {
        var existingBytes = partialFile.length().coerceAtLeast(0L)
        var retryWithoutRange = false
        while (true) {
            coroutineContext.ensureActive()
            if (cancelled) throw CancellationException("下载已取消")
            val request = requestBuilder(media).apply {
                if ((existingBytes > 0L || MediaDownloadPolicy.forcesRangeRequest(media.platform)) && !retryWithoutRange) {
                    header("Range", "bytes=$existingBytes-")
                }
            }.build()
            val call = client.newCall(request)
            activeCalls += call
            try {
                call.execute().use { response ->
                    throwForStatus(response)
                    if (response.code == 416 && existingBytes > 0L && !retryWithoutRange) {
                        partialFile.delete()
                        existingBytes = 0L
                        retryWithoutRange = true
                        return@use
                    }
                    if (!response.isSuccessful) {
                        throw IOException("下载请求失败：HTTP ${response.code}")
                    }
                    rejectNonVideoResponse(response)
                    val append = existingBytes > 0L && response.code == 206
                    writeSingleResponse(response, partialFile, existingBytes, append, onProgress)
                    return
                }
            } catch (error: IOException) {
                translateCancellation(call, error)
            } finally {
                activeCalls -= call
            }
            if (retryWithoutRange) continue
        }
    }

    private suspend fun writeSingleResponse(
        response: Response,
        partialFile: File,
        existingBytes: Long,
        append: Boolean,
        onProgress: suspend (MediaDownloadProgress) -> Unit
    ) {
        val body = response.body ?: throw IOException("服务器没有返回视频内容")
        val contentLength = body.contentLength().coerceAtLeast(0L)
        val totalBytes = parseTotalBytes(response, existingBytes, contentLength, append)
        if (totalBytes > 0L && availableBytes(partialFile) < totalBytes + MIN_FREE_SPACE) {
            throw IOException("设备剩余空间不足，无法保存该视频")
        }

        var downloadedBytes = if (append) existingBytes else 0L
        var checkpointBytes = downloadedBytes
        var checkpointTime = System.nanoTime()
        var smoothedSpeed = 0L
        var lastUiUpdate = 0L

        body.byteStream().use { input ->
            FileOutputStream(partialFile, append).use { output ->
                val buffer = ByteArray(SINGLE_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    val now = System.nanoTime()
                    val elapsed = now - checkpointTime
                    if (elapsed >= SPEED_WINDOW_NS) {
                        val instant = (downloadedBytes - checkpointBytes) * 1_000_000_000L / elapsed.coerceAtLeast(1L)
                        smoothedSpeed = if (smoothedSpeed == 0L) instant else (smoothedSpeed * 3L + instant) / 4L
                        checkpointBytes = downloadedBytes
                        checkpointTime = now
                    }
                    if (now - lastUiUpdate >= UI_UPDATE_NS) {
                        onProgress(MediaDownloadProgress(downloadedBytes, totalBytes, smoothedSpeed, 1))
                        lastUiUpdate = now
                    }
                }
                output.fd.sync()
            }
        }
        onProgress(MediaDownloadProgress(downloadedBytes, totalBytes.takeIf { it > 0L } ?: downloadedBytes, smoothedSpeed, 1))
    }

    private fun requestBuilder(media: ResolvedMedia): Request.Builder = Request.Builder()
        .url(media.mediaUrl)
        .header("User-Agent", media.userAgent)
        .header("Referer", media.referer)
        .header("Accept", "video/mp4,video/*;q=0.9,application/octet-stream;q=0.8,*/*;q=0.4")
        .header("Accept-Encoding", "identity")
        .apply {
            if (media.cookieHeader.isNotBlank()) header("Cookie", media.cookieHeader)
            MediaDownloadPolicy.originHeader(media.platform).takeIf(String::isNotBlank)?.let { origin ->
                header("Origin", origin)
                header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            }
        }

    private fun buildSegments(
        partialFile: File,
        existingBytes: Long,
        totalBytes: Long,
        connectionCount: Int
    ): List<Segment> {
        return MediaRangePlanner.ranges(existingBytes, totalBytes, connectionCount).map { range ->
            val file = File(outputDirectory, "${partialFile.name}.segment-${range.start}-${range.end}")
            Segment(range.start, range.end, file)
        }
    }

    private fun mergeSegments(partialFile: File, segments: List<Segment>) {
        FileOutputStream(partialFile, true).use { output ->
            val buffer = ByteArray(MERGE_BUFFER_SIZE)
            segments.sortedBy(Segment::start).forEach { segment ->
                FileInputStream(segment.file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
                segment.file.delete()
            }
            output.fd.sync()
        }
    }

    private fun cleanupSegments(partialFile: File, keepNames: Set<String> = emptySet()) {
        val prefix = "${partialFile.name}.segment-"
        outputDirectory.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix) && file.name !in keepNames) file.delete()
        }
    }

    private fun cancelActiveCalls() {
        activeCalls.toList().forEach(Call::cancel)
    }

    private suspend fun translateCancellation(call: Call, error: IOException): Nothing {
        if (cancelled || call.isCanceled()) throw CancellationException("下载已取消")
        coroutineContext.ensureActive()
        throw error
    }

    private fun throwForStatus(response: Response) {
        if (response.code == 403 || response.code == 410) {
            throw ExpiredMediaUrlException("视频地址已过期，需要重新获取")
        }
    }

    private fun rejectNonVideoResponse(response: Response) {
        val type = response.body?.contentType()?.toString()?.lowercase().orEmpty()
        val explicitlyTextual = type.startsWith("text/") || listOf(
            "json", "html", "xml", "css", "javascript"
        ).any(type::contains)
        if (explicitlyTextual) {
            throw ExpiredMediaUrlException("解析到了网页资源，正在重新获取视频源")
        }
    }

    private fun parseContentRangeTotal(response: Response): Long = response.header("Content-Range")
        ?.substringAfterLast('/', missingDelimiterValue = "")
        ?.toLongOrNull()
        ?: 0L

    private fun parseTotalBytes(response: Response, existing: Long, contentLength: Long, append: Boolean): Long {
        val contentRangeTotal = parseContentRangeTotal(response).takeIf { it > 0L }
        return contentRangeTotal ?: if (append) existing + contentLength else contentLength
    }

    private fun availableBytes(target: File): Long = runCatching {
        val directory = target.parentFile ?: outputDirectory
        val storageUuid = storageManager.getUuidForPath(directory)
        storageManager.getAllocatableBytes(storageUuid)
    }.getOrElse {
        target.parentFile?.usableSpace ?: Long.MAX_VALUE
    }

    private fun isLikelyMedia(file: File, mimeType: String, extension: String): Boolean {
        if (!file.isFile || file.length() < MIN_MEDIA_HEADER_BYTES) return false
        val header = ByteArray(MEDIA_SCAN_BYTES)
        val count = runCatching { FileInputStream(file).use { it.read(header) } }.getOrDefault(-1)
        if (count < 8) return false
        val normalized = extension.lowercase()
        if (normalized == "webm" || mimeType.contains("webm", ignoreCase = true)) {
            return header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
        }
        for (index in 0..(count - 4)) {
            if (header[index] == 'f'.code.toByte() &&
                header[index + 1] == 't'.code.toByte() &&
                header[index + 2] == 'y'.code.toByte() &&
                header[index + 3] == 'p'.code.toByte()
            ) return true
        }
        return false
    }

    private fun moveIntoPlace(partialFile: File, finalFile: File) {
        if (!partialFile.renameTo(finalFile)) {
            partialFile.copyTo(finalFile, overwrite = true)
            partialFile.delete()
        }
    }

    private fun ResolvedMedia.toRecord(file: File) = MediaDownloadRecord(
        videoId = videoId,
        title = title,
        author = author,
        thumbnailUrl = thumbnailUrl,
        thumbnailFallbackUrl = thumbnailFallbackUrl,
        sourceUrl = sourceUrl,
        filePath = file.absolutePath,
        fileSize = file.length(),
        watermarkFree = prefersWatermarkFree,
        platform = platform,
        qualityLabel = qualityLabel,
        mimeType = mimeType,
        fileExtension = fileExtension
    )

    private data class DownloadProbe(
        val totalBytes: Long = 0L,
        val supportsRanges: Boolean = false
    )

    private data class Segment(
        val start: Long,
        val end: Long,
        val file: File
    ) {
        val length: Long get() = end - start + 1L
    }

    private class MediaSourceUnavailableException(val statusCode: Int) : IOException("Media source unavailable: HTTP $statusCode")
    private class RangeUnsupportedException : IOException()

    private companion object {
        const val SINGLE_BUFFER_SIZE = 256 * 1024
        const val PARALLEL_BUFFER_SIZE = 128 * 1024
        const val MERGE_BUFFER_SIZE = 512 * 1024
        const val PARALLEL_MIN_BYTES = 512L * 1024L
        const val MIN_FREE_SPACE = 16L * 1024L * 1024L
        const val SPEED_WINDOW_NS = 500_000_000L
        const val UI_UPDATE_NS = 120_000_000L
        const val UI_UPDATE_MS = 160L
        const val SEGMENT_RETRIES = 3
        const val MIN_MEDIA_HEADER_BYTES = 12L
        const val MEDIA_SCAN_BYTES = 4 * 1024
    }
}


