package com.adong.adchat.media.gif

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 三档预设。 */
enum class GifPreset(val widthPx: Int, val fps: Int, val maxDurationMs: Long, val label: String, val estimateLabel: String) {
    Compact(320, 8, 8_000L, "小巧", "约 1–2 MB"),
    Standard(480, 12, 10_000L, "标准", "约 3–5 MB"),
    Smooth(640, 15, 15_000L, "流畅", "约 8–12 MB")
}

data class VideoToGifRequest(
    val videoPath: String,
    val outputDirectory: File,
    val baseName: String,
    val preset: GifPreset
)

data class VideoToGifResult(
    val file: File,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val durationMs: Long,
    val preset: GifPreset
)

object VideoToGifConverter {

    /**
     * 视频转 GIF。转换在调用方协程上下文执行（内部已 withContext(Dispatchers.Default)）。
     * [onProgress] 回调 0f..1f；支持协作取消（CancellationException 原样抛出，输出半成品文件删除）。
     */
    suspend fun convert(request: VideoToGifRequest, onProgress: (Float) -> Unit): VideoToGifResult =
        withContext(Dispatchers.Default) {
            val preset = request.preset
            require(request.outputDirectory.exists() || request.outputDirectory.mkdirs()) {
                "无法创建 GIF 输出目录：${request.outputDirectory.absolutePath}"
            }
            require(request.outputDirectory.isDirectory) {
                "GIF 输出路径不是目录：${request.outputDirectory.absolutePath}"
            }
            val prefix = request.baseName
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_')
                .ifBlank { "aster_gif" }
                .take(36)
                .let { if (it.length < 2) "aster_gif" else it }
            // Each conversion gets its own file. A retry must never destroy a GIF the user already saved.
            val output = File.createTempFile("${prefix}_", ".gif", request.outputDirectory)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(request.videoPath)
                val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                require(videoWidth > 0 && videoHeight > 0) { "无法读取视频尺寸：$request.videoPath" }
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()?.mod(360) ?: 0
                val displayRotation = rotation.takeIf { it % 90 == 0 } ?: 0
                val (displayWidth, displayHeight) = if (displayRotation == 90 || displayRotation == 270) {
                    videoHeight to videoWidth
                } else {
                    videoWidth to videoHeight
                }
                val videoDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L

                val requestedDurationMs = minOf(videoDurationMs, preset.maxDurationMs)
                val timeline = GifTimeline.create(requestedDurationMs, preset.fps)
                val frameCount = timeline.size

                val scale = minOf(1.0, preset.widthPx.toDouble() / max(displayWidth, displayHeight).toDouble())
                val outWidth = even((displayWidth * scale).roundToInt())
                val outHeight = even((displayHeight * scale).roundToInt())

                FileOutputStream(output).use { stream ->
                    val encoder = GifEncoder(stream, loopCount = 0)
                    var globalPalette: GifPalette? = null
                    val quantizer = GifColorQuantizer()

                    for ((i, timing) in timeline.withIndex()) {
                        ensureActive()
                        val timeUs = timing.timestampUs
                        val option = if (i == 0) MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        else MediaMetadataRetriever.OPTION_CLOSEST
                        val frameWidth = if (displayRotation == 90 || displayRotation == 270) outHeight else outWidth
                        val frameHeight = if (displayRotation == 90 || displayRotation == 270) outWidth else outHeight
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(timeUs, option, frameWidth, frameHeight)
                        } else {
                            retriever.getFrameAtTime(timeUs, option)
                        } ?: error("第 $i 帧解码失败：timeUs=$timeUs")

                        var rotated: Bitmap? = null
                        var scaled: Bitmap? = null
                        try {
                            val frameAlreadyRotated =
                                (displayRotation == 90 || displayRotation == 270) &&
                                    bitmap.width == outWidth && bitmap.height == outHeight
                            val upright = if (displayRotation == 0 || frameAlreadyRotated) {
                                bitmap
                            } else {
                                Bitmap.createBitmap(
                                    bitmap,
                                    0,
                                    0,
                                    bitmap.width,
                                    bitmap.height,
                                    Matrix().apply { postRotate(displayRotation.toFloat()) },
                                    true
                                ).also { rotated = it }
                            }
                            val outputBitmap = if (upright.width == outWidth && upright.height == outHeight) {
                                upright
                            } else {
                                Bitmap.createScaledBitmap(upright, outWidth, outHeight, true).also { scaled = it }
                            }
                            val pixels = IntArray(outputBitmap.width * outputBitmap.height)
                            outputBitmap.getPixels(pixels, 0, outputBitmap.width, 0, 0, outputBitmap.width, outputBitmap.height)
                            val quantized = if (i == 0) {
                                quantizer.quantize(pixels, maxColors = 256, dither = true, rowWidth = outputBitmap.width)
                            } else {
                                quantizer.quantize(pixels, maxColors = 256, dither = true, existing = globalPalette, rowWidth = outputBitmap.width)
                            }
                            if (i == 0) {
                                globalPalette = quantized.palette
                                encoder.writeHeader(outWidth, outHeight, quantized.palette)
                            }
                            encoder.writeFrame(quantized.indices, timing.delayCentiseconds)
                        } finally {
                            scaled?.takeIf { it !== bitmap && it !== rotated }?.let { if (!it.isRecycled) it.recycle() }
                            rotated?.takeIf { it !== bitmap }?.let { if (!it.isRecycled) it.recycle() }
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                        onProgress((i + 1).toFloat() / frameCount)
                    }
                    encoder.finish()
                }
                onProgress(1f)
                VideoToGifResult(
                    file = output,
                    width = outWidth,
                    height = outHeight,
                    frameCount = frameCount,
                    durationMs = timeline.sumOf { it.delayCentiseconds.toLong() } * 10L,
                    preset = preset
                )
            } catch (error: Throwable) {
                // 半成品文件清理：取消与其他异常都删掉输出
                if (output.exists()) {
                    output.delete()
                }
                throw error
            } finally {
                runCatching { retriever.release() }
            }
        }

    /** 取偶数（H.264 宽高对齐习惯），最小 2 */
    private fun even(value: Int): Int = max(2, if (value % 2 == 0) value else value - 1)
}
