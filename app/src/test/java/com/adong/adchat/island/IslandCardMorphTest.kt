package com.adong.adchat.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandCardMorphTest {
    @Test fun allCardEdgesConvergeOnRingCenterAtZeroAndMatchShellAtOne() {
        val anchorX = 200f
        val anchorY = 32f
        val left = 14f
        val top = 19f
        val right = 386f
        val bottom = 250f

        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val scale = IslandCardMorph.scale(progress)
            val mappedLeft = IslandCardMorph.mappedLeft(left, anchorX, scale)
            val mappedTop = IslandCardMorph.mappedTop(top, anchorY, scale)
            val mappedRight = IslandCardMorph.mappedRight(right, anchorX, scale)
            val mappedBottom = IslandCardMorph.mappedBottom(bottom, anchorY, scale)
            val q = progress

            assertEquals(anchorX + (left - anchorX) * q, mappedLeft, 0.0001f)
            assertEquals(anchorY + (top - anchorY) * q, mappedTop, 0.0001f)
            assertEquals(anchorX + (right - anchorX) * q, mappedRight, 0.0001f)
            assertEquals(anchorY + (bottom - anchorY) * q, mappedBottom, 0.0001f)
            if (progress == 0f) {
                assertEquals(anchorX, mappedLeft, 0f)
                assertEquals(anchorY, mappedTop, 0f)
                assertEquals(anchorX, mappedRight, 0f)
                assertEquals(anchorY, mappedBottom, 0f)
            }
            if (progress == 1f) {
                assertEquals(left, mappedLeft, 0f)
                assertEquals(top, mappedTop, 0f)
                assertEquals(right, mappedRight, 0f)
                assertEquals(bottom, mappedBottom, 0f)
            }
        }
    }

    @Test fun closeUsesSameMappingAndNeverProducesNegativeContentScale() {
        for (time in 0..1_000) {
            val openProgress = IslandSpring.progress(time / 1_000f)
            val closeProgress = (1f - openProgress).coerceIn(0f, 1f)
            val openingScale = IslandCardMorph.scale(openProgress)
            val closingScale = IslandCardMorph.scale(closeProgress)
            assertTrue(openingScale >= 0f && openingScale <= 1f)
            assertTrue(closingScale >= 0f && closingScale <= 1f)
            assertEquals(closeProgress, closingScale, 0f)
        }
    }
}
