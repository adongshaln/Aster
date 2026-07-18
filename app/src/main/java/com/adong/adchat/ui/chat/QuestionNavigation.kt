package com.adong.adchat.ui.chat

internal data class QuestionNavigationTargets(
    val previous: Int?,
    val next: Int?
)

/**
 * Resolves question anchors relative to a reading-position item inside the viewport. When the user
 * is reading an assistant answer and its question has moved off-screen, "previous" returns to that
 * question. If nearby questions are already visible, navigation skips them and targets the nearest
 * off-screen question so pressing a button always produces a meaningful movement.
 */
internal fun questionNavigationTargets(
    questionIndices: List<Int>,
    anchorItemIndex: Int,
    visibleQuestionIndices: Set<Int>
): QuestionNavigationTargets {
    if (questionIndices.isEmpty()) return QuestionNavigationTargets(null, null)
    val currentQuestion = questionIndices.lastOrNull { it <= anchorItemIndex } ?: questionIndices.first()
    val previous = if (currentQuestion in visibleQuestionIndices) {
        questionIndices.lastOrNull { it < currentQuestion && it !in visibleQuestionIndices }
    } else {
        currentQuestion
    }
    val next = questionIndices.firstOrNull { it > currentQuestion && it !in visibleQuestionIndices }
    return QuestionNavigationTargets(previous, next)
}
