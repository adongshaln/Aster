package com.adong.adchat.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Keeps the lens black and fixed; only the independent ink stroke breathes or transfers away. */
internal class IslandInkRingView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private var pathDirty = true
    private val cosines = FloatArray(IslandInkRing.SAMPLES + 1)
    private val sines = FloatArray(IslandInkRing.SAMPLES + 1)
    private val inner = FloatArray(IslandInkRing.SAMPLES + 1)
    private val weights = FloatArray(IslandInkRing.SAMPLES + 1)

    /** Ring colour (clay). */
    var inkColor: Int = 0xFFD97757.toInt()
        set(value) {
            if (field != value) {
                field = value
                inkPaint.color = value
                updateInkAlpha()
                invalidate()
            }
        }

    /** The expanded surface owns the continuous outline while the resident view keeps the lens. */
    var inkVisible: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Opacity belongs to the stroke, never to the black camera backing or this View's alpha. */
    var inkAlpha: Float = 1f
        set(value) {
            val bounded = value.coerceIn(0f, 1f)
            if (field != bounded) {
                field = bounded
                // Retain the precise value for outline handoff, but only redraw a visible change.
                val alphaChanged = updateInkAlpha()
                if (inkVisible && alphaChanged) invalidate()
            }
        }

    /** Stroke weight multiplier: 1 at rest, heavier on press and on the closing gulp. */
    var ink: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                pathDirty = true
                if (inkVisible) invalidate()
            }
        }

    /** Ring scale for the "nothing new" bounces; drawn as max(1, value) so it never shrinks. */
    var bounce: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                pathDirty = true
                if (inkVisible) invalidate()
            }
        }

    init {
        inkPaint.color = inkColor
        updateInkAlpha()
        for (i in 0..IslandInkRing.SAMPLES) {
            val u = i.toFloat() / IslandInkRing.SAMPLES
            val a = IslandInkRing.angle(u)
            cosines[i] = cos(a)
            sines[i] = sin(a)
            inner[i] = IslandInkRing.innerRadius(u)
            weights[i] = IslandInkRing.weight(u)
        }
    }

    private fun updateInkAlpha(): Boolean {
        val alpha = (Color.alpha(inkColor) * inkAlpha).toInt()
        if (inkPaint.alpha == alpha) return false
        inkPaint.alpha = alpha
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pathDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, IslandInkRing.CORE_RADIUS_DP * density, corePaint)
        if (!inkVisible) return
        if (pathDirty) rebuildPath(cx, cy)
        canvas.drawPath(path, inkPaint)
    }

    private fun rebuildPath(cx: Float, cy: Float) {
        val scale = max(1f, bounce) * density
        val inkWeight = max(0f, ink)
        path.rewind()
        for (i in 0..IslandInkRing.SAMPLES) {
            val r = (inner[i] + weights[i] * inkWeight) * scale
            val x = cx + cosines[i] * r
            val y = cy + sines[i] * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (i in IslandInkRing.SAMPLES downTo 0) {
            val r = inner[i] * scale
            path.lineTo(cx + cosines[i] * r, cy + sines[i] * r)
        }
        path.close()
        pathDirty = false
    }
}
