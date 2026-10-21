package com.adong.adchat.media.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GifTimelineTest {

    @Test
    fun `preset duration limits produce the intended frame counts`() {
        for (preset in GifPreset.entries) {
            val timeline = GifTimeline.create(preset.maxDurationMs, preset.fps)
            val expected = (preset.maxDurationMs * preset.fps + 999L) / 1_000L
            assertEquals(preset.name, expected.toInt(), timeline.size)
            assertEquals(preset.maxDurationMs, timeline.sumOf { it.delayCentiseconds.toLong() } * 10L)
        }
    }

    @Test
    fun `timestamps increase and stay inside the requested duration`() {
        val durationMs = 1_234L
        val fps = 15
        val timeline = GifTimeline.create(durationMs, fps)

        assertEquals(19, timeline.size)
        assertEquals(0L, timeline.first().timestampUs)
        assertTrue(timeline.zipWithNext().all { (a, b) -> a.timestampUs < b.timestampUs })
        assertTrue(timeline.all { it.timestampUs < durationMs * 1_000L })
        val gifDurationMs = (durationMs + 5L) / 10L * 10L
        assertEquals(gifDurationMs, timeline.sumOf { it.delayCentiseconds.toLong() } * 10L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero duration is rejected`() {
        GifTimeline.create(0L, 12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported frame rate is rejected`() {
        GifTimeline.create(1_000L, 51)
    }
}
