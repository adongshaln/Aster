package com.adong.adchat.island

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.data.ChatMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenQuestionTest {
    @Test fun screenSelectionInkMorphStartsAtAndReturnsToTheCameraRing() {
        val body = IslandJellyBody()
        val contour = IslandInkContour()
        body.configure(180f, 25.5f, IslandInkRing.CORE_RADIUS_DP, 0f, 0f, 360f, 800f, 40f)
        body.snap(false, 1.0)
        body.update(1.0)
        contour.update(body, 1f, 1f, 1f)
        listOf(0, IslandInkRing.SAMPLES / 3, IslandInkRing.SAMPLES).forEach { index ->
            val angle = IslandInkRing.angle(index.toFloat() / IslandInkRing.SAMPLES)
            val expected = IslandInkRing.outerRadius(index.toFloat() / IslandInkRing.SAMPLES, 1f)
            assertEquals(180f + kotlin.math.cos(angle) * expected, contour.outerX[index], 0.001f)
            assertEquals(25.5f + kotlin.math.sin(angle) * expected, contour.outerY[index], 0.001f)
        }

        body.open(1.0)
        body.update(1.12)
        assertTrue("the full-screen selection grows from the ring", body.progress > 0f)
        assertTrue(body.progress < 1f)
        body.close(1.12)
        body.update(1.28)
        assertTrue("cancel or confirmation retracts the same spring", body.progress > 0f)
        assertTrue(body.progress < 1f)
        body.update(4.0)
        assertEquals(0f, body.progress, 0.001f)
        assertEquals(IslandInkRing.CORE_RADIUS_DP * 2f, body.right - body.left, 0.001f)
        assertEquals(IslandInkRing.CORE_RADIUS_DP * 2f, body.bottom - body.top, 0.001f)
    }

    @Test fun cropMappingUsesImageBoundsRatherThanWindowBounds() {
        val fittedImage = RectF(40f, 100f, 440f, 900f)
        assertEquals(Rect(100, 200, 500, 600),
            screenCropBounds(RectF(90f, 200f, 290f, 400f), fittedImage, 800, 1600))
        assertEquals(Rect(0, 0, 800, 1600),
            screenCropBounds(RectF(-50f, -50f, 1000f, 2000f), fittedImage, 800, 1600))
        assertThrows(IllegalArgumentException::class.java) {
            screenCropBounds(RectF(0f, 0f, 20f, 20f), fittedImage, 800, 1600)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun uploadContainsOnlyConfirmedPixelsAndDoesNotPersistAFile() {
        val screenshot = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        screenshot.eraseColor(Color.RED)
        for (y in 80 until 160) for (x in 40 until 120) screenshot.setPixel(x, y, Color.BLUE)
        val session = prepareScreenQuestion(screenshot, Rect(40, 80, 120, 160))
        val attachment = session.attachment
        assertEquals(80, attachment.width)
        assertEquals(80, attachment.height)
        assertEquals("", attachment.uri)
        val bytes = checkNotNull(attachment.bytes)
        val uploaded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertTrue(Color.blue(uploaded.getPixel(0, 0)) > 240)
        assertTrue(Color.red(uploaded.getPixel(0, 0)) < 15)
        assertTrue(Color.blue(uploaded.getPixel(79, 79)) > 240)
    }

    @Test fun followUpsKeepOneCropAndABoundedConversation() {
        val crop = ChatImageAttachment(uri = "", name = "crop.jpg", bytes = byteArrayOf(1))
        val history = (0..5).flatMap { listOf(ChatMessage(role = "user", content = "q$it"),
            ChatMessage(role = "assistant", content = "a$it")) }
        val messages = screenQuestionHistory("现在的问题", ScreenQuestionRequest(crop, history))
        assertEquals(9, messages.size)
        assertEquals("q2", messages.first().content)
        assertEquals(listOf(crop), messages.first().attachments)
        assertTrue(messages.drop(1).all { it.attachments.isEmpty() })
        assertEquals("现在的问题", messages.last().content)
        assertThrows(IllegalArgumentException::class.java) {
            screenQuestionHistory("x".repeat(2001), ScreenQuestionRequest(crop, emptyList()))
        }
    }

    @Test fun redmiTurbo5MaxFullScreenSheetHugsExactBezelAndThinsSmoothlyOnRetract() {
        val density = 3.25f
        val width = 1280f
        val height = 2772f
        val bezelRadius = 156f // 48dp * 3.25
        val body = IslandJellyBody()
        val contour = IslandInkContour()
        body.configure(
            centerX = width / 2f,
            centerY = 25.5f * density,
            coreRadius = IslandInkRing.CORE_RADIUS_DP * density,
            expandedLeft = 0f,
            expandedTop = 0f,
            expandedRight = width,
            expandedBottom = height,
            cornerRadius = bezelRadius,
            fullScreenSheet = true,
        )
        body.open(0.0)
        for (ms in 0..1600 step 16) {
            body.update(ms / 1000.0)
            contour.update(body, density, 1f, 1f)
            // The outer edge cushions flush onto 0..width / 0..height without clipping outside the bezel.
            for (i in 0..IslandInkRing.SAMPLES) {
                assertTrue("outerX[$i]=${contour.outerX[i]} at ${ms}ms", contour.outerX[i] in -0.5f..(width + 0.5f))
                assertTrue("outerY[$i]=${contour.outerY[i]} at ${ms}ms", contour.outerY[i] in -0.5f..(height + 0.5f))
            }
        }
        body.snap(true, 2.0)
        body.update(2.0)
        contour.update(body, density, 1f, 1f)
        // Settled body hugs 100% of the screen (0, 0, 1280, 2772) with inward-stroked ink border.
        assertEquals(0f, body.left, 0.01f)
        assertEquals(0f, body.top, 0.01f)
        assertEquals(width, body.right, 0.01f)
        assertEquals(height, body.bottom, 0.01f)
        assertEquals(bezelRadius, body.radius, 0.01f)
        var topLeftArcPoints = 0
        var bottomRightArcPoints = 0
        for (i in 0..IslandInkRing.SAMPLES) {
            if (contour.outerX[i] < bezelRadius && contour.outerY[i] < bezelRadius) topLeftArcPoints++
            if (contour.outerX[i] > width - bezelRadius && contour.outerY[i] > height - bezelRadius) bottomRightArcPoints++
            val strokePx = kotlin.math.hypot(contour.outerX[i] - contour.innerX[i], contour.outerY[i] - contour.innerY[i])
            assertTrue("inward bezel stroke[$i]=$strokePx", strokePx >= 2f * density)
        }
        assertTrue("topLeftArcPoints=$topLeftArcPoints", topLeftArcPoints >= 5)
        assertTrue("bottomRightArcPoints=$bottomRightArcPoints", bottomRightArcPoints >= 5)

        // During close, when the sheet is still ~100dp tall, inkTransfer must already remain high
        // so it never renders a thick 2.1dp ring stroke on a medium-sized rectangle.
        body.close(2.0)
        var checkedMidRetract = false
        for (ms in 2000..2600 step 10) {
            body.update(ms / 1000.0)
            val spanDp = (body.bottom - body.top) / density
            if (spanDp in 95f..125f) {
                checkedMidRetract = true
                assertTrue("inkTransfer=${body.inkTransfer} at span=${spanDp}dp", body.inkTransfer > 0.65f)
            }
        }
        assertTrue(checkedMidRetract)
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun liveDragUpdatesVisualSelectionImmediatelyDuringMoveEvents() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        val context = RuntimeEnvironment.getApplication()
        val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        val view = ScreenSelectionView(context, bitmap, IslandPalette.Light, onConfirm = {}, onCancel = {})
        try {
            assertTrue(view.attach())
            view.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 400, 800)
            repeat(80) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(16))
            }
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float) {
                val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                view.dispatchTouchEvent(ev)
                ev.recycle()
            }
            send(MotionEvent.ACTION_DOWN, 60f, 120f)
            assertTrue(view.hasLiveDragFeedback)
            send(MotionEvent.ACTION_MOVE, 140f, 220f)
            // Intermediate MOVE must immediately expose the live selection rectangle before ACTION_UP.
            assertEquals(RectF(60f, 120f, 140f, 220f), view.visualSelectionBounds)
            send(MotionEvent.ACTION_MOVE, 240f, 360f)
            assertEquals(RectF(60f, 120f, 240f, 360f), view.visualSelectionBounds)
            send(MotionEvent.ACTION_UP, 240f, 360f)
            assertFalse(view.hasLiveDragFeedback)
            assertEquals(RectF(60f, 120f, 240f, 360f), view.visualSelectionBounds)

            // Edge-to-edge drag starting at the outermost pixel (0, 0) to (400, 800) must also succeed!
            send(MotionEvent.ACTION_DOWN, 0f, 0f)
            send(MotionEvent.ACTION_MOVE, 400f, 800f)
            assertEquals(RectF(0f, 0f, 400f, 800f), view.visualSelectionBounds)
            send(MotionEvent.ACTION_UP, 400f, 800f)
            repeat(20) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(16))
            }
            assertEquals(RectF(0f, 0f, 400f, 800f), view.visualSelectionBounds)
        } finally {
            view.destroy()
            org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(1))
            org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        }
    }

    @Test fun immersiveDetectionIgnoresEdgeToEdgeAppsWhenTopStatusBarRemainsVisible() {
        assertFalse(
            "portrait edge-to-edge app or launcher with visible StatusBar must never hide the island",
            IslandTouchService.computeImmersiveStatusBarHidden(
                hasVisibleTopStatusBar = true,
                hasFocusedApplicationWindow = true,
            )
        )
        assertTrue(
            "genuine immersive app with hidden StatusBar hides the island",
            IslandTouchService.computeImmersiveStatusBarHidden(
                hasVisibleTopStatusBar = false,
                hasFocusedApplicationWindow = true,
            )
        )
        assertFalse(
            IslandTouchService.computeImmersiveStatusBarHidden(
                hasVisibleTopStatusBar = false,
                hasFocusedApplicationWindow = false,
            )
        )
    }
}
