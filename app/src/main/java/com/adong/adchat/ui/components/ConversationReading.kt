package com.adong.adchat.ui.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos

/** Reveal explicitly opened details above the floating composer, without jumping to the latest reply. */
suspend fun LazyListState.revealConversationDetails(index: Int, bottomClearancePx: Float) {
    stopScroll()
    withFrameNanos { }
    withFrameNanos { }
    val layout = layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
    if (item != null) {
        val overflow = item.offset + item.size + bottomClearancePx - layout.viewportEndOffset
        if (overflow > 0f) animateScrollBy(overflow)
    } else {
        animateScrollToItem(index)
    }
}
