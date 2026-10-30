package com.adong.adchat.island

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Frozen frame selection unfolded from and retracted into the resident island ink contour.
 *
 * Calibrated for hardware rounded corners such as Redmi Turbo 5 Max (1280×2772, 520 dpi, 48dp
 * corner radius, 25.5dp camera centre): the ink outline settles concentrically inside the bezel
 * so all four rounded corners and straight edges remain continuous and fully visible.
 */
internal class ScreenSelectionView(
    context: Context,
    private val screenshot: Bitmap,
    private val palette: IslandPalette,
    private val onConfirm: (Rect) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {
    companion object {
        private const val CUTOUT_CENTER_Y_DP = 25.5f
        /** Redmi Turbo 5 Max default hardware corner radius (156px at 3.25x = 48dp). */
        private const val DEFAULT_BEZEL_RADIUS_DP = 48f
        private const val MIN_SELECTION_DP = 16f
        private const val GULP_WIDTH_DP = 40f
        private const val GULP_KICK = 5.5f
        private val SPRING_INTERPOLATOR = android.animation.TimeInterpolator { IslandSpring.progress(it) }
    }

    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = resources.displayMetrics.density
    private fun dp(n: Int): Int = (n * density).roundToInt()
    private fun dpF(n: Float): Float = n * density

    private var attached = false
    private var submitted = false
    private var interactive = false
    private var dismissing = false
    private var geometryReady = false
    private var gulped = true
    private var detectedBezelRadiusPx = 0f
    private var animationClock: TimeAnimator? = null
    private var transitionEnd: (() -> Unit)? = null
    private val inkSpring = IslandJellySpring(1f, 0.3f, 0.46f)

    private val morph: IslandSurfaceView
    private val lensCore: View
    private val confirm: TextView
    private val reset: TextView
    private val crop: CropCanvas
    private val hint: TextView
    private val actions: LinearLayout

    internal val currentProgress: Float get() = morph.body.progress
    internal val visualSelectionBounds: RectF get() = RectF(crop.currentVisualSelection)
    internal val hasLiveDragFeedback: Boolean get() = crop.isActivelyDragging

    init {
        isFocusableInTouchMode = true
        clipChildren = false
        clipToPadding = false
        morph = IslandSurfaceView(context).apply {
            setPalette(palette)
            // Keep the terracotta pen stroke crisp and full-strength against the darkened screen.
            borderClayMix = 0f
            borderAlphaDrop = 0f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        addView(morph, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        crop = CropCanvas(context)
        morph.addView(crop, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        hint = TextView(context).apply {
            text = "拖动框选要提问的区域"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(palette.ink)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = paperPill(24f)
            alpha = 0f
            scaleX = IslandJellyBody.CONTENT_REST_SCALE
            scaleY = IslandJellyBody.CONTENT_REST_SCALE
        }
        morph.addView(hint, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(58)
            leftMargin = dp(20)
            rightMargin = dp(20)
        })

        actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = paperPill(28f)
            alpha = 0f
            scaleX = IslandJellyBody.CONTENT_REST_SCALE
            scaleY = IslandJellyBody.CONTENT_REST_SCALE
        }
        fun button(label: String, primary: Boolean = false, action: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(if (primary) palette.onClay else palette.ink)
            background = GradientDrawable().apply {
                cornerRadius = dpF(22f)
                setColor(if (primary) palette.clay else palette.well)
            }
            setOnClickListener {
                if (!submitted && interactive) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    action()
                }
            }
            setOnTouchListener { view, event ->
                if (!interactive) return@setOnTouchListener true
                val target = when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> 0.94f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
                    else -> return@setOnTouchListener false
                }
                view.animate().cancel()
                view.animate().scaleX(target).scaleY(target)
                    .setDuration(if (ValueAnimator.areAnimatorsEnabled()) IslandSpring.BUTTON_MS else 0L)
                    .setInterpolator(SPRING_INTERPOLATOR)
                    .start()
                false
            }
        }
        val cancel = button("取消") { dismiss(onCancel) }
        reset = button("重选") { crop.resetSelection() }.apply {
            isEnabled = false
            alpha = 0.45f
        }
        confirm = button("就问这里", true) {
            val bounds = crop.selectedBounds() ?: return@button
            submitted = true
            interactive = false
            confirm.isEnabled = false
            confirm.text = "正在准备…"
            onConfirm(bounds)
        }.apply {
            isEnabled = false
            alpha = 0.45f
        }
        listOf(cancel, reset, confirm).forEachIndexed { index, button ->
            actions.addView(button, LinearLayout.LayoutParams(0, dp(46), if (index == 2) 1.35f else 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        morph.addView(actions, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(18)
            rightMargin = dp(18)
            bottomMargin = dp(30)
        })

        // Keep the black camera core anchored OUTSIDE morph's moving clip, exactly like IslandView's
        // resident lens, so opening and closing unfold cleanly from behind the camera cutout.
        lensCore = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val coreDiameter = (dpF(IslandInkRing.CORE_RADIUS_DP) * 2f).roundToInt()
        addView(lensCore, LayoutParams(coreDiameter, coreDiameter).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (dpF(CUTOUT_CENTER_Y_DP) - dpF(IslandInkRing.CORE_RADIUS_DP)).roundToInt()
        })

        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                (hint.layoutParams as LayoutParams).also {
                    it.topMargin = max(dp(54), bars.top + dp(10))
                    hint.layoutParams = it
                }
                (actions.layoutParams as LayoutParams).also {
                    it.bottomMargin = max(dp(22), bars.bottom + dp(14))
                    actions.layoutParams = it
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val corner = listOf(
                    RoundedCorner.POSITION_TOP_LEFT,
                    RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_LEFT,
                    RoundedCorner.POSITION_BOTTOM_RIGHT,
                ).mapNotNull { insets.getRoundedCorner(it)?.radius?.toFloat() }.maxOrNull() ?: 0f
                if (corner > 0f && abs(corner - detectedBezelRadiusPx) > 0.5f) {
                    detectedBezelRadiusPx = corner
                    if (width > 0 && height > 0) configureSheetGeometry(width, height)
                }
            }
            insets
        }
    }

    private fun paperPill(radiusDp: Float) = GradientDrawable().apply {
        setColor(palette.paper)
        setStroke(max(1, dp(1)), palette.edge)
        cornerRadius = dpF(radiusDp)
    }

    fun attach(): Boolean {
        val params = WindowManager.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setCanPlayMoveAnimation(false)
            }
            setTitle("Aster Star Island screen selection")
        }
        attached = runCatching { manager.addView(this, params) }.isSuccess
        if (attached) {
            requestFocus()
            if (geometryReady && !dismissing && animationClock == null && !interactive) {
                animateMorph(open = true) {
                    interactive = true
                    updateContent()
                }
            }
        }
        return attached
    }

    fun destroy() {
        dismissing = true
        crop.cancelAnimations()
        animationClock?.cancel()
        animationClock = null
        if (attached) runCatching { manager.removeViewImmediate(this) }
        attached = false
    }

    /** Retract the frozen screen sheet and any active selection smoothly back into the camera ring. */
    fun dismiss(after: () -> Unit = {}) {
        if (!attached) {
            after()
            return
        }
        if (dismissing) {
            val previous = transitionEnd
            transitionEnd = {
                previous?.invoke()
                after()
            }
            return
        }
        dismissing = true
        interactive = false
        crop.onSheetDismissing()
        animateMorph(open = false, after)
    }

    fun disposeImmediately() = destroy()

    private fun nowSeconds(): Double = SystemClock.uptimeMillis() / 1000.0

    private fun animateMorph(open: Boolean, after: () -> Unit = {}) {
        transitionEnd = after
        val now = nowSeconds()
        if (!geometryReady || !ValueAnimator.areAnimatorsEnabled()) {
            inkSpring.jump(1f, now)
            morph.body.snap(open, now)
            morph.setInkState(1f, 1f, 1f)
            morph.render(now)
            finishMorph(open)
            return
        }
        gulped = open
        inkSpring.set(1f, now, 0.0, 0.32f, 0.46f)
        if (open) morph.body.open(now) else morph.body.close(now)
        animationClock?.cancel()
        animationClock = TimeAnimator().apply {
            setTimeListener { _, _, _ ->
                val frameTime = nowSeconds()
                val body = morph.body
                if (!open && !gulped && body.right - body.left < dpF(GULP_WIDTH_DP)) {
                    gulped = true
                    inkSpring.set(1f, frameTime, 0.0, 0.28f, 0.48f)
                    inkSpring.kick(GULP_KICK, frameTime)
                }
                morph.setInkState(inkSpring.value(frameTime), 1f, 1f)
                morph.render(frameTime)
                updateContent()
                // On close, hand off as soon as the sheet has tucked onto the camera core and the
                // gulp has settled; do not hold an invisible sub-seed tail for another 400ms.
                val finished = if (open) {
                    body.isAtRest(frameTime)
                } else {
                    (body.hasCollapsedToSeed(frameTime) && inkSpring.isSettled(frameTime, 0.03f)) ||
                        body.isAtRest(frameTime)
                }
                if (finished) {
                    inkSpring.jump(1f, frameTime)
                    body.snap(open, frameTime)
                    morph.setInkState(1f, 1f, 1f)
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
        } else {
            after?.invoke()
        }
    }

    private fun updateContent() {
        val body = morph.body
        val visible = body.contentAlpha.coerceIn(0f, 1f)
        val scale = body.contentScale
        val dragSuppression = 1f - crop.chromeSuppression
        crop.sheetReveal = body.heightProgress.coerceIn(0f, 1f)
        val chromeAlpha = (visible * dragSuppression).coerceIn(0f, 1f)
        hint.alpha = chromeAlpha
        hint.scaleX = scale
        hint.scaleY = scale
        actions.alpha = chromeAlpha
        actions.scaleX = scale
        actions.scaleY = scale
        hint.isEnabled = interactive
        actions.isEnabled = interactive
    }

    private fun configureSheetGeometry(w: Int, h: Int) {
        // Hug the exact physical display boundary (0, 0, w, h) and hardware rounded-corner radius
        // so 100% of the screen can be selected while the inward-stroked ink border frames the bezel.
        val bezelRadius = if (detectedBezelRadiusPx > 0f) detectedBezelRadiusPx else dpF(DEFAULT_BEZEL_RADIUS_DP)
        morph.body.configure(
            centerX = w / 2f,
            centerY = dpF(CUTOUT_CENTER_Y_DP),
            coreRadius = dpF(IslandInkRing.CORE_RADIUS_DP),
            expandedLeft = 0f,
            expandedTop = 0f,
            expandedRight = w.toFloat().coerceAtLeast(1f),
            expandedBottom = h.toFloat().coerceAtLeast(1f),
            cornerRadius = bezelRadius,
            fullScreenSheet = true,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val wasReady = geometryReady
        configureSheetGeometry(w, h)
        morph.active = true
        val now = nowSeconds()
        if (!wasReady) {
            morph.body.snap(false, now)
            morph.setInkState(1f, 1f, 1f)
            morph.render(now)
            geometryReady = true
            if (attached && !dismissing && !interactive) {
                animateMorph(open = true) {
                    interactive = true
                    updateContent()
                }
            }
        } else {
            morph.render(now)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!interactive || dismissing) return true
        // Every pixel of the frozen screen is selectable once open, including the outermost edges
        // and rounded corners; never drop edge drags via polygon hit-testing.
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !dismissing) dismiss(onCancel)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    internal inner class CropCanvas(context: Context) : View(context) {
        private val imageBounds = RectF()
        private val selection = RectF()
        private val dragStart = RectF()
        private val visualSelection = RectF()
        private val cornerPath = Path()
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
            )
        }
        private val badgeRect = RectF()

        private var downX = 0f
        private var downY = 0f
        private var activeX = 0f
        private var activeY = 0f
        private var drag = 0
        private var pointerDown = false
        private var crossedReadyThreshold = false
        private var pulseProgress = 0f
        private var touchPresence = 0f
        private var selectionAnimator: ValueAnimator? = null
        private var presenceAnimator: ValueAnimator? = null
        private var chromeAnimator: ValueAnimator? = null

        var sheetReveal = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }
        var chromeSuppression = 0f
            private set

        val currentVisualSelection: RectF get() = visualSelection
        val isActivelyDragging: Boolean get() = pointerDown

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (w <= 0 || h <= 0) return
            val scale = minOf(w.toFloat() / screenshot.width, h.toFloat() / screenshot.height)
            val bw = screenshot.width * scale
            val bh = screenshot.height * scale
            imageBounds.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
        }

        fun cancelAnimations() {
            selectionAnimator?.cancel()
            presenceAnimator?.cancel()
            chromeAnimator?.cancel()
        }

        fun onSheetDismissing() {
            pointerDown = false
            animateTouchPresence(0f)
            animateChromeSuppression(0f)
        }

        fun resetSelection() {
            selection.setEmpty()
            crossedReadyThreshold = false
            animateSelectionTo(RectF())
            updateActionButtons(ready = false, hasAnyBox = false)
            hint.text = "拖动框选要提问的区域"
        }

        private fun updateActionButtons(ready: Boolean, hasAnyBox: Boolean) {
            setButtonEnabled(confirm, ready)
            setButtonEnabled(reset, hasAnyBox)
        }

        private fun setButtonEnabled(target: TextView, enabled: Boolean) {
            if (target.isEnabled == enabled) return
            target.isEnabled = enabled
            target.animate().cancel()
            target.animate().alpha(if (enabled) 1f else 0.45f)
                .setDuration(if (ValueAnimator.areAnimatorsEnabled()) 180L else 0L)
                .setInterpolator(SPRING_INTERPOLATOR)
                .start()
        }

        fun selectedBounds(): Rect? {
            val minPx = dpF(MIN_SELECTION_DP)
            return if (selection.width() < minPx || selection.height() < minPx) null
            else screenCropBounds(selection, imageBounds, screenshot.width, screenshot.height)
        }

        private fun isSelectionReady(rect: RectF = selection): Boolean =
            rect.width() >= dpF(MIN_SELECTION_DP) && rect.height() >= dpF(MIN_SELECTION_DP)

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(screenshot, null, imageBounds, bitmapPaint)

            val shown = visualSelection
            val hasBox = shown.width() > 0.5f || shown.height() > 0.5f
            val baseScrimAlpha = ((0x48 + ((0x8C - 0x48) * if (hasBox) 1f else 0.35f * touchPresence)) *
                sheetReveal).roundToInt().coerceIn(0, 200)
            scrimPaint.color = Color.argb(baseScrimAlpha, 0, 0, 0)

            if (!hasBox) {
                canvas.drawRect(imageBounds, scrimPaint)
                if (touchPresence > 0.01f) drawTouchAnchorRing(canvas, activeX, activeY, touchPresence)
                return
            }

            val boxRadius = minOf(dpF(14f), shown.width() / 2f, shown.height() / 2f)
            // Dim outside the rounded selection while keeping the selected region 100% crisp.
            val scrimSave = canvas.save()
            cornerPath.rewind()
            cornerPath.addRoundRect(shown, boxRadius, boxRadius, Path.Direction.CW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canvas.clipOutPath(cornerPath)
                canvas.drawRect(imageBounds, scrimPaint)
            } else {
                canvas.drawRect(imageBounds.left, imageBounds.top, imageBounds.right, shown.top, scrimPaint)
                canvas.drawRect(imageBounds.left, shown.bottom, imageBounds.right, imageBounds.bottom, scrimPaint)
                canvas.drawRect(imageBounds.left, shown.top, shown.left, shown.bottom, scrimPaint)
                canvas.drawRect(shown.right, shown.top, imageBounds.right, shown.bottom, scrimPaint)
            }
            canvas.restoreToCount(scrimSave)

            val ready = isSelectionReady(shown)
            // Subtle rule-of-thirds alignment hairlines while actively dragging or resizing.
            if (touchPresence > 0.02f && shown.width() > dpF(36f) && shown.height() > dpF(36f)) {
                strokePaint.color = palette.paper
                strokePaint.alpha = (touchPresence * 72f).roundToInt().coerceIn(0, 90)
                strokePaint.strokeWidth = max(1f, dpF(0.8f))
                val thirdW = shown.width() / 3f
                val thirdH = shown.height() / 3f
                for (step in 1..2) {
                    val gx = shown.left + thirdW * step
                    val gy = shown.top + thirdH * step
                    canvas.drawLine(gx, shown.top + dpF(4f), gx, shown.bottom - dpF(4f), strokePaint)
                    canvas.drawLine(shown.left + dpF(4f), gy, shown.right - dpF(4f), gy, strokePaint)
                }
            }

            // Crisp clay ink frame around the live selection.
            val pulseBoost = 1f + 0.25f * pulseProgress
            strokePaint.color = palette.clay
            strokePaint.alpha = if (ready) 255 else 190
            strokePaint.strokeWidth = dpF(1.8f) * pulseBoost
            canvas.drawRoundRect(shown, boxRadius, boxRadius, strokePaint)

            // Paper-filled ink eyelet handles at the four corners (mirrors the Star Island ring motif).
            val handleScale = (0.75f + 0.25f * if (ready) 1f else (shown.width() / dpF(MIN_SELECTION_DP)).coerceIn(0f, 1f)) *
                (1f + 0.14f * touchPresence)
            val outerR = dpF(6.2f) * handleScale
            val innerR = dpF(3.3f) * handleScale
            for (hx in floatArrayOf(shown.left, shown.right)) {
                for (hy in floatArrayOf(shown.top, shown.bottom)) {
                    handleFillPaint.color = palette.clay
                    canvas.drawCircle(hx, hy, outerR, handleFillPaint)
                    handleFillPaint.color = palette.paper
                    canvas.drawCircle(hx, hy, innerR, handleFillPaint)
                }
            }

            if (touchPresence > 0.02f) {
                drawLiveReadoutBadge(canvas, shown, ready, touchPresence)
            }
        }

        private fun drawTouchAnchorRing(canvas: Canvas, x: Float, y: Float, presence: Float) {
            val radius = dpF(11f) + dpF(5f) * presence
            handleFillPaint.color = palette.clay
            handleFillPaint.alpha = (38f * presence).roundToInt().coerceIn(0, 64)
            canvas.drawCircle(x, y, radius * 1.35f, handleFillPaint)
            strokePaint.color = palette.clay
            strokePaint.alpha = (235f * presence).roundToInt().coerceIn(0, 255)
            strokePaint.strokeWidth = dpF(2f)
            canvas.drawCircle(x, y, radius, strokePaint)
            handleFillPaint.color = palette.paper
            handleFillPaint.alpha = (240f * presence).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, dpF(3.2f) * presence, handleFillPaint)
        }

        private fun drawLiveReadoutBadge(canvas: Canvas, shown: RectF, ready: Boolean, presence: Float) {
            val cropPx = if (imageBounds.width() > 0f && imageBounds.height() > 0f) {
                val wPx = ((shown.width() / imageBounds.width()) * screenshot.width).roundToInt().coerceAtLeast(1)
                val hPx = ((shown.height() / imageBounds.height()) * screenshot.height).roundToInt().coerceAtLeast(1)
                "${wPx} × ${hPx}"
            } else ""
            val label = when {
                !ready -> "继续拖动以框选"
                drag == 16 -> "移动选区 · $cropPx"
                drag != 0 -> "调整大小 · $cropPx"
                else -> "松手确认范围 · $cropPx"
            }
            val textWidth = badgeTextPaint.measureText(label)
            val padH = dpF(12f)
            val badgeW = textWidth + padH * 2f
            val badgeH = dpF(28f)
            val cx = shown.centerX().coerceIn(imageBounds.left + badgeW / 2f + dpF(10f), imageBounds.right - badgeW / 2f - dpF(10f))
            val preferAbove = shown.top - dpF(12f) - badgeH >= max(imageBounds.top + dpF(52f), dpF(56f))
            val top = if (preferAbove) {
                shown.top - dpF(10f) - badgeH
            } else {
                min(imageBounds.bottom - badgeH - dpF(16f), shown.bottom + dpF(10f))
            }
            badgeRect.set(cx - badgeW / 2f, top, cx + badgeW / 2f, top + badgeH)
            val alpha = (245f * presence).roundToInt().coerceIn(0, 255)
            badgeBgPaint.color = palette.paper
            badgeBgPaint.alpha = alpha
            canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, badgeBgPaint)
            badgeBorderPaint.color = if (ready) palette.clay else palette.edge
            badgeBorderPaint.strokeWidth = dpF(1f)
            badgeBorderPaint.alpha = alpha
            canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, badgeBorderPaint)
            badgeTextPaint.color = if (ready) palette.ink else palette.muted
            badgeTextPaint.alpha = alpha
            val fm = badgeTextPaint.fontMetrics
            val baseline = badgeRect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, badgeRect.centerX(), baseline, badgeTextPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (submitted || !interactive || imageBounds.isEmpty) return true
            val x = event.x.coerceIn(imageBounds.left, imageBounds.right)
            val y = event.y.coerceIn(imageBounds.top, imageBounds.bottom)
            activeX = x
            activeY = y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selectionAnimator?.cancel()
                    downX = x
                    downY = y
                    dragStart.set(selection)
                    val hit = dpF(26f)
                    drag = 0
                    val hasExisting = isSelectionReady(selection)
                    if (hasExisting && x in (selection.left - hit)..(selection.right + hit) &&
                        y in (selection.top - hit)..(selection.bottom + hit)
                    ) {
                        if (abs(x - selection.left) < hit) drag = drag or 1
                        else if (abs(x - selection.right) < hit) drag = drag or 4
                        if (abs(y - selection.top) < hit) drag = drag or 2
                        else if (abs(y - selection.bottom) < hit) drag = drag or 8
                        if (drag == 0 && selection.contains(x, y)) drag = 16
                    }
                    if (drag == 0) {
                        selection.set(x, y, x, y)
                        visualSelection.setEmpty()
                        crossedReadyThreshold = false
                    } else {
                        visualSelection.set(selection)
                        crossedReadyThreshold = isSelectionReady(selection)
                    }
                    pointerDown = true
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    animateTouchPresence(1f)
                    updateChromeAvoidance(y)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val minimum = dpF(MIN_SELECTION_DP)
                    when (drag) {
                        0 -> selection.set(minOf(x, downX), minOf(y, downY), maxOf(x, downX), maxOf(y, downY))
                        16 -> {
                            val dx = (x - downX).coerceIn(imageBounds.left - dragStart.left, imageBounds.right - dragStart.right)
                            val dy = (y - downY).coerceIn(imageBounds.top - dragStart.top, imageBounds.bottom - dragStart.bottom)
                            selection.set(dragStart)
                            selection.offset(dx, dy)
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
                    val readyNow = isSelectionReady(selection)
                    if (readyNow && !crossedReadyThreshold) {
                        crossedReadyThreshold = true
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        triggerReadyPulse()
                    } else if (!readyNow) {
                        crossedReadyThreshold = false
                    }
                    updateChromeAvoidance(y)
                    updateActionButtons(ready = readyNow, hasAnyBox = selection.width() > 2f || selection.height() > 2f)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    pointerDown = false
                    animateTouchPresence(0f)
                    animateChromeSuppression(0f)
                    val readyNow = isSelectionReady(selection)
                    if (!readyNow) {
                        // A tiny accidental tap without dragging restores the previous valid selection
                        // if one existed, or clears the sub-threshold dot cleanly.
                        if (isSelectionReady(dragStart) &&
                            abs(x - downX) < dpF(8f) && abs(y - downY) < dpF(8f)
                        ) {
                            selection.set(dragStart)
                        } else {
                            selection.setEmpty()
                        }
                    }
                    val finalReady = isSelectionReady(selection)
                    hint.text = if (finalReady) "拖动边角调整，拖动框内移动" else "拖动框选要提问的区域"
                    animateSelectionTo(selection)
                    updateActionButtons(ready = finalReady, hasAnyBox = finalReady)
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    pointerDown = false
                    animateTouchPresence(0f)
                    animateChromeSuppression(0f)
                    selection.set(dragStart)
                    val finalReady = isSelectionReady(selection)
                    animateSelectionTo(selection)
                    updateActionButtons(ready = finalReady, hasAnyBox = finalReady)
                }
            }
            return true
        }

        private fun updateChromeAvoidance(pointerY: Float) {
            val nearTop = pointerY < hint.bottom + dpF(24f) || (!visualSelection.isEmpty && visualSelection.top < hint.bottom + dpF(12f))
            val nearBottom = pointerY > actions.top - dpF(24f) || (!visualSelection.isEmpty && visualSelection.bottom > actions.top - dpF(12f))
            animateChromeSuppression(if (nearTop || nearBottom) 0.78f else 0.18f)
        }

        private fun animateTouchPresence(target: Float) {
            presenceAnimator?.cancel()
            if (!ValueAnimator.areAnimatorsEnabled()) {
                touchPresence = target
                invalidate()
                return
            }
            presenceAnimator = ValueAnimator.ofFloat(touchPresence, target).apply {
                duration = if (target > touchPresence) 140L else 200L
                interpolator = SPRING_INTERPOLATOR
                addUpdateListener {
                    touchPresence = (it.animatedValue as Float).coerceIn(0f, 1f)
                    invalidate()
                }
                start()
            }
        }

        private fun animateChromeSuppression(target: Float) {
            if (abs(chromeSuppression - target) < 0.02f && chromeAnimator?.isRunning != true) return
            chromeAnimator?.cancel()
            if (!ValueAnimator.areAnimatorsEnabled()) {
                chromeSuppression = target
                updateContent()
                return
            }
            chromeAnimator = ValueAnimator.ofFloat(chromeSuppression, target).apply {
                duration = 180L
                interpolator = SPRING_INTERPOLATOR
                addUpdateListener {
                    chromeSuppression = (it.animatedValue as Float).coerceIn(0f, 1f)
                    updateContent()
                }
                start()
            }
        }

        private fun triggerReadyPulse() {
            if (!ValueAnimator.areAnimatorsEnabled()) return
            ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 240L
                addUpdateListener {
                    pulseProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun animateSelectionTo(target: RectF) {
            val from = RectF(visualSelection)
            val centerX = if (target.isEmpty) from.centerX() else target.centerX()
            val centerY = if (target.isEmpty) from.centerY() else target.centerY()
            val end = if (target.isEmpty) RectF(centerX, centerY, centerX, centerY) else RectF(target)
            selectionAnimator?.cancel()
            if ((from.isEmpty && end.isEmpty) || !ValueAnimator.areAnimatorsEnabled()) {
                if (target.isEmpty) visualSelection.setEmpty() else visualSelection.set(target)
                invalidate()
                return
            }
            if (from.isEmpty && !target.isEmpty) {
                visualSelection.set(target)
                invalidate()
                return
            }
            selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200L
                addUpdateListener { animator ->
                    val p = IslandSpring.progress(animator.animatedFraction)
                    visualSelection.set(
                        lerp(from.left, end.left, p),
                        lerp(from.top, end.top, p),
                        lerp(from.right, end.right, p),
                        lerp(from.bottom, end.bottom, p),
                    )
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

        private fun lerp(from: Float, to: Float, p: Float): Float = from + (to - from) * p
    }
}
