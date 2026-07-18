package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaTranslationBatchTest {
    @Test
    fun keepsSuccessfulPagesInOriginalInputOrder() {
        val results = listOf(
            MangaTranslationPageResult(2, "3.png", "1024x1536", listOf("page-3")),
            MangaTranslationPageResult(0, "1.png", "1024x1536", listOf("page-1")),
            MangaTranslationPageResult(1, "2.png", "1024x1536", errorMessage = "timeout")
        )
        assertEquals(listOf("page-1", "page-3"), orderedMangaSuccesses(results).map { it.second })
    }

    @Test
    fun reportsEveryFailedPageByOneBasedIndexAndName() {
        val summary = mangaFailureSummary(
            listOf(
                MangaTranslationPageResult(3, "final.png", "1024x1536", errorMessage = "HTTP 429"),
                MangaTranslationPageResult(1, "middle.png", "1024x1536", errorMessage = "连接超时")
            )
        )
        assertTrue(summary.startsWith("第 2 张（middle.png）"))
        assertTrue(summary.contains("第 4 张（final.png）：HTTP 429"))
    }
}
