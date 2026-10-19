package com.adong.adchat.media.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class GifPresetTest {

    private fun delayCentiseconds(preset: GifPreset): Int {
        val frameIntervalUs = 1_000_000L / preset.fps
        val fps = 1_000_000.0 / frameIntervalUs
        return max(2, (100 / fps).toInt())
    }

    @Test
    fun `三档预设的帧延时都落在 2 到 13 厘秒之间`() {
        for (preset in GifPreset.entries) {
            val delay = delayCentiseconds(preset)
            assertTrue("${preset.name} 帧延时 $delay 应至少 2 厘秒", delay >= 2)
            assertTrue("${preset.name} 帧延时 $delay 应至多 13 厘秒", delay <= 13)
        }
    }

    @Test
    fun `各档位帧数上界符合内存约束`() {
        // Compact: 8000ms * 8fps = 64 帧
        assertEquals(64L, GifPreset.Compact.maxDurationMs * GifPreset.Compact.fps / 1_000L)
        // Standard: 10000ms * 12fps = 120 帧
        assertEquals(120L, GifPreset.Standard.maxDurationMs * GifPreset.Standard.fps / 1_000L)
        // Smooth: 15000ms * 15fps = 225 帧
        assertEquals(225L, GifPreset.Smooth.maxDurationMs * GifPreset.Smooth.fps / 1_000L)

        for (preset in GifPreset.entries) {
            val frameCount = preset.maxDurationMs * preset.fps / 1_000L
            val upper = when (preset) {
                GifPreset.Compact -> 64L
                GifPreset.Standard -> 120L
                GifPreset.Smooth -> 225L
            }
            assertTrue("${preset.name} 帧数 $frameCount 超过上限 $upper", frameCount <= upper)
        }
    }

    @Test
    fun `各档位 label 互不相同`() {
        val labels = GifPreset.entries.map { it.label }
        assertEquals("各档位 label 应互不相同：$labels", labels.size, labels.toSet().size)
    }
}
