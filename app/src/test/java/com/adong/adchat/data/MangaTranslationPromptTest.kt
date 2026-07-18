package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaTranslationPromptTest {
    @Test
    fun promptProtectsCompositionAndOriginalTextBounds() {
        val prompt = MangaTranslationPrompt.build(MangaTranslationTarget.SimplifiedChinese)
        assertTrue(prompt.contains("构图"))
        assertTrue(prompt.contains("完全相同的位置"))
        assertTrue(prompt.contains("不得侵入原本未被该段文字占用"))
        assertTrue(prompt.contains("匹配原字体"))
        assertTrue(prompt.contains("简体中文"))
    }

    @Test
    fun choosesClosestSupportedCanvasOrientation() {
        assertEquals("1024x1536", canvasSizeForReference(1000, 1600))
        assertEquals("1536x1024", canvasSizeForReference(1600, 1000))
        assertEquals("1024x1024", canvasSizeForReference(1000, 1050))
    }

    @Test
    fun restoresTargetFromSafeHistoryLabelWithoutStoringPrompt() {
        assertEquals(
            MangaTranslationTarget.English,
            MangaTranslationPrompt.detectTarget("漫画翻译 · English · 原位排版")
        )
    }
}
