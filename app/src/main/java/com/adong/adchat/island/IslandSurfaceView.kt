package com.adong.adchat.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.FrameLayout

/** One pure-black island, not a second popup. Layout stays fixed while its rounded outline morphs and clips the content. */
internal class IslandSurfaceView(context: Context) : FrameLayout(context) {
    val geometry = IslandMorphGeometry()
    private val bounds = RectF()
    private val clip = Path()
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
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
