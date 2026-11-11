package com.adong.adchat.data.rp.prompt

import com.adong.adchat.data.rp.RpGenerationType
import com.adong.adchat.data.rp.RpWorldScanInput
import com.adong.adchat.data.rp.RpWorldScanResult
import com.adong.adchat.data.story.StoryWorldBook
import com.adong.adchat.data.story.StoryWorldBookEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RpWorldInfoTest {
    private fun entry(
        uid: Int,
        keys: List<String> = emptyList(),
        content: String = "c$uid",
        secondary: List<String> = emptyList(),
        logic: Int = 0,
        order: Int = 100,
        position: Int = 1,
        constant: Boolean = false
    ) = StoryWorldBookEntry(
        keys = keys, secondaryKeys = secondary, content = content, uid = uid, insertionOrder = order,
        position = position, constant = constant, selective = true, selectiveLogic = logic, comment = "e$uid"
    )

    private fun scan(
        entries: List<StoryWorldBookEntry>,
        chat: List<String>,
        recursive: Boolean? = null,
        chatLength: Int = chat.size,
        timed: JSONObject = JSONObject(),
        budget: Int = 0,
        random: Random = Random(1),
        type: RpGenerationType = RpGenerationType.Normal,
        dryRun: Boolean = false,
        scanDepth: Int? = null
    ): RpWorldScanResult = RpWorldInfo.scan(
        RpWorldScanInput(
            book = StoryWorldBook(entries = entries, recursiveScanning = recursive, bookScanDepth = scanDepth),
            chatNewestFirst = chat, characterName = "梅尔文", chatLength = chatLength, budgetTokens = budget,
            timedState = timed, random = random, type = type, dryRun = dryRun,
            macros = { it.replace("{{char}}", "梅尔文") }
        )
    )

    @Test
    fun selectiveLogicVariants() {
        val chat = listOf("我们在时钟塔谈魔术")
        fun hit(logic: Int, secondary: List<String>) =
            scan(listOf(entry(1, listOf("魔术"), secondary = secondary, logic = logic)), chat).activated.isNotEmpty()
        assertTrue(hit(0, listOf("时钟塔", "协会")))
        assertFalse(hit(0, listOf("协会")))
        assertTrue(hit(1, listOf("时钟塔", "协会")))
        assertFalse(hit(1, listOf("时钟塔")))
        assertTrue(hit(2, listOf("协会")))
        assertFalse(hit(2, listOf("时钟塔", "协会")))
        assertTrue(hit(3, listOf("时钟塔", "魔术")))
        assertFalse(hit(3, listOf("时钟塔", "协会")))
    }

    @Test
    fun regexKeysAndMacroKeys() {
        val r = scan(listOf(entry(1, listOf("/圣杯\\s*战争/i")), entry(2, listOf("{{char}}")), entry(3, listOf("/GRAIL/"))),
            listOf("梅尔文说起圣杯 战争和grail"))
        assertEquals(listOf("e1", "e2"), r.activated)
    }

    @Test
    fun scanDepthDefaultsToTwoMessages() {
        val chat = listOf("新", "中", "很早以前提过龙")
        assertTrue(scan(listOf(entry(1, listOf("龙"))), chat).activated.isEmpty())
        assertEquals(1, scan(listOf(entry(1, listOf("龙"))), chat, scanDepth = 3).activated.size)
        assertEquals(1, scan(listOf(entry(1, listOf("龙")).copy(scanDepth = 3)), chat).activated.size)
    }

    @Test
    fun wholeWordsAndCase() {
        val e = entry(1, listOf("Cat")).copy(matchWholeWords = true)
        assertTrue(scan(listOf(e), listOf("a cat sat")).activated.isNotEmpty())
        assertTrue(scan(listOf(e), listOf("concatenate")).activated.isEmpty())
        assertTrue(scan(listOf(e.copy(caseSensitiveOverride = true)), listOf("a cat sat")).activated.isEmpty())
        val cjk = entry(2, listOf("龙")).copy(matchWholeWords = true)
        assertTrue(scan(listOf(cjk), listOf("一条龙飞过")).activated.isNotEmpty())
    }

    @Test
    fun recursionOnlyWhenBookEnablesIt() {
        val entries = listOf(entry(1, listOf("钥匙"), content = "钥匙能打开地下室"), entry(2, listOf("地下室"), content = "地下室封着恶魔"))
        assertEquals(listOf("e1"), scan(entries, listOf("我捡到钥匙")).activated)
        assertEquals(listOf("e1", "e2"), scan(entries, listOf("我捡到钥匙"), recursive = true).activated)
        val excluded = listOf(entries[0], entries[1].copy(excludeRecursion = true))
        assertEquals(listOf("e1"), scan(excluded, listOf("我捡到钥匙"), recursive = true).activated)
        val prevented = listOf(entries[0].copy(preventRecursion = true), entries[1])
        assertEquals(listOf("e1"), scan(prevented, listOf("我捡到钥匙"), recursive = true).activated)
    }

    @Test
    fun delayUntilRecursionWaitsForLevel() {
        val entries = listOf(entry(1, listOf("钥匙")).copy(delayUntilRecursion = 1))
        // ST presets the first level; it is only reached through a recursion pass.
        assertTrue(scan(entries, listOf("钥匙")).activated.isEmpty())
        val withTrigger = entries + entry(2, listOf("钥匙"))
        assertEquals(listOf("e1", "e2"), scan(withTrigger, listOf("钥匙"), recursive = true).activated)
        val two = listOf(entry(1, listOf("钥匙"), content = "门"), entry(2, listOf("门")).copy(delayUntilRecursion = 2))
        assertEquals(listOf("e1", "e2"), scan(two, listOf("钥匙"), recursive = true).activated)
    }

    @Test
    fun triggersAndDecorators() {
        val e = entry(1, constant = true).copy(triggers = listOf("continue"))
        assertTrue(scan(listOf(e), listOf("x")).activated.isEmpty())
        assertEquals(1, scan(listOf(e), listOf("x"), type = RpGenerationType.Continue).activated.size)
        val forced = entry(2, content = "@@activate\n总会出现")
        val r = scan(listOf(forced, entry(3, listOf("x"), content = "@@dont_activate\n不会")), listOf("x"))
        assertEquals("总会出现", r.after)
    }

    @Test
    fun groupPickIsDeterministicWithSeed() {
        val group = (1..4).map { entry(it, listOf("门")).copy(group = "门") }
        val a = scan(group, listOf("门"), random = Random(42)).activated
        val b = scan(group, listOf("门"), random = Random(42)).activated
        assertEquals(1, a.size)
        assertEquals(a, b)
        val override = group.mapIndexed { i, e -> if (i == 2) e.copy(groupOverride = true) else e }
        assertEquals(listOf("e3"), scan(override, listOf("门")).activated)
        val scored = listOf(entry(1, listOf("门")), entry(2, listOf("门", "锁"))).map { it.copy(group = "g", useGroupScoring = true) }
        assertEquals(listOf("e2"), scan(scored, listOf("门上有锁")).activated)
    }

    @Test
    fun stickyThenCooldown() {
        val e = entry(1, listOf("龙")).copy(sticky = 2, cooldown = 2)
        val t1 = scan(listOf(e), listOf("龙出现了"), chatLength = 1)
        assertEquals(1, t1.activated.size)
        val t2 = scan(listOf(e), listOf("没有关键词"), chatLength = 2, timed = t1.timedState)
        assertEquals("sticky keeps it active", 1, t2.activated.size)
        val t3 = scan(listOf(e), listOf("龙又出现了"), chatLength = 3, timed = t2.timedState)
        assertTrue("sticky ended → protected cooldown", t3.activated.isEmpty())
        val t4 = scan(listOf(e), listOf("龙"), chatLength = 4, timed = t3.timedState)
        assertTrue(t4.activated.isEmpty())
        val t5 = scan(listOf(e), listOf("龙"), chatLength = 5, timed = t4.timedState)
        assertEquals(1, t5.activated.size)
    }

    @Test
    fun swipeWithoutAdvanceDropsUnprotectedTimer() {
        val e = entry(1, listOf("龙")).copy(sticky = 3)
        val t1 = scan(listOf(e), listOf("龙"), chatLength = 1)
        val swipe = scan(listOf(e), listOf("别的"), chatLength = 1, timed = t1.timedState)
        assertTrue(swipe.activated.isEmpty())
        assertFalse(swipe.timedState.getJSONObject("sticky").has("1"))
    }

    @Test
    fun dryRunLeavesTimedStateUntouched() {
        val e = entry(1, listOf("龙")).copy(sticky = 3)
        val r = scan(listOf(e), listOf("龙"), dryRun = true)
        assertEquals(1, r.activated.size)
        assertEquals(0, r.timedState.optJSONObject("sticky")?.length() ?: 0)
    }

    @Test
    fun delayHoldsUntilChatLength() {
        val e = entry(1, constant = true).copy(delay = 3)
        assertTrue(scan(listOf(e), listOf("x"), chatLength = 2).activated.isEmpty())
        assertEquals(1, scan(listOf(e), listOf("x"), chatLength = 3).activated.size)
    }

    @Test
    fun budgetCutsLowerOrderButIgnoreBudgetPasses() {
        val long = "长".repeat(40)
        val entries = listOf(
            entry(1, constant = true, content = long, order = 300),
            entry(2, constant = true, content = long, order = 200),
            entry(3, constant = true, content = "小", order = 100).copy(ignoreBudget = true)
        )
        val r = scan(entries, listOf("x"), budget = 100)
        assertEquals(listOf("e1", "e3"), r.activated)
    }

    @Test
    fun probabilityUsesInputRandom() {
        val e = entry(1, constant = true).copy(probability = 0)
        assertTrue(scan(listOf(e), listOf("x")).activated.isEmpty())
        assertEquals(1, scan(listOf(e.copy(useProbability = false)), listOf("x")).activated.size)
    }

    @Test
    fun positionsRouteAndOrderAscending() {
        val entries = listOf(
            entry(1, constant = true, content = "B2", order = 20, position = 0),
            entry(2, constant = true, content = "B1", order = 10, position = 0),
            entry(3, constant = true, content = "A", position = 1),
            entry(4, constant = true, content = "ANT", position = 2),
            entry(5, constant = true, content = "ANB", position = 3),
            entry(6, constant = true, content = "D", position = 4).copy(depth = 2, role = 1),
            entry(7, constant = true, content = "EMT", position = 5),
            entry(8, constant = true, content = "EMB", position = 6),
            entry(9, constant = true, content = "O1", order = 50, position = 7).copy(outletName = "hud"),
            entry(10, constant = true, content = "O2", order = 10, position = 7).copy(outletName = "hud"),
            entry(11, constant = true, content = "梅尔文是{{char}}", position = 1, order = 200)
        )
        val r = scan(entries, listOf("x"))
        assertEquals("B1\nB2", r.before)
        assertEquals("A\n梅尔文是梅尔文", r.after)
        assertEquals(listOf("ANT"), r.anTop)
        assertEquals(listOf("ANB"), r.anBottom)
        assertEquals(listOf("EMT"), r.emTop)
        assertEquals(listOf("EMB"), r.emBottom)
        assertEquals(2, r.depth.single().depth)
        assertEquals("user", r.depth.single().role)
        assertEquals("O1\nO2", r.outlets["hud"])
    }

    @Test
    fun matchFieldsExtendScan() {
        val e = entry(1, listOf("旧书店")).copy(matchCharacterDescription = true)
        val input = RpWorldScanInput(
            book = StoryWorldBook(entries = listOf(e)), chatNewestFirst = listOf("你好"), characterName = "梅尔文",
            characterDescription = "住在旧书店", chatLength = 1, budgetTokens = 0
        )
        assertEquals(1, RpWorldInfo.scan(input).activated.size)
        assertTrue(RpWorldInfo.scan(input.copy(book = StoryWorldBook(entries = listOf(e.copy(matchCharacterDescription = false))))).activated.isEmpty())
    }
}
