package com.adong.adchat.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryDiscussionPresetsTest {
    @Test fun cardWorkbenchIsRegisteredBuiltIn() {
        val preset = StoryDiscussionPresets.byId(StoryDiscussionPresets.CARD_WORKBENCH_ID)
        assertNotNull(preset)
        assertEquals("制卡工作台", preset!!.name)
        assertTrue(preset.prompt.contains("角色卡创作助手"))
        assertTrue(preset.prompt.contains("```charactercard"))
        assertTrue(preset.prompt.contains("零度原则"))
        assertEquals(1, StoryDiscussionPresets.all.size)
    }
}
