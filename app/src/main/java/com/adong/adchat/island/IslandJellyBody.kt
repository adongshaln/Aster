package com.adong.adchat.island

import kotlin.math.max
import kotlin.math.min

/**
 * A soft sheet pulled from the lens. Width leads height slightly; their shared momentum bends
 * the outline unequally at the shoulders and lower edge. All deformation comes from continuous
 * spring state, including during reversals, rather than restarting a decorative wobble timer.
 */
internal class IslandJellyBody {
    companion object {
        const val WIDTH_RESPONSE = 0.50f
        const val WIDTH_DAMPING = 0.76f
        const val HEIGHT_RESPONSE = 0.58f
        const val HEIGHT_DAMPING = 0.66f
        const val HEIGHT_DELAY = 0.028
        const val CONTENT_DELAY = 0.09
        const val SQUASH = 0.8f
        const val CONTENT_REST_SCALE = 0.96f
        /** Upper bound of the body's growth past the card, as a fraction of the vertical travel. */
        const val MAX_HEIGHT_EXCESS = 0.12f
        private const val CONTENT_FADE_START = 0.72f
        private const val CONTENT_FADE_END = 0.98f
    }

    private val width = IslandJellySpring(0f, WIDTH_RESPONSE, WIDTH_DAMPING)
    private val height = IslandJellySpring(0f, HEIGHT_RESPONSE, HEIGHT_DAMPING)
    private val content = IslandJellySpring(CONTENT_REST_SCALE, 0.42f, 0.62f)
    private var configured = false
    private var centerX = 0f
    private var centerY = 0f
    private var coreRadius = 0f
    private var expandedLeft = 0f
    private var expandedTop = 0f
    private var expandedRight = 0f
    private var expandedBottom = 0f
    private var cornerRadius = 0f

    var opened = false
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
    /** 0 while the tag is a seed-high strip, 1 once it has dropped to the card height. */
    var heightProgress = 0f
        private set
    /** 0 at the hidden core, 1 at the card; briefly above 1 while overshooting. */
    var progress = 0f
        private set
    var contentAlpha = 0f
        private set
    var contentScale = CONTENT_REST_SCALE
        private set
    /** Amount of the original pen stroke that has become the card's edge. */
    var inkTransfer = 0f
        private set
    var paperAlpha = 0f
        private set
    /** Bounded pixel displacements; settle to zero without a second animation clock. */
    var contourLean = 0f
        private set
    var contourWave = 0f
        private set
    val seedRadius: Float get() = coreRadius

    private val seed: Float get() = coreRadius * 2f

    fun configure(
        centerX: Float,
        centerY: Float,
        coreRadius: Float,
        expandedLeft: Float,
        expandedTop: Float,
        expandedRight: Float,
        expandedBottom: Float,
        cornerRadius: Float
    ) {
        this.centerX = centerX
        this.centerY = centerY
        this.coreRadius = coreRadius
        this.expandedLeft = expandedLeft
        this.expandedTop = expandedTop
        this.expandedRight = expandedRight
        this.expandedBottom = expandedBottom
        this.cornerRadius = cornerRadius
        if (!configured) {
            configured = true
            width.jump(seed, 0.0)
            height.jump(seed, 0.0)
        }
    }

    fun open(now: Double) {
        opened = true
        width.set(expandedRight - expandedLeft, now, 0.0, WIDTH_RESPONSE, WIDTH_DAMPING)
        height.set(expandedBottom - expandedTop, now, HEIGHT_DELAY, HEIGHT_RESPONSE, HEIGHT_DAMPING)
        content.set(1f, now, CONTENT_DELAY, 0.40f, 0.82f)
    }

    /** Height retracts first, width follows: the tag slips back into the core along its own path. */
    fun close(now: Double) {
        opened = false
        height.set(seed, now, 0.0, HEIGHT_RESPONSE * 0.78f, min(0.95f, HEIGHT_DAMPING + 0.12f))
        width.set(seed, now, HEIGHT_DELAY * 0.8, WIDTH_RESPONSE * 0.9f, min(0.95f, WIDTH_DAMPING + 0.06f))
        content.set(CONTENT_REST_SCALE, now, 0.0, 0.3f, 0.9f)
    }

    /** Jump straight to the open or closed rest state (animations disabled, or once settled). */
    fun snap(open: Boolean, now: Double) {
        opened = open
        width.jump(if (open) expandedRight - expandedLeft else seed, now)
        height.jump(if (open) expandedBottom - expandedTop else seed, now)
        content.jump(if (open) 1f else CONTENT_REST_SCALE, now)
    }

    fun isAtRest(now: Double): Boolean =
        width.isSettled(now) && height.isSettled(now) && content.isSettled(now, 0.002f)

    fun update(now: Double) {
        val w = width.value(now)
        val h = height.value(now)
        val travelW = max(1f, expandedRight - expandedLeft - seed)
        val travelH = max(1f, expandedBottom - expandedTop - seed)
        val vw = (width.velocity(now) * WIDTH_RESPONSE / travelW).coerceIn(-1f, 1f)
        val vh = (height.velocity(now) * HEIGHT_RESPONSE / travelH).coerceIn(-1f, 1f)
        // Surging sideways thins the tag; dropping narrows it. Never smaller than the core.
        val renderedHeight = max(seed, h - SQUASH * vw * 0.12f * max(0f, h - seed * 0.6f))
        val renderedWidth = max(seed, w - SQUASH * vh * 0.05f * max(0f, w - seed))
        val widthFraction = ((w - seed) / travelW).coerceIn(0f, 1f)
        heightProgress = ((h - seed) / travelH).coerceIn(0f, 1f)
        progress = ((w - seed) / travelW + (h - seed) / travelH) / 2f
        val seedTop = centerY - coreRadius
        top = seedTop + (expandedTop - seedTop) * heightProgress
        bottom = top + renderedHeight
        val cx = centerX + ((expandedLeft + expandedRight) / 2f - centerX) * widthFraction
        left = cx - renderedWidth / 2f
        right = cx + renderedWidth / 2f
        radius = minOf(cornerRadius, (bottom - top) / 2f, (right - left) / 2f)
        inkTransfer = smooth(0f, 0.64f, progress)
        paperAlpha = smooth(0.03f, 0.55f, heightProgress)
        val formation = smooth(0f, 0.28f, progress)
        contourLean = coreRadius * 0.42f * (0.7f * vw - 0.3f * vh) * formation
        contourWave = coreRadius * 0.42f * (0.8f * vh + 0.2f * vw) * formation
        val fade = ((heightProgress - CONTENT_FADE_START) / (CONTENT_FADE_END - CONTENT_FADE_START)).coerceIn(0f, 1f)
        // The text waits for both shoulders and the lower edge. Otherwise a partially exposed
        // button becomes a coloured wedge while the pen is still describing the silhouette.
        contentAlpha = fade * fade * (3f - 2f * fade) * smooth(0.88f, 0.99f, widthFraction)
        contentScale = content.value(now)
    }

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    private fun smooth(start: Float, end: Float, value: Float): Float {
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
