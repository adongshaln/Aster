package com.adong.adchat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionNavigationTest {
    private val questions = listOf(0, 2, 4, 6)

    @Test
    fun assistantAnswerReturnsToItsQuestion() {
        val targets = questionNavigationTargets(questions, anchorItemIndex = 5, visibleQuestionIndices = emptySet())
        assertEquals(4, targets.previous)
        assertEquals(6, targets.next)
    }

    @Test
    fun visibleQuestionMovesToActualPreviousQuestion() {
        val targets = questionNavigationTargets(questions, anchorItemIndex = 5, visibleQuestionIndices = setOf(2, 4))
        assertEquals(0, targets.previous)
        assertEquals(6, targets.next)
    }

    @Test
    fun lowerViewportAnchorSelectsTheLaterQuestion() {
        val targets = questionNavigationTargets(questions, anchorItemIndex = 6, visibleQuestionIndices = setOf(4, 6))
        assertEquals(2, targets.previous)
        assertNull(targets.next)
    }

    @Test
    fun boundariesDisableUnavailableDirections() {
        val atTop = questionNavigationTargets(questions, 0, setOf(0))
        assertNull(atTop.previous)
        assertEquals(2, atTop.next)

        val atBottom = questionNavigationTargets(questions, 7, emptySet())
        assertEquals(6, atBottom.previous)
        assertNull(atBottom.next)
    }
}
