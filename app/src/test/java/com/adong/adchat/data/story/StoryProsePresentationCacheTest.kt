package com.adong.adchat.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class StoryProsePresentationCacheTest {
    @Test
    fun recycledCompletedRowReusesExactPresentationWithoutSecondParse() {
        val cache = StoryProsePresentationCache()
        val key = StoryProsePresentationCache.Key(
            revisionId = "revision-1",
            content = "<konatan_planning~>很长的思考过程</konatan_planning~>正文",
            depth = 3
        )
        var parses = 0

        val first = cache.getOrPut(key) {
            parses++
            StoryProsePresentation(planning = "很长的思考过程", blocks = listOf(StoryProseBlock("正文")))
        }
        val afterLazyRecycle = cache.getOrPut(key) {
            parses++
            StoryProsePresentation()
        }

        assertSame(first, afterLazyRecycle)
        assertEquals(1, parses)
    }

    @Test
    fun changedRevisionContentGetsNewPresentation() {
        val cache = StoryProsePresentationCache()
        val before = StoryProsePresentationCache.Key("revision-1", "旧正文", 1)
        val after = StoryProsePresentationCache.Key("revision-1", "新正文", 1)

        val oldPresentation = cache.getOrPut(before) { StoryProsePresentation(blocks = listOf(StoryProseBlock("旧正文"))) }
        val newPresentation = cache.getOrPut(after) { StoryProsePresentation(blocks = listOf(StoryProseBlock("新正文"))) }

        assertNotSame(oldPresentation, newPresentation)
        assertEquals("新正文", newPresentation.blocks.single().text)
    }

    @Test
    fun cacheIsBoundedForLongStories() {
        val cache = StoryProsePresentationCache(maxEntries = 2)
        repeat(3) { index ->
            val key = StoryProsePresentationCache.Key("revision-$index", "正文$index", index)
            cache.getOrPut(key) { StoryProsePresentation(blocks = listOf(StoryProseBlock("正文$index"))) }
        }
        assertEquals(2, cache.size())
    }
}
