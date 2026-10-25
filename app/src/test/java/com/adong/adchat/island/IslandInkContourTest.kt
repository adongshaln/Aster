package com.adong.adchat.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class IslandInkContourTest {
    private fun body(density: Float) = IslandJellyBody().apply {
        configure(200f * density, 32f * density, 13f * density,
            18f * density, 11f * density, 382f * density, 171f * density, 40f * density)
        update(0.0)
    }

    @Test fun closedContourMatchesEveryResidentRingPointAcrossDensityAndPress() {
        for (density in listOf(1f, 1.5f, 3f)) {
            val body = body(density)
            val contour = IslandInkContour()
            for ((ink, bounce) in listOf(1f to 1f, 1.45f to 1.1f)) {
                contour.update(body, density, ink, bounce)
                for (index in 0..IslandInkRing.SAMPLES) {
                    val u = index.toFloat() / IslandInkRing.SAMPLES
                    val angle = IslandInkRing.angle(u)
                    val inner = IslandInkRing.innerRadius(u) * density * bounce
                    val outer = IslandInkRing.outerRadius(u, ink) * density * bounce
                    val epsilon = 0.001f * density
                    assertEquals("inner x $index, density=$density", 200f * density + cos(angle) * inner, contour.innerX[index], epsilon)
                    assertEquals("inner y $index, density=$density", 32f * density + sin(angle) * inner, contour.innerY[index], epsilon)
                    assertEquals("outer x $index, density=$density", 200f * density + cos(angle) * outer, contour.outerX[index], epsilon)
                    assertEquals("outer y $index, density=$density", 32f * density + sin(angle) * outer, contour.outerY[index], epsilon)
                }
            }
        }
    }

    @Test fun reversingKeepsEveryContourPointContinuousAndInsideTheViewport() {
        val density = 2f
        val body = body(density)
        val contour = IslandInkContour()
        body.open(0.0)
        var previous: List<FloatArray>? = null
        val viewportHeight = (171f + IslandJellyBody.MAX_HEIGHT_EXCESS * (160f - 26f) + 28f) * density
        for (ms in 0..2500) {
            val now = ms / 1000.0
            body.update(now)
            contour.update(body, density, 1.45f, 1.1f)
            if (ms == 220 || ms == 370 || ms == 800) {
                val before = points(contour)
                if (ms == 370) body.open(now) else body.close(now)
                body.update(now)
                contour.update(body, density, 1.45f, 1.1f)
                val after = points(contour)
                before.indices.forEach { axis ->
                    before[axis].indices.forEach { index ->
                        assertEquals("reversal at $ms, axis=$axis, point=$index", before[axis][index], after[axis][index], 0.01f * density)
                    }
                }
            }
            val current = points(contour)
            current.forEachIndexed { axis, coordinates ->
                val limit = if (axis % 2 == 0) 400f * density else viewportHeight
                coordinates.forEachIndexed { index, value ->
                    assertTrue("viewport at $ms, axis=$axis, point=$index: $value", value.isFinite() && value >= 0f && value <= limit)
                    previous?.let {
                        assertTrue("jump at $ms, axis=$axis, point=$index", abs(value - it[axis][index]) < 4f * density)
                    }
                }
            }
            previous = current
        }
        assertTrue(body.isAtRest(2.5))
    }

    @Test fun openedContourSettlesOnTheRoundedCardWithAnInsetBorder() {
        for (density in listOf(1f, 2.5f)) {
            val body = body(density)
            val contour = IslandInkContour()
            body.open(0.0)
            body.update(3.0)
            assertTrue(body.isAtRest(3.0))
            // The View performs this exact final snap after observing isAtRest.
            body.snap(true, 3.0)
            body.update(3.0)
            assertEquals(1f, body.inkTransfer, 0f)
            assertEquals(1f, body.paperAlpha, 0f)
            assertEquals(0f, body.contourLean, 0f)
            assertEquals(0f, body.contourWave, 0f)
            contour.update(body, density, 1.45f, 1.1f)
            val settled = points(contour)
            for (index in 0..IslandInkRing.SAMPLES) {
                val outerDistance = roundedCardDistance(contour.outerX[index] / density, contour.outerY[index] / density)
                assertEquals("outer point $index is on the card", 0f, outerDistance, 0.02f)
                assertTrue("inner point $index is inside the card", roundedCardDistance(contour.innerX[index] / density, contour.innerY[index] / density) <= 0.02f)
                val dx = (contour.outerX[index] - contour.innerX[index]) / density
                val dy = (contour.outerY[index] - contour.innerY[index]) / density
                assertTrue("border point $index stays thin and visible", sqrt(dx * dx + dy * dy) in 0.85f..1.2f)
            }
            body.update(4.0)
            contour.update(body, density, 1f, 1f)
            val later = points(contour)
            settled.indices.forEach { axis ->
                settled[axis].indices.forEach { index ->
                    assertEquals("rest stays still, axis=$axis, point=$index", settled[axis][index], later[axis][index], 0.001f * density)
                }
            }
        }
    }

    private fun points(contour: IslandInkContour) = listOf(
        contour.innerX.copyOf(), contour.innerY.copyOf(), contour.outerX.copyOf(), contour.outerY.copyOf()
    )

    private fun roundedCardDistance(x: Float, y: Float): Float {
        val dx = abs(x - 200f) - (182f - 40f)
        val dy = abs(y - 91f) - (80f - 40f)
        return sqrt(max(dx, 0f) * max(dx, 0f) + max(dy, 0f) * max(dy, 0f)) + min(max(dx, dy), 0f) - 40f
    }
}
