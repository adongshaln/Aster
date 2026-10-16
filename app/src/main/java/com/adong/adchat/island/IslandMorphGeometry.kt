package com.adong.adchat.island

/**
 * Screen-space anchor is always the centre of the original 64dp halo. Only the body bounds grow;
 * neither the window's y nor the halo's local y changes between idle and expanded states.
 * Mutable scalars deliberately avoid Rect/data-class allocations on the animation hot path.
 */
internal class IslandMorphGeometry {
    var centerX = 0f
        private set
    var centerY = 0f
        private set
    var left = 0f
        private set
    var top = 0f
        private set
    var right = 0f
        private set
    var bottom = 0f
        private set
    var radius = 0f
        private set
    /** Transfer the closing tail out of the camera's opaque core so its single recoil is visible. */
    var haloRecoil = 0f
        private set

    private var seedRadius = 0f
    private var expandedLeft = 0f
    private var expandedTop = 0f
    private var expandedRight = 0f
    private var expandedBottom = 0f
    private var expandedRadius = 0f

    fun configure(
        centerX: Float,
        centerY: Float,
        seedRadius: Float,
        expandedLeft: Float,
        expandedTop: Float,
        expandedRight: Float,
        expandedBottom: Float,
        expandedRadius: Float
    ) {
        this.centerX = centerX
        this.centerY = centerY
        this.seedRadius = seedRadius
        this.expandedLeft = expandedLeft
        this.expandedTop = expandedTop
        this.expandedRight = expandedRight
        this.expandedBottom = expandedBottom
        this.expandedRadius = expandedRadius
    }

    fun update(progress: Float) {
        // The card silhouette follows the same circle-to-card path in reverse. Closing-tail
        // overshoot belongs to the resident halo; never extrapolate the card through zero size.
        val shapeProgress = progress.coerceIn(0f, 1.02f)
        left = centerX - seedRadius + (expandedLeft - (centerX - seedRadius)) * shapeProgress
        right = centerX + seedRadius + (expandedRight - (centerX + seedRadius)) * shapeProgress
        top = centerY - seedRadius + (expandedTop - (centerY - seedRadius)) * shapeProgress
        bottom = centerY + seedRadius + (expandedBottom - (centerY + seedRadius)) * shapeProgress
        val halfWidth = (right - left) / 2f
        val halfHeight = (bottom - top) / 2f
        radius = (seedRadius + (expandedRadius - seedRadius) * shapeProgress)
            .coerceIn(0f, minOf(halfWidth, halfHeight))
        val recoil = (-progress / IslandSpring.OVERSHOOT).coerceIn(0f, 1f)
        // |closing progress| rises to one peak then returns to zero with the same master curve.
        haloRecoil = 0.10f * recoil
    }

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}
