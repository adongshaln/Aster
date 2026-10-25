package com.adong.adchat

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adong.adchat.ui.components.ComposerReasoningSlider
import com.adong.adchat.ui.theme.AsterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerReasoningSliderTest {
    @get:Rule val rule = createAndroidComposeRule<SharedUiTestActivity>()

    @Test fun continuousDragCommitsOnlyNearestSupportedLevelOnRelease() {
        var effort by mutableStateOf("low")
        val changes = mutableListOf<String>()
        rule.runOnUiThread {
            rule.activity.setContent {
                AsterTheme {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        ComposerReasoningSlider(
                            model = "gpt-5",
                            effort = effort,
                            onEffortChange = {
                                changes += it
                                effort = it
                            }
                        )
                    }
                }
            }
        }

        val slider = rule.onNodeWithTag("reasoning-slider")
        val thumbBounds = rule.onNodeWithTag("reasoning-thumb").fetchSemanticsNode().boundsInRoot
        val sliderBounds = slider.fetchSemanticsNode().boundsInRoot
        val decorLocation = IntArray(2)
        rule.activity.window.decorView.getLocationOnScreen(decorLocation)
        fun toScreen(point: Offset) = Offset(
            point.x + decorLocation[0],
            point.y + decorLocation[1]
        )
        val start = toScreen(thumbBounds.center)
        // GPT supported effort levels have indices 0..5. 0.49 is just below
        // the medium/high midpoint, so the nearest level on release is medium.
        val thumbRadius = thumbBounds.width / 2f
        val targetX = sliderBounds.left + thumbRadius +
            (sliderBounds.width - thumbRadius * 2f) * 0.49f
        val target = toScreen(Offset(targetX, sliderBounds.center.y))
        val downTime = SystemClock.uptimeMillis()

        injectTouch(MotionEvent.ACTION_DOWN, downTime, start)
        injectTouch(MotionEvent.ACTION_MOVE, downTime, target)
        rule.waitForIdle()
        rule.runOnIdle { assertTrue("drag must stay pending until release", changes.isEmpty()) }

        injectTouch(MotionEvent.ACTION_UP, downTime, target)
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("medium"), changes)
            assertEquals("medium", effort)
        }
    }

    private fun injectTouch(action: Int, downTime: Long, position: Offset) {
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            position.x,
            position.y,
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue(
                InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
            )
        } finally {
            event.recycle()
        }
    }
}
