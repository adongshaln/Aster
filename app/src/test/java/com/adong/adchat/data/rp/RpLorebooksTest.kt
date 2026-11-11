package com.adong.adchat.data.rp

import com.adong.adchat.data.story.StoryWorldBook
import com.adong.adchat.data.story.StoryWorldBookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpLorebooksTest {
    @Test
    fun parsesSillyTavernNativeLorebook() {
        val raw = """{"name":"大陆","entries":{"0":{"uid":0,"key":["王都"],"keysecondary":["夜"],"comment":"王都","content":"王都在北方","disable":false,"order":120,"position":4,"depth":2,"selectiveLogic":3,"constant":false},
            "1":{"uid":1,"key":["龙"],"comment":"龙","content":"龙已灭绝","disable":true,"order":100,"position":1}}}"""
        val book = RpLorebookStore.parseFile(raw, "x.json")
        assertEquals("大陆", book.name)
        val first = book.book.entries.first { it.comment == "王都" }
        assertEquals(listOf("夜"), first.secondaryKeys)
        assertEquals(4, first.position)
        assertEquals(2, first.depth)
        assertEquals(3, first.selectiveLogic)
        assertEquals(120, first.insertionOrder)
        assertFalse(book.book.entries.first { it.comment == "龙" }.enabled)
    }

    @Test
    fun parsesCharacterBookFormat() {
        val raw = """{"name":"卡书","entries":[{"id":3,"keys":["猫"],"content":"猫会说话","enabled":true,"insertion_order":50}]}"""
        val book = RpLorebookStore.parseFile(raw, "card.json")
        assertEquals("猫会说话", book.book.entries.single().content)
    }

    @Test
    fun mergeRebasesUidsSoTimedEffectsDoNotCollide() {
        val a = StoryWorldBook(entries = listOf(StoryWorldBookEntry(keys = listOf("a"), content = "A", uid = 0)))
        val b = StoryWorldBook(entries = listOf(StoryWorldBookEntry(keys = listOf("b"), content = "B", uid = 0)))
        val merged = RpLorebookStore.merge(listOf(a, b))
        assertEquals(2, merged.entries.size)
        assertTrue(merged.entries[0].uid != merged.entries[1].uid)
    }
}
