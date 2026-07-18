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
    fun promptUnderstandsSettingBeforeTranslatingWithoutInventingLore() {
        val prompt = MangaTranslationPrompt.build(MangaTranslationTarget.SimplifiedChinese)

        assertTrue(prompt.contains("先理解设定，再决定译法"))
        assertTrue(prompt.contains("身份关系"))
        assertTrue(prompt.contains("建立本页内部术语表"))
        assertTrue(prompt.contains("广泛认可的官方译名"))
        assertTrue(prompt.contains("不得猜测作品来源、虚构背景"))
        assertTrue(prompt.contains("不要输出分析过程"))
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
