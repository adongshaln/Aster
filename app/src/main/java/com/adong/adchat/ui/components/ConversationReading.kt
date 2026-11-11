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

/**
 * 用户是否已经停在末尾附近。聚焦输入框、键盘弹起这类自动跟随只在「本来就在看末尾」
 * 时才该发生；回看历史的人一碰输入框就被拽回底部，阅读位置就丢了。
 *
 * 最后一项必须已进入视口，且溢出不超过视口的一小部分。末尾 contentPadding 不计入
 * 溢出量，避免已到末尾却被留白误判；历史中途的裁切行不能算作末尾。
 */
fun LazyListState.isCloseToBottom(toleranceRatio: Float = 0.12f): Boolean {
    val layout = layoutInfo
    val items = layout.visibleItemsInfo
    if (items.isEmpty()) return true
    val last = items.last()
    // A clipped row in the middle of history also extends past the viewport. Its negative empty
    // space must never be mistaken for reaching the end when the user focuses the composer.
    if (last.index < layout.totalItemsCount - 1) return false
    val overflow = (last.offset + last.size - layout.viewportEndOffset).coerceAtLeast(0).toFloat()
    val viewport = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(1)
    return overflow <= viewport * toleranceRatio
}
