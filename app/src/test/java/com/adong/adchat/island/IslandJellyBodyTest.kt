package com.adong.adchat.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class IslandJellyBodyTest {
    // Anchor (200, 32), 13dp core; card 18..382 × 11..171 (364 × 160), 40dp corners.
    private fun body() = IslandJellyBody().apply { configure(200f, 32f, 13f, 18f, 11f, 382f, 171f, 40f) }

    private fun assertRect(b: IslandJellyBody, left: Float, top: Float, right: Float, bottom: Float) {
        assertEquals(left, b.left, 0.01f)
        assertEquals(top, b.top, 0.01f)
        assertEquals(right, b.right, 0.01f)
        assertEquals(bottom, b.bottom, 0.01f)
    }

    @Test fun restsAsTheHiddenCore() {
        val b = body()
        b.update(0.0)
        assertRect(b, 187f, 19f, 213f, 45f)
        assertEquals(13f, b.radius, 0.01f)
        assertEquals(0f, b.contentAlpha, 0f)
        assertEquals(IslandJellyBody.CONTENT_REST_SCALE, b.contentScale, 0f)
        assertEquals(0f, b.progress, 0f)
        assertEquals(0f, b.inkTransfer, 0f)
        assertEquals(0f, b.paperAlpha, 0f)
        assertTrue(b.isAtRest(0.0))
    }

    @Test fun widthLeadsWhileHeightWaitsForItsDelay() {
        val b = body()
        b.open(0.0)
        b.update(IslandJellyBody.HEIGHT_DELAY / 2.0)
        assertEquals("still a core-high strip", 26f, b.bottom - b.top, 1e-3f)
        assertEquals(19f, b.top, 1e-3f)
        assertTrue("width begins before height; width=${b.right - b.left}", b.right - b.left > 26f)
        var widthAt90 = -1
        var heightAt90 = -1
        for (ms in 0..1500) {
            b.update(ms / 1000.0)
            if (widthAt90 < 0 && b.right - b.left > 26f + 0.9f * 338f) widthAt90 = ms
            if (heightAt90 < 0 && b.bottom - b.top > 26f + 0.9f * 134f) heightAt90 = ms
        }
        assertTrue("width must lead; width=$widthAt90 height=$heightAt90", widthAt90 in 0 until heightAt90)
    }

    @Test fun settlesExactlyOnTheCardAfterOneVisibleDrop() {
        val b = body()
        b.open(0.0)
        var maxBottom = 0f
        var maxWidth = 0f
        for (ms in 0..2500) {
            b.update(ms / 1000.0)
            maxBottom = max(maxBottom, b.bottom)
            maxWidth = max(maxWidth, b.right - b.left)
        }
        assertTrue(b.isAtRest(2.5))
        b.snap(true, 2.5)
        b.update(2.5)
        assertRect(b, 18f, 11f, 382f, 171f)
        assertEquals(40f, b.radius, 0.01f)
        assertEquals(1f, b.contentAlpha, 0f)
        assertEquals(1f, b.contentScale, 0f)
        assertEquals(1f, b.progress, 1e-4f)
        assertEquals(1f, b.inkTransfer, 0f)
        assertEquals(1f, b.paperAlpha, 0f)
        assertEquals(0f, b.contourLean, 0f)
        assertEquals(0f, b.contourWave, 0f)
        assertTrue("the drop must visibly bounce; max=$maxBottom", maxBottom > 171f + 3f)
        assertTrue(
            "bounce stays inside the reserved viewport; max=$maxBottom",
            maxBottom < 171f + IslandJellyBody.MAX_HEIGHT_EXCESS * (160f - 26f)
        )
        assertTrue("width overshoots slightly; max=$maxWidth", maxWidth > 364f && maxWidth < 364f + 15f)
    }

    @Test fun closingNeverShrinksBelowTheCoreAndReturnsExactly() {
        val b = body()
        b.snap(true, 0.0)
        b.close(0.0)
        for (ms in 0..2500) {
            b.update(ms / 1000.0)
            assertTrue("width at ${ms}ms", b.right - b.left >= 26f - 1e-3f)
            assertTrue("height at ${ms}ms", b.bottom - b.top >= 26f - 1e-3f)
            assertTrue("top at ${ms}ms", b.top >= 11f - 1e-3f && b.top <= 19f + 1e-3f)
        }
        assertTrue(b.isAtRest(2.5))
        assertFalse(b.opened)
        b.snap(false, 2.5)
        b.update(2.5)
        assertRect(b, 187f, 19f, 213f, 45f)
        assertEquals(0f, b.contentAlpha, 0f)
        assertEquals(0f, b.progress, 1e-4f)
        assertEquals(0f, b.inkTransfer, 0f)
        assertEquals(0f, b.paperAlpha, 0f)
    }

    @Test fun reversingMidFlightKeepsMomentum() {
        val b = body()
        b.open(0.0)
        b.update(0.25)
        val beforeLeft = b.left
        val beforeBottom = b.bottom
        b.close(0.25)
        b.update(0.25)
        assertEquals(beforeLeft, b.left, 1e-3f)
        assertEquals(beforeBottom, b.bottom, 1e-3f)
        var previous: FloatArray? = null
        for (ms in 250..2000) {
            val now = ms / 1000.0
            if (ms == 400) b.open(now)
            b.update(now)
            val current = floatArrayOf(b.left, b.right, b.bottom, b.top)
            previous?.let {
                assertTrue("left jumped at ${ms}ms", abs(current[0] - it[0]) < 4f)
                assertTrue("right jumped at ${ms}ms", abs(current[1] - it[1]) < 4f)
                assertTrue("bottom jumped at ${ms}ms", abs(current[2] - it[2]) < 4f)
                assertTrue("top jumped at ${ms}ms", abs(current[3] - it[3]) < 1f)
            }
            previous = current
        }
        assertTrue(b.opened)
    }

    @Test fun contentFadesInOnlyAfterTheTagDrops() {
        val b = body()
        b.open(0.0)
        for (ms in 0..1500) {
            b.update(ms / 1000.0)
            if (b.heightProgress < 0.4f) assertEquals("content stays hidden before the paper forms, ms=$ms", 0f, b.contentAlpha, 0f)
            assertTrue("paper alpha at ${ms}ms", b.paperAlpha in 0f..1f)
            assertTrue("ink transfer at ${ms}ms", b.inkTransfer in 0f..1f)
            if (b.contentAlpha > 0f) assertTrue("paper precedes content, ms=$ms", b.paperAlpha > 0f)
        }
        assertEquals(1f, b.contentAlpha, 1e-3f)
    }

    @Test fun containsMatchesTheRenderedRect() {
        val b = body()
        b.snap(true, 0.0)
        b.update(0.0)
        assertTrue(b.contains(200f, 100f))
        assertTrue(b.contains(18.01f, 11.01f))
        assertFalse(b.contains(17f, 100f))
        assertFalse(b.contains(200f, 172f))
    }
}
