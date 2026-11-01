package com.adong.adchat.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Lossless hardware-native DASH track muxer using Android's [MediaExtractor] and [MediaMuxer].
 *
 * Combines a video-only `.m4s` stream and an audio-only `.m4s` stream from Bilibili's TV DASH
 * endpoint into a standard, seekable `.mp4` container without re-encoding.
 */
internal object BilibiliDashMuxer {
    private const val TAG = "AsterMedia"
    private const val DEFAULT_BUFFER_SIZE = 2 * 1024 * 1024

    private class TrackHandle(
        val extractor: MediaExtractor,
        val format: MediaFormat
    ) {
        fun release() = runCatching { extractor.release() }
    }

    fun mux(videoFile: File, audioFile: File, outputFile: File): Boolean {
        val videoTrack = openTrack(videoFile, "video/")
        val audioTrack = openTrack(audioFile, "audio/")
        if (videoTrack == null || audioTrack == null) {
            Log.w(TAG, "BilibiliDashMuxer: missing video or audio track (video=${videoTrack != null}, audio=${audioTrack != null})")
            videoTrack?.release()
            audioTrack?.release()
            return false
        }

        if (outputFile.exists()) outputFile.delete()
        val muxer = try {
            MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Throwable) {
            Log.e(TAG, "BilibiliDashMuxer: failed to create MediaMuxer: ${error.message}")
            videoTrack.release()
            audioTrack.release()
            return false
        }

        try {
            val outVideoIndex = muxer.addTrack(videoTrack.format)
            val outAudioIndex = muxer.addTrack(audioTrack.format)
            val rotation = runCatching {
                if (videoTrack.format.containsKey(MediaFormat.KEY_ROTATION)) {
                    videoTrack.format.getInteger(MediaFormat.KEY_ROTATION)
                } else 0
            }.getOrDefault(0)
            if (rotation != 0) {
                runCatching { muxer.setOrientationHint(rotation) }
            }

            muxer.start()
            copyAllSamples(videoTrack, muxer, outVideoIndex)
            copyAllSamples(audioTrack, muxer, outAudioIndex)
            muxer.stop()
            return outputFile.exists() && outputFile.length() > 0L
        } catch (error: Throwable) {
            Log.e(TAG, "BilibiliDashMuxer: mux failed: ${error.message}", error)
            return false
        } finally {
            runCatching { muxer.release() }
            videoTrack.release()
            audioTrack.release()
            if (!outputFile.exists() || outputFile.length() == 0L) {
                runCatching { outputFile.delete() }
            }
        }
    }

    private fun openTrack(file: File, mimePrefix: String): TrackHandle? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith(mimePrefix, ignoreCase = true)) {
                    extractor.selectTrack(index)
                    return TrackHandle(extractor, format)
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "BilibiliDashMuxer: failed to inspect $file for $mimePrefix: ${error.message}")
        }
        runCatching { extractor.release() }
        return null
    }

    private fun copyAllSamples(track: TrackHandle, muxer: MediaMuxer, outTrackIndex: Int) {
        val extractor = track.extractor
        val info = MediaCodec.BufferInfo()
        var capacity = runCatching {
            if (track.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                track.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_BUFFER_SIZE)
            } else DEFAULT_BUFFER_SIZE
        }.getOrDefault(DEFAULT_BUFFER_SIZE)
        var buffer = ByteBuffer.allocate(capacity)

        var basePtsUs = Long.MIN_VALUE
        var lastPtsUs = -1L
        while (true) {
            buffer.clear()
            val sampleSize = try {
                extractor.readSampleData(buffer, 0)
            } catch (_: IllegalArgumentException) {
                val retryPts = extractor.sampleTime.coerceAtLeast(0L)
                capacity *= 2
                buffer = ByteBuffer.allocate(capacity)
                extractor.seekTo(retryPts, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                continue
            }
            if (sampleSize < 0) break
            val rawPts = extractor.sampleTime
            if (basePtsUs == Long.MIN_VALUE) {
                // M4A files with an `elst` edit list (e.g. 2048 priming samples) report negative
                // sampleTime (-46439 us) on the first 2 samples; shift them to start at 0 us.
                basePtsUs = rawPts.coerceAtMost(0L)
            }
            val effectivePts = (rawPts - basePtsUs).coerceAtLeast(lastPtsUs + 1L)
            lastPtsUs = effectivePts
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = effectivePts
            val rawFlags = extractor.sampleFlags
            var codecFlags = 0
            if ((rawFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            if ((rawFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }
            info.flags = codecFlags
            muxer.writeSampleData(outTrackIndex, buffer, info)
            extractor.advance()
        }
    }
}
