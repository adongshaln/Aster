package com.adong.adchat.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IslandMorphGeometryTest {
    private fun geometry(width: Float = 400f) = IslandMorphGeometry().apply {
        configure(width / 2f, 32f, 13f, 14f, 19f, width - 14f, 250f, 40f)
    }

    @Test fun seedIsExactlyTheOriginalBlackHole() {
        val g = geometry()
        g.update(0f)
        assertEquals(SEED_LEFT, g.left, DELTA)
        assertEquals(SEED_RIGHT, g.right, DELTA)
        assertEquals(SEED_TOP, g.top, DELTA)
        assertEquals(SEED_BOTTOM, g.bottom, DELTA)
        assertEquals(SEED_RADIUS, g.radius, DELTA)
        assertEquals(1f, g.ringAlpha, DELTA)
        assertEquals(0f, g.contentAlpha, DELTA)
        assertEquals(0f, g.recoil, DELTA)
    }

    @Test fun bodyStretchesSidewaysBeforeGrowingDown() {
        val g = geometry()
        var previousWidth = -1f
        for (index in 0..300) {
            val progress = index / 1000f
            g.update(progress)
            // The sideways stage is a pure horizontal stretch around a frozen top edge.
            assertEquals("top moved at p=$progress", SEED_TOP, g.top, 0f)
            assertEquals("bottom moved at p=$progress", SEED_BOTTOM, g.bottom, 0f)
            assertEquals("radius moved at p=$progress", SEED_RADIUS, g.radius, DELTA)
            val width = g.right - g.left
            assertTrue("width retracted at p=$progress", width >= previousWidth - 1e-6f)
            previousWidth = width
        }
        assertTrue("must be nearly full width by HEIGHT_START; width=$previousWidth", previousWidth > 0.75f * FULL_WIDTH)

        g.update(IslandMorphGeometry.WIDTH_END)
        assertEquals(EXPANDED_LEFT, g.left, DELTA)
        assertEquals(EXPANDED_RIGHT, g.right, DELTA)
        assertTrue("must barely have grown down by WIDTH_END; bottom=${g.bottom}", g.bottom - SEED_BOTTOM < 0.2f * VERTICAL_TRAVEL)
    }

    @Test fun widthHoldsWhileOnlyHeightGrowsAndRebounds() {
        val g = geometry()
        var previousBottom = -1f
        for (index in 550..1020) {
            val progress = index / 1000f
            g.update(progress)
            assertEquals("left moved at p=$progress", EXPANDED_LEFT, g.left, DELTA)
            assertEquals("right moved at p=$progress", EXPANDED_RIGHT, g.right, DELTA)
            assertEquals("top moved at p=$progress", SEED_TOP, g.top, DELTA)
            assertTrue("bottom retracted at p=$progress", g.bottom >= previousBottom - 1e-6f)
            previousBottom = g.bottom
        }

        g.update(1f)
        assertEquals(EXPANDED_BOTTOM, g.bottom, DELTA)
        assertEquals(EXPANDED_RADIUS, g.radius, DELTA)
        assertEquals(1f, g.contentAlpha, DELTA)
        assertEquals(1f, g.contentScale, DELTA)
        assertEquals(0f, g.ringAlpha, DELTA)

        g.update(1f + IslandSpring.OVERSHOOT)
        assertEquals(EXPANDED_BOTTOM + VERTICAL_TRAVEL * IslandMorphGeometry.MAX_HEIGHT_OVERSHOOT, g.bottom, 0.01f)
        assertEquals(EXPANDED_LEFT, g.left, DELTA)
        assertEquals(EXPANDED_RIGHT, g.right, DELTA)
        assertEquals(1f, g.contentAlpha, DELTA)
        assertEquals(1f, g.contentScale, DELTA)
        assertEquals(EXPANDED_RADIUS, g.radius, DELTA)
    }

    @Test fun openingHasExactlyOneHeightOvershootAndNoWidthOvershoot() {
        val g = geometry()
        val bottoms = mutableListOf<Float>()
        for (step in 0..1_000) {
            g.update(IslandSpring.progress(step / 1_000f))
            assertTrue("body crossed the left anchor at step=$step; left=${g.left}", g.left >= EXPANDED_LEFT - 1e-3f)
            assertTrue("body crossed the right anchor at step=$step; right=${g.right}", g.right <= EXPANDED_RIGHT + 1e-3f)
            bottoms += g.bottom
        }
        var peaks = 0
        var peakValue = 0f
        for (index in 1 until bottoms.lastIndex) {
            if (bottoms[index] > bottoms[index - 1] && bottoms[index] > bottoms[index + 1]) {
                peaks++
                if (bottoms[index] > peakValue) peakValue = bottoms[index]
            }
        }
        assertEquals("opening must carry exactly one height peak", 1, peaks)
        assertTrue("the single peak must be the spring overshoot; peak=$peakValue", peakValue > EXPANDED_BOTTOM)
        assertEquals(SEED_BOTTOM, bottoms.first(), 1e-3f)
        assertEquals(EXPANDED_BOTTOM, bottoms.last(), 1e-3f)
    }

    @Test fun closingNeverShrinksBelowTheSeedAndRecoilPeaksOnce() {
        val g = geometry()
        val recoils = ArrayList<Float>(1_001)
        for (step in 0..1_000) {
            val closing = 1f - IslandSpring.progress(step / 1_000f)
            g.update(closing)
            assertTrue("left overshot the seed at step=$step; left=${g.left}", g.left <= SEED_LEFT + 1e-4f)
            assertTrue("right undershot the seed at step=$step; right=${g.right}", g.right >= SEED_RIGHT - 1e-4f)
            assertEquals("top moved at step=$step", SEED_TOP, g.top, 1e-4f)
            assertTrue("bottom shrank below the seed at step=$step; bottom=${g.bottom}", g.bottom >= SEED_BOTTOM - 1e-4f)
            assertTrue("radius exceeded the body width at step=$step", g.radius <= (g.right - g.left) / 2f + 1e-4f)
            assertTrue("radius exceeded the body height at step=$step", g.radius <= (g.bottom - g.top) / 2f + 1e-4f)
            recoils += g.recoil
        }
        var peaks = 0
        for (index in 1 until recoils.lastIndex) {
            if (recoils[index] > recoils[index - 1] && recoils[index] > recoils[index + 1]) peaks++
        }
        assertEquals("closing must carry exactly one recoil peak", 1, peaks)
        assertEquals(0f, recoils.first(), 0f)
        assertEquals(0f, recoils.last(), 0f)

        g.update(-IslandSpring.OVERSHOOT / 2f)
        assertEquals(SEED_LEFT, g.left, 1e-4f)
        assertEquals(SEED_RIGHT, g.right, 1e-4f)
        assertEquals(SEED_TOP, g.top, 1e-4f)
        assertEquals(SEED_BOTTOM, g.bottom, 1e-4f)
        assertEquals(0.5f, g.recoil, 1e-4f)
        assertEquals(1f, g.ringAlpha, 0f)
    }

    @Test fun ringHandsOffOnlyWhileTheBodyIsNearTheSeed() {
        val g = geometry()
        g.update(0f)
        assertEquals(1f, g.ringAlpha, 0f)
        var previousRing = g.ringAlpha
        for (index in 0..200) {
            val progress = index / 1000f
            g.update(progress)
            assertTrue("ring brightened mid-handoff at p=$progress", g.ringAlpha <= previousRing + 1e-6f)
            if (progress >= IslandMorphGeometry.RING_HANDOFF) {
                assertEquals("ring must be gone past the handoff at p=$progress", 0f, g.ringAlpha, 0f)
            }
            previousRing = g.ringAlpha
        }

        g.update(IslandMorphGeometry.RING_HANDOFF)
        assertTrue("handoff must finish while the body is still narrow; width=${g.right - g.left}", g.right - g.left < 0.5f * FULL_WIDTH)
    }

    @Test fun contentAppearsOnlyAsTheBodyGrowsDown() {
        val g = geometry()
        val minScale = IslandMorphGeometry.CONTENT_MIN_SCALE
        for (index in 0..300) {
            val progress = index / 1000f
            g.update(progress)
            assertEquals("content faded in before the body grew at p=$progress", 0f, g.contentAlpha, 0f)
            assertEquals("content scaled before the body grew at p=$progress", minScale, g.contentScale, 0f)
        }
        var previousAlpha = -1f
        for (index in 0..1_000) {
            g.update(index / 1000f)
            assertTrue("content alpha retracted at step=$index", g.contentAlpha >= previousAlpha - 1e-6f)
            assertEquals(
                "content scale must track content alpha",
                minScale + (1f - minScale) * g.contentAlpha,
                g.contentScale,
                1e-5f
            )
            previousAlpha = g.contentAlpha
        }
    }

    @Test fun samplingIsPureSoReversingFromAnyPointHasNoJump() {
        val g = geometry()
        for (value in listOf(0.05f, 0.2f, 0.45f, 0.79f, 0.95f, 1.01f)) {
            g.update(value)
            val left = g.left
            val top = g.top
            val right = g.right
            val bottom = g.bottom
            val radius = g.radius
            val contentAlpha = g.contentAlpha
            val ringAlpha = g.ringAlpha
            g.update(0.9f)
            g.update(0.1f)
            g.update(value)
            assertEquals(left, g.left, 0f)
            assertEquals(top, g.top, 0f)
            assertEquals(right, g.right, 0f)
            assertEquals(bottom, g.bottom, 0f)
            assertEquals(radius, g.radius, 0f)
            assertEquals(contentAlpha, g.contentAlpha, 0f)
            assertEquals(ringAlpha, g.ringAlpha, 0f)
        }
    }

    @Test fun adjacentMillisecondsNeverJump() {
        val g = geometry()
        var previousLeft = 0f
        var previousRight = 0f
        var previousBottom = 0f
        for (ms in 0..600) {
            g.update(IslandSpring.progress(ms / 600f))
            if (ms > 0) {
                assertTrue("left jumped at ${ms}ms", abs(g.left - previousLeft) < 3f)
                assertTrue("right jumped at ${ms}ms", abs(g.right - previousRight) < 3f)
                assertTrue("bottom jumped at ${ms}ms", abs(g.bottom - previousBottom) < 3f)
            }
            previousLeft = g.left
            previousRight = g.right
            previousBottom = g.bottom
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

    private companion object {
        const val DELTA = 1e-4f
        const val SEED_LEFT = 187f
        const val SEED_RIGHT = 213f
        const val SEED_TOP = 19f
        const val SEED_BOTTOM = 45f
        const val SEED_RADIUS = 13f
        const val EXPANDED_LEFT = 14f
        const val EXPANDED_RIGHT = 386f
        const val EXPANDED_BOTTOM = 250f
        const val EXPANDED_RADIUS = 40f
        const val FULL_WIDTH = 372f
        const val VERTICAL_TRAVEL = 205f
    }
}
