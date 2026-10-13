package com.adong.adchat.ui

import com.adong.adchat.data.ASK_USER_TOOL
import com.adong.adchat.data.ChatToolActivity
import com.adong.adchat.data.TOOL_STATUS_COMPLETED
import com.adong.adchat.data.TOOL_STATUS_RUNNING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingLayoutTest {
    @Test
    fun portraitAndLandscapeKeepTheirOwnShape() {
        val portrait = fittedImageDp(900, 1600, maxWidth = 440f, maxHeight = 480f)
        assertEquals(270f, portrait!!.first, 0.5f)
        assertEquals(480f, portrait.second, 0.5f)
        val landscape = fittedImageDp(1600, 900, maxWidth = 440f, maxHeight = 480f)
        assertEquals(440f, landscape!!.first, 0.5f)
        assertEquals(247.5f, landscape.second, 0.5f)
    }

    @Test
    fun unknownSizeIsNotInventedAsLandscape() {
        assertNull(fittedImageDp(0, 0, maxWidth = 440f, maxHeight = 480f))
    }

    @Test
    fun runningToolIsTheCaptionUntilTheModelIsJustWriting() {
        val searching = listOf(ChatToolActivity("1", "web_search", "正在搜索网页", TOOL_STATUS_RUNNING))
        assertEquals("正在搜索网页", modelWorkingCaption(searching, recovering = false))
        val done = listOf(ChatToolActivity("1", "web_search", "已完成网页搜索", TOOL_STATUS_COMPLETED))
        assertEquals("正在继续写", modelWorkingCaption(done, recovering = false))
        assertEquals("连接波动，正在续传", modelWorkingCaption(emptyList(), recovering = true))
    }

    @Test
    fun questionWaitIsNotShownAsStillWorking() {
        val asking = listOf(ChatToolActivity("1", ASK_USER_TOOL, "正在等你选择", TOOL_STATUS_RUNNING))
        assertTrue(waitingOnUser(asking))
        assertFalse(showModelWorkingFoot(streaming = true, contentBlank = false, waitingOnUser = true))
        assertTrue(showModelWorkingFoot(streaming = true, contentBlank = false, waitingOnUser = false))
        assertFalse(showModelWorkingFoot(streaming = true, contentBlank = true, waitingOnUser = false))
    }
}
