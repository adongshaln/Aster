package com.adong.adchat.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
    }
    private val density = resources.displayMetrics.density
    private var accentColor = 0xFF38BDF8.toInt()
    private var progress = 0f
    private val finalBodyColor = Color.rgb(16, 19, 26)
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

    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        setProgress(progress)
        invalidate()
    }

    fun setProgress(value: Float) {
        progress = value
        geometry.update(value)
        bounds.set(geometry.left, geometry.top, geometry.right, geometry.bottom)
        clip.rewind()
        clip.addRoundRect(bounds, geometry.radius, geometry.radius, Path.Direction.CW)
        // Keep the seed opaque and fade only the shared shell's fill/border channels.
        val fill = value.coerceIn(0f, 1f)
        bodyPaint.color = Color.rgb(
            (Color.red(finalBodyColor) * fill).toInt(),
            (Color.green(finalBodyColor) * fill).toInt(),
            (Color.blue(finalBodyColor) * fill).toInt()
        )
        bodyPaint.alpha = 255
        val borderAlpha = (0x66 * fill).toInt()
        borderPaint.color = (accentColor and 0x00FFFFFF) or (borderAlpha shl 24)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        canvas.drawRoundRect(bounds, geometry.radius, geometry.radius, bodyPaint)
        if (geometry.radius > density * 4f && borderPaint.alpha > 0) {
            val inset = borderPaint.strokeWidth / 2f
            bounds.inset(inset, inset)
            canvas.drawRoundRect(
                bounds,
                (geometry.radius - inset).coerceAtLeast(0f),
                (geometry.radius - inset).coerceAtLeast(0f),
                borderPaint
            )
            bounds.inset(-inset, -inset)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!active) return
        val save = canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
