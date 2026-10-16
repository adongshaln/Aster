package com.adong.adchat.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandInkRingTest {
    private fun samples() = (0..IslandInkRing.SAMPLES).map { it.toFloat() / IslandInkRing.SAMPLES }

    @Test fun inkNeverClosesInOnTheLens() {
        for (u in samples()) {
            assertTrue(
                "u=$u inner=${IslandInkRing.innerRadius(u)}",
                IslandInkRing.innerRadius(u) >= IslandInkRing.CORE_RADIUS_DP + 0.5f
            )
        }
    }

    @Test fun heavierInkOnlyGrowsOutward() {
        for (u in samples()) {
            val inner = IslandInkRing.innerRadius(u)
            assertTrue(IslandInkRing.outerRadius(u, 1.45f) > IslandInkRing.outerRadius(u, 1f))
            assertEquals(inner + IslandInkRing.weight(u), IslandInkRing.outerRadius(u, 1f), 1e-5f)
        }
    }

    @Test fun strokeTapersAtBothEndsAndOverlapsAtTheJoin() {
        assertEquals(IslandInkRing.WEIGHT_DP, IslandInkRing.weight(0.5f), 1e-4f)
        assertTrue(IslandInkRing.weight(0f) < 0.25f * IslandInkRing.WEIGHT_DP)
        assertTrue(IslandInkRing.weight(1f) < 0.25f * IslandInkRing.WEIGHT_DP)
        assertTrue(IslandInkRing.SWEEP_DEG > 360f)
    }

    @Test fun handWobbleStaysUnderHalfADp() {
        for (u in samples()) {
            val wobble = IslandInkRing.innerRadius(u) - (IslandInkRing.RADIUS_DP - IslandInkRing.weight(u) / 2f)
            assertTrue("u=$u wobble=$wobble", wobble >= -0.5f && wobble <= 0.5f)
        }
    }
}
