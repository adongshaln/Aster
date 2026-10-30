package com.adong.adchat.island

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
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

/** Frozen frame selection revealed and retracted by the resident island ink contour. */
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
    private fun dp(n: Float) = (n * density).roundToInt()
    private var attached = false
    private var submitted = false
    private var interactive = false
    private var dismissing = false
    private var geometryReady = false
    private var animationClock: TimeAnimator? = null
    private var transitionEnd: (() -> Unit)? = null
    private val morph: IslandSurfaceView
    private val lensCore: View
    private val confirm: TextView
    private val crop: CropCanvas
    private val hint: TextView
    private val actions: LinearLayout

    init {
        isFocusableInTouchMode = true
        morph = IslandSurfaceView(context).apply {
            setPalette(palette)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        addView(morph, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        crop = CropCanvas(context)
        morph.addView(crop, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        lensCore = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.BLACK)
            }
        }
        morph.addView(lensCore, android.widget.FrameLayout.LayoutParams(dp(26), dp(26)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(25.5f) - dp(13)
        })

        hint = TextView(context).apply {
            text = "框选要提问的区域\n拖动边角调整，拖动框内移动"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(palette.ink)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = paper()
            alpha = 0f
        }
        morph.addView(hint, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(56)
            leftMargin = dp(20)
            rightMargin = dp(20)
        })

        actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = paper()
            alpha = 0f
        }
        fun button(label: String, primary: Boolean = false, action: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(if (primary) palette.onClay else palette.ink)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (primary) palette.clay else palette.paper)
            }
            setOnClickListener { if (!submitted && interactive) action() }
            setOnTouchListener { view, event ->
                if (!interactive) return@setOnTouchListener true
                val target = when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> 0.96f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
                    else -> return@setOnTouchListener false
                }
                view.animate().cancel()
                view.animate().scaleX(target).scaleY(target)
                    .setDuration(if (target < 1f) 100L else 220L)
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f))
                    .start()
                false
            }
        }
        val cancel = button("取消") { dismiss(onCancel) }
        val reset = button("重选") { crop.resetSelection() }
        confirm = button("就问这里", true) {
            val bounds = crop.selectedBounds() ?: return@button
            submitted = true
            interactive = false
            confirm.isEnabled = false
            confirm.text = "正在准备…"
            onConfirm(bounds)
            dismiss()
        }.apply { isEnabled = false; alpha = 0.45f }
        listOf(cancel, reset, confirm).forEachIndexed { i, button ->
            actions.addView(button, LinearLayout.LayoutParams(0, dp(48), if (i == 2) 1.4f else 1f))
        }
        morph.addView(actions, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(32)
        })
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                (hint.layoutParams as android.widget.FrameLayout.LayoutParams).also {
                    it.topMargin = maxOf(dp(48), bars.top + dp(8)); hint.layoutParams = it
                }
                (actions.layoutParams as android.widget.FrameLayout.LayoutParams).also {
                    it.bottomMargin = bars.bottom + dp(12); actions.layoutParams = it
                }
            }
            insets
        }
    }

    private fun paper() = android.graphics.drawable.GradientDrawable().apply {
        setColor(palette.paper)
        setStroke(dp(1), palette.edge)
        cornerRadius = dp(28).toFloat()
    }

    fun attach(): Boolean {
        val params = WindowManager.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
        if (attached) {
            requestFocus()
            if (geometryReady && !dismissing && animationClock == null && !interactive) {
                animateMorph(open = true) { interactive = true; updateContent() }
            }
        }
        return attached
    }

    fun destroy() {
        dismissing = true
        animationClock?.cancel()
        animationClock = null
        if (attached) runCatching { manager.removeViewImmediate(this) }
        attached = false
    }

    /** Retract the selected screen back to the camera ring before changing stages. */
    fun dismiss(after: () -> Unit = {}) {
        if (!attached) {
            after()
            return
        }
        if (dismissing) {
            val previous = transitionEnd
            transitionEnd = { previous?.invoke(); after() }
            return
        }
        dismissing = true
        interactive = false
        animateMorph(open = false, after)
    }

    fun disposeImmediately() = destroy()

    private fun animateMorph(open: Boolean, after: () -> Unit = {}) {
        transitionEnd = after
        val now = SystemClock.uptimeMillis() / 1000.0
        if (!geometryReady || !ValueAnimator.areAnimatorsEnabled()) {
            morph.body.snap(open, now)
            morph.render(now)
            finishMorph(open)
            return
        }
        if (open) morph.body.open(now) else morph.body.close(now)
        animationClock?.cancel()
        animationClock = TimeAnimator().apply {
            setTimeListener { animator, _, _ ->
                val frameTime = SystemClock.uptimeMillis() / 1000.0
                morph.render(frameTime)
                updateContent()
                if (morph.body.isAtRest(frameTime)) {
                    morph.body.snap(open, frameTime)
                    morph.render(frameTime)
                    cancel()
                    animationClock = null
                    finishMorph(open)
                }
            }
            start()
        }
    }

    private fun finishMorph(open: Boolean) {
        interactive = open && !dismissing
        updateContent()
        val after = transitionEnd
        transitionEnd = null
        if (!open) {
            destroy()
            after?.invoke()
        } else after?.invoke()
    }

    private fun updateContent() {
        val visible = morph.body.contentAlpha.coerceIn(0f, 1f)
        // The captured screen is clipped by the growing ink contour itself. Do not wait for the
        // card's text fade threshold or the full-screen sheet would pop in near the end.
        crop.alpha = 1f
        morph.setInkState(1f, 1f, 1f)
        lensCore.alpha = (1f - morph.body.progress * 5f).coerceIn(0f, 1f)
        hint.alpha = visible
        actions.alpha = visible
        hint.isEnabled = interactive
        actions.isEnabled = interactive
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        morph.body.configure(w / 2f, (25.5f * density), dp(13).toFloat(),
            0f, 0f, w.toFloat(), h.toFloat(), dp(40).toFloat())
        morph.active = true
        morph.body.snap(false, SystemClock.uptimeMillis() / 1000.0)
        morph.render(SystemClock.uptimeMillis() / 1000.0)
        geometryReady = true
        if (attached && !dismissing && !interactive) {
            animateMorph(open = true) { interactive = true; updateContent() }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!interactive || dismissing) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !morph.containsPoint(event.x, event.y)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !dismissing) dismiss(onCancel)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private inner class CropCanvas(context: Context) : View(context) {
        private val imageBounds = RectF()
        private val selection = RectF()
        private val dragStart = RectF()
        private val visualSelection = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var downX = 0f
        private var downY = 0f
        private var drag = 0
        private var selectionAnimator: ValueAnimator? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val scale = minOf(w.toFloat() / screenshot.width, h.toFloat() / screenshot.height)
            val bw = screenshot.width * scale
            val bh = screenshot.height * scale
            imageBounds.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
        }

        fun resetSelection() {
            selection.setEmpty()
            animateSelectionTo(RectF())
            setConfirmEnabled(false)
        }

        private fun setConfirmEnabled(enabled: Boolean) {
            if (confirm.isEnabled == enabled) return
            confirm.isEnabled = enabled
            confirm.animate().cancel()
            confirm.animate().alpha(if (enabled) 1f else 0.45f)
                .setDuration(180L)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f))
                .start()
        }

        fun selectedBounds(): Rect? = if (selection.width() < dp(16) || selection.height() < dp(16)) null
            else screenCropBounds(selection, imageBounds, screenshot.width, screenshot.height)

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(android.graphics.Color.BLACK)
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.WHITE
            canvas.drawBitmap(screenshot, null, imageBounds, paint)
            paint.color = 0x99000000.toInt()
            val shown = visualSelection
            if (shown.isEmpty) canvas.drawRect(imageBounds, paint) else {
                canvas.drawRect(imageBounds.left, imageBounds.top, imageBounds.right, shown.top, paint)
                canvas.drawRect(imageBounds.left, shown.bottom, imageBounds.right, imageBounds.bottom, paint)
                canvas.drawRect(imageBounds.left, shown.top, shown.left, shown.bottom, paint)
                canvas.drawRect(shown.right, shown.top, imageBounds.right, shown.bottom, paint)
                paint.color = palette.clay
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                canvas.drawRoundRect(shown, dp(6).toFloat(), dp(6).toFloat(), paint)
                paint.style = Paint.Style.FILL
                for (x in listOf(shown.left, shown.right)) for (y in listOf(shown.top, shown.bottom)) {
                    canvas.drawCircle(x, y, dp(6).toFloat(), paint)
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (submitted || !interactive) return true
            val x = event.x.coerceIn(imageBounds.left, imageBounds.right)
            val y = event.y.coerceIn(imageBounds.top, imageBounds.bottom)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y; dragStart.set(selection)
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
                            val dx = (x - downX).coerceIn(imageBounds.left - dragStart.left, imageBounds.right - dragStart.right)
                            val dy = (y - downY).coerceIn(imageBounds.top - dragStart.top, imageBounds.bottom - dragStart.bottom)
                            selection.set(dragStart); selection.offset(dx, dy)
                        }
                        else -> {
                            if (drag and 1 != 0) selection.left = x.coerceAtMost(selection.right - minimum)
                            if (drag and 4 != 0) selection.right = x.coerceAtLeast(selection.left + minimum)
                            if (drag and 2 != 0) selection.top = y.coerceAtMost(selection.bottom - minimum)
                            if (drag and 8 != 0) selection.bottom = y.coerceAtLeast(selection.top + minimum)
                            selection.intersect(imageBounds)
                        }
                    }
                    visualSelection.set(selection)
                }
                MotionEvent.ACTION_UP -> {
                    animateSelectionTo(selection)
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    selection.set(dragStart)
                    animateSelectionTo(selection)
                }
            }
            setConfirmEnabled(selectedBounds() != null)
            morph.invalidate()
            return true
        }

        private fun animateSelectionTo(target: RectF) {
            val from = RectF(visualSelection)
            val centerX = if (target.isEmpty) from.centerX() else target.centerX()
            val centerY = if (target.isEmpty) from.centerY() else target.centerY()
            val end = if (target.isEmpty) RectF(centerX, centerY, centerX, centerY) else RectF(target)
            selectionAnimator?.cancel()
            if (from.isEmpty && end.isEmpty) {
                visualSelection.setEmpty()
                invalidate()
                return
            }
            selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180L
                addUpdateListener { animator ->
                    val p = IslandSpring.progress(animator.animatedFraction)
                    visualSelection.set(lerp(from.left, end.left, p), lerp(from.top, end.top, p),
                        lerp(from.right, end.right, p), lerp(from.bottom, end.bottom, p))
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (target.isEmpty) visualSelection.setEmpty() else visualSelection.set(target)
                        invalidate()
                    }
                })
                start()
            }
        }

        private fun lerp(from: Float, to: Float, p: Float) = from + (to - from) * p
    }
}
