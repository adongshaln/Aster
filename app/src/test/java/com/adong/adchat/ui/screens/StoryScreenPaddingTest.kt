package com.adong.adchat.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertNotNull
import org.junit.Test

class StoryScreenPaddingTest {
    @Test
    fun horizontalAndBottomPaddingOverloadRemainsAvailable() {
        val modifier = Modifier.padding(horizontal = 20.dp, bottom = 92.dp)
        assertNotNull(modifier)
    }
}
