package com.adong.adchat.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

class IslandJellySpringTest {
    @Test fun restsAtItsInitialValue() {
        val spring = IslandJellySpring(26f, 0.44f, 0.76f)
        assertEquals(26f, spring.value(3.0), 0f)
        assertEquals(0f, spring.velocity(3.0), 0f)
        assertTrue(spring.isSettled(3.0))
    }

    @Test fun matchesANumericalIntegrationOfTheSpringEquation() {
        val response = 0.52f
        val damping = 0.64f
        val spring = IslandJellySpring(26f, response, damping)
        spring.set(160f, 0.0)
        val w0 = 2.0 * PI / response
        val z = damping.toDouble()
        var x = 26.0
        var v = 0.0
        var t = 0.0
        val dt = 1e-5
        for (checkpoint in listOf(0.05, 0.1, 0.2, 0.35, 0.6, 1.0)) {
            while (t < checkpoint - 1e-12) {
                val a = -w0 * w0 * (x - 160.0) - 2.0 * z * w0 * v
                v += a * dt
                x += v * dt
                t += dt
            }
            assertEquals("t=$checkpoint", x, spring.value(checkpoint).toDouble(), 0.05)
        }
    }

    @Test fun overshootsOnceByTheDampingRatioPrediction() {
        val spring = IslandJellySpring(0f, 0.52f, 0.64f)
        spring.set(100f, 0.0)
        var peak = 0f
        for (ms in 0..2000) peak = maxOf(peak, spring.value(ms / 1000.0))
        val z = 0.64
        val predicted = 100.0 * (1.0 + exp(-PI * z / sqrt(1.0 - z * z)))
        assertEquals(predicted, peak.toDouble(), 0.05)
    }

    @Test fun retargetKeepsPositionAndVelocity() {
        val spring = IslandJellySpring(26f, 0.44f, 0.76f)
        spring.set(364f, 0.0)
        val position = spring.value(0.2)
        val speed = spring.velocity(0.2)
        spring.set(26f, 0.2, response = 0.396f, damping = 0.82f)
        assertEquals(position, spring.value(0.2), 1e-3f)
        assertEquals(speed, spring.velocity(0.2), 1e-2f)
        assertEquals(26f, spring.target, 0f)
        assertEquals(0.396f, spring.response, 0f)
    }

    @Test fun delayedTargetFollowsTheOldPathUntilItsTime() {
        val spring = IslandJellySpring(26f, 0.52f, 0.64f)
        spring.set(160f, 1.0, delay = 0.06)
        assertTrue(spring.hasPending)
        assertFalse(spring.isSettled(1.03))
        assertEquals(26f, spring.value(1.03), 0f)
        // Just past the delay the spring has barely left its old rest position: no jump.
        assertEquals(26f, spring.value(1.0601), 1e-3f)
        assertFalse(spring.hasPending)
        assertEquals(160f, spring.target, 0f)
        assertTrue(spring.value(1.2) > 26f)
    }

    @Test fun kickAddsVelocityAndSettlesBack() {
        val spring = IslandJellySpring(1f, 0.22f, 0.45f)
        spring.kick(5f, 0.0)
        assertEquals(1f, spring.value(0.0), 0f)
        assertEquals(5f, spring.velocity(0.0), 1e-4f)
        var peak = 0f
        for (ms in 0..600) peak = maxOf(peak, spring.value(ms / 1000.0))
        assertTrue("peak=$peak", peak > 1.05f && peak < 1.2f)
        assertTrue(spring.isSettled(1.5, 0.002f))
    }

    @Test fun sixtyAndOneHundredTwentyHertzSampleTheSameCurve() {
        fun run(hz: Int): FloatArray {
            val spring = IslandJellySpring(26f, 0.44f, 0.76f)
            spring.set(364f, 0.0)
            var reversed = false
            val out = FloatArray(61)
            val stride = hz / 60
            for (frame in 0..hz) {
                val t = frame.toDouble() / hz
                if (!reversed && t >= 0.25) {
                    spring.set(26f, 0.25, delay = 0.05)
                    reversed = true
                }
                if (frame % stride == 0) out[frame / stride] = spring.value(t)
            }
            return out
        }
        val sixty = run(60)
        val oneTwenty = run(120)
        for (i in sixty.indices) assertEquals("frame $i", sixty[i], oneTwenty[i], 1e-4f)
    }
}
