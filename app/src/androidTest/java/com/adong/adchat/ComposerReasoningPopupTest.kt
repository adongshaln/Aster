package com.adong.adchat

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adong.adchat.ui.components.ComposerReasoningControl
import com.adong.adchat.ui.theme.AsterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerReasoningPopupTest {
    @get:Rule val rule = createAndroidComposeRule<SharedUiTestActivity>()

    @Test fun touchingTheOpenEntryClosesItWithoutReopening() {
        var expanded by mutableStateOf(false)
        var entryCenter = Offset.Zero
        val changes = mutableListOf<Boolean>()
        rule.runOnUiThread {
            rule.activity.setContent {
                AsterTheme {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                        contentAlignment = Alignment.BottomCenter) {
                        ComposerReasoningControl(
                            model = "gpt-5", effort = "medium", expanded = expanded,
                            onExpandedChange = { expanded = it; changes += it },
                            onEffortChange = {}, onModelClick = {},
                            modifier = Modifier.onGloballyPositioned {
                                entryCenter = it.positionOnScreen() +
                                    Offset(it.size.width / 2f, it.size.height / 2f)
                            }
                        )
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("选择思考强度").performClick()
        rule.onNodeWithTag("reasoning-popover").assertIsDisplayed()
        val downTime = SystemClock.uptimeMillis()
        injectTouch(MotionEvent.ACTION_DOWN, downTime, entryCenter)
        // Let ACTION_OUTSIDE and any resulting recomposition finish before the release.
        // Sending directly to a Compose node would bypass this cross-window regression.
        rule.waitForIdle()
        injectTouch(MotionEvent.ACTION_UP, downTime, entryCenter)
        rule.onNodeWithTag("reasoning-popover").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(true, false), changes) }
    }

    private fun injectTouch(action: Int, downTime: Long, position: Offset) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            position.x, position.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation
                .injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }
}
