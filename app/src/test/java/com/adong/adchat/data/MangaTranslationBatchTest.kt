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

    @Test
    fun retryPlanKeepsOnlyFailuresAndReusesUncertainRequestKey() {
        val plan = nextMangaRetryPlan(
            signature = "same-batch",
            results = listOf(
                MangaTranslationPageResult(0, "1.png", "1024x1536", sources = listOf("done"), requestKey = "success-key"),
                MangaTranslationPageResult(1, "2.png", "1024x1536", errorMessage = "timeout", requestKey = "paid-key", deliveryUncertain = true),
                MangaTranslationPageResult(2, "3.png", "1024x1536", errorMessage = "HTTP 429", requestKey = "old-key")
            ),
            now = 1_000L,
            newRequestKey = { index -> "new-$index" }
        )!!

        assertEquals(setOf(1, 2), plan.pageIndices)
        assertEquals("paid-key", plan.requestKeys.getValue(1))
        assertEquals("new-2", plan.requestKeys.getValue(2))
        assertTrue(plan.reusableFor("same-batch", pageCount = 3, now = 2_000L))
    }

    @Test
    fun failureSummaryShowsLongWaitDuration() {
        val summary = mangaFailureSummary(
            listOf(MangaTranslationPageResult(0, "page.png", "1024x1536", errorMessage = "连接中断", durationMs = 185_000L))
        )

        assertTrue(summary.contains("等待 3:05"))
    }
}
