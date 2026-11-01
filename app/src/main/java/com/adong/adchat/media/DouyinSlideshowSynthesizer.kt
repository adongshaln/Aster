package com.adong.adchat.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native hardware-accelerated synthesizer that converts Douyin image-note slideshows (`images[]`)
 * + background music (`MP3` or `M4A/AAC`) into a standard 1080P H.264 + AAC `.mp4` video.
 */
internal object DouyinSlideshowSynthesizer {
    private const val TAG = "AsterMedia"
    private const val FPS = 15
    private const val FRAME_INTERVAL_US = 1_000_000L / FPS
    private const val SLIDE_DURATION_US = 2_600_000L
    private const val CROSSFADE_DURATION_US = 340_000L
    private const val MIN_TOTAL_DURATION_US = 3_200_000L
    private const val MAX_TOTAL_DURATION_US = 60_000_000L
    private const val VIDEO_BITRATE = 5_500_000
    private const val AUDIO_BITRATE = 128_000
    private const val CODEC_TIMEOUT_US = 12_000L

    fun synthesize(
        imageFiles: List<File>,
        audioFile: File?,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val rawBitmaps = imageFiles.mapNotNull { file ->
            runCatching { decodeBoundedBitmap(file, maxEdge = 1920) }.getOrNull()
        }
        if (rawBitmaps.isEmpty()) {
            Log.w(TAG, "DouyinSlideshowSynthesizer: no decodable images")
            return false
        }

        val first = rawBitmaps.first()
        val (targetWidth, targetHeight) = selectCanvasSize(first.width, first.height)
        val composedSlides = rawBitmaps.map { src ->
            composeSlideFrame(src, targetWidth, targetHeight).also {
                if (it !== src) src.recycle()
            }
        }

        val workDir = outputFile.parentFile ?: imageFiles.first().parentFile
        val videoTemp = File(workDir, "${outputFile.name}.video_only.mp4.tmp")
        val audioTemp = File(workDir, "${outputFile.name}.audio_aac.m4a.tmp")
        try {
            val bgmDurationUs = audioFile?.takeIf { it.exists() && it.length() > 256L }
                ?.let(::probeAudioDurationUs)
                ?: 0L
            val singlePassDurationUs = composedSlides.size * SLIDE_DURATION_US
            val totalDurationUs = when {
                composedSlides.size == 1 && bgmDurationUs > 0L ->
                    bgmDurationUs.coerceIn(MIN_TOTAL_DURATION_US, 18_000_000L)
                bgmDurationUs > singlePassDurationUs ->
                    min(bgmDurationUs, max(singlePassDurationUs, 15_000_000L)).coerceAtMost(MAX_TOTAL_DURATION_US)
                else ->
                    singlePassDurationUs.coerceIn(MIN_TOTAL_DURATION_US, MAX_TOTAL_DURATION_US)
            }

            val videoOk = encodeSlideshowVideo(
                slides = composedSlides,
                width = targetWidth,
                height = targetHeight,
                totalDurationUs = totalDurationUs,
                outputFile = videoTemp,
                onProgress = { fraction -> onProgress(fraction * 0.78f) }
            )
            if (!videoOk || !videoTemp.exists() || videoTemp.length() <= 0L) {
                return false
            }

            val hasAudio = audioFile != null && audioFile.exists() && audioFile.length() > 256L
            if (hasAudio) {
                val audioReady = runCatching {
                    transcodeOrCopyAudioToAacM4a(
                        inputAudioFile = audioFile!!,
                        targetDurationUs = totalDurationUs,
                        outputM4aFile = audioTemp
                    )
                }.getOrDefault(false)
                onProgress(0.92f)
                if (audioReady && audioTemp.exists() && audioTemp.length() > 0L) {
                    if (BilibiliDashMuxer.mux(videoTemp, audioTemp, outputFile)) {
                        onProgress(1f)
                        return true
                    }
                }
            }

            // Fallback: if the note had no BGM or audio transcode failed, deliver the valid video MP4
            if (outputFile.exists()) outputFile.delete()
            if (!videoTemp.renameTo(outputFile)) {
                videoTemp.copyTo(outputFile, overwrite = true)
            }
            onProgress(1f)
            return outputFile.exists() && outputFile.length() > 0L
        } catch (error: Throwable) {
            Log.e(TAG, "DouyinSlideshowSynthesizer failed: ${error.message}", error)
            return false
        } finally {
            composedSlides.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
            runCatching { videoTemp.delete() }
            runCatching { audioTemp.delete() }
        }
    }

