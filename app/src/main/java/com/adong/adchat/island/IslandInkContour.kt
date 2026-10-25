package com.adong.adchat.island

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The resident pen stroke and the expanded border share point order, taper and winding.
 * At rest closed these coordinates exactly match IslandInkRingView; while opening, a rounded
 * sheet replaces the small circle and its momentum bends the same outline. Reused arrays keep
 * drawing allocation-free. This geometry is independent of Android so it can be inspected.
 */
internal class IslandInkContour {
    val innerX = FloatArray(IslandInkRing.SAMPLES + 1)
    val innerY = FloatArray(IslandInkRing.SAMPLES + 1)
    val outerX = FloatArray(IslandInkRing.SAMPLES + 1)
    val outerY = FloatArray(IslandInkRing.SAMPLES + 1)
    private val count = IslandInkRing.SAMPLES + 3
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val cosines = FloatArray(count)
    private val sines = FloatArray(count)
    private val wobbleX = FloatArray(count)
    private val wobbleY = FloatArray(count)
    private val innerRadius = FloatArray(count)
    private val weight = FloatArray(count)

    init {
        for (index in 0 until count) {
            val u = (index - 1).toFloat() / IslandInkRing.SAMPLES
            val angle = IslandInkRing.angle(u)
            cosines[index] = cos(angle)
            sines[index] = sin(angle)
            wobbleX[index] = sin(2f * angle + 0.4f)
            wobbleY[index] = sin(3f * angle - 0.7f)
            innerRadius[index] = IslandInkRing.innerRadius(u)
            weight[index] = IslandInkRing.weight(u)
        }
    }

    fun update(body: IslandJellyBody, density: Float, ink: Float, bounce: Float) {
        val transfer = body.inkTransfer
        val seedMix = 1f - transfer
        val cx = (body.left + body.right) / 2f
        val cy = (body.top + body.bottom) / 2f
        val hw = (body.right - body.left) / 2f
        val hh = (body.bottom - body.top) / 2f
        val r = body.radius
        val circleScale = max(1f, bounce) * density
        for (index in 0 until count) {
            val c = cosines[index]
            val s = sines[index]
            val radius = roundedRadius(hw, hh, r, c, s) +
                (innerRadius[index] * circleScale - body.seedRadius) * seedMix
            x[index] = cx + c * radius + body.contourLean * s * (0.65f + 0.35f * s) +
                body.contourWave * 0.45f * wobbleX[index]
            y[index] = cy + s * radius - body.contourLean * c * 0.65f +
                body.contourWave * 0.55f * wobbleY[index]
        }
        for (index in 0..IslandInkRing.SAMPLES) {
            val i = index + 1
            // Outward normal of the clockwise path. Radial thickness at the ring blends into
            // normal thickness at the card, so its long straight sides never become heavy bars.
            val dx = x[i + 1] - x[i - 1]
            val dy = y[i + 1] - y[i - 1]
            val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val nx = cosines[i] * seedMix + dy / length * transfer
            val ny = sines[i] * seedMix - dx / length * transfer
            val penWidth = weight[i] * max(0f, ink) * circleScale
            // Retain a hint of pen pressure at rest without thinning the card's border to zero.
            val borderWidth = density * (0.9f + 0.22f * weight[i] / IslandInkRing.WEIGHT_DP)
            val stroke = penWidth * seedMix + borderWidth * transfer
            val inset = stroke * transfer
            innerX[index] = x[i] - nx * inset
            innerY[index] = y[i] - ny * inset
            outerX[index] = x[i] + nx * (stroke - inset)
            outerY[index] = y[i] + ny * (stroke - inset)
        }
    }

    /** Ray intersection with a rounded rectangle, smoothly tending to a circle at the seed. */
    private fun roundedRadius(hw: Float, hh: Float, r: Float, c: Float, s: Float): Float {
        val ac = abs(c).coerceAtLeast(0.00001f)
        val asn = abs(s).coerceAtLeast(0.00001f)
        val hit = min(hw / ac, hh / asn)
        if (hit * ac <= hw - r || hit * asn <= hh - r) return hit
        val qx = hw - r
        val qy = hh - r
        val dot = ac * qx + asn * qy
        val discriminant = r * r - qx * qx - qy * qy + dot * dot
        return dot + sqrt(max(0f, discriminant))
    }
}
