package com.adong.adchat.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyChatGreetingTest {

    // ---- 时段归属：每个小时的候选句固定属于本时段 ----

    @Test
    fun bandBoundaries() {
        // 夜(23,0..4)=6 早(5..7)=0 上午(8..10)=1 午(11..13)=2 下午(14..16)=3 傍晚(17..19)=4 晚(20..22)=5
        val expected = listOf(
            6 to listOf(0, 1, 2, 3, 4),           // 0..4 夜
            0 to listOf(5, 6, 7),                 // 5..7 早
            1 to listOf(8, 9, 10),                // 8..10 上午
            2 to listOf(11, 12, 13),              // 11..13 午
            3 to listOf(14, 15, 16),              // 14..16 下午
            4 to listOf(17, 18, 19),              // 17..19 傍晚
            5 to listOf(20, 21, 22),              // 20..22 晚
            6 to listOf(23),                      // 23 夜
        )
        for ((band, hours) in expected) {
            for (h in hours) assertEquals("h=$h", band, greetingBand(h))
        }
    }

    @Test
    fun bandHourNormalization() {
        assertEquals(greetingBand(23), greetingBand(-1))
        assertEquals(greetingBand(4), greetingBand(28))
    }

    // ---- 随机轮换：roll 覆盖候选句集合；固定句不吞掉带称呼句 ----

    @Test
    fun everyCandidateReachableWithinBand() {
        // 带称呼：每个时段至少两句可轮换。空称呼退到固定池；早/下午/傍晚固定池各只有一句兜底。
        for (h in 0..23) {
            val named = (0 until 40).map { emptyChatGreeting(h, "小明", it) }.toSet()
            assertTrue("h=$h 带称呼只有 ${named.size} 句", named.size >= 2)
        }
    }

    @Test
    fun rollPicksEveryNamedVariantInMorning() {
        val got = (0 until 12).map { emptyChatGreeting(6, "小明", it) }.toSet()
        listOf("早上好，小明", "早安，小明，新的一天从一句话开始", "小明，早。今天想先做哪件事？").forEach {
            assertTrue(it in got)
        }
    }

    @Test
    fun negativeRollStillDeterministic() {
        assertEquals(emptyChatGreeting(6, "小明", 3), emptyChatGreeting(6, "小明", 3 + 3 * 1))
    }

    // ---- 固定句时段不携带称呼 ----

    @Test
    fun coffeeAndNightNeverIncludeName() {
        for (roll in 0..9) {
            for (h in listOf(11, 12, 13, 23, 0, 3)) {
                val text = emptyChatGreeting(h, "小明", roll)
                assertTrue("h=$h 不应带称呼: $text", "小明" !in text)
            }
        }
    }

    @Test
    fun coffeeBandCandidates() {
        val got = (0 until 12).map { emptyChatGreeting(12, "", it) }.toSet()
        listOf("咖啡加 Aster 时间？", "午饭时间，聊点轻松的？", "中午了，忙里偷闲问点什么？").forEach {
            assertTrue(it in got)
        }
    }

    @Test
    fun nightBandCandidates() {
        val got = (0 until 12).map { emptyChatGreeting(1, "", it) }.toSet()
        listOf("还没睡？要做什么？", "夜深了，睡不着就聊聊", "这么晚还醒着，有事在心上？").forEach {
            assertTrue(it in got)
        }
    }

    // ---- 称呼处理 ----

    @Test
    fun nameIsTrimmed() {
        assertEquals("早上好，小明", emptyChatGreeting(6, "  小明  ", 0))
    }

    @Test
    fun emptyNameFallsBackToFixedSentence() {
        for (roll in 0..9) {
            assertTrue(emptyChatGreeting(6, "", roll).startsWith("晨光正好"))
            assertTrue(emptyChatGreeting(15, "", roll).startsWith("下午好，想聊点什么"))
        }
    }

    @Test
    fun whitespaceOnlyNameBehavesLikeEmpty() {
        assertEquals(emptyChatGreeting(6, "", 2), emptyChatGreeting(6, "   ", 2))
    }

    // ---- 原示例句仍在池中 ----

    @Test
    fun classicSentencesStillPresent() {
        assertEquals("咖啡加 Aster 时间？", emptyChatGreeting(12, "小明", 0))
        assertEquals("还没睡？要做什么？", emptyChatGreeting(23, "小明", 0))
    }
}
