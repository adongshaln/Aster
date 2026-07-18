package com.adong.adchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableParserTest {
    @Test
    fun parsesAlignmentAndRows() {
        val lines = listOf(
            "| 项目 | 状态 | 耗时 |",
            "| :--- | :---: | ---: |",
            "| SSE 解析 | 完成 | 120 ms |",
            "| 安全续传 | 完成 | 1.4 s |",
            "",
            "后续文本"
        )

        val match = parseMarkdownTableAt(lines, 0)

        assertNotNull(match)
        assertEquals(listOf("项目", "状态", "耗时"), match!!.table.header)
        assertEquals(
            listOf(MarkdownTableAlignment.Start, MarkdownTableAlignment.Center, MarkdownTableAlignment.End),
            match.table.alignments
        )
        assertEquals(2, match.table.rows.size)
        assertEquals(4, match.nextLineIndex)
    }

    @Test
    fun keepsEscapedAndInlineCodePipesInsideCells() {
        val cells = splitMarkdownTableRow("| `a | b` | A \\| B | **完成** |")

        assertEquals(listOf("`a | b`", "A | B", "**完成**"), cells)
    }

    @Test
    fun toleratesRowsWithMissingOrExtraCells() {
        val match = parseMarkdownTableAt(
            listOf(
                "名称 | 说明",
                "--- | ---",
                "A | 第一项 | 多余内容",
                "B |"
            ),
            0
        )

        assertNotNull(match)
        assertEquals(listOf("A", "第一项 | 多余内容"), match!!.table.rows[0])
        assertEquals(listOf("B", ""), match.table.rows[1])
    }

    @Test
    fun convertsHtmlBreaksAndExportsTsv() {
        val match = parseMarkdownTableAt(
            listOf("| 名称 | 说明 |", "| --- | --- |", "| A | 第一行<br>第二行 |"),
            0
        )!!

        assertEquals("第一行\n第二行", match.table.rows.single()[1])
        assertEquals("名称\t说明\nA\t第一行 第二行", markdownTableToTsv(match.table))
    }

    @Test
    fun rejectsOrdinaryTextAndDetectsRealTable() {
        assertFalse(containsMarkdownTable("这是 A | B 的普通说明。\n下一行仍是正文。"))
        assertTrue(containsMarkdownTable("A | B\n--- | ---\n1 | 2"))
    }
}
