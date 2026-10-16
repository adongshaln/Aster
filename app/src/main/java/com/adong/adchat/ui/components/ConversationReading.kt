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
 * 判据是「最后一个可见条目之后，视口里只剩一点余量」——不要求严格停在最后一条，
 * 否则列表末尾的 24dp contentPadding 会让这个判断永远不成立。
 */
fun LazyListState.isCloseToBottom(toleranceRatio: Float = 0.12f): Boolean {
    val layout = layoutInfo
    val items = layout.visibleItemsInfo
    if (items.isEmpty()) return true
    val last = items.last()
    // 已经看到最后一项，剩下的距离就是它之后的空隙。
    val remaining = (layout.viewportEndOffset - last.offset - last.size).toFloat()
    if (remaining <= 0f) return true
    if (last.index >= layout.totalItemsCount - 1) return true
    return remaining < layout.viewportEndOffset * toleranceRatio
}
