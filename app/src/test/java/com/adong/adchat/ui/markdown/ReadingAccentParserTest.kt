package com.adong.adchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingAccentParserTest {
    @Test
    fun highlightsCommonQuotationContentsInAmberGroup() {
        val text = "他说：“你好”，然后写下 \"world\" 和 ‘再见’。"
        val ranges = findReadingAccentRanges(text).filter { it.kind == ReadingAccentKind.Quote }
        assertEquals(listOf("“你好”", "\"world\"", "‘再见’"), ranges.map { text.substring(it.start, it.end) })
    }

    @Test
    fun highlightsCornerAndSquareBracketContentsInBlueGroup() {
        val text = "角色「侦探」获得了[隐藏线索]。"
        val ranges = findReadingAccentRanges(text).filter { it.kind == ReadingAccentKind.Bracket }
        assertEquals(listOf("「侦探」", "[隐藏线索]"), ranges.map { text.substring(it.start, it.end) })
    }

    @Test
    fun bracketAccentKeepsPriorityInsideQuotation() {
        val text = "“请查看[证据]”"
        val ranges = findReadingAccentRanges(text)
        assertEquals(ReadingAccentKind.Quote, ranges.first().kind)
        assertEquals(ReadingAccentKind.Bracket, ranges.last().kind)
    }

    @Test
    fun excludesInlineCodeFromReadingAccents() {
        val text = "普通“高亮”和代码\"不高亮\""
        val codeStart = text.indexOf("\"不高亮\"")
        val codeEnd = codeStart + "\"不高亮\"".length
        val ranges = findReadingAccentRanges(text, listOf(codeStart until codeEnd))
        assertTrue(ranges.any { text.substring(it.start, it.end) == "“高亮”" })
        assertFalse(ranges.any { text.substring(it.start, it.end) == "\"不高亮\"" })
    }

    @Test
    fun ignoresUnclosedDelimiters() {
        assertTrue(findReadingAccentRanges("未闭合“内容和[括号").isEmpty())
    }
}
