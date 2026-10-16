package com.adong.adchat.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max

class IslandSpringTest {
    @Test
    fun endpointsAndOutOfRangeTimesAreHeldExactly() {
        for (fraction in listOf(Float.NEGATIVE_INFINITY, -10f, -0.1f, 0f, Float.NaN)) {
            assertEquals(0f, IslandSpring.progress(fraction), 0f)
        }
        assertEquals(1f, IslandSpring.progress(IslandSpring.MAIN_PHASE), 0f)
        for (fraction in listOf(1f, 1.1f, 10f, Float.POSITIVE_INFINITY)) {
            assertEquals(1f, IslandSpring.progress(fraction), 0f)
        }
        assertEquals(0.8f, IslandSpring.MAIN_PHASE, 0f)
        assertEquals(0.02f, IslandSpring.OVERSHOOT, 0f)
        assertEquals(600L, IslandSpring.EXPAND_MS)
        assertEquals(520L, IslandSpring.COLLAPSE_MS)
        assertEquals(360L, IslandSpring.PRESS_MS)
        assertEquals(260L, IslandSpring.BUTTON_MS)
    }

    @Test
    fun mainMorphIsMonotonicAndDoesNotOvershoot() {
        var previous = 0f
        for (index in 0..SAMPLES) {
            val fraction = IslandSpring.MAIN_PHASE * index / SAMPLES
            val value = IslandSpring.progress(fraction)
            assertTrue("main phase must stay finite at $fraction", value.isFinite())
            assertTrue("main phase reversed at $fraction", value >= previous)
            assertTrue("main phase overshot at $fraction", value in 0f..1f)
            if (index in 1 until SAMPLES) {
                assertTrue("main phase must keep moving at $fraction", IslandSpring.derivative(fraction) > 0f)
            }
            previous = value
        }
        assertEquals(1f, previous, 0f)
    }

    @Test
    fun tailHasExactlyOneTwoPercentReboundAndNoUndershoot() {
        val boundary = IslandSpring.MAIN_PHASE
        var turns = 0
        var wasFalling = false
        var largest = 1f
        for (index in 1 until SAMPLES) {
            val fraction = boundary + (1f - boundary) * index / SAMPLES
            val value = IslandSpring.progress(fraction)
            val velocity = IslandSpring.derivative(fraction)
            assertTrue("tail must stay finite at $fraction", value.isFinite())
            assertTrue("tail undershot at $fraction", value >= 1f)
            assertTrue("tail exceeded configured amplitude at $fraction", value <= 1f + IslandSpring.OVERSHOOT + 1e-6f)
            largest = max(largest, value)
            if (velocity < 0f && !wasFalling) {
                turns++
                wasFalling = true
            }
            if (wasFalling) {
                assertTrue("tail started another oscillation at $fraction", velocity <= 0f)
            } else {
                assertTrue("tail must initially rise at $fraction", velocity > 0f)
            }
        }
        assertTrue("tail must return toward its target", wasFalling)
        assertEquals(1, turns)
        assertEquals(1f + IslandSpring.OVERSHOOT, largest, 1e-6f)

        // The single spring maximum occurs at phase PI; locate its time independently.
        val peakTime = analyticPeakTime()
        assertTrue(peakTime > boundary && peakTime < 1f)
        assertEquals(1f + IslandSpring.OVERSHOOT, IslandSpring.progress(peakTime), 1e-6f)
        assertEquals(0f, IslandSpring.derivative(peakTime), 2e-5f)
    }

    @Test
    fun joinsAreContinuousAndEndpointsHaveZeroVelocityAndAcceleration() {
        val boundary = IslandSpring.MAIN_PHASE
        val h = 0.0001f
        val before = boundary - h
        val after = boundary + h
        val atVelocity = IslandSpring.derivative(boundary)
        assertEquals(1f, IslandSpring.progress(before), 3e-5f)
        assertEquals(1f, IslandSpring.progress(after), 3e-5f)
        assertEquals(atVelocity, IslandSpring.derivative(before), 0.0003f)
        assertEquals(atVelocity, IslandSpring.derivative(after), 0.0003f)
        assertTrue("phase boundary must not pause before the rebound", atVelocity > 0.2f)

        // Differentiate the analytic velocity rather than Float-rounded positions.
        val accelerationBefore = (atVelocity - IslandSpring.derivative(before)) / (boundary - before)
        val accelerationAfter = (IslandSpring.derivative(after) - atVelocity) / (after - boundary)
        assertEquals(accelerationBefore, accelerationAfter, 0.1f)
        for (fraction in listOf(-1f, 0f, 1f, 2f)) {
            assertEquals(0f, IslandSpring.derivative(fraction), 0f)
        }
        assertEquals(0f, IslandSpring.derivative(h), 1e-6f)
        assertEquals(0f, IslandSpring.derivative(1f - h), 1e-6f)
        assertEquals(0f, IslandSpring.derivative(h) / h, 0.003f)
        assertEquals(0f, -IslandSpring.derivative(1f - h) / h, 0.003f)
    }

