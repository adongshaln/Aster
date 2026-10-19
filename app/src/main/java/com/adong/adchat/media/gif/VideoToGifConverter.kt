package com.adong.adchat.media.gif

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
    val preset: GifPreset,
    val fullVideo: Boolean
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
            val retriever = MediaMetadataRetriever()
            val output = File(request.outputDirectory, "${request.baseName}.gif")
            var succeeded = false
            try {
                retriever.setDataSource(request.videoPath)
                val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                require(videoWidth > 0 && videoHeight > 0) { "无法读取视频尺寸：$request.videoPath" }
                val videoDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L

                val durationMs = minOf(videoDurationMs, preset.maxDurationMs).coerceAtLeast(0L)
                val frameIntervalUs = 1_000_000L / preset.fps
                val durationUs = durationMs * 1_000L
                val frameCount = max(1L, durationUs / frameIntervalUs).toInt()

                val outWidth = preset.widthPx
                val outHeight = even((videoHeight.toLong() * outWidth / videoWidth).toInt())

                FileOutputStream(output).use { stream ->
                    val encoder = GifEncoder(stream, loopCount = 0)
                    var globalPalette: GifPalette? = null
                    val quantizer = GifColorQuantizer()
                    val delayCentiseconds = max(2, 100 / preset.fps)

                    for (i in 0 until frameCount) {
                        ensureActive()
                        val timeUs = i * frameIntervalUs
                        val bitmap = if (i == 0) {
                            // 首帧取关键帧建全局调色板
                            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } else {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        } ?: error("第 $i 帧解码失败：timeUs=$timeUs")

                        try {
                            val scaled = if (bitmap.width == outWidth && bitmap.height == outHeight) {
                                bitmap
                            } else {
                                Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true)
                            }
                            val pixels = IntArray(scaled.width * scaled.height)
                            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                            val quantized = if (i == 0) {
                                quantizer.quantize(pixels, maxColors = 256, dither = true)
                            } else {
                                quantizer.quantize(pixels, maxColors = 256, dither = true, existing = globalPalette)
                            }
                            if (i == 0) {
                                globalPalette = quantized.palette
                                encoder.writeHeader(outWidth, outHeight, quantized.palette)
                            }
                            encoder.writeFrame(quantized.indices, delayCentiseconds)
                            if (scaled !== bitmap) scaled.recycle()
                        } finally {
                            bitmap.recycle()
                        }
                        onProgress((i + 1).toFloat() / frameCount)
                    }
                    encoder.finish()
                }
                succeeded = true
                onProgress(1f)
                VideoToGifResult(
                    file = output,
                    width = outWidth,
                    height = outHeight,
                    frameCount = frameCount,
                    durationMs = durationMs,
                    preset = preset
                )
            } catch (error: Throwable) {
                // 半成品文件清理：取消与其他异常都删掉输出
                if (output.exists()) {
                    output.delete()
                }
                throw error
            } finally {
                if (!succeeded) {
                    output.deleteOnExit()
                }
                runCatching { retriever.release() }
            }
        }

    /** 取偶数（H.264 宽高对齐习惯），最小 2 */
    private fun even(value: Int): Int = max(2, if (value % 2 == 0) value else value - 1)
}
