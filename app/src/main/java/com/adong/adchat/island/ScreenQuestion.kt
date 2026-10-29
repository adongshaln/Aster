package com.adong.adchat.island

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.data.ChatMessage
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Only the confirmed crop survives selection. No full-screen image or URI is persisted. */
internal data class ScreenQuestionSession(
    val preview: Bitmap,
    val attachment: ChatImageAttachment,
    val history: List<ChatMessage> = emptyList(),
    val question: String = "",
    val draft: String = "",
    val task: StarIslandTaskState? = null,
)

internal data class ScreenQuestionRequest(
    val attachment: ChatImageAttachment,
    val history: List<ChatMessage>,
)

/** Map a selection through the actual fitted image, including letterboxing/rotation dimensions. */
internal fun screenCropBounds(selection: RectF, imageBounds: RectF, width: Int, height: Int): Rect {
    require(width > 0 && height > 0 && imageBounds.width() > 0 && imageBounds.height() > 0)
    val clipped = RectF(selection)
    require(clipped.intersect(imageBounds) && clipped.width() > 0 && clipped.height() > 0) { "请先框选要提问的区域" }
    val left = floor((clipped.left - imageBounds.left) * width / imageBounds.width()).toInt().coerceIn(0, width - 1)
    val top = floor((clipped.top - imageBounds.top) * height / imageBounds.height()).toInt().coerceIn(0, height - 1)
    val right = ceil((clipped.right - imageBounds.left) * width / imageBounds.width()).toInt().coerceIn(left + 1, width)
    val bottom = ceil((clipped.bottom - imageBounds.top) * height / imageBounds.height()).toInt().coerceIn(top + 1, height)
    return Rect(left, top, right, bottom)
}

internal fun prepareScreenQuestion(bitmap: Bitmap, crop: Rect): ScreenQuestionSession {
    require(crop.width() > 0 && crop.height() > 0 && Rect(0, 0, bitmap.width, bitmap.height).contains(crop))
    val selected = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
    val scale = minOf(1f, 1600f / maxOf(selected.width, selected.height))
    val preview = if (scale < 1f) Bitmap.createScaledBitmap(selected,
        (selected.width * scale).roundToInt().coerceAtLeast(1),
        (selected.height * scale).roundToInt().coerceAtLeast(1), true) else selected
    val bytes = ByteArrayOutputStream().use { output ->
        check(preview.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "无法准备选区图片" }
        output.toByteArray()
    }
    return ScreenQuestionSession(preview, ChatImageAttachment(
        uri = "", name = "星岛选区.jpg", mimeType = "image/jpeg", size = bytes.size.toLong(),
        width = preview.width, height = preview.height, bytes = bytes,
    ))
}

/** A short isolated conversation; attach the crop once, even after older turns are dropped. */
internal fun screenQuestionHistory(question: String, request: ScreenQuestionRequest): List<ChatMessage> {
    require(question.isNotBlank() && question.length <= 2_000) { "问题请控制在 2,000 个字符以内" }
    require(request.attachment.bytes?.isNotEmpty() == true) { "选区图片已失效，请重新框选" }
    val previous = request.history.takeLast(8).map { it.copy(attachments = emptyList()) }
    return (previous + ChatMessage(role = "user", content = question)).mapIndexed { index, message ->
        if (index == 0) message.copy(attachments = listOf(request.attachment)) else message
    }
}
