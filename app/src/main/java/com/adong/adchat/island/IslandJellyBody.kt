package com.adong.adchat.island

import kotlin.math.max
import kotlin.math.min

/**
 * The paper tag's silhouette, driven by two jelly springs: width leads, height follows 60ms later
 * with a visible bounce, and velocity-coupled squash keeps the volume roughly constant. Its top
 * lifts off the black core as it drops so the ink ring sits inside the tag. Every output is
 * sampled from absolute time; nothing allocates per frame.
 */
internal class IslandJellyBody {
    companion object {
        const val WIDTH_RESPONSE = 0.44f
        const val WIDTH_DAMPING = 0.76f
        const val HEIGHT_RESPONSE = 0.52f
        const val HEIGHT_DAMPING = 0.64f
        const val HEIGHT_DELAY = 0.06
        const val CONTENT_DELAY = 0.12
        const val SQUASH = 0.8f
        const val CONTENT_REST_SCALE = 0.9f
        /** Upper bound of the body's growth past the card, as a fraction of the vertical travel. */
        const val MAX_HEIGHT_EXCESS = 0.12f
        private const val CONTENT_FADE_START = 0.34f
        private const val CONTENT_FADE_END = 0.84f
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
        content.set(1f, now, CONTENT_DELAY, 0.42f, 0.62f)
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
        val fade = ((heightProgress - CONTENT_FADE_START) / (CONTENT_FADE_END - CONTENT_FADE_START)).coerceIn(0f, 1f)
        contentAlpha = fade * fade * (3f - 2f * fade)
        contentScale = content.value(now)
    }

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}
