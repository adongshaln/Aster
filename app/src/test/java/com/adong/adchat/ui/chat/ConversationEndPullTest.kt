package com.adong.adchat.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ConversationEndPullTest {
    @Test fun onlyAFreshTouchPastTheActualEndCanArm() {
        val pull = ConversationEndPull(100f)
        assertEquals(0f, pull.pull(-400f, true, true), 0f)
        pull.begin(true)
        assertEquals(0f, pull.pull(-400f, false, true), 0f)
        assertEquals(-80f, pull.pull(-80f, true, true), 0f)
        assertEquals(.52f, pull.progress, .001f)
        assertFalse(pull.armed)
        pull.pull(-100f, true, true)
        assertTrue(pull.armed)
        assertTrue(pull.release(true))
        assertFalse(pull.release(true))
        assertEquals(0f, pull.progress, 0f)
    }
    @Test fun aShortPullOrReversingBeforeReleaseDoesNotCreateAConversation() {
        val pull = ConversationEndPull(100f)
        pull.begin(true)
        pull.pull(-50f, true, true)
        assertFalse(pull.release(true))
        pull.begin(true)
        pull.pull(-200f, true, true)
        assertTrue(pull.armed)
        assertEquals(50f, pull.reverse(50f), .001f)
        assertFalse(pull.armed)
        assertTrue(pull.reverse(500f) < 500f)
        assertEquals(0f, pull.progress, 0f)
        assertFalse(pull.release(true))
    }
    @Test fun busyStateAndCancelledPointersCannotCompleteTheAction() {
        val pull = ConversationEndPull(100f)
        pull.begin(false)
        pull.pull(-500f, true, true)
        assertFalse(pull.release(true))
        pull.begin(true)
        pull.pull(-500f, true, true)
        assertFalse(pull.release(false))
        pull.begin(true)
        pull.pull(-500f, true, true)
        pull.cancel()
        assertFalse(pull.release(true))
    }
    @Test fun overpullIsBoundedAndItsReleaseVelocityIsConsumedOnce() {
        val pull = ConversationEndPull(100f)
        pull.begin(true)
        pull.pull(-10_000f, true, true)
        assertEquals(125f, pull.distancePx, .001f)
        assertEquals(1f, pull.progress, 0f)
        pull.cancel()
        assertTrue(pull.consumeFling())
        assertFalse(pull.consumeFling())
        assertEquals(0f, pull.pull(Float.NaN, true, true), 0f)
    }
}
