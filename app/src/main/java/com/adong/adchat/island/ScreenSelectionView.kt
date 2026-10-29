package com.adong.adchat.island

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Frozen frame selection. All editing happens locally over this captured frame. */
internal class ScreenSelectionView(
    context: Context,
    private val screenshot: Bitmap,
    private val palette: IslandPalette,
    private val onConfirm: (Rect) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).roundToInt()
    private var attached = false
    private var submitted = false
    private val confirm: TextView
    private val crop: CropCanvas

    init {
        isFocusableInTouchMode = true
        crop = CropCanvas(context)
        addView(crop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val hint = TextView(context).apply {
            text = "框选要提问的区域\n拖动边角调整，拖动框内移动"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(palette.ink)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = paper()
        }
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(56)
            leftMargin = dp(20)
            rightMargin = dp(20)
        })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = paper()
        }
        fun button(label: String, primary: Boolean = false, action: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(if (primary) palette.onClay else palette.ink)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (primary) palette.clay else palette.paper)
            }
            setOnClickListener { if (!submitted) action() }
        }
        val cancel = button("取消", action = onCancel)
        val reset = button("重选") { crop.resetSelection() }
        confirm = button("就问这里", true) {
            val bounds = crop.selectedBounds() ?: return@button
            submitted = true
            confirm.isEnabled = false
            confirm.text = "正在准备…"
            onConfirm(bounds)
        }.apply { isEnabled = false; alpha = 0.45f }
        listOf(cancel, reset, confirm).forEachIndexed { i, button ->
            actions.addView(button, LinearLayout.LayoutParams(0, dp(48), if (i == 2) 1.4f else 1f))
        }
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(32)
        })
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                (hint.layoutParams as LayoutParams).also { it.topMargin = maxOf(dp(48), bars.top + dp(8)); hint.layoutParams = it }
                (actions.layoutParams as LayoutParams).also { it.bottomMargin = bars.bottom + dp(12); actions.layoutParams = it }
            }
            insets
        }
    }

    private fun paper() = GradientDrawable().apply {
        setColor(palette.paper)
        setStroke(dp(1), palette.edge)
        cornerRadius = dp(28).toFloat()
    }

    fun attach(): Boolean {
        val params = WindowManager.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            setTitle("Aster Star Island screen selection")
        }
        attached = runCatching { manager.addView(this, params) }.isSuccess
        if (attached) requestFocus()
        return attached
    }

    fun destroy() {
        if (attached) runCatching { manager.removeViewImmediate(this) }
        attached = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onCancel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private inner class CropCanvas(context: Context) : View(context) {
        private val imageBounds = RectF()
        private val selection = RectF()
        private val start = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var downX = 0f
        private var downY = 0f
        // 1/2/4/8 = left/top/right/bottom; 16 = move; 0 = draw a new rectangle.
        private var drag = 0

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val scale = minOf(w.toFloat() / screenshot.width, h.toFloat() / screenshot.height)
            val bw = screenshot.width * scale
            val bh = screenshot.height * scale
            imageBounds.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
            resetSelection()
        }

        fun resetSelection() {
            selection.setEmpty()
            confirm.isEnabled = false
            confirm.alpha = 0.45f
            invalidate()
        }

        fun selectedBounds(): Rect? = if (selection.width() < dp(16) || selection.height() < dp(16)) null
            else screenCropBounds(selection, imageBounds, screenshot.width, screenshot.height)

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(android.graphics.Color.BLACK)
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.WHITE
            canvas.drawBitmap(screenshot, null, imageBounds, paint)
            paint.color = 0x99000000.toInt()
            if (selection.isEmpty) canvas.drawRect(imageBounds, paint) else {
                canvas.drawRect(imageBounds.left, imageBounds.top, imageBounds.right, selection.top, paint)
                canvas.drawRect(imageBounds.left, selection.bottom, imageBounds.right, imageBounds.bottom, paint)
                canvas.drawRect(imageBounds.left, selection.top, selection.left, selection.bottom, paint)
                canvas.drawRect(selection.right, selection.top, imageBounds.right, selection.bottom, paint)
                paint.color = palette.clay
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                canvas.drawRoundRect(selection, dp(6).toFloat(), dp(6).toFloat(), paint)
                paint.style = Paint.Style.FILL
                for (x in listOf(selection.left, selection.right)) for (y in listOf(selection.top, selection.bottom)) {
                    canvas.drawCircle(x, y, dp(6).toFloat(), paint)
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (submitted) return true
            val x = event.x.coerceIn(imageBounds.left, imageBounds.right)
            val y = event.y.coerceIn(imageBounds.top, imageBounds.bottom)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y; start.set(selection)
                    val hit = dp(24)
                    drag = 0
                    if (!selection.isEmpty && x in (selection.left - hit)..(selection.right + hit) &&
                        y in (selection.top - hit)..(selection.bottom + hit)) {
                        if (abs(x - selection.left) < hit) drag = drag or 1
                        else if (abs(x - selection.right) < hit) drag = drag or 4
                        if (abs(y - selection.top) < hit) drag = drag or 2
                        else if (abs(y - selection.bottom) < hit) drag = drag or 8
                        if (drag == 0 && selection.contains(x, y)) drag = 16
                    }
                    if (drag == 0) selection.set(x, y, x, y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val minimum = dp(16).toFloat()
                    when (drag) {
                        0 -> selection.set(minOf(x, downX), minOf(y, downY), maxOf(x, downX), maxOf(y, downY))
                        16 -> {
                            val dx = (x - downX).coerceIn(imageBounds.left - start.left, imageBounds.right - start.right)
                            val dy = (y - downY).coerceIn(imageBounds.top - start.top, imageBounds.bottom - start.bottom)
                            selection.set(start); selection.offset(dx, dy)
                        }
                        else -> {
                            if (drag and 1 != 0) selection.left = x.coerceAtMost(selection.right - minimum)
                            if (drag and 4 != 0) selection.right = x.coerceAtLeast(selection.left + minimum)
                            if (drag and 2 != 0) selection.top = y.coerceAtMost(selection.bottom - minimum)
                            if (drag and 8 != 0) selection.bottom = y.coerceAtLeast(selection.top + minimum)
                            selection.intersect(imageBounds)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> performClick()
                MotionEvent.ACTION_CANCEL -> selection.set(start)
            }
            confirm.isEnabled = selectedBounds() != null
            confirm.alpha = if (confirm.isEnabled) 1f else 0.45f
            invalidate()
            return true
        }
    }
}
