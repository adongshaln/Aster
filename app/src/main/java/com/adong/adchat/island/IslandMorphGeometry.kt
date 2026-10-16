package com.adong.adchat.island

/**
 * Two-stage island silhouette driven by one spring progress. The body first stretches sideways
 * from the resident halo's black core into a seed-height capsule, then grows downward; its top
 * edge and horizontal centre never move. Every output is a pure function of progress, so closing
 * retraces exactly the opening path. Mutable scalars avoid allocations on the animation hot path.
 */
internal class IslandMorphGeometry {
    companion object {
        /** Progress at which the sideways stretch has decelerated to full width. */
        const val WIDTH_END = 0.55f
        /** Progress at which the body starts growing downward. */
        const val HEIGHT_START = 0.30f
        /** dH/ds at s = 1: the master curve's single overshoot is carried by height only. */
        const val HEIGHT_END_SLOPE = 1.5f
        /** Upper bound of the height overshoot, as a fraction of the vertical travel. */
        const val MAX_HEIGHT_OVERSHOOT = HEIGHT_END_SLOPE * IslandSpring.OVERSHOOT / (1f - HEIGHT_START)
        /** The resident halo is fully hidden from this progress on, and fully back at zero. */
        const val RING_HANDOFF = 0.12f
        const val CONTENT_START = 0.25f
        const val CONTENT_END = 0.90f
        const val CONTENT_MIN_SCALE = 0.94f

        fun widthStage(progress: Float): Float {
            val s = (progress / WIDTH_END).coerceIn(0f, 1f)
            val remaining = 1f - s
            return 1f - remaining * remaining
        }

        fun heightStage(progress: Float): Float {
            val s = (progress - HEIGHT_START) / (1f - HEIGHT_START)
            return when {
                s <= 0f -> 0f
                // H(0)=0, H'(0)=0, H(1)=1, H'(1)=HEIGHT_END_SLOPE; monotonic on 0..1.
                s <= 1f -> s * s * ((3f - HEIGHT_END_SLOPE) + (HEIGHT_END_SLOPE - 2f) * s)
                else -> 1f + HEIGHT_END_SLOPE * (s - 1f)
            }
        }

        private fun smoothStep(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun smootherStep(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * t * (t * (6f * t - 15f) + 10f)
        }
    }

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
    var widthProgress = 0f
        private set
    var heightProgress = 0f
        private set
    /** Card content opacity; content is revealed by (and clipped to) the growing body. */
    var contentAlpha = 0f
        private set
    /** Card content scale around the halo centre x and the body's top edge. */
    var contentScale = CONTENT_MIN_SCALE
        private set
    /** Resident halo opacity: fades out at the start of opening, picks up at the end of closing. */
    var ringAlpha = 1f
        private set
    /** 0..1 depth of the closing-tail undershoot; the view turns it into one glow pulse. */
    var recoil = 0f
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
        val w = widthStage(progress)
        val h = heightStage(progress)
        widthProgress = w
        heightProgress = h
        // Negative (closing-tail) progress never extrapolates the shape below the black core.
        left = centerX - seedRadius + (expandedLeft - (centerX - seedRadius)) * w
        right = centerX + seedRadius + (expandedRight - (centerX + seedRadius)) * w
        top = centerY - seedRadius + (expandedTop - (centerY - seedRadius)) * h.coerceIn(0f, 1f)
        bottom = centerY + seedRadius + (expandedBottom - (centerY + seedRadius)) * h
        radius = minOf(expandedRadius, (bottom - top) / 2f, (right - left) / 2f).coerceAtLeast(0f)
        val content = smootherStep((h.coerceIn(0f, 1f) - CONTENT_START) / (CONTENT_END - CONTENT_START))
        contentAlpha = content
        contentScale = CONTENT_MIN_SCALE + (1f - CONTENT_MIN_SCALE) * content
        ringAlpha = 1f - smoothStep(progress.coerceAtLeast(0f) / RING_HANDOFF)
        recoil = (-progress / IslandSpring.OVERSHOOT).coerceIn(0f, 1f)
    }

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}
