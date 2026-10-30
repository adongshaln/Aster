package com.adong.adchat.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.widget.FrameLayout

/** One evolving outline owns the pen stroke, paper fill and content clipping. */
internal class IslandSurfaceView(context: Context) : FrameLayout(context) {
    val body = IslandJellyBody()
    private val density = resources.displayMetrics.density
    private val contour = IslandInkContour()
    private val clip = Path()
    private val inkPath = Path()
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var palette = IslandPalette.Light
    private var shadowStep = -1
    private var shapeDirty = true
    private var inkWeight = 1f
    private var inkBounce = 1f
    private var inkAlpha = 1f
    internal val currentPalette: IslandPalette get() = palette
    var active = false
        set(value) {
            field = value
            invalidate()
        }
    var borderClayMix = 0.28f
        set(value) {
            val bounded = value.coerceIn(0f, 1f)
            if (field != bounded) {
                field = bounded
                shapeDirty = true
                invalidate()
            }
        }
    var borderAlphaDrop = 0.12f
        set(value) {
            val bounded = value.coerceIn(0f, 1f)
            if (field != bounded) {
                field = bounded
                shapeDirty = true
                invalidate()
            }
        }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setPalette(IslandPalette.Light)
    }

    fun setPalette(palette: IslandPalette) {
        this.palette = palette
        paperPaint.color = palette.paper
        shadowStep = -1
        shapeDirty = true
        invalidate()
    }

    fun setInkState(weight: Float, bounce: Float, alpha: Float) {
        if (inkWeight == weight && inkBounce == bounce && inkAlpha == alpha) return
        inkWeight = weight
        inkBounce = bounce
        inkAlpha = alpha
        shapeDirty = true
        invalidate()
    }

    /** Geometry changes, never the measured card. All paths and arrays are reused. */
    fun render(now: Double) {
        body.update(now)
        shapeDirty = true
        invalidate()
    }

    private fun updateShape() {
        if (!shapeDirty) return
        shapeDirty = false
        contour.update(body, density, inkWeight, inkBounce)
        clip.rewind()
        inkPath.rewind()
        for (i in 0..IslandInkRing.SAMPLES) {
            val x = contour.outerX[i]
            val y = contour.outerY[i]
            if (i == 0) {
                clip.moveTo(x, y)
                inkPath.moveTo(x, y)
            } else {
                clip.lineTo(x, y)
                inkPath.lineTo(x, y)
            }
        }
        clip.close()
        for (i in IslandInkRing.SAMPLES downTo 0) inkPath.lineTo(contour.innerX[i], contour.innerY[i])
        inkPath.close()
        paperPaint.alpha = (body.paperAlpha * 255f).toInt().coerceIn(0, 255)
        inkPaint.color = mixColor(palette.clay, palette.edge, body.inkTransfer * borderClayMix)
        inkPaint.alpha = (inkAlpha * (1f - body.inkTransfer * borderAlphaDrop) * 255f).toInt().coerceIn(0, 255)
        applyShadow()
    }

    private fun applyShadow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val step = (body.paperAlpha * 32f).toInt()
        if (step == shadowStep) return
        shadowStep = step
        if (step == 0) {
            paperPaint.clearShadowLayer()
        } else {
            val alpha = (((palette.shadow ushr 24) and 0xFF) * step / 32f).toInt()
            paperPaint.setShadowLayer(14f * density, 0f, 6f * density,
                (palette.shadow and 0x00FFFFFF) or (alpha shl 24))
        }
    }

    /** Match the winding fill, including the pen's small overlap at the upper-right shoulder. */
    fun containsPoint(x: Float, y: Float): Boolean {
        if (!active) return false
        updateShape()
        var winding = 0
        var previous = IslandInkRing.SAMPLES
        for (i in 0..IslandInkRing.SAMPLES) {
            val x0 = contour.outerX[previous]
            val y0 = contour.outerY[previous]
            val x1 = contour.outerX[i]
            val y1 = contour.outerY[i]
            val side = (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0)
            if (y0 <= y && y1 > y && side > 0f) winding++
            else if (y0 > y && y1 <= y && side < 0f) winding--
            previous = i
        }
        return winding != 0
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        updateShape()
        if (paperPaint.alpha > 0) canvas.drawPath(clip, paperPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!active) return
        updateShape()
        val save = canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
        canvas.drawPath(inkPath, inkPaint)
    }

    private fun mixColor(from: Int, to: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val start = (from ushr shift) and 0xff
            return (start + (((to ushr shift) and 0xff) - start) * amount).toInt()
        }
        return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
