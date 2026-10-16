package com.adong.adchat.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandMorphGeometryTest {
    private fun geometry(width: Float = 400f) = IslandMorphGeometry().apply {
        configure(width, 64f, 13f, width - 28f, 250f, 32f)
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
        assertEquals(32f, g.radius, 0f)
    }

    @Test fun bothOpeningAndClosingReboundsAreNotClampedAway() {
        val g = geometry()
        g.update(1.02f)
        assertTrue(g.left < 14f)
        assertTrue(g.right > 386f)
        assertTrue(g.bottom > 250f)
        g.update(-0.02f)
        assertTrue(g.left > 187f)
        assertTrue(g.right < 213f)
        assertTrue(g.bottom < 45f)
        assertTrue(g.bottom > g.top)
        assertTrue(g.radius > 0f)
        assertEquals(0f, g.haloRecoil, 0f)
        g.update(-IslandSpring.OVERSHOOT / 2f)
        assertEquals(0.10f, g.haloRecoil, 0.0001f)
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
