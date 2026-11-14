package com.adong.adchat.ui.chat

import org.junit.Assert.*
import org.junit.Test

class AnswerNavigationTest {
    private val answers = listOf(1, 3, 5)

    @Test fun assistantAnchorSkipsItsOwnAnswer() {
        assertEquals(AnswerNavigationTargets(1, 5), answerNavigationTargets(answers, 3))
    }
    @Test fun readingAQuestionCanReturnToTheAnswerAboveIt() {
        assertEquals(AnswerNavigationTargets(1, 3), answerNavigationTargets(answers, 2))
    }
    @Test fun beginningAndEndDoNotWrapAround() {
        assertEquals(AnswerNavigationTargets(null, 1), answerNavigationTargets(answers, 0, canScrollBackward = false))
        assertEquals(AnswerNavigationTargets(3, null), answerNavigationTargets(answers, 5, canScrollForward = false))
    }
    @Test fun fullyVisibleHistoryHasNoFalseNavigationTargets() {
        assertEquals(AnswerNavigationTargets(null, null), answerNavigationTargets(answers, 0, false, false))
        assertEquals(AnswerNavigationTargets(null, null), answerNavigationTargets(emptyList(), 0))
    }
}
