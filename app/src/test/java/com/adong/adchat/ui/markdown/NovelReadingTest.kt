package com.adong.adchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelReadingTest {
    @Test
    fun chapterLineBecomesHeadingButLongProseDoesNot() {
        assertTrue(isChapterHeading("第一章 雨夜"))
        assertTrue(isChapterHeading("Chapter 3"))
        assertFalse(isChapterHeading("第一章" + "雨".repeat(40)))
        assertFalse(isChapterHeading("他走进第一章提到的那条巷子。"))
    }

    @Test
    fun starredActionIsNotAListAndLongStarDoesNotEmphasize() {
        assertTrue(isStarredProseLine("* 他低声说*"))
        assertFalse(isStarredProseLine("* 一条普通列表"))
        assertTrue(emphasisSpanAllowed("微微一笑"))
        assertTrue(emphasisSpanAllowed("终于开口，把后面一句斜体。"))
        assertFalse(emphasisSpanAllowed("星".repeat(401)))
    }

    @Test
    fun nameMarksHideDelimitersAndLeaveUnclosedTextAlone() {
        val spans = readingSpans("门外是*+-沈青*+-，还有*+-未写完")
        assertEquals(
            listOf(
                ReadingSpan.Text("门外是"),
                ReadingSpan.Name("沈青"),
                ReadingSpan.Text("，还有"),
                ReadingSpan.Text("*+-未写完")
            ),
            spans
        )
    }

    @Test
    fun singleLineBreaksReflowInsideOneParagraph() {
        val paragraph = StringBuilder()
        joinProseLine(paragraph, "雅儿贝德的额角渗出了一滴冷汗，")
        joinProseLine(paragraph, "背后那对漆黑的羽翼紧绷到了极致。")
        joinProseLine(paragraph, "She looked up.")
        assertEquals(
            "雅儿贝德的额角渗出了一滴冷汗，背后那对漆黑的羽翼紧绷到了极致。 She looked up.",
            paragraph.toString()
        )
    }
}
