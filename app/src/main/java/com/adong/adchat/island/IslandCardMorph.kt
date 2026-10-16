package com.adong.adchat.island

import kotlin.math.max

/** Pure transform math for a card whose visual pivot is the resident halo's exact centre. */
internal object IslandCardMorph {
    fun scale(progress: Float): Float = progress.coerceIn(0f, 1f)


    fun mappedLeft(cardLeft: Float, anchorX: Float, scale: Float): Float =
        anchorX + (cardLeft - anchorX) * scale

    fun mappedTop(cardTop: Float, anchorY: Float, scale: Float): Float =
        anchorY + (cardTop - anchorY) * scale

    fun mappedRight(cardRight: Float, anchorX: Float, scale: Float): Float =
        anchorX + (cardRight - anchorX) * scale

    fun mappedBottom(cardBottom: Float, anchorY: Float, scale: Float): Float =
        anchorY + (cardBottom - anchorY) * scale
}
