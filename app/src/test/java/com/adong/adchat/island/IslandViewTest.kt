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
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class IslandViewTest {
    private class Events : IslandView.Callbacks {
        val shown = mutableListOf<IslandLink>()
        var dismissed = 0
        var captures = 0
        override fun onLinkShown(link: IslandLink) { shown += link }
        override fun onDismissed() { dismissed++ }
        override fun onParseRequested(link: IslandLink) = Unit
        override fun onScreenCaptureRequested() { captures++ }
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

    private val ring get() = view.getChildAt(1) as IslandInkRingView
    private val surface get() = view.getChildAt(0) as IslandSurfaceView

    @Test @Config(sdk = [35])
    fun longPressCapturesOnceWithoutAlsoReadingClipboard() {
        view.attach()
        layoutWindow()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "https://v.douyin.com/one/"))
        val start = SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, ring.width / 2f, ring.height / 2f, 0)
            ring.dispatchTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN)
        advance(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 32)
        touch(MotionEvent.ACTION_UP)
        advance(100)
        assertEquals(1, events.captures)
        assertNull(view.displayedContent)
        assertTrue(events.shown.isEmpty())
    }

    @Test @Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
    fun screenCardKeepsInkAnchorAndDraftAcrossKeyboardAndStreamingUpdates() {
        view.destroy()
        view = IslandView(RuntimeEnvironment.getApplication(), events, accessibilityOverlay = true)
        view.attach()
        layoutWindow()
        val y = (view.layoutParams as WindowManager.LayoutParams).y
        val size = ring.height
        val preview = android.graphics.Bitmap.createBitmap(80, 80, android.graphics.Bitmap.Config.ARGB_8888)
        val session = ScreenQuestionSession(preview,
            com.adong.adchat.data.ChatImageAttachment(uri = "", name = "crop.jpg", bytes = byteArrayOf(1)), draft = "解释这里")
        view.showScreenQuestion(session)
        layoutWindow()
        advance(1_200)
        val compactPromptHeight = surface.getChildAt(0).height
        assertTrue(
            "initial prompt card should stay compact before any answer exists: height=$compactPromptHeight",
            compactPromptHeight < 320
        )
        fun findTextButton(parent: ViewGroup, label: String): android.widget.TextView? {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child is android.widget.TextView && child.text?.toString() == label) return child
                if (child is ViewGroup) findTextButton(child, label)?.let { return it }
            }
            return null
        }
        assertNotNull("收起 must wear a visible pill background", findTextButton(view, "收起")?.background)
        assertNotNull("结束 must wear a visible pill background", findTextButton(view, "结束")?.background)

        view.updateScreenQuestion(session.copy(question = "解释这里", task = StarIslandTaskState(StarIslandAction.AskScreen,
            StarIslandPhase.Working, output = "这是答案")))
        layoutWindow()
        advance(1_200)
        val expandedConversationHeight = surface.getChildAt(0).height
        assertTrue(
            "conversation panel expands smoothly once a question is asked: compact=$compactPromptHeight expanded=$expandedConversationHeight",
            expandedConversationHeight > compactPromptHeight + 80
        )
        view.dispatchApplyWindowInsets(android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, 360))
            .setVisible(android.view.WindowInsets.Type.ime(), true).build())
        layoutWindow()
        assertTrue("keyboard should reduce active conversation card height: before=$expandedConversationHeight, after=${surface.getChildAt(0).height}",
            surface.getChildAt(0).height < expandedConversationHeight)
        assertEquals(y, (view.layoutParams as WindowManager.LayoutParams).y)
        assertEquals(size, ring.height)
        fun findInput(parent: ViewGroup): android.widget.EditText? {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child is android.widget.EditText) return child
                if (child is ViewGroup) findInput(child)?.let { return it }
            }
            return null
        }
        assertEquals("解释这里", findInput(view)?.text.toString())
        view.collapse()
        advance(1_200)
        assertEquals(y, (view.layoutParams as WindowManager.LayoutParams).y)
        assertEquals(size, (view.layoutParams as WindowManager.LayoutParams).height)
        assertEquals(0, (view.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test fun openingAndClosingKeepWindowOriginAndRingSize() {
        view.attach()
        layoutWindow()
        val idle = view.layoutParams as WindowManager.LayoutParams
        val y = idle.y
        val size = idle.width
        val ringHeight = ring.height
        view.show(first)
        layoutWindow()
        val expanded = view.layoutParams as WindowManager.LayoutParams
        val width = expanded.width
        val height = expanded.height
        assertEquals(y, expanded.y)
        assertEquals(size, ring.width)
        assertEquals(ringHeight, ring.height)
        // All frames stay in a fixed-size buffer. The springs, not WindowManager, are animated.
        repeat(8) {
            advance(50)
            assertEquals(width, expanded.width)
            assertEquals(height, expanded.height)
            assertEquals(y, expanded.y)
        }
        advance(1_200)
        assertEquals(1f, view.expansionProgress, 0f)
        view.collapse()
        advance(1_200)
        layoutWindow()
        assertEquals(y, expanded.y)
        assertEquals(size, expanded.width)
        assertEquals(size, expanded.height)
        assertEquals(0f, view.expansionProgress, 1e-4f)
        assertEquals(1, events.dismissed)
    }

    @Test fun temporaryLandscapeHideRetractsAndRestoresTheSameCard() {
        view.attach()
        layoutWindow()
        view.show(first)
        layoutWindow()
        advance(1_200)
        assertEquals(1f, view.expansionProgress, 0f)

        view.retractForTemporaryHide { view.visibility = View.GONE }
        advance(1_200)
        assertTrue(view.isTemporarilySuppressed)
        assertEquals(View.GONE, view.visibility)
        assertEquals(first.originalUrl, view.displayedContent?.key)
        assertEquals(0f, view.expansionProgress, 1e-4f)

        view.restoreAfterTemporaryHide()
        layoutWindow()
        advance(1_200)
        assertFalse(view.isTemporarilySuppressed)
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(first.originalUrl, view.displayedContent?.key)
        assertEquals(1f, view.expansionProgress, 0f)
    }

    @Test fun lockScreenSuppressionAndSeamlessScreenCaptureRestoreKeepRingInteractiveAndOpaque() {
        view.attach()
        layoutWindow()
        view.suppressForSecureDisplay()
        assertTrue(view.isTemporarilySuppressed)
        assertEquals(View.GONE, view.visibility)

        view.restoreAfterTemporaryHide()
        layoutWindow()
        // Black camera core must remain opaque immediately on reveal; only the ink stroke blooms in.
        assertEquals(1f, ring.alpha, 0f)
        advance(500)
        assertFalse(view.isTemporarilySuppressed)
        assertEquals(View.VISIBLE, view.visibility)
        assertTrue(ring.isClickable)
        assertEquals(1f, ring.inkAlpha, 0.06f)

        // Seamless handoff after ScreenSelectionView's contour has already converged onto the ring:
        // must NOT drop inkAlpha back to 0 or create a double-fade flicker.
        view.restoreAfterScreenCapture(seamlessFromContour = true)
        assertEquals(1f, ring.alpha, 0f)
        assertEquals(1f, ring.inkAlpha, 0.06f)
        assertTrue(ring.isClickable)
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

    @Test fun appThemeUpdatesIslandPaletteAndAllVisibleCardColorsInBothDirections() {
        view.attach()
        layoutWindow()
        view.show(first)

        view.setNightMode(true)
        assertEquals(IslandPalette.Dark, view.islandPalette)
        assertEquals(IslandPalette.Dark, surface.currentPalette)
        assertEquals(IslandPalette.Dark.clay, ring.inkColor)
        assertEquals(IslandPalette.Dark.ink, view.cardTitleColor)
        assertEquals(IslandPalette.Dark.muted, view.cardSummaryColor)
        assertEquals(IslandPalette.Dark.ink, view.outputColor)
        val darkActions = view.actionTextColors
        assertEquals(4, darkActions.size)
        assertEquals(IslandPalette.Dark.onClay, darkActions.first())
        assertTrue(darkActions.drop(1).all { it == IslandPalette.Dark.ink })

        view.setNightMode(false)
        assertEquals(IslandPalette.Light, view.islandPalette)
        assertEquals(IslandPalette.Light, surface.currentPalette)
        assertEquals(IslandPalette.Light.clay, ring.inkColor)
        assertEquals(IslandPalette.Light.ink, view.cardTitleColor)
        assertEquals(IslandPalette.Light.muted, view.cardSummaryColor)
        assertEquals(IslandPalette.Light.ink, view.outputColor)
        assertEquals(IslandPalette.Light.onClay, view.actionTextColors.first())
        assertTrue(view.actionTextColors.drop(1).all { it == IslandPalette.Light.ink })
    }

    @Test fun tagTransfersInkToItsGrowingOutlineBeforeContentAppears() {
        view.attach()
        layoutWindow()
        view.show(first)
        layoutWindow()
        val card = surface.getChildAt(0)
        val b = surface.body
        val anchorX = ring.left + ring.width / 2f
        val anchorY = ring.top + ring.height / 2f
        val ringWidth = ring.width
        val ringHeight = ring.height
        assertEquals(anchorX - card.left, card.pivotX, 0f)
        assertEquals(0f, card.pivotY, 0f)
        assertEquals(0f, card.alpha, 0f)
        // Before the first frame the tag is hidden inside the ring's black core.
        assertEquals(anchorX - 13f, b.left, 0.01f)
        assertEquals(anchorY - 13f, b.top, 0.01f)
        assertEquals(anchorY + 13f, b.bottom, 0.01f)

        var maxBottom = 0f
        var widthAt90 = -1
        var heightAt90 = -1
        repeat(100) { frame ->
            advance(16)
            maxBottom = max(maxBottom, b.bottom)
            if (widthAt90 < 0 && b.right - b.left >= 26f + 0.9f * (card.width - 26f)) widthAt90 = frame
            if (heightAt90 < 0 && b.bottom - b.top >= 26f + 0.9f * (card.height - 26f)) heightAt90 = frame
            if (b.heightProgress < 0.4f) assertEquals("content waits for the paper", 0f, card.alpha, 0f)
            assertFalse("the surface owns the stroke while expanded", ring.inkVisible)
            assertEquals("black lens backing stays opaque", 1f, ring.alpha, 0f)
            assertEquals(ringWidth, ring.width)
            assertEquals(ringHeight, ring.height)
            assertEquals("the black core is never scaled with the ink", 1f, ring.scaleX, 0f)
            assertEquals(1f, ring.scaleY, 0f)
        }
        assertTrue("width leads height; width=$widthAt90 height=$heightAt90", widthAt90 in 0 until heightAt90)
        assertTrue("the drop must bounce past the card; max=$maxBottom card=${card.bottom}", maxBottom > card.bottom + 2f)
        assertEquals(1f, view.expansionProgress, 0f)
        assertEquals(card.left.toFloat(), b.left, 0.01f)
        assertEquals(card.top.toFloat(), b.top, 0.01f)
        assertEquals(card.right.toFloat(), b.right, 0.01f)
        assertEquals(card.bottom.toFloat(), b.bottom, 0.01f)
        assertEquals(1f, card.alpha, 0f)
        assertEquals(1f, card.scaleX, 0f)
        // Only the black lens backing remains in the resident View; the border owns its ink.
        assertFalse(ring.inkVisible)
        assertEquals(1f, ring.alpha, 0f)
    }

    @Test fun closingTucksTheTagBackIntoTheRingAndTheInkGulpsOnce() {
        view.attach()
        layoutWindow()
        view.show(first)
        layoutWindow()
        advance(1_500)
        val b = surface.body
        view.collapse()
        var minWidth = Float.MAX_VALUE
        var minHeight = Float.MAX_VALUE
        var peakInk = 0f
        repeat(70) {
            advance(16)
            if (surface.active) {
                minWidth = min(minWidth, b.right - b.left)
                minHeight = min(minHeight, b.bottom - b.top)
            }
            peakInk = max(peakInk, view.ringInk)
        }
        assertTrue("width=$minWidth", minWidth >= 26f - 0.01f)
        assertTrue("height=$minHeight", minHeight >= 26f - 0.01f)
        assertTrue("the ink must gulp once the tag is back; peak=$peakInk", peakInk > 1.15f)
        assertEquals(1f, view.ringInk, 1e-3f)
        layoutWindow()
        assertEquals(1, events.dismissed)
        assertEquals(ring.width, (view.layoutParams as WindowManager.LayoutParams).width)
    }

    @Test fun emptyClipboardBouncesTheRingTwiceWithoutOpening() {
        view.attach()
        layoutWindow()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("empty", ""))
        val root = Shadow.extract<ShadowViewRootImpl>(view.parent)
        root.callWindowFocusChanged(true)
        val size = ring.width
        assertTrue(ring.performClick())
        val samples = mutableListOf<Float>()
        repeat(40) {
            advance(16)
            samples += view.ringBounce
            assertEquals(size, ring.width)
        }
        var peaks = 0
        for (i in 1 until samples.lastIndex) {
            if (samples[i] > samples[i - 1] && samples[i] >= samples[i + 1] && samples[i] > 1.03f) peaks++
        }
        assertEquals("two bounces; samples=$samples", 2, peaks)
        assertEquals(1f, view.ringBounce, 1e-3f)
        assertNull(view.displayedLink)
        assertEquals(0, events.shown.size)
    }

    @Test fun ringKeepsItsSizeAndCentreThroughOpenAndClose() {
        view.attach()
        layoutWindow()
        val size = ring.width
        view.show(first)
        layoutWindow()
        fun assertRingFixed() {
            assertEquals(size, ring.width)
            assertEquals(size, ring.height)
            assertEquals(0, ring.top)
            assertEquals(view.width / 2f, ring.left + ring.width / 2f, 0.5f)
            assertEquals(1f, ring.bounce, 0f)
            assertEquals(1f, ring.scaleX, 0f)
            assertEquals(1f, ring.scaleY, 0f)
            assertEquals("black camera backing must never fade", 1f, ring.alpha, 0f)
        }
        repeat(80) {
            advance(16)
            assertRingFixed()
            assertFalse("the expanded surface owns the ink", ring.inkVisible)
        }
        view.collapse()
        repeat(80) {
            advance(16)
            assertRingFixed()
        }
        layoutWindow()
        assertRingFixed()
        assertTrue("the resident stroke returns after closing", ring.inkVisible)
        assertEquals(1, events.dismissed)
    }

    @Test fun dayModeUsesIvoryPaper() {
        assertEquals(IslandPalette.Light, view.islandPalette)
    }

    @Config(qualifiers = "night")
    @Test fun nightModeUsesWarmCharcoalPaper() {
        assertEquals(IslandPalette.Dark, view.islandPalette)
    }

    @Test fun cardShowsPlatformTitleAndOneLineLinkSummary() {
        view.show(first)
        layoutWindow()
        assertEquals("抖音视频", view.cardTitleText.toString())
        assertEquals("v.douyin.com/one", view.cardSummaryText.toString())
        val card = (surface.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        // Header + action row only: no grabber bar, no nested link capsule.
        assertEquals(2, card.childCount)
        advance(1_500)
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
        advance(1_200)
        assertNull(view.displayedLink)
        assertEquals(1, events.dismissed)
    }

    @Test fun rapidReverseKeepsMomentumAndCancelsOldCompletion() {
        view.show(first)
        layoutWindow()
        advance(200)
        val b = surface.body
        val opening = view.expansionProgress
        assertTrue("opening must still be in flight after 200ms; progress=$opening", opening > 0f && opening < 1f)
        val heightAtReversal = b.bottom - b.top
        val leftAtReversal = b.left
        view.collapse()
        advance(16)
        assertTrue("no jump on reversal", kotlin.math.abs(b.left - leftAtReversal) < 25f)
        advance(80)
        assertTrue(
            "closing retracts the height first; before=$heightAtReversal now=${b.bottom - b.top}",
            b.bottom - b.top < heightAtReversal
        )
        assertEquals("dismiss must cancel the old task before the closing animation finishes", 1, events.dismissed)
        view.show(second)
        layoutWindow()
        advance(1_500)
        assertEquals(1f, view.expansionProgress, 0f)
        assertEquals(second, view.displayedLink)
        assertEquals("reopening must not dismiss the new task when the old close animation ends", 1, events.dismissed)
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
        // Find an actual overshoot instead of coupling the regression to one spring timing.
        for (frame in 0 until 100) {
            advance(16)
            if (view.expansionProgress > 1.01f) break
        }
        val overshoot = view.expansionProgress
        assertTrue("must exercise the overshoot; progress=$overshoot", overshoot > 1f)
        view.show(second)
        layoutWindow()
        assertEquals(overshoot, view.expansionProgress, 0.01f)
        assertEquals(second, view.displayedLink)
        advance(1_500)
        assertEquals("retargeted overshoot must settle on the card", 1f, view.expansionProgress, 0f)
        assertEquals(0, events.dismissed)
        assertEquals(2, events.shown.size)
    }

    @Test fun quickTapReleaseDoesNotCancelReadWhileWaitingForWindowFocus() {
        view.attach()
        layoutWindow()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", first.originalUrl))
        val target = ring
        val downAt = SystemClock.uptimeMillis()
        fun dispatch(action: Int) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, target.width / 2f, target.height / 2f, 0)
            try { assertTrue(target.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN)
        assertTrue("the ink presses heavier under the finger", run { advance(64); view.ringInk > 1.1f })
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
        assertTrue(ring.performClick())
        root.callWindowFocusChanged(false)
        advance(200)
        assertNull(view.displayedLink)
        root.callWindowFocusChanged(true)
        advance(50)
        assertEquals(first.originalUrl, view.displayedLink?.originalUrl)
        assertEquals(1, events.shown.size)
        assertTrue(
            "clipboard focus is released after reading",
            (view.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        )
    }

    @Test @Config(sdk = [35])
    fun compatibilityHostKeepsCameraAnchorAcrossFocusAndViewportChanges() {
        view.destroy()
        view = IslandView(RuntimeEnvironment.getApplication(), events, accessibilityOverlay = true)
        view.attach()
        layoutWindow()
        val params = view.layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
        assertEquals(0, params.fitInsetsTypes)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, params.layoutInDisplayCutoutMode)
        val density = view.resources.displayMetrics.density
        val cameraCenter = params.y + ring.top + ring.height / 2f
        assertEquals(25.5f * density, cameraCenter, 1f)

        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", first.originalUrl))
        val root = Shadow.extract<ShadowViewRootImpl>(view.parent)
        root.callWindowFocusChanged(false)
        ring.performClick()
        root.callWindowFocusChanged(true)
        advance(50)
        layoutWindow()
        assertEquals(first.originalUrl, view.displayedLink?.originalUrl)
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
        assertEquals(cameraCenter, params.y + ring.top + ring.height / 2f, 0f)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        advance(1_200)
        view.collapse()
        advance(1_200)
        layoutWindow()
        assertEquals(cameraCenter, params.y + ring.top + ring.height / 2f, 0f)
        assertEquals(1, events.dismissed)
    }

    @Test fun destroyingCancelsPendingLayoutAndAnimationCallbacks() {
        view.show(first)
        view.destroy() // Before the first expanded layout has happened.
        advance(1_000)
        assertNull(view.displayedLink)
        assertEquals(0, events.dismissed)
    }
}
