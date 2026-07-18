package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkCollectionTest {
    @Test
    fun groupsNonAdjacentPagesFromSameMangaSeriesAndSortsByPage() {
        val images = listOf(
            mangaPage(id = 30, seriesId = "batch-a", page = 2, total = 3),
            GeneratedImage(id = 20, prompt = "普通绘图", source = "single"),
            mangaPage(id = 10, seriesId = "batch-a", page = 0, total = 3)
        )

        val collections = groupArtworkCollections(images)

        assertEquals(2, collections.size)
        assertTrue(collections.first().isSeries)
        assertEquals(listOf(0, 2), collections.first().images.map { it.seriesIndex })
        assertEquals(3, collections.first().expectedTotal)
        assertFalse(collections.last().isSeries)
    }

    @Test
    fun onePageFromMultiPageBatchStillAppearsAsFoldedSeries() {
        val collection = groupArtworkCollections(
            listOf(mangaPage(id = 1, seriesId = "partial", page = 1, total = 4))
        ).single()

        assertTrue(collection.isSeries)
        assertEquals("漫画翻译 · 简体中文", collection.title)
        assertEquals(4, collection.expectedTotal)
    }

    private fun mangaPage(id: Long, seriesId: String, page: Int, total: Int) = GeneratedImage(
        id = id,
        prompt = "漫画翻译",
        source = "page-$page",
        style = "漫画翻译",
        seriesId = seriesId,
        seriesIndex = page,
        seriesTotal = total,
        seriesTitle = "漫画翻译 · 简体中文"
    )
}
