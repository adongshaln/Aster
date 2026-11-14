package com.adong.adchat.ui.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.min

/** A fresh touch must pull past the end and release; velocity alone never arms the action. */
@Stable
internal class ConversationEndPull(private val thresholdPx: Float) {
    init { require(thresholdPx.isFinite() && thresholdPx > 0f) }

    var distancePx by mutableFloatStateOf(0f)
        private set
    val progress: Float get() = (distancePx / thresholdPx).coerceIn(0f, 1f)
    val armed: Boolean get() = distancePx >= thresholdPx
    private var touching = false
    private var eligibleAtStart = false
    private var interceptFling = false
    private val resistance = .65f

    fun begin(eligible: Boolean) {
        touching = true
        eligibleAtStart = eligible
        distancePx = 0f
        interceptFling = false
    }

    fun pull(availableY: Float, atEnd: Boolean, eligible: Boolean): Float {
        if (!availableY.isFinite() || availableY >= 0f || !touching || !eligibleAtStart || !eligible || !atEnd) return 0f
        distancePx = (distancePx - availableY * resistance).coerceAtMost(thresholdPx * 1.25f)
        interceptFling = true
        return availableY
    }

    fun reverse(availableY: Float): Float {
        if (!availableY.isFinite() || availableY <= 0f || !touching || distancePx <= 0f) return 0f
        val consumed = min(availableY, distancePx / resistance)
        distancePx = (distancePx - consumed * resistance).coerceAtLeast(0f)
        return consumed
    }

    fun release(eligible: Boolean): Boolean {
        val accepted = touching && eligibleAtStart && eligible && armed
        cancel()
        return accepted
    }

    fun cancel() {
        touching = false
        eligibleAtStart = false
        distancePx = 0f
    }

    fun consumeFling(): Boolean = interceptFling.also { interceptFling = false }
}
