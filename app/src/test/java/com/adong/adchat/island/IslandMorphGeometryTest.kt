package com.adong.adchat.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandMorphGeometryTest {
    private fun geometry(width: Float = 400f) = IslandMorphGeometry().apply {
        configure(width / 2f, 32f, 13f, 14f, 19f, width - 14f, 250f, 26f)
    }

    @Test fun seedIsExactlyTheOriginalBlackHole() {
        val g = geometry()
        g.update(0f)
        assertEquals(200f, g.centerX, 0f)
        assertEquals(32f, g.centerY, 0f)
        assertEquals(187f, g.left, 0f)
        assertEquals(213f, g.right, 0f)
        assertEquals(19f, g.top, 0f)
        assertEquals(45f, g.bottom, 0f)
        assertEquals(13f, g.radius, 0f)
    }

    @Test fun bodyGrowsAroundOneHorizontalAnchorWithoutMovingItsTop() {
        val g = geometry()
        for (index in 0..1_000) {
            g.update(index / 1_000f * 1.02f)
            assertEquals(200f, (g.left + g.right) / 2f, 0.0001f)
            assertEquals(19f, g.top, 0f)
            assertTrue(g.bottom >= 45f)
        }
        g.update(1f)
        assertEquals(14f, g.left, 0f)
        assertEquals(386f, g.right, 0f)
        assertEquals(250f, g.bottom, 0f)
        assertEquals(26f, g.radius, 0f)
    }

    @Test fun cardShapeStaysAtTheSeedWhileClosingTailReboundsOnlyTheHalo() {
        val g = geometry()
        g.update(1.02f)
        assertTrue(g.left < 14f)
        assertTrue(g.right > 386f)
        assertTrue(g.bottom > 250f)

        g.update(0f)
        assertEquals(187f, g.left, 0f)
        assertEquals(213f, g.right, 0f)
        assertEquals(19f, g.top, 0f)
        assertEquals(45f, g.bottom, 0f)
        assertEquals(0f, g.haloRecoil, 0f)

        g.update(-IslandSpring.OVERSHOOT / 2f)
        assertEquals(187f, g.left, 0f)
        assertEquals(213f, g.right, 0f)
        assertEquals(19f, g.top, 0f)
        assertEquals(45f, g.bottom, 0f)
        assertEquals(0.05f, g.haloRecoil, 0.0001f)

        g.update(-IslandSpring.OVERSHOOT)
        assertEquals(0f, g.haloRecoil, 0f)
    }

    @Test fun collapseTailProducesExactlyOneHaloRecoilPeak() {
        val g = geometry()
        val samples = (0..1_000).map { index ->
            val closingProgress = 1f - IslandSpring.progress(index / 1_000f)
            g.update(closingProgress)
            g.haloRecoil
        }
        var peaks = 0
        for (i in 1 until samples.lastIndex) {
            if (samples[i] > samples[i - 1] && samples[i] > samples[i + 1]) peaks++
        }
        assertEquals(1, peaks)
        assertEquals(0f, samples.first(), 0f)
        assertEquals(0f, samples.last(), 0f)
    }

    @Test fun samplingOrderAndReverseFromCurrentPositionHaveNoPositionJump() {
        val g = geometry()
        for (time in listOf(0.05f, 0.79f, 0.81f, 0.95f)) {
            val from = IslandSpring.progress(time)
            g.update(from)
            val left = g.left
            val top = g.top
            val bottom = g.bottom
            val restarted = from + (0f - from) * IslandSpring.progress(0f)
            g.update(restarted)
            assertEquals(left, g.left, 0f)
            assertEquals(top, g.top, 0f)
            assertEquals(bottom, g.bottom, 0f)
        }
    }

    @Test fun viewportResizeChangesOnlyLocalXNotTheScreenAnchorOrSeedY() {
        for (width in listOf(64f, 360f, 400f, 440f)) {
            val g = geometry(width)
            g.update(0f)
            // A TOP|CENTER_HORIZONTAL overlay has this screen-space left; vertical origin is fixed.
            val windowLeft = (1080f - width) / 2f
            assertEquals(540f, windowLeft + g.centerX, 0f)
            assertEquals(32f, g.centerY, 0f)
            assertEquals(19f, g.top, 0f)
        }
    }
}
