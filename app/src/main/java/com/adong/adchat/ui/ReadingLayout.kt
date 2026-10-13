package com.adong.adchat.ui

import com.adong.adchat.data.ASK_USER_TOOL
import com.adong.adchat.data.ChatToolActivity
import com.adong.adchat.data.TOOL_STATUS_RUNNING

/** Width and height in dp that keep the photo's own orientation inside the caps. */
internal fun fittedImageDp(width: Int, height: Int, maxWidth: Float, maxHeight: Float): Pair<Float, Float>? {
    if (width <= 0 || height <= 0 || maxWidth <= 0f || maxHeight <= 0f) return null
    val ratio = width.toFloat() / height.toFloat()
    var frameWidth = maxWidth
    var frameHeight = frameWidth / ratio
    if (frameHeight > maxHeight) {
        frameHeight = maxHeight
        frameWidth = frameHeight * ratio
    }
    return frameWidth to frameHeight
}

/** The model already wrote something and is still going, and it is not paused on a question. */
internal fun showModelWorkingFoot(streaming: Boolean, contentBlank: Boolean, waitingOnUser: Boolean): Boolean =
    streaming && !contentBlank && !waitingOnUser

internal fun waitingOnUser(activities: List<ChatToolActivity>): Boolean {
    val running = activities.lastOrNull { it.status == TOOL_STATUS_RUNNING } ?: return false
    return running.name == ASK_USER_TOOL
}

internal fun modelWorkingCaption(activities: List<ChatToolActivity>, recovering: Boolean): String {
    if (recovering) return "连接波动，正在续传"
    val running = activities.lastOrNull { it.status == TOOL_STATUS_RUNNING }
    if (running != null && running.name != ASK_USER_TOOL && running.label.isNotBlank()) return running.label
    return "正在继续写"
}
