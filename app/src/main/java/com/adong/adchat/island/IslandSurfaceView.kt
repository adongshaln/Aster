package com.adong.adchat.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.FrameLayout

/** One opaque island, not a second popup. Layout stays fixed while its rounded outline morphs. */
internal class IslandSurfaceView(context: Context) : FrameLayout(context) {
    val geometry = IslandMorphGeometry()
    private val bounds = RectF()
    private val clip = Path()
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    var active = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setProgress(value: Float) {
        geometry.update(value)
        bounds.set(geometry.left, geometry.top, geometry.right, geometry.bottom)
        clip.rewind()
        clip.addRoundRect(bounds, geometry.radius, geometry.radius, Path.Direction.CW)
        // Opaque seed is identical to the existing hole. Keep signed closing overshoot visible;
        // alpha clamped to progress would silently delete the final 20% of the closing morph.
        bodyPaint.alpha = 255
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        canvas.drawRoundRect(bounds, geometry.radius, geometry.radius, bodyPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!active) return
        val save = canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
