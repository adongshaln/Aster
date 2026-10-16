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

    private var seedRadius = 0f
    private var expandedWidth = 0f
    private var expandedBottom = 0f
    private var expandedRadius = 0f

    fun configure(
        viewportWidth: Float,
        haloSize: Float,
        seedRadius: Float,
        expandedWidth: Float,
        expandedBottom: Float,
        expandedRadius: Float
    ) {
        centerX = viewportWidth / 2f
        centerY = haloSize / 2f
        this.seedRadius = seedRadius
        this.expandedWidth = expandedWidth
        this.expandedBottom = expandedBottom
        this.expandedRadius = expandedRadius
    }

    fun update(progress: Float) {
        // Keep the 2% overshoot. Clamping progress to 0..1 would erase the requested rebound.
        val halfWidth = (seedRadius + (expandedWidth / 2f - seedRadius) * progress)
            .coerceAtLeast(seedRadius * 0.5f)
        left = centerX - halfWidth
        right = centerX + halfWidth
        top = centerY - seedRadius
        bottom = centerY + seedRadius + (expandedBottom - centerY - seedRadius) * progress
        val halfHeight = ((bottom - top) / 2f).coerceAtLeast(0f)
        radius = (seedRadius + (expandedRadius - seedRadius) * progress)
            .coerceIn(0f, minOf(halfWidth, halfHeight))
    }

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}
