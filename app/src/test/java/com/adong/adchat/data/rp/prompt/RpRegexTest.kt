package com.adong.adchat.data.rp.prompt

import com.adong.adchat.data.TavernRegexScript
import org.junit.Assert.assertEquals
import org.junit.Test

class RpRegexTest {
    private val ctx = RpMacroContext(user = "旅人", char = "朝凪")

    private fun script(
        find: String, replace: String, placement: Set<Int> = setOf(2), promptOnly: Boolean = false,
        markdownOnly: Boolean = false, trim: List<String> = emptyList(), min: Int? = null, max: Int? = null,
        substitute: Int = 0, id: String = find
    ) = TavernRegexScript(id, id, find, replace, trim, placement, false, promptOnly, markdownOnly, false, substitute, min, max)

    @Test
    fun surfacesAreGatedByFlags() {
        val ingest = script("/a/g", "b")
        val prompt = script("/c/g", "d", promptOnly = true)
        val display = script("/e/g", "f", markdownOnly = true)
        val all = listOf(ingest, prompt, display)
        assertEquals("bce", RpRegex.ingest("ace", "assistant", all, ctx))
        assertEquals("ade", RpRegex.prompt("ace", "assistant", 0, all, ctx))
        val shown = RpRegex.display("ace", "assistant", 0, all, ctx)
        assertEquals("acf", shown.text)
        assertEquals(listOf("/e/g"), shown.matchedScriptIds)
        // Wrong placement: user text with an AI-output script.
        assertEquals("ace", RpRegex.ingest("ace", "user", all, ctx))
    }

    @Test
    fun depthBoundsAllowMinusOne() {
        val s = script("x", "y", promptOnly = true, min = -1, max = 1)
        assertEquals("y", RpRegex.prompt("x", "assistant", -1, listOf(s), ctx))
        assertEquals("y", RpRegex.prompt("x", "assistant", 1, listOf(s), ctx))
        assertEquals("x", RpRegex.prompt("x", "assistant", 2, listOf(s), ctx))
        val min2 = script("x", "y", promptOnly = true, min = 2)
        assertEquals("x", RpRegex.prompt("x", "assistant", 1, listOf(min2), ctx))
    }

    @Test
    fun replacementGroupsTrimAndMacros() {
        val s = script("/<b>(.*?)<\\/b>/g", "[{{char}}:\$1|{{match}}]", trim = listOf("!"))
        assertEquals("[朝凪:hi|<b>hi</b>] [朝凪:yo|<b>yo</b>]", RpRegex.ingest("<b>hi!</b> <b>yo</b>", "assistant", listOf(s), ctx))
        val named = script("/(?<n>\\d+)/", "#\$<n>\$2")
        assertEquals("#1 2", RpRegex.ingest("1 2", "assistant", listOf(named), ctx))
    }

    @Test
    fun findRegexSubstitution() {
        val raw = script("{{char}}", "她", substitute = 1)
        assertEquals("她说", RpRegex.ingest("朝凪说", "assistant", listOf(raw), ctx))
        val escaped = script("/{{char}}/g", "X", substitute = 2)
        val dotCtx = ctx.copy(char = "a.b")
        assertEquals("X axb", RpRegex.ingest("a.b axb", "assistant", listOf(escaped), dotCtx))
    }

    @Test
    fun worldInfoAndPlacementMigration() {
        val wi = script("/秘密/g", "***", placement = setOf(5), promptOnly = true)
        assertEquals("***", RpRegex.worldInfo("秘密", null, listOf(wi), ctx))
        val legacy = script("/q/g", "w", placement = setOf(0))
        assertEquals("w", RpRegex.display("q", "user", 0, listOf(legacy), ctx).text)
        assertEquals("w", RpRegex.prompt("q", "assistant", 0, listOf(legacy), ctx))
        assertEquals("q", RpRegex.ingest("q", "assistant", listOf(legacy), ctx))
    }

    @Test
    fun jsSyntaxCompiles() {
        val s = script("/<x>([^]*?)<\\/x>/gs", "{\$1}")
        assertEquals("{a\nb}", RpRegex.ingest("<x>a\nb</x>", "assistant", listOf(s), ctx))
    }
}
