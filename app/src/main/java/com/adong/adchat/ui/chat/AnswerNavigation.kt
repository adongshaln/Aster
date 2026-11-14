package com.adong.adchat.ui.chat

internal data class AnswerNavigationTargets(val previous: Int?, val next: Int?)

/** Navigate assistant answers around the first visible message, rather than user questions. */
internal fun answerNavigationTargets(
    answerIndices: List<Int>, anchorItemIndex: Int,
    canScrollBackward: Boolean = true, canScrollForward: Boolean = true
): AnswerNavigationTargets = AnswerNavigationTargets(
    previous = if (canScrollBackward) answerIndices.lastOrNull { it < anchorItemIndex } else null,
    next = if (canScrollForward) answerIndices.firstOrNull { it > anchorItemIndex } else null
)
