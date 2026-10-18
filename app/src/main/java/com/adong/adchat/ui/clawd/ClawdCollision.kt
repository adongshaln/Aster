package com.adong.adchat.ui.clawd

import kotlin.math.abs

internal object ClawdCollision {

    private const val EPS = 1e-9

    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        fun translated(dx: Double, dy: Double): Box =
            Box(left + dx, top + dy, right + dx, bottom + dy)
    }

    data class Solid(val name: String, val box: Box)

    data class Hit(
        val solid: Solid,
        val time: Double,
        val normalX: Int,
        val normalY: Int,
        val pushX: Double = 0.0,
        val pushY: Double = 0.0,
    )

    fun firstHit(box: Box, dx: Double, dy: Double, solids: List<Solid>): Hit? {
        var best: Hit? = null
        for (solid in solids) {
            val hit = hitSolid(box, dx, dy, solid) ?: continue
            val current = best
            best = when {
                current == null -> hit
                hit.time < current.time - EPS -> hit
                hit.time <= current.time + EPS && hit.solid.name < current.solid.name -> hit
                else -> current
            }
        }
        return best
    }

    private fun hitSolid(box: Box, dx: Double, dy: Double, solid: Solid): Hit? {
        val b = solid.box
        val overlapX = minOf(box.right, b.right) - maxOf(box.left, b.left)
        val overlapY = minOf(box.bottom, b.bottom) - maxOf(box.top, b.top)
        if (overlapX > EPS && overlapY > EPS) {
            return initialOverlap(box, b, solid)
        }
        val xAxis = axisEntry(dx, box.left, box.right, b.left, b.right) ?: return null
        val yAxis = axisEntry(dy, box.top, box.bottom, b.top, b.bottom) ?: return null
        val entry = maxOf(xAxis.first, yAxis.first)
        val exit = minOf(xAxis.second, yAxis.second)
        if (entry < -EPS || entry > 1.0 + EPS || entry >= exit - EPS) return null
        val normalX: Int
        val normalY: Int
        if (xAxis.first >= yAxis.first) {
            normalX = if (dx > 0.0) -1 else 1
            normalY = 0
        } else {
            normalX = 0
            normalY = if (dy > 0.0) -1 else 1
        }
        if (entry <= EPS && dx * normalX + dy * normalY >= 0.0) return null
        return Hit(solid, entry, normalX, normalY)
    }

    private fun initialOverlap(box: Box, b: Box, solid: Solid): Hit {
        val leftPush = b.left - box.right
        val rightPush = b.right - box.left
        val upPush = b.top - box.bottom
        val downPush = b.bottom - box.top
        var bestPush = leftPush
        var bestNormalX = -1
        var bestNormalY = 0
        if (abs(rightPush) < abs(bestPush)) {
            bestPush = rightPush
            bestNormalX = 1
            bestNormalY = 0
        }
        if (abs(upPush) < abs(bestPush)) {
            bestPush = upPush
            bestNormalX = 0
            bestNormalY = -1
        }
        if (abs(downPush) < abs(bestPush)) {
            bestPush = downPush
            bestNormalX = 0
            bestNormalY = 1
        }
        val pushX = if (bestNormalY == 0) bestPush else 0.0
        val pushY = if (bestNormalX == 0) bestPush else 0.0
        return Hit(solid, 0.0, bestNormalX, bestNormalY, pushX, pushY)
    }

    private fun axisEntry(
        d: Double,
        lo: Double,
        hi: Double,
        otherLo: Double,
        otherHi: Double,
    ): Pair<Double, Double>? {
        if (d == 0.0) {
            return if (hi > otherLo && lo < otherHi) {
                Pair(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            } else {
                null
            }
        }
        return if (d > 0.0) {
            Pair((otherLo - hi) / d, (otherHi - lo) / d)
        } else {
            Pair((otherHi - lo) / d, (otherLo - hi) / d)
        }
    }
}
