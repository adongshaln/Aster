package com.adong.adchat.island

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analytic damped spring sampled from absolute time (seconds), never integrated per frame, so
 * 60Hz, 120Hz and dropped frames all read the same curve. Retargeting, immediately or after a
 * delay, rebases on the current position and velocity: a reversed island keeps its momentum.
 * [response] is the undamped period in seconds and [damping] the damping ratio (below 1: bouncy).
 */
internal class IslandJellySpring(initial: Float, response: Float, damping: Float) {
    var target: Float = initial
        private set
    var response: Float = response
        private set
    var damping: Float = damping
        private set

    private var x0 = 0.0
    private var v0 = 0.0
    private var t0 = 0.0
    private var pendingAt = Double.NaN
    private var pendingTarget = 0f
    private var pendingResponse = 0f
    private var pendingDamping = 0f

    val hasPending: Boolean get() = !pendingAt.isNaN()

    fun set(
        target: Float,
        now: Double,
        delay: Double = 0.0,
        response: Float = this.response,
        damping: Float = this.damping
    ) {
        advance(now)
        if (delay <= 0.0) {
            pendingAt = Double.NaN
            rebase(now, target, response, damping)
        } else {
            pendingAt = now + delay
            pendingTarget = target
            pendingResponse = response
            pendingDamping = damping
        }
    }

    /** Teleport to [value] at rest, dropping any pending target. */
    fun jump(value: Float, now: Double) {
        pendingAt = Double.NaN
        target = value
        x0 = 0.0
        v0 = 0.0
        t0 = now
    }

    /** Add [velocity] (units per second) to the current motion without changing the target. */
    fun kick(velocity: Float, now: Double) {
        advance(now)
        val dt = (now - t0).coerceAtLeast(0.0)
        val position = offset(dt)
        val speed = speed(dt)
        x0 = position
        v0 = speed + velocity
        t0 = now
    }

    fun value(now: Double): Float {
        advance(now)
        return (target + offset((now - t0).coerceAtLeast(0.0))).toFloat()
    }

    fun velocity(now: Double): Float {
        advance(now)
        return speed((now - t0).coerceAtLeast(0.0)).toFloat()
    }

    fun isSettled(now: Double, epsilon: Float = 0.3f): Boolean {
        advance(now)
        if (hasPending) return false
        val dt = (now - t0).coerceAtLeast(0.0)
        return abs(offset(dt)) < epsilon && abs(speed(dt)) < epsilon * 20.0
    }

    private fun advance(now: Double) {
        if (!pendingAt.isNaN() && now >= pendingAt) {
            val at = pendingAt
            pendingAt = Double.NaN
            rebase(at, pendingTarget, pendingResponse, pendingDamping)
        }
    }

    private fun rebase(at: Double, newTarget: Float, newResponse: Float, newDamping: Float) {
        val dt = (at - t0).coerceAtLeast(0.0)
        val position = target + offset(dt)
        val speed = speed(dt)
        target = newTarget
        response = newResponse
        damping = newDamping
        x0 = position - newTarget
        v0 = speed
        t0 = at
    }

    // x(t) = e^(-ζω₀t) · (x₀·cos ω_d t + (v₀ + ζω₀x₀)/ω_d · sin ω_d t)
    private fun offset(dt: Double): Double {
        if (x0 == 0.0 && v0 == 0.0) return 0.0
        val w0 = omega()
        val z = zeta()
        val decay = z * w0
        val wd = w0 * sqrt(1.0 - z * z)
        val e = exp(-decay * dt)
        return e * (x0 * cos(wd * dt) + (v0 + decay * x0) / wd * sin(wd * dt))
    }

    private fun speed(dt: Double): Double {
        if (x0 == 0.0 && v0 == 0.0) return 0.0
        val w0 = omega()
        val z = zeta()
        val decay = z * w0
        val wd = w0 * sqrt(1.0 - z * z)
        val e = exp(-decay * dt)
        return e * (v0 * cos(wd * dt) - (decay * v0 + w0 * w0 * x0) / wd * sin(wd * dt))
    }

    private fun omega(): Double = 2.0 * PI / response.toDouble().coerceAtLeast(1e-3)

    private fun zeta(): Double = damping.toDouble().coerceIn(0.01, 0.999)
}
