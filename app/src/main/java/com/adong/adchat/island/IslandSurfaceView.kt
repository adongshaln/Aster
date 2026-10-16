package com.adong.adchat.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.widget.FrameLayout

/**
 * The paper tag: one rounded sheet with a fine edge and a soft shadow that clips the card content.
 * Its shape comes from [body]; layout never changes while the outline moves.
 */
internal class IslandSurfaceView(context: Context) : FrameLayout(context) {
    val body = IslandJellyBody()
    private val density = resources.displayMetrics.density
    private val bounds = RectF()
    private val edgeBounds = RectF()
    private val clip = Path()
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private var palette = IslandPalette.Light
    private var shadowStep = -1
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
        setPalette(IslandPalette.Light)
    }

    fun setPalette(palette: IslandPalette) {
        this.palette = palette
        paperPaint.color = palette.paper
        edgePaint.color = palette.edge
        shadowStep = -1
        applyShadow()
        invalidate()
    }

    /** Samples the body at [now] (seconds) and redraws; called once per frame by the island clock. */
    fun render(now: Double) {
        body.update(now)
        bounds.set(body.left, body.top, body.right, body.bottom)
        clip.rewind()
        clip.addRoundRect(bounds, body.radius, body.radius, Path.Direction.CW)
        applyShadow()
        invalidate()
    }

    private fun applyShadow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // Fade the shadow in as the tag leaves the core, so no shadow halo rings the lens at rest.
        val fraction = ((bounds.width() - 26f * density) / (48f * density)).coerceIn(0f, 1f)
        val step = (fraction * 32f).toInt()
        if (step == shadowStep) return
        shadowStep = step
        if (step == 0) {
            paperPaint.clearShadowLayer()
        } else {
            val alpha = (((palette.shadow ushr 24) and 0xFF) * step / 32f).toInt()
            paperPaint.setShadowLayer(14f * density, 0f, 6f * density, (palette.shadow and 0x00FFFFFF) or (alpha shl 24))
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        canvas.drawRoundRect(bounds, body.radius, body.radius, paperPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!active) return
        val save = canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
        // The fine edge sits on top of the content, inset half a stroke so it stays on the sheet.
        val inset = edgePaint.strokeWidth / 2f
        edgeBounds.set(bounds)
        edgeBounds.inset(inset, inset)
        val r = (body.radius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(edgeBounds, r, r, edgePaint)
    }
}
