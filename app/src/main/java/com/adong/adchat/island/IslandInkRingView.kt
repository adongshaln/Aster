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

/** Draws the black lens backing and the clay pen ring. State is three floats; drawing has no logic. */
internal class IslandInkRingView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
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
                invalidate()
            }
        }

    /** Stroke weight multiplier: 1 at rest, heavier on press and on the closing gulp. */
    var ink: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Ring scale for the "nothing new" bounces; drawn as max(1, value) so it never shrinks. */
    var bounce: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        inkPaint.color = inkColor
        for (i in 0..IslandInkRing.SAMPLES) {
            val u = i.toFloat() / IslandInkRing.SAMPLES
            val a = IslandInkRing.angle(u)
            cosines[i] = cos(a)
            sines[i] = sin(a)
            inner[i] = IslandInkRing.innerRadius(u)
            weights[i] = IslandInkRing.weight(u)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, IslandInkRing.CORE_RADIUS_DP * density, corePaint)
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
        canvas.drawPath(path, inkPaint)
    }
}
