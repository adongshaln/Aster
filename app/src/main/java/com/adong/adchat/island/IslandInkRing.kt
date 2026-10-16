package com.adong.adchat.island

import kotlin.math.PI
import kotlin.math.sin

/**
 * The resident ring as one pen stroke around the lens: tapered start and end, a fuller middle, a
 * 12° overlap where the pen lifts, and a fixed hand wobble under half a dp. Pure dp-unit geometry.
 */
internal object IslandInkRing {
    /** Black backing behind the lens; the ink never covers it. */
    const val CORE_RADIUS_DP = 13f
    const val RADIUS_DP = 15.4f
    const val WEIGHT_DP = 2.1f
    const val SAMPLES = 120
    const val START_DEG = -58f
    const val SWEEP_DEG = 372f

    fun angle(u: Float): Float = ((START_DEG + SWEEP_DEG * u) * PI / 180.0).toFloat()

    fun weight(u: Float): Float {
        val taper = smooth(0f, 0.1f, u) * (1f - smooth(0.86f, 1f, u))
        val pressure = 0.78f + 0.22f * sin(PI.toFloat() * u)
        return WEIGHT_DP * pressure * (0.18f + 0.82f * taper)
    }

    fun innerRadius(u: Float): Float {
        val a = angle(u)
        return RADIUS_DP - weight(u) / 2f + 0.32f * sin(3f * a + 0.7f) + 0.18f * sin(5f * a)
    }

    /** Extra ink grows outward only, so a heavier stroke never closes in on the lens. */
    fun outerRadius(u: Float, ink: Float): Float = innerRadius(u) + weight(u) * ink

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