    private fun selectCanvasSize(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 1080 to 1440
        val ratio = srcHeight.toFloat() / srcWidth.toFloat()
        return when {
            ratio >= 1.55f -> 1080 to 1920
            ratio >= 1.0f -> 1080 to 1440
            else -> 1920 to 1080
        }
    }

    private fun decodeBoundedBitmap(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxEdge * 2 || bounds.outHeight / sampleSize > maxEdge * 2) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun composeSlideFrame(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(18, 18, 17))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 1. Subtle dimmed center-crop backdrop so non-matching aspect ratios have no harsh black bars
        val cropScale = max(
            targetWidth.toFloat() / src.width.toFloat(),
            targetHeight.toFloat() / src.height.toFloat()
        ) * 1.08f
        val bgW = src.width * cropScale
        val bgH = src.height * cropScale
        val bgRect = RectF(
            (targetWidth - bgW) / 2f,
            (targetHeight - bgH) / 2f,
            (targetWidth + bgW) / 2f,
            (targetHeight + bgH) / 2f
        )
        paint.alpha = 70
        canvas.drawBitmap(src, null, bgRect, paint)
        canvas.drawColor(Color.argb(145, 16, 16, 15))

        // 2. Crisp center-fit foreground slide
        val fitScale = min(
            targetWidth.toFloat() / src.width.toFloat(),
            targetHeight.toFloat() / src.height.toFloat()
        )
        val fgW = src.width * fitScale
        val fgH = src.height * fitScale
        val fgRect = RectF(
            (targetWidth - fgW) / 2f,
            (targetHeight - fgH) / 2f,
            (targetWidth + fgW) / 2f,
            (targetHeight + fgH) / 2f
        )
        paint.alpha = 255
        canvas.drawBitmap(src, null, fgRect, paint)
        return out
    }

    private fun encodeSlideshowVideo(
        slides: List<Bitmap>,
        width: Int,
        height: Int,
        totalDurationUs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        if (outputFile.exists()) outputFile.delete()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var inputSurface: android.view.Surface? = null
        var muxerStarted = false
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()
        var writtenFrames = 0L

        fun drainEncoder(endOfStream: Boolean) {
            if (endOfStream) {
                encoder.signalEndOfInputStream()
            }
            while (true) {
                val status = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) CODEC_TIMEOUT_US else 4_000L)
                when {
                    status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) break
                    }
                    status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    status >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(status)
                        if (encodedData != null && bufferInfo.size > 0) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted) {
                                bufferInfo.presentationTimeUs = writtenFrames * FRAME_INTERVAL_US
                                writtenFrames += 1L
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(status, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }
        }

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            val totalFrames = max(1, (totalDurationUs / FRAME_INTERVAL_US).toInt())
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val matrix = Matrix()

            for (frameIdx in 0 until totalFrames) {
                val timeUs = frameIdx * FRAME_INTERVAL_US
                val slideStep = (timeUs / SLIDE_DURATION_US).toInt()
                val primarySlide = slides[slideStep % slides.size]
                val offsetInSlideUs = timeUs % SLIDE_DURATION_US
                val crossfadeStartUs = SLIDE_DURATION_US - CROSSFADE_DURATION_US

                val canvas = inputSurface.lockCanvas(null)
                try {
                    paint.alpha = 255
                    canvas.drawBitmap(primarySlide, matrix, paint)
                    if (slides.size > 1 && offsetInSlideUs >= crossfadeStartUs) {
                        val nextSlide = slides[(slideStep + 1) % slides.size]
                        val blend = ((offsetInSlideUs - crossfadeStartUs).toFloat() / CROSSFADE_DURATION_US.toFloat())
                            .coerceIn(0f, 1f)
                        paint.alpha = (blend * 255f).roundToInt().coerceIn(0, 255)
                        canvas.drawBitmap(nextSlide, matrix, paint)
                    }
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                drainEncoder(endOfStream = false)
                if (frameIdx % 5 == 0 || frameIdx == totalFrames - 1) {
                    onProgress((frameIdx + 1).toFloat() / totalFrames.toFloat())
                }
            }

            drainEncoder(endOfStream = true)
            if (muxerStarted) {
                runCatching { muxer.stop() }
                muxerStarted = false
            }
            return writtenFrames > 0L && outputFile.exists() && outputFile.length() > 0L
        } finally {
            runCatching { inputSurface?.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }

    private fun probeAudioDurationUs(audioFile: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioFile.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/", ignoreCase = true)) {
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        val dur = format.getLong(MediaFormat.KEY_DURATION)
                        if (dur > 0L) return dur
                    }
                    val bitRate = runCatching {
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else 128_000
                    }.getOrDefault(128_000).coerceAtLeast(32_000)
                    return (audioFile.length() * 8L * 1_000_000L) / bitRate.toLong()
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { extractor.release() }
        }
        return if (audioFile.length() > 8_192L) {
            (audioFile.length() * 8L * 1_000_000L) / 128_000L
        } else 0L
    }

    /**
     * Converts an arbitrary Douyin audio file (commonly `audio/mpeg` MP3 or `audio/mp4a-latm` M4A)
     * into an MP4-muxable AAC (`.m4a`) file trimmed (or looped) to [targetDurationUs].
     */
    private fun transcodeOrCopyAudioToAacM4a(
        inputAudioFile: File,
        targetDurationUs: Long,
        outputM4aFile: File
    ): Boolean {
        if (outputM4aFile.exists()) outputM4aFile.delete()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputAudioFile.absolutePath)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            var inputMime = ""
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/", ignoreCase = true)) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    inputMime = mime
                    break
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) return false
            extractor.selectTrack(audioTrackIndex)

            // Fast path: already AAC (`audio/mp4a-latm`) -> copy samples directly with looping/trimming
            if (inputMime.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)) {
                val muxer = MediaMuxer(outputM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var muxerStopped = false
                try {
                    val outTrack = muxer.addTrack(inputFormat)
                    muxer.start()
                    val buf = ByteBuffer.allocate(256 * 1024)
                    val info = MediaCodec.BufferInfo()
                    var timeOffsetUs = 0L
                    var lastSampleUs = -1L
                    var samplesWritten = 0
                    while (timeOffsetUs < targetDurationUs) {
                        val pts = extractor.sampleTime
                        if (pts < 0L) {
                            if (lastSampleUs <= 0L) break
                            timeOffsetUs = lastSampleUs + 23_219L
                            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            continue
                        }
                        val effectivePts = (timeOffsetUs + pts).coerceAtLeast(lastSampleUs + 1L)
                        if (effectivePts > targetDurationUs) break
                        buf.clear()
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) break
                        info.offset = 0
                        info.size = sz
                        info.presentationTimeUs = effectivePts
                        info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else 0
                        muxer.writeSampleData(outTrack, buf, info)
                        lastSampleUs = effectivePts
                        samplesWritten += 1
                        extractor.advance()
                    }
                    muxer.stop()
                    muxerStopped = true
                    return samplesWritten > 0 && outputM4aFile.exists() && outputM4aFile.length() > 0L
                } finally {
                    if (!muxerStopped) runCatching { muxer.stop() }
                    runCatching { muxer.release() }
                }
            }

            // General path: decode (`audio/mpeg` MP3, etc.) -> PCM -> encode AAC (`audio/mp4a-latm`)
            val sampleRate = runCatching { inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100)
            val channelCount = runCatching { inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2).coerceIn(1, 2)
            val bytesPerSecond = (sampleRate * channelCount * 2L).coerceAtLeast(1L)
            val decoder = MediaCodec.createDecoderByType(inputMime)
            val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val muxer = MediaMuxer(outputM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false
            var outTrack = -1
            var samplesWritten = 0

            fun drainAacEncoder(): Boolean {
                val encInfo = MediaCodec.BufferInfo()
                while (true) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encInfo, 2_000L)
                    when {
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                outTrack = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        encOutIdx >= 0 -> {
                            val encOutBuf = encoder.getOutputBuffer(encOutIdx)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                encInfo.size = 0
                            }
                            if (encOutBuf != null && encInfo.size > 0 && muxerStarted) {
                                encOutBuf.position(encInfo.offset)
                                encOutBuf.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(outTrack, encOutBuf, encInfo)
                                samplesWritten += 1
                            }
                            val eos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if (eos) return true
                        }
                    }
                }
            }

            try {
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()
                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                val decInfo = MediaCodec.BufferInfo()
                var extractorDone = false
                var decoderDone = false
                var encoderDone = false
                var totalPcmBytesQueued = 0L

                while (!encoderDone) {
                    if (!extractorDone) {
                        val inIdx = decoder.dequeueInputBuffer(2_000L)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)!!
                            inBuf.clear()
                            val currentPcmPtsUs = (totalPcmBytesQueued * 1_000_000L) / bytesPerSecond
                            if (currentPcmPtsUs >= targetDurationUs) {
                                decoder.queueInputBuffer(inIdx, 0, 0, currentPcmPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                var sz = extractor.readSampleData(inBuf, 0)
                                if (sz < 0 && totalPcmBytesQueued > 0L) {
                                    // Loop BGM from the beginning if it's shorter than the slideshow
                                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                    sz = extractor.readSampleData(inBuf, 0)
                                }
                                if (sz < 0) {
                                    decoder.queueInputBuffer(inIdx, 0, 0, currentPcmPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    extractorDone = true
                                } else {
                                    val pts = extractor.sampleTime.coerceAtLeast(0L)
                                    decoder.queueInputBuffer(inIdx, 0, sz, pts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    while (!decoderDone) {
                        val decOutIdx = decoder.dequeueOutputBuffer(decInfo, 2_000L)
                        if (decOutIdx < 0) break
                        val decOutBuf = decoder.getOutputBuffer(decOutIdx)
                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (decOutBuf != null && decInfo.size > 0) {
                            decOutBuf.position(decInfo.offset)
                            decOutBuf.limit(decInfo.offset + decInfo.size)
                            while (decOutBuf.hasRemaining()) {
                                drainAacEncoder()
                                val encInIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                                if (encInIdx < 0) continue
                                val encInBuf = encoder.getInputBuffer(encInIdx)!!
                                encInBuf.clear()
                                val toCopy = min(decOutBuf.remaining(), encInBuf.remaining())
                                val oldLimit = decOutBuf.limit()
                                decOutBuf.limit(decOutBuf.position() + toCopy)
                                encInBuf.put(decOutBuf)
                                decOutBuf.limit(oldLimit)
                                val pcmPtsUs = (totalPcmBytesQueued * 1_000_000L) / bytesPerSecond
                                totalPcmBytesQueued += toCopy.toLong()
                                encoder.queueInputBuffer(encInIdx, 0, toCopy, pcmPtsUs, 0)
                            }
                        }
                        decoder.releaseOutputBuffer(decOutIdx, false)
                        if (isEos) {
                            while (true) {
                                drainAacEncoder()
                                val encInIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                                if (encInIdx >= 0) {
                                    val eosPtsUs = (totalPcmBytesQueued * 1_000_000L) / bytesPerSecond
                                    encoder.queueInputBuffer(encInIdx, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    break
                                }
                            }
                            decoderDone = true
                        }
                    }

                    if (drainAacEncoder()) {
                        encoderDone = true
                    }
                }

                if (muxerStarted) {
                    runCatching { muxer.stop() }
                    muxerStarted = false
                }
                return samplesWritten > 0 && outputM4aFile.exists() && outputM4aFile.length() > 0L
            } finally {
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }
}
