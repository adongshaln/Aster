package com.adong.adchat.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyChatGreetingTest {
    @Test
    fun broadBandsCoverEveryHourIncludingMidnight() {
        for (hour in 0..23) {
            val expected = when (hour) {
                in 5..10 -> 0
                in 11..16 -> 1
                in 17..22 -> 2
                else -> 3
            }
            assertEquals("hour=$hour", expected, greetingBand(hour))
        }
        assertEquals(greetingBand(23), greetingBand(0))
    }

    @Test
    fun outOfRangeHoursNormalizeWithoutOverflow() {
        for (hour in listOf(Int.MIN_VALUE, -25, -1, 24, 28, Int.MAX_VALUE)) {
            val normalized = Math.floorMod(hour, 24)
            assertEquals(greetingBand(normalized), greetingBand(hour))
            assertEquals(emptyChatGreeting(normalized, 7), emptyChatGreeting(hour, 7))
        }
    }

    @Test
    fun eachBandOffersASmallSetOfDistinctShortOpenings() {
        val all = mutableSetOf<String>()
        for (hour in 0..23) {
            val candidates = (0..11).map { emptyChatGreeting(hour, it) }.toSet()
            assertTrue("hour=$hour candidates=${candidates.size}", candidates.size in 2..4)
            candidates.forEach { text ->
                assertTrue("Empty or oversized opening: $text", text.length in 1..20)
                assertEquals(text.trim(), text)
            }
            all += candidates
        }
        assertTrue("Keep the total copy concise: ${all.size}", all.size <= 12)
    }

    @Test
    fun anHourChangeWithinTheSameBandKeepsTheOpening() {
        for (hours in listOf(5..10, 11..16, 17..22, 0..4)) {
            for (roll in listOf(Int.MIN_VALUE, -7, -1, 0, 1, 2, 3, Int.MAX_VALUE)) {
                val expected = emptyChatGreeting(hours.first, roll)
                for (hour in hours) assertEquals(expected, emptyChatGreeting(hour, roll))
            }
        }
    }

    @Test
    fun negativeAndLargeRollsReachTheSameCandidates() {
        for (hour in 0..23) {
            val candidates = (0..11).map { emptyChatGreeting(hour, it) }.toSet()
            assertEquals(candidates, (-12..-1).map { emptyChatGreeting(hour, it) }.toSet())
            assertTrue(emptyChatGreeting(hour, Int.MIN_VALUE) in candidates)
            assertTrue(emptyChatGreeting(hour, Int.MAX_VALUE) in candidates)
        }
    }

    @Test
    fun openingsDoNotAssumeNamesSleepHabitsOrDailyRoutines() {
        val unwanted = listOf("{name}", "朋友", "还没睡", "睡不着", "起床", "困意", "这么晚", "午饭时间", "思路最清楚")
        for (hour in 0..23) for (roll in 0..11) {
            val text = emptyChatGreeting(hour, roll)
            unwanted.forEach { phrase -> assertFalse("Unexpected assumption in: $text", phrase in text) }
        }
    }
}
