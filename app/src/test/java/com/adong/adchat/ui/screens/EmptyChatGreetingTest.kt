package com.adong.adchat.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class EmptyChatGreetingTest {

    // ---- 各时段内部（带称呼） ----

    @Test
    fun morningBand_interior_withName() {
        assertEquals("早上好，小明", emptyChatGreeting(6, "小明"))
    }

    @Test
    fun coffeeBand_interior() {
        assertEquals("咖啡加 Aster 时间？", emptyChatGreeting(12, "小明"))
    }

    @Test
    fun afternoonBand_interior_withName() {
        assertEquals("下午好，小明", emptyChatGreeting(15, "小明"))
    }

    @Test
    fun eveningBand_interior_withName() {
        assertEquals("晚上好，小明", emptyChatGreeting(20, "小明"))
    }

    @Test
    fun nightBand_interior_23() {
        assertEquals("还没睡？要做什么？", emptyChatGreeting(23, "小明"))
    }

    @Test
    fun nightBand_interior_1() {
        assertEquals("还没睡？要做什么？", emptyChatGreeting(1, "小明"))
    }

    // ---- 所有时段边界 ----

    @Test
    fun boundaries() {
        assertEquals("还没睡？要做什么？", emptyChatGreeting(4, "小明"))
        assertEquals("早上好，小明", emptyChatGreeting(5, "小明"))
        assertEquals("早上好，小明", emptyChatGreeting(10, "小明"))
        assertEquals("咖啡加 Aster 时间？", emptyChatGreeting(11, "小明"))
        assertEquals("咖啡加 Aster 时间？", emptyChatGreeting(13, "小明"))
        assertEquals("下午好，小明", emptyChatGreeting(14, "小明"))
        assertEquals("下午好，小明", emptyChatGreeting(17, "小明"))
        assertEquals("晚上好，小明", emptyChatGreeting(18, "小明"))
        assertEquals("晚上好，小明", emptyChatGreeting(22, "小明"))
        assertEquals("还没睡？要做什么？", emptyChatGreeting(23, "小明"))
        assertEquals("还没睡？要做什么？", emptyChatGreeting(0, "小明"))
    }

    // ---- 称呼处理 ----

    @Test
    fun nameIsTrimmed() {
        assertEquals("早上好，小明", emptyChatGreeting(6, "  小明  "))
    }

    @Test
    fun emptyName_fallsBack_forGreetingBands() {
        assertEquals("早上好，想聊点什么？", emptyChatGreeting(6, ""))
        assertEquals("下午好，想聊点什么？", emptyChatGreeting(15, ""))
        assertEquals("晚上好，想聊点什么？", emptyChatGreeting(20, ""))
    }

    @Test
    fun whitespaceOnlyName_behavesLikeEmpty() {
        assertEquals("早上好，想聊点什么？", emptyChatGreeting(6, "   "))
        assertEquals("下午好，想聊点什么？", emptyChatGreeting(15, "   "))
        assertEquals("晚上好，想聊点什么？", emptyChatGreeting(20, "   "))
    }

    @Test
    fun coffeeBand_ignoresName() {
        assertEquals("咖啡加 Aster 时间？", emptyChatGreeting(12, "小明"))
        assertEquals("咖啡加 Aster 时间？", emptyChatGreeting(12, ""))
        assertEquals("咖啡加 Aster 时间？", emptyChatGreeting(12, "   "))
    }

    @Test
    fun nightBand_ignoresName() {
        assertEquals("还没睡？要做什么？", emptyChatGreeting(23, "小明"))
        assertEquals("还没睡？要做什么？", emptyChatGreeting(23, ""))
        assertEquals("还没睡？要做什么？", emptyChatGreeting(2, "   "))
    }

    // ---- 小时归一化 ----

    @Test
    fun hourNormalization_positive() {
        assertEquals(emptyChatGreeting(4, "小明"), emptyChatGreeting(28, "小明"))
    }

    @Test
    fun hourNormalization_negative() {
        assertEquals(emptyChatGreeting(23, "小明"), emptyChatGreeting(-1, "小明"))
    }
}
