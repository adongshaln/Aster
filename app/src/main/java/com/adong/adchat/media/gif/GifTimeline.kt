package com.adong.adchat.media.gif

import kotlin.math.max
import kotlin.math.min

data class GifFrameTiming(val timestampUs: Long, val delayCentiseconds: Int)

/** Builds frame timestamps and GIF's 1/100-second delays while keeping the requested duration. */
object GifTimeline {
    fun create(durationMs: Long, framesPerSecond: Int): List<GifFrameTiming> {
        require(durationMs > 0L) { "视频时长必须大于 0" }
        require(framesPerSecond in 1..50) { "GIF 帧率须在 1 到 50 之间" }

        val durationUs = durationMs * 1_000L
        val frameCount = ((durationUs * framesPerSecond + 999_999L) / 1_000_000L).toInt()
        return List(frameCount) { index ->
            val startUs = index * 1_000_000L / framesPerSecond
            val endUs = min(durationUs, (index + 1L) * 1_000_000L / framesPerSecond)
            val startCentiseconds = (startUs + 5_000L) / 10_000L
            val endCentiseconds = (endUs + 5_000L) / 10_000L
            GifFrameTiming(
                timestampUs = startUs,
                delayCentiseconds = max(2, (endCentiseconds - startCentiseconds).toInt())
            )
        }
    }
}
