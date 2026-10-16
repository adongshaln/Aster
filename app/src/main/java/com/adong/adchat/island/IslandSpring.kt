package com.adong.adchat.island

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * A deterministic spring-shaped interpolation, evaluated from elapsed time rather than frame steps.
 *
 * The main morph first reaches its target at [MAIN_PHASE]. The remaining time contains exactly one
 * small overshoot and return. Position, velocity and acceleration are continuous, including at the
 * phase boundary; velocity and acceleration are zero at both animation endpoints.
 *
 * The response is an analytic underdamped spring in phase time, with a monotonic polynomial time
 * warp to impose the finite 80/20 schedule. It is not a constant-stiffness physical simulation in
 * wall-clock time. Its geometric output intentionally exceeds one; clamp only bounded channels
 * such as alpha, not the morph itself.
 */
internal object IslandSpring {
    const val MAIN_PHASE = 0.8f
    const val OVERSHOOT = 0.02f
    const val EXPAND_MS = 600L
    const val COLLAPSE_MS = 520L
    const val PRESS_MS = 360L
    const val BUTTON_MS = 260L

    private val mainDuration = MAIN_PHASE.toDouble()
    private val reboundDuration = 1.0 - mainDuration
    private val decayPerRadian = -ln(OVERSHOOT.toDouble()) / PI
    private val firstCrossingPhase = PI / 2.0 + atan(decayPerRadian)
    private val tailInitialSlope = reboundDuration * firstCrossingPhase / (mainDuration * PI)

    /**
     * Maps a linear elapsed-time fraction to morph progress. No previous sample is consulted.
     * Out-of-range times are held at the endpoints; NaN is treated as the initial frame.
     */
    fun progress(fraction: Float): Float {
        if (fraction.isNaN() || fraction <= 0f) return 0f
        if (fraction >= 1f || fraction == MAIN_PHASE) return 1f
        val q = phase(fraction.toDouble())
        return (1.0 - exp(-decayPerRadian * q) *
            (cos(q) + decayPerRadian * sin(q))).toFloat()
    }

    /** Derivative with respect to normalized time, not milliseconds; useful for continuity tests. */
    internal fun derivative(fraction: Float): Float {
        if (fraction.isNaN() || fraction <= 0f || fraction >= 1f) return 0f
        val t = fraction.toDouble()
        val q = phase(t)
        val phaseVelocity = if (t <= mainDuration) {
            val u = t / mainDuration
            firstCrossingPhase / mainDuration * u * (6.0 + u * (-9.0 + 4.0 * u))
        } else {
            val u = (t - mainDuration) / reboundDuration
            val remaining = 1.0 - u
            PI / reboundDuration * remaining * remaining *
                (tailInitialSlope + 2.0 * tailInitialSlope * u +
                    (30.0 - 15.0 * tailInitialSlope) * u * u)
        }
        return ((1.0 + decayPerRadian * decayPerRadian) * exp(-decayPerRadian * q) *
            sin(q) * phaseVelocity).toFloat()
    }

    private fun phase(t: Double): Double = if (t <= mainDuration) {
        val u = t / mainDuration
        // Q(u) = 3u^2 - 3u^3 + u^4: Q'(0)=0, Q'(1)=1, Q''(1)=0.
        firstCrossingPhase * u * u * (3.0 + u * (-3.0 + u))
    } else {
        val u = (t - mainDuration) / reboundDuration
        val remaining = 1.0 - u
        val smootherStep = u * u * u * (10.0 + u * (-15.0 + 6.0 * u))
        // H(u) = u - 6u^3 + 8u^4 - 3u^5, factored for stable evaluation near the end.
        val slopeBridge = u * remaining * remaining * remaining * (1.0 + 3.0 * u)
        firstCrossingPhase + PI * (smootherStep + tailInitialSlope * slopeBridge)
    }
}