    @Test
    fun reboundIsSlowerThanTheMainMorph() {
        var mainSpeed = 0f
        var tailSpeed = 0f
        for (index in 0..SAMPLES) {
            val fraction = index.toFloat() / SAMPLES
            val speed = abs(IslandSpring.derivative(fraction))
            if (fraction <= IslandSpring.MAIN_PHASE) mainSpeed = max(mainSpeed, speed)
            else tailSpeed = max(tailSpeed, speed)
        }
        assertTrue(mainSpeed > 2f)
        assertTrue(tailSpeed > 0f)
        assertTrue("tail must be substantially slower, not another fast pulse", tailSpeed < mainSpeed * 0.25f)
    }

    @Test
    fun sixtyAndOneTwentyHertzUseTheSameElapsedTimeCurve() {
        for (duration in listOf(IslandSpring.EXPAND_MS, IslandSpring.COLLAPSE_MS, IslandSpring.PRESS_MS, IslandSpring.BUTTON_MS)) {
            val frameCount = ((duration * 60L + 999L) / 1000L).toInt()
            val sixtyHz = (0..frameCount).map { index -> atTime(index * 1_000_000_000L / 60L, duration) }
            val oneTwentyHz = (0..frameCount * 2).map { index -> atTime(index * 1_000_000_000L / 120L, duration) }
            for (index in sixtyHz.indices) {
                assertEquals("same timestamp at duration $duration, frame $index", sixtyHz[index], oneTwentyHz[index * 2], 0f)
            }
            assertEquals(1f, atTime(duration * 1_000_000L, duration), 0f)
            assertEquals(1f, sixtyHz.last(), 0f)
            assertEquals(1f, oneTwentyHz.last(), 0f)
        }
    }

    @Test
    fun jitterLongGapsAndSampleOrderCannotChangeTheTrajectory() {
        val duration = IslandSpring.EXPAND_MS
        val timesMs = listOf(0L, 7L, 19L, 42L, 143L, 147L, 299L, 479L, 480L, 481L, 592L, 600L, 715L)
        val expected = timesMs.associateWith { atTime(it * 1_000_000L, duration) }
        for (timeMs in timesMs.reversed()) {
            // Other animations and skipped frames must not leave state in the interpolator.
            IslandSpring.progress(0.93f)
            IslandSpring.progress(0.12f)
            assertEquals(expected.getValue(timeMs), atTime(timeMs * 1_000_000L, duration), 0f)
        }
        assertEquals(1f, expected.getValue(480L), 0f)
        assertEquals(1f, expected.getValue(600L), 0f)
        assertEquals(1f, expected.getValue(715L), 0f)
    }

    private fun atTime(elapsedNanos: Long, durationMillis: Long): Float =
        IslandSpring.progress((elapsedNanos.toDouble() / (durationMillis * 1_000_000.0)).toFloat())

    private fun analyticPeakTime(): Float {
        val a = IslandSpring.MAIN_PHASE.toDouble()
        val b = 1.0 - a
        val r = -ln(IslandSpring.OVERSHOOT.toDouble()) / PI
        val first = PI / 2.0 + atan(r)
        val eta = b * first / (a * PI)
        var low = 0.0
        var high = 1.0
        repeat(60) {
            val u = (low + high) / 2.0
            val smooth = u * u * u * (10.0 + u * (-15.0 + 6.0 * u))
            val remaining = 1.0 - u
            val bridge = u * remaining * remaining * remaining * (1.0 + 3.0 * u)
            if (first + PI * (smooth + eta * bridge) < PI) low = u else high = u
        }
        return (a + b * (low + high) / 2.0).toFloat()
    }

    private companion object {
        const val SAMPLES = 10_000
    }
}
