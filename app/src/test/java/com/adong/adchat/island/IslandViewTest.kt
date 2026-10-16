package com.adong.adchat.island

import android.os.Looper
import android.view.View
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
import java.time.Duration

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
        events = Events()
        view = IslandView(RuntimeEnvironment.getApplication(), events)
    }

    @After fun destroy() { view.destroy() }

    private fun layoutWindow() {
        val params = view.layoutParams as WindowManager.LayoutParams
        view.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, params.width, params.height)
    }

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
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
        assertTrue(openingPosition > 0f)
        view.collapse()
        assertEquals(openingPosition, view.expansionProgress, 0.00001f)
        advance(100)
        val closingPosition = view.expansionProgress
        view.show(second)
        layoutWindow()
        assertEquals(closingPosition, view.expansionProgress, 0.00001f)
        advance(900)
        assertEquals(1f, view.expansionProgress, 0f)
        assertEquals(second, view.displayedLink)
        assertEquals("cancelled close must not shrink window/call dismissed", 0, events.dismissed)
    }

    @Test fun destroyingCancelsPendingLayoutAndAnimationCallbacks() {
        view.show(first)
        view.destroy() // Before the first expanded layout has happened.
        advance(1_000)
        assertNull(view.displayedLink)
        assertEquals(0, events.dismissed)
    }
}
