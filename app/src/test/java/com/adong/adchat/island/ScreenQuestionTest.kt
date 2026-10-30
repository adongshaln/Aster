package com.adong.adchat.island

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.data.ChatMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
