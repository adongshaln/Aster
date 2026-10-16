package com.adong.adchat.island

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowViewRootImpl
import java.time.Duration
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class IslandViewTest {
    private class Events : IslandView.Callbacks {
        val shown = mutableListOf<IslandLink>()
        var dismissed = 0
        override fun onLinkShown(link: IslandLink) { shown += link }
        override fun onDismissed() { dismissed++ }
        override fun onParseRequested(link: IslandLink) = Unit
    }

    private lateinit var events: Events
    private lateinit var view: IslandView
    private val first get() = checkNotNull(IslandLinkParser.parse("https://v.douyin.com/one/"))
    private val second get() = checkNotNull(IslandLinkParser.parse("https://v.douyin.com/two/"))

    @Before fun create() {
        // PAUSED Looper does not itself pause Robolectric's auto-advancing vsync clock.
        // Without this, draining one frame can run an entire spring before idleFor returns.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        events = Events()
        view = IslandView(RuntimeEnvironment.getApplication(), events)
    }

    @After fun destroy() {
        try {
            view.destroy()
        } finally {
            // Restore Robolectric 4.14.1 defaults after all view animators are cancelled.
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(1))
            ShadowChoreographer.setPaused(false)
        }
    }

    private fun layoutWindow() {
        val params = view.layoutParams as WindowManager.LayoutParams
        // Repeated show may keep the same viewport size, but still needs a layout traversal.
        view.forceLayout()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, params.width, params.height)
    }

    private fun advance(ms: Long) {
        val startedAt = SystemClock.uptimeMillis()
        var remaining = ms
        // Paused vsync is delivered by clock listeners, not scheduled as a future Looper task.
        // Step through each frame; one large idleFor jump would produce only one animation frame.
        while (remaining > 0L) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
        assertEquals("advance($ms) must not auto-run extra animation time", startedAt + ms, SystemClock.uptimeMillis())
    }

    @Test fun openingAndClosingKeepWindowOriginAndHaloSize() {
        view.attach()
        layoutWindow()
        val idle = view.layoutParams as WindowManager.LayoutParams
        val y = idle.y
        val size = idle.width
        val halo = view.getChildAt(1)
        val haloHeight = halo.height
        view.show(first)
        layoutWindow()
        val expanded = view.layoutParams as WindowManager.LayoutParams
        val width = expanded.width
        val height = expanded.height
        assertEquals(y, expanded.y)
        assertEquals(size, halo.width)
        assertEquals(haloHeight, halo.height)
        // All frames stay in a fixed-size buffer. The geometry, not WindowManager, is animated.
        repeat(8) {
            advance(50)
            assertEquals(width, expanded.width)
            assertEquals(height, expanded.height)
            assertEquals(y, expanded.y)
        }
        advance(400)
        assertEquals(1f, view.expansionProgress, 0f)
        view.collapse()
        advance(800)
        layoutWindow()
        assertEquals(y, expanded.y)
        assertEquals(size, expanded.width)
        assertEquals(size, expanded.height)
        assertEquals(0f, view.expansionProgress, 0f)
        assertEquals(1, events.dismissed)
    }

    @Test fun overlayResizeNeverPlaysTheWindowMoveAnimation() {
        view.attach()
        layoutWindow()
        val params = view.layoutParams as WindowManager.LayoutParams
        val privateFlags = WindowManager.LayoutParams::class.java.getField("privateFlags")
        // PRIVATE_FLAG_NO_MOVE_ANIMATION: the resize to/from the card must not slide the ring.
        assertTrue(privateFlags.getInt(params) and 0x00000040 != 0)
        view.show(first)
        layoutWindow()
        assertTrue(privateFlags.getInt(view.layoutParams as WindowManager.LayoutParams) and 0x00000040 != 0)
    }

    @Test fun islandStretchesSidewaysThenGrowsDownWithAFixedTop() {
        view.attach()
        layoutWindow()
        val glow = view.getChildAt(1) as IslandGlowView
        view.show(first)
        layoutWindow()
        val surface = view.getChildAt(0) as IslandSurfaceView
        val card = surface.getChildAt(0)
        val g = surface.geometry
        val anchorX = glow.left + glow.width / 2f
        val anchorY = glow.top + glow.height / 2f
        assertEquals(anchorX - card.left, card.pivotX, 0f)
        assertEquals(0f, card.pivotY, 0f)
        assertEquals(0f, card.alpha, 0f)
        assertEquals(anchorX - 13f, g.left, 0.001f)
        assertEquals(anchorY - 13f, g.top, 0.001f)
        assertEquals(anchorY + 13f, g.bottom, 0.001f)

        // Spring progress stays below HEIGHT_START for the first 150ms of the 600ms clock.
        advance(150)
        assertTrue("must already stretch sideways; width=${g.right - g.left}", g.right - g.left > 60f)
        assertEquals(anchorY + 13f, g.bottom, 0.001f)
        assertEquals(anchorX, (g.left + g.right) / 2f, 0.5f)
        assertEquals(0f, card.alpha, 0f)

        // By 300ms the width has settled; only the height is still growing.
        advance(150)
        assertEquals(card.left.toFloat(), g.left, 0.01f)
        assertEquals(card.right.toFloat(), g.right, 0.01f)
        assertTrue("height must be growing; bottom=${g.bottom}", g.bottom > anchorY + 13f + 20f)
        assertTrue(g.bottom < card.bottom)
        assertEquals(anchorY - 13f, g.top, 1f)

        advance(IslandSpring.EXPAND_MS)
        assertEquals(1f, view.expansionProgress, 0f)
        assertEquals(card.left.toFloat(), g.left, 0.01f)
        assertEquals(card.top.toFloat(), g.top, 0.01f)
        assertEquals(card.right.toFloat(), g.right, 0.01f)
        assertEquals(card.bottom.toFloat(), g.bottom, 0.01f)
        assertEquals(1f, card.alpha, 0f)
        assertEquals(1f, card.scaleX, 0f)
        assertEquals(0f, glow.alpha, 0f)

        view.collapse()
        advance(IslandSpring.COLLAPSE_MS + 32L)
        assertEquals(0f, view.expansionProgress, 0f)
        assertEquals(0f, card.alpha, 0f)
        assertEquals(1, events.dismissed)
    }

    @Test fun residentHaloKeepsItsSizeAndCentreThroughOpenAndClose() {
        view.attach()
        layoutWindow()
        val glow = view.getChildAt(1) as IslandGlowView
        val size = glow.width
        view.show(first)
        layoutWindow()
        val surface = view.getChildAt(0) as IslandSurfaceView
        fun assertHaloFixed() {
            assertEquals(1f, glow.springScale, 0f)
            assertEquals(size, glow.width)
            assertEquals(size, glow.height)
            assertEquals(0, glow.top)
            assertEquals(view.width / 2f, glow.left + glow.width / 2f, 0.5f)
        }
        repeat((IslandSpring.EXPAND_MS / 16 + 2).toInt()) {
            advance(16)
            assertHaloFixed()
        }
        assertEquals(0f, glow.alpha, 0f)
        view.collapse()
        var peakGlow = 0f
        var ringVisibleWhileWide = false
        repeat((IslandSpring.COLLAPSE_MS / 16).toInt()) {
            advance(16)
            assertHaloFixed()
            peakGlow = max(peakGlow, glow.glowIntensity)
            if (surface.geometry.right - surface.geometry.left > 200f && glow.alpha > 0f) ringVisibleWhileWide = true
        }
        assertFalse("the ring must only reappear once the island is nearly back in the core", ringVisibleWhileWide)
        assertTrue("closing tail must pulse the glow once; peak=$peakGlow", peakGlow > IslandGlowView.BASE_GLOW_INTENSITY + 0.1f)
        advance(64)
        layoutWindow()
        assertEquals(1, events.dismissed)
        assertHaloFixed()
    }

    @Test fun cardShowsPlatformTitleAndOneLineLinkSummary() {
        view.show(first)
        layoutWindow()
        assertEquals("抖音视频", view.cardTitleText.toString())
        assertEquals("v.douyin.com/one", view.cardSummaryText.toString())
        val card = (view.getChildAt(0) as IslandSurfaceView).getChildAt(0) as ViewGroup
        // Header + action row only: no grabber bar, no nested link capsule.
        assertEquals(2, card.childCount)
        advance(IslandSpring.EXPAND_MS + 32L)
        view.updateCard(first.copy(cleanedUrl = "https://www.douyin.com/video/1"))
        assertEquals("douyin.com/video/1", view.cardSummaryText.toString())
    }

    @Test fun shortLinkUpdateNeverReopensDismissedOrDifferentCard() {
        view.updateCard(first.copy(cleanedUrl = "https://www.douyin.com/video/1"))
        assertNull(view.displayedLink)
        assertEquals(0, events.shown.size)
        view.show(first)
        layoutWindow()
        view.show(second)
        val resolvedFirst = first.copy(cleanedUrl = "https://www.douyin.com/video/1")
        view.updateCard(resolvedFirst)
        assertEquals(second.originalUrl, view.displayedLink?.originalUrl)
        val resolvedSecond = second.copy(cleanedUrl = "https://www.douyin.com/video/2")
        view.updateCard(resolvedSecond)
        assertEquals(resolvedSecond, view.displayedLink)
        assertEquals(2, events.shown.size)
        view.collapse()
        view.updateCard(resolvedSecond)
        assertNull(view.displayedLink)
        advance(800)
        assertNull(view.displayedLink)
        assertEquals(1, events.dismissed)
    }

    @Test fun rapidReverseStartsAtRenderedPositionAndCancelsOldCompletion() {
        view.show(first)
        layoutWindow()
        advance(350)
        val openingPosition = view.expansionProgress
        assertTrue("opening must still be in flight after 350ms; progress=$openingPosition", openingPosition > 0f && openingPosition < 1f)
        view.collapse()
        assertEquals(openingPosition, view.expansionProgress, 0.00001f)
        advance(100)
        val closingPosition = view.expansionProgress
        assertTrue("closing must still be in flight after 100ms; opening=$openingPosition closing=$closingPosition dismissed=${events.dismissed}", closingPosition > 0f && closingPosition < openingPosition)
        assertEquals("close must not finish before reversal; progress=$closingPosition", 0, events.dismissed)
        view.show(second)
        layoutWindow()
        assertEquals(closingPosition, view.expansionProgress, 0.00001f)
        advance(900)
        assertEquals(1f, view.expansionProgress, 0f)
        assertEquals(second, view.displayedLink)
        assertEquals("cancelled close must not shrink window/call dismissed; progress=${view.expansionProgress} link=${view.displayedLink?.originalUrl}", 0, events.dismissed)
    }

    @Test fun collapseBeforeExpandedLayoutCancelsPendingOpen() {
        view.attach()
        layoutWindow()
        val idleSize = (view.layoutParams as WindowManager.LayoutParams).width
        view.show(first)
        assertEquals(0f, view.expansionProgress, 0f)
        view.collapse() // Cancels the open callback before the requested expanded layout.
        layoutWindow()
        advance(900)
        layoutWindow()
        assertNull(view.displayedLink)
        assertEquals(0f, view.expansionProgress, 0f)
        assertEquals(idleSize, (view.layoutParams as WindowManager.LayoutParams).width)
        assertEquals(1, events.dismissed)
        assertEquals(1, events.shown.size)
        // A cancelled pending open cannot fire on a later, unrelated layout traversal.
        layoutWindow()
        advance(200)
        assertNull(view.displayedLink)
        assertEquals(0f, view.expansionProgress, 0f)
        assertEquals(1, events.dismissed)
    }

    @Test fun repeatedShowDuringOvershootKeepsCurrentGeometryAndSettles() {
        view.show(first)
        layoutWindow()
        // The tail peak is near 86% of the 600ms clock; allow vsync quantisation around it.
        advance(520)
        val overshoot = view.expansionProgress
        assertTrue("must exercise the overshoot, not the main phase; progress=$overshoot", overshoot > 1f)
        view.show(second)
        layoutWindow()
        assertEquals(overshoot, view.expansionProgress, 0.00001f)
        assertEquals(second, view.displayedLink)
        // Retargeting keeps the same 600ms 80/20 clock; wait for that clock, not an arbitrary
        // shorter wall time, before asserting the exact endpoint.
        advance(IslandSpring.EXPAND_MS + 32L)
        assertEquals("retargeted overshoot must settle to the expanded endpoint", 1f, view.expansionProgress, 0f)
        assertEquals(0, events.dismissed)
        assertEquals(2, events.shown.size)
    }

    @Test fun quickTapReleaseDoesNotCancelReadWhileWaitingForWindowFocus() {
        view.attach()
        layoutWindow()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", first.originalUrl))
        val halo = view.getChildAt(1)
        val downAt = SystemClock.uptimeMillis()
        fun dispatch(action: Int) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, halo.width / 2f, halo.height / 2f, 0)
            try { assertTrue(halo.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN)
        dispatch(MotionEvent.ACTION_UP)
        // WM focus delivery is delayed beyond the release runnable's 150ms hold.
        val root = Shadow.extract<ShadowViewRootImpl>(view.parent)
        root.callWindowFocusChanged(false)
        advance(200)
        assertNull(view.displayedLink)
        root.callWindowFocusChanged(true)
        advance(50)
        assertEquals(first.originalUrl, view.displayedLink?.originalUrl)
        assertEquals(1, events.shown.size)
    }

    @Test fun accessibilityClickUsesTheSameFocusReadyReadPath() {
        view.attach()
        layoutWindow()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", first.originalUrl))
        val root = Shadow.extract<ShadowViewRootImpl>(view.parent)
        root.callWindowFocusChanged(false)
        // TalkBack invokes performClick without a preceding touch/down/up gesture.
        assertTrue(view.getChildAt(1).performClick())
        root.callWindowFocusChanged(false)
        advance(200)
        assertNull(view.displayedLink)
        root.callWindowFocusChanged(true)
        advance(50)
        assertEquals(first.originalUrl, view.displayedLink?.originalUrl)
        assertEquals(1, events.shown.size)
        assertTrue("clipboard focus is released after reading", (view.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
    }

    @Test fun destroyingCancelsPendingLayoutAndAnimationCallbacks() {
        view.show(first)
        view.destroy() // Before the first expanded layout has happened.
        advance(1_000)
        assertNull(view.displayedLink)
        assertEquals(0, events.dismissed)
    }
}
