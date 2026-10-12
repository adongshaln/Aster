package com.adong.adchat.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryWorldBookEngineTest {
    private fun entry(
        keys: List<String> = emptyList(),
        secondaryKeys: List<String> = emptyList(),
        logic: WorldLogic = WorldLogic.AND_ANY,
        content: String,
        constant: Boolean = false,
        insertionOrder: Int = 100,
        beforeChar: Boolean = false,
        enabled: Boolean = true
    ) = StoryWorldBookEntry(keys, secondaryKeys, logic, content, enabled, constant, insertionOrder, beforeChar)

    @Test
    fun constantEntryIsAlwaysIncluded() {
        val book = StoryWorldBook(entries = listOf(entry(content = "这座城永远在下雨", constant = true)))
        val activation = StoryWorldBookEngine.activate(book, "毫无相关的文本")
        assertEquals(listOf("这座城永远在下雨"), activation.after)
        assertTrue(activation.before.isEmpty())
    }

    @Test
    fun keywordEntryTriggersOnSubstringCaseInsensitively() {
        val book = StoryWorldBook(entries = listOf(entry(keys = listOf("圣杯", "Grail"), content = "圣杯战争的规则")))
        assertTrue(StoryWorldBookEngine.activate(book, "他说起了GRAIL的传闻").after.contains("圣杯战争的规则"))
        assertFalse(StoryWorldBookEngine.activate(book, "什么都没发生").after.contains("圣杯战争的规则"))
    }

    @Test
    fun secondaryLogicAndAnyRequiresAtLeastOne() {
        val book = StoryWorldBook(entries = listOf(
            entry(keys = listOf("魔术"), secondaryKeys = listOf("时钟塔", "协会"), logic = WorldLogic.AND_ANY, content = "魔术协会的背景")
        ))
        assertTrue(StoryWorldBookEngine.activate(book, "魔术师提到了时钟塔").after.contains("魔术协会的背景"))
        assertFalse(StoryWorldBookEngine.activate(book, "魔术师走远了").after.contains("魔术协会的背景"))
    }

    @Test
    fun secondaryLogicNotAnyRejectsAnyPresence() {
        val book = StoryWorldBook(entries = listOf(
            entry(keys = listOf("魔术"), secondaryKeys = listOf("时钟塔"), logic = WorldLogic.NOT_ANY, content = "野路子魔术")
        ))
        assertTrue(StoryWorldBookEngine.activate(book, "路边的魔术师").after.contains("野路子魔术"))
        assertFalse(StoryWorldBookEngine.activate(book, "时钟塔的魔术师").after.contains("野路子魔术"))
    }

    @Test
    fun recursionPullsInReferencedEntries() {
        val book = StoryWorldBook(
            entries = listOf(
                entry(keys = listOf("使魔"), content = "她的使魔是一只名叫影的猫"),
                entry(keys = listOf("影"), content = "影其实是很久以前死去的熟人")
            )
        )
        assertTrue(StoryWorldBookEngine.activate(book, "使魔出现了").after.contains("影其实是很久以前死去的熟人"))
        val nonRecursive = book.copy(recursive = false)
        assertFalse(StoryWorldBookEngine.activate(nonRecursive, "使魔出现了").after.contains("影其实是很久以前死去的熟人"))
    }

    @Test
    fun budgetDropsOverflowEntries() {
        val book = StoryWorldBook(
            charBudget = 10,
            entries = listOf(
                entry(content = "0123456789", constant = true),
                entry(keys = listOf("钥匙"), content = "overflow text here")
            )
        )
        val activation = StoryWorldBookEngine.activate(book, "提到了钥匙")
        assertEquals(listOf("0123456789"), activation.after)
        assertEquals(1, activation.omitted)
    }

    @Test
    fun constantsWinUnderBudgetPressure() {
        val book = StoryWorldBook(
            charBudget = 5,
            entries = listOf(
                entry(keys = listOf("钥匙"), content = "关键词词条", insertionOrder = 500),
                entry(content = "常量", constant = true, insertionOrder = 10)
            )
        )
        val activation = StoryWorldBookEngine.activate(book, "提到了钥匙")
        assertEquals(listOf("常量"), activation.after)
        assertEquals(1, activation.omitted)
    }

    @Test
    fun groupsSortAscendingByInsertionOrder() {
        val book = StoryWorldBook(entries = listOf(
            entry(content = "后注入的高位", insertionOrder = 300, constant = true),
            entry(content = "先注入的低位", insertionOrder = 10, constant = true)
        ))
        assertEquals(listOf("先注入的低位", "后注入的高位"), StoryWorldBookEngine.activate(book, "").after)
    }

    @Test
    fun beforeAndAfterSplitByPosition() {
        val book = StoryWorldBook(entries = listOf(
            entry(content = "角色之前的背景", constant = true, beforeChar = true),
            entry(content = "角色之后的补充", constant = true)
        ))
        val activation = StoryWorldBookEngine.activate(book, "")
        assertEquals(listOf("角色之前的背景"), activation.before)
        assertEquals(listOf("角色之后的补充"), activation.after)
    }

    @Test
    fun disabledAndKeylessEntriesAreSkipped() {
        val book = StoryWorldBook(entries = listOf(
            entry(content = "被禁用", constant = true, enabled = false),
            entry(content = "没有关键词")
        ))
        val activation = StoryWorldBookEngine.activate(book, "任意文本")
        assertTrue(activation.before.isEmpty() && activation.after.isEmpty())
    }

    @Test
    fun scanTextJoinsCardFieldsPersonaAndRecentMessages() {
        val card = StoryCharacterCard("梅尔文", "旧书店的店主", "寡言", "雨夜横滨")
        val text = StoryWorldBookEngine.scanText(listOf("今天聊到了圣杯"), card, "旁白视角")
        assertTrue(text.contains("旧书店的店主"))
        assertTrue(text.contains("雨夜横滨"))
        assertTrue(text.contains("旁白视角"))
        assertTrue(text.contains("圣杯"))
    }
}
