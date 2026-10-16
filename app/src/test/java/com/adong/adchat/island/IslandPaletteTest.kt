package com.adong.adchat.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class IslandPaletteTest {
    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test fun textMeetsAaContrastInBothThemes() {
        for (p in listOf(IslandPalette.Light, IslandPalette.Dark)) {
            assertTrue(contrast(p.ink, p.paper) >= 4.5)
            assertTrue(contrast(p.muted, p.paper) >= 4.5)
            assertTrue(contrast(p.ink, p.well) >= 4.5)
            assertTrue(contrast(p.onClay, p.clay) >= 4.5)
        }
    }

    @Test fun glyphIsInkAtEightySixPercent() {
        assertEquals(0xDB, (IslandPalette.Light.glyph ushr 24) and 0xFF)
        assertEquals(IslandPalette.Light.ink and 0x00FFFFFF, IslandPalette.Light.glyph and 0x00FFFFFF)
    }

    @Test fun nightModeSelectsTheWarmCharcoalPaper() {
        assertEquals(IslandPalette.Dark, IslandPalette.forNightMode(true))
        assertEquals(IslandPalette.Light, IslandPalette.forNightMode(false))
        assertNotEquals(IslandPalette.Light.paper, IslandPalette.Dark.paper)
    }
}
