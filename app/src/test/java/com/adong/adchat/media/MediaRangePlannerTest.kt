package com.adong.adchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRangePlannerTest {
    @Test
    fun choosesConnectionsByRemainingSize() {
        assertEquals(1, MediaRangePlanner.connectionCount(400L * 1024L))
        assertEquals(3, MediaRangePlanner.connectionCount(1_400L * 1024L))
        assertEquals(4, MediaRangePlanner.connectionCount(4L * 1024L * 1024L))
        assertEquals(6, MediaRangePlanner.connectionCount(700L * 1024L * 1024L))
    }

    @Test
    fun rangesAreContiguousAndCoverTheWholeRemainder() {
        val existing = 17L
        val total = 10_003L
        val ranges = MediaRangePlanner.ranges(existing, total, 6)

        assertTrue(ranges.isNotEmpty())
        assertEquals(existing, ranges.first().start)
        assertEquals(total - 1L, ranges.last().end)
        ranges.zipWithNext().forEach { (left, right) ->
            assertEquals(left.end + 1L, right.start)
        }
        assertEquals(total - existing, ranges.sumOf(MediaByteRange::length))
    }

    @Test
    fun completedFileProducesNoRanges() {
        assertTrue(MediaRangePlanner.ranges(100L, 100L, 6).isEmpty())
    }
}
