package com.adong.adchat.island

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.adong.adchat.R
import com.adong.adchat.media.MediaPlatform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One overlay, one anchored island. The original 64dp breathing halo stays unchanged at idle.
 * One pure-black island grows out of the halo's black core — first sideways into a capsule,
 * then downward — and closes along the same path; the halo keeps its position and size.
 * Window size changes only BEFORE opening / AFTER closing; all in-flight geometry, content and
 * halo channels share one vsync-driven spring clock.
 */
class IslandView(context: Context, private val callbacks: Callbacks) : FrameLayout(context) {
    interface Callbacks {
        fun onParseRequested(link: IslandLink)
        fun onLinkShown(link: IslandLink)
        fun onDismissed()
    }

    companion object {
        // Keep the existing device-calibrated idle position and appearance.
        private const val CUTOUT_CENTER_Y_DP = 25.5f
        private const val WINDOW_SIZE_DP = IslandGlowView.WINDOW_SIZE_DP
        private const val CARD_TIMEOUT_MS = 12_000L
        private const val BREATH_PERIOD_MS = 3_800L
        private const val MIN_TAP_HOLD_MS = 150L
        private const val SEED_RADIUS_DP = 13f
        private const val ISLAND_RADIUS_DP = 40f
        /** Closing tail: the island's single rebound becomes one glow pulse; the ring never resizes. */
        private const val CLOSE_GLOW_PULSE = 0.32f
        private val TEXT_PRIMARY = 0xFFF5F5F7.toInt()
        private val TEXT_SECONDARY = 0xFF8E8E93.toInt()
        private val BUTTON_SECONDARY_FILL = 0xFF2C2C2E.toInt()
        private val BUTTON_PRIMARY_FILL = 0xFFF5F5F7.toInt()
        private val BUTTON_PRIMARY_TEXT = 0xFF000000.toInt()
        private val SPRING_INTERPOLATOR = android.animation.TimeInterpolator { IslandSpring.progress(it) }
    }

    sealed class State {
        data object Hidden : State()
        data class Card(val link: IslandLink) : State()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val screenSize = Point().also {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(it)
    }
    private fun dp(v: Int): Int = (v * density).roundToInt()
    private fun dpF(v: Float): Float = v * density
    private fun lerp(from: Float, to: Float, p: Float) = from + (to - from) * p

    private var state: State = State.Hidden
    private var attached = false
    private var destroyed = false
    private var windowParams: WindowManager.LayoutParams? = null
    private var expandedViewport = false
    private var pendingMorph: (() -> Unit)? = null
    private var morphProgress = 0f
    private var idleFeedbackRunning = false
    private var renderedPlatform: MediaPlatform? = null
    private var deferredCardLink: IslandLink? = null
    private var lastHandledLink: String? = null
    private var lastPressDownUptimeMs = 0L
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var swipeDismissed = false
    private val hideRunnable: Runnable = Runnable { collapse() }
    private val releaseSpringRunnable = Runnable { animateSpringPress(pressed = false) }
    private var clipboardReadPending = false
    private val readClipboardRunnable: Runnable = Runnable {
        if (clipboardReadPending && hasWindowFocus()) {
            clipboardReadPending = false
            mainHandler.removeCallbacks(clipboardFocusTimeout)
            readClipboardAndHandle()
        }
    }
    private val clipboardFocusTimeout: Runnable = Runnable {
        if (clipboardReadPending) {
            clipboardReadPending = false
            setWindowFocusable(false)
            playEmptyFeedback()
        }
    }

    private val glowView: IslandGlowView
    private val surface: IslandSurfaceView
    private val cardHost: LinearLayout
    private val platformIcon: ImageView
    private val cardTitle: TextView
    private val cardSummary: TextView
    private val parseButton: TextView
    private val cancelButton: TextView
    private var breathAnimator: ValueAnimator? = null
    private var feedbackAnimator: ValueAnimator? = null
    private var morphAnimator: ValueAnimator? = null

    internal val displayedLink: IslandLink? get() = (state as? State.Card)?.link
    internal val expansionProgress: Float get() = morphProgress
    internal val cardTitleText: CharSequence get() = cardTitle.text
    internal val cardSummaryText: CharSequence get() = cardSummary.text

    init {
        clipChildren = false
        clipToPadding = false
        surface = IslandSurfaceView(context)
        val card = buildCard()
        cardHost = card.host
        platformIcon = card.icon
        cardTitle = card.title
        cardSummary = card.summary
        parseButton = card.parse
        cancelButton = card.cancel
        surface.addView(cardHost)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        glowView = IslandGlowView(context).apply {
            isHapticFeedbackEnabled = true
            contentDescription = "链接灵动岛，点按读取剪贴板"
            isClickable = true
            setOnTouchListener { _, event -> handlePillTouch(event) }
            setOnClickListener { requestClipboardRead() }
        }
        // This view is ALWAYS 64dp and centred, even when its parent window is wider.
        addView(glowView, LayoutParams(dpF(WINDOW_SIZE_DP).roundToInt(), dpF(WINDOW_SIZE_DP).roundToInt()).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        })
        cardHost.alpha = 0f
        cardHost.scaleX = IslandMorphGeometry.CONTENT_MIN_SCALE
        cardHost.scaleY = IslandMorphGeometry.CONTENT_MIN_SCALE
        cardHost.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun attach(): Unit = onMain { ensureAttached() }

    /** Keep the existing trigger entry point, but start expansion immediately (no 740ms prelude). */
    fun show(link: IslandLink): Unit = onMain {
        if (!ensureAttached()) return@onMain
        val previous = displayedLink
        deferredCardLink = null
        state = State.Card(link)
        renderCard(link)
        mainHandler.removeCallbacks(hideRunnable)
        cancelClipboardRead()
        cancelIdleFeedback()
        setWindowFocusable(false)
        cardHost.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        // A new link arriving during the 2% tail must settle the current overshoot to 1;
        // otherwise the old animator is cancelled by show() and the island can remain at >1.
        val restartMorph = previous == null || morphProgress != 1f
        if (restartMorph) morphAnimator?.cancel()
        prepareExpandedViewport()
        if (restartMorph) {
            afterViewportLayout { animateMorph(expanding = true) }
            hapticDoublePulse()
        } else {
            hapticTick()
            mainHandler.postDelayed(hideRunnable, CARD_TIMEOUT_MS)
        }
        // Short-link resolution runs in parallel, never on the drawing/animation path.
        if (previous?.originalUrl != link.originalUrl) callbacks.onLinkShown(link)
    }

    /** Late or stale short-link results must NEVER reopen or replace a dismissed/different island. */
    fun updateCard(link: IslandLink): Unit = onMain {
        val shown = displayedLink ?: return@onMain
        if (shown.originalUrl != link.originalUrl) return@onMain
        state = State.Card(link)
        // Avoid rerasterising the hardware-cached text layer in the middle of the spring.
        if (morphAnimator?.isRunning == true || pendingMorph != null) deferredCardLink = link
        else renderCard(link)
    }

    fun collapse(): Unit = onMain {
        setWindowFocusable(false)
        if (state == State.Hidden) return@onMain
        state = State.Hidden
        pendingMorph = null
        deferredCardLink = null
        mainHandler.removeCallbacks(hideRunnable)
        cancelClipboardRead()
        mainHandler.removeCallbacks(releaseSpringRunnable)
        cardHost.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        animateMorph(expanding = false)
    }

    fun destroy(): Unit = onMain {
        destroyed = true
        pendingMorph = null
        state = State.Hidden
        mainHandler.removeCallbacksAndMessages(null)
        breathAnimator?.cancel()
        feedbackAnimator?.cancel()
        morphAnimator?.cancel()
        parseButton.animate().cancel()
        cancelButton.animate().cancel()
        if (attached) {
            runCatching { windowManager.removeViewImmediate(this@IslandView) }
            attached = false
        }
        windowParams = null
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!destroyed) block()
        } else mainHandler.post { if (!destroyed) block() }
    }

    private data class CardParts(
        val host: LinearLayout, val icon: ImageView, val title: TextView,
        val summary: TextView, val parse: TextView, val cancel: TextView
    )

    private fun buildCard(): CardParts {
        val host = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Top inset = 26dp black core + 10dp: the camera band at the island's top stays empty.
            setPadding(dp(18), dp(36), dp(18), dp(18))
            // The black island is the only card background; this content host stays transparent.
        }
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = iconTileDrawable(0xFF3A3A3C.toInt())
        }
        headerRow.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val summary = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        texts.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        texts.addView(summary, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })
        headerRow.addView(texts, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cancel = actionButton("收起", primary = false) { hapticTick(); collapse() }
        val parse = actionButton("解析链接", primary = true) {
            hapticClick()
            displayedLink?.let { callbacks.onParseRequested(it) }
        }
        buttonRow.addView(cancel, LinearLayout.LayoutParams(0, dp(44), 1f))
        buttonRow.addView(parse, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(10)
        })
        host.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        host.addView(buttonRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        return CardParts(host, icon, title, summary, parse, cancel)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun actionButton(text: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(if (primary) BUTTON_PRIMARY_TEXT else TEXT_PRIMARY)
        background = GradientDrawable().apply {
            setColor(if (primary) BUTTON_PRIMARY_FILL else BUTTON_SECONDARY_FILL)
            // Concentric with the 40dp island at an 18dp inset.
            cornerRadius = dpF(22f)
        }
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateButton(v, 0.94f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateButton(v, 1f)
            }
            false
        }
        setOnClickListener { onClick() }
    }

    private fun animateButton(view: View, target: Float) {
        view.animate().cancel()
        view.animate().scaleX(target).scaleY(target)
            .setDuration(if (ValueAnimator.areAnimatorsEnabled()) IslandSpring.BUTTON_MS else 0L)
            .setInterpolator(SPRING_INTERPOLATOR).start()
    }

    // Swipe dismissal also works when the gesture begins on a child button or URL.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && state is State.Card) {
            collapse()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureDownX = event.rawX
            gestureDownY = event.rawY
            swipeDismissed = false
            if (state is State.Card && !surface.geometry.contains(event.x, event.y)) {
                collapse()
                return true
            }
        }
        if (state is State.Card && event.actionMasked == MotionEvent.ACTION_MOVE &&
            gestureDownY - event.rawY > dp(20) &&
            gestureDownY - event.rawY > abs(event.rawX - gestureDownX)
        ) {
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            swipeDismissed = true
            hapticTick()
            collapse()
            return true
        }
        if (swipeDismissed) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        state is State.Card || super.onTouchEvent(event)

    @SuppressLint("ClickableViewAccessibility")
    private fun handlePillTouch(event: MotionEvent): Boolean {
        if (state is State.Card || expandedViewport) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelClipboardRead()
                mainHandler.removeCallbacks(releaseSpringRunnable)
                lastPressDownUptimeMs = SystemClock.uptimeMillis()
                setWindowFocusable(true)
                hapticClick()
                animateSpringPress(pressed = true)
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = SystemClock.uptimeMillis() - lastPressDownUptimeMs
                mainHandler.postDelayed(releaseSpringRunnable, max(0L, MIN_TAP_HOLD_MS - elapsed))
                // performClick is also the accessibility entry point; wait until clipboard focus arrives.
                glowView.performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelClipboardRead()
                mainHandler.removeCallbacks(releaseSpringRunnable)
                animateSpringPress(pressed = false)
                setWindowFocusable(false)
            }
        }
        return true
    }

    private fun requestClipboardRead() {
        cancelClipboardRead()
        clipboardReadPending = true
        setWindowFocusable(true)
        if (hasWindowFocus()) mainHandler.post(readClipboardRunnable)
        mainHandler.postDelayed(clipboardFocusTimeout, 800L)
    }

    private fun cancelClipboardRead() {
        clipboardReadPending = false
        mainHandler.removeCallbacks(readClipboardRunnable)
        mainHandler.removeCallbacks(clipboardFocusTimeout)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && clipboardReadPending) mainHandler.post(readClipboardRunnable)
    }

    private fun readClipboardAndHandle() {
        if (!attached || state is State.Card) return
        val link = currentClipText()?.let { IslandLinkParser.parse(it) }
        if (link == null) {
            playEmptyFeedback()
        } else {
            val key = link.cleanedUrl.ifBlank { link.originalUrl }
            if (key == lastHandledLink) playEmptyFeedback() else {
                lastHandledLink = key
                show(link)
            }
        }
        setWindowFocusable(false)
    }

    private fun currentClipText(): String? = runCatching {
        val item = clipboard.primaryClip?.getItemAt(0) ?: return@runCatching null
        // coerceToText may open/read an arbitrary content provider synchronously on the UI thread.
        // Media sharing needs only actual text or the URI itself, never provider contents.
        item.text?.take(16_384)?.toString() ?: item.uri?.toString()?.take(16_384)
    }.getOrNull()

    private fun setWindowFocusable(focusable: Boolean) {
        val params = windowParams ?: return
        val flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (flags == params.flags || !attached) return
        params.flags = flags
        runCatching { windowManager.updateViewLayout(this, params) }
    }

    // ---------- One analytic spring for every state transition ----------

    private fun startBreathing() {
        breathAnimator?.cancel()
        if (state != State.Hidden || expandedViewport || windowVisibility != VISIBLE) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            glowView.alpha = 0.74f
            return
        }
        val startAlpha = glowView.alpha
        var entering = true
        breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BREATH_PERIOD_MS
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val p = it.animatedFraction
                // Preserve idle appearance: only this cached 64dp RenderNode changes alpha.
                val wave = 0.74f + 0.16f * cos(2.0 * Math.PI * p).toFloat()
                glowView.alpha = if (entering && p < 0.15f) {
                    // Entry is also a spring transition; do not reapply it on every cosine repeat.
                    lerp(startAlpha, wave, IslandSpring.progress(p / 0.15f)).coerceIn(0f, 1f)
                } else {
                    entering = false
                    wave
                }
            }
            start()
        }
    }

    private fun cancelIdleFeedback() {
        mainHandler.removeCallbacks(releaseSpringRunnable)
        breathAnimator?.cancel()
        feedbackAnimator?.cancel()
        idleFeedbackRunning = false
    }

    private fun animateSpringPress(pressed: Boolean) {
        if (state != State.Hidden || expandedViewport) return
        cancelIdleFeedback()
        idleFeedbackRunning = true
        val fromScale = glowView.springScale
        val fromGlow = glowView.glowIntensity
        val fromAlpha = glowView.alpha
        val fromNudge = glowView.nudgeShift
        val fromFlash = glowView.flashIntensity
        val fromRipple = glowView.rippleProgress
        val fromOrbit = glowView.orbitAngle
        feedbackAnimator = springAnimation(IslandSpring.PRESS_MS, { p ->
            glowView.springScale = lerp(fromScale, if (pressed) 1.68f else 1f, p)
            glowView.glowIntensity = lerp(fromGlow, if (pressed) 1f else IslandGlowView.BASE_GLOW_INTENSITY, p)
            glowView.alpha = lerp(fromAlpha, 1f, p).coerceIn(0f, 1f)
            glowView.nudgeShift = lerp(fromNudge, 0f, p).coerceIn(0f, 1f)
            glowView.flashIntensity = lerp(fromFlash, if (pressed) 0.5f else 0f, p).coerceIn(0f, 1f)
            glowView.rippleProgress = lerp(fromRipple, if (pressed) 0.7f else 1f, p).coerceIn(0f, 1f)
            glowView.orbitAngle = lerp(fromOrbit, if (pressed) fromOrbit + 85f else -45f, p)
        }) {
            idleFeedbackRunning = false
            if (!pressed) {
                glowView.reset()
                startBreathing()
            }
        }
    }

    private fun playEmptyFeedback() {
        if (state != State.Hidden || expandedViewport) return
        cancelIdleFeedback()
        idleFeedbackRunning = true
        hapticEmptyDoubleTick()
        val fromScale = glowView.springScale
        val fromGlow = glowView.glowIntensity
        val fromAlpha = glowView.alpha
        val fromNudge = glowView.nudgeShift
        val fromFlash = glowView.flashIntensity
        val fromRipple = glowView.rippleProgress
        val fromOrbit = glowView.orbitAngle
        feedbackAnimator = springAnimation(IslandSpring.COLLAPSE_MS, { p ->
            // Single rebound, not the old multi-pulse Euler oscillator. No popup for empty/duplicate.
            glowView.springScale = lerp(fromScale, 1f, p)
            glowView.glowIntensity = lerp(fromGlow, IslandGlowView.BASE_GLOW_INTENSITY, p)
            glowView.alpha = lerp(fromAlpha, 1f, p).coerceIn(0f, 1f)
            glowView.nudgeShift = lerp(fromNudge, 0f, p).coerceIn(0f, 1f)
            glowView.flashIntensity = lerp(fromFlash, 0f, p).coerceIn(0f, 1f)
            glowView.rippleProgress = lerp(fromRipple, 1f, p).coerceIn(0f, 1f)
            glowView.orbitAngle = lerp(fromOrbit, -45f, p)
        }) {
            idleFeedbackRunning = false
            glowView.reset()
            startBreathing()
        }
    }

    private fun animateMorph(expanding: Boolean) {
        morphAnimator?.cancel() // Cancelled animators never fire stale completion callbacks.
        cancelIdleFeedback()
        val from = morphProgress
        val target = if (expanding) 1f else 0f
        val fromAlpha = glowView.alpha
        val fromScale = glowView.springScale
        val fromGlow = glowView.glowIntensity
        val fromColor = glowView.colorShift
        val fromNudge = glowView.nudgeShift
        val fromFlash = glowView.flashIntensity
        val fromRipple = glowView.rippleProgress
        val fromOrbit = glowView.orbitAngle
        cardHost.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        morphAnimator = springAnimation(if (expanding) IslandSpring.EXPAND_MS else IslandSpring.COLLAPSE_MS, { p ->
            // Body bounds reverse from expanded to idle during collapse; p itself is the
            // forward wall-clock progress, so every visual channel reaches its idle target once.
            applyMorph(lerp(from, target, p))
            val g = surface.geometry
            // The resident halo never moves or resizes during a morph: an interrupted press only settles
            // back to 1, opacity hands over to the island, and the closing rebound is one glow pulse.
            glowView.alpha = minOf(g.ringAlpha, lerp(fromAlpha, 1f, p)).coerceIn(0f, 1f)
            glowView.springScale = lerp(fromScale, 1f, p)
            glowView.glowIntensity = lerp(fromGlow, IslandGlowView.BASE_GLOW_INTENSITY, p) +
                if (expanding) 0f else CLOSE_GLOW_PULSE * g.recoil
            glowView.colorShift = lerp(fromColor, if (expanding) 1f else 0f, p).coerceIn(0f, 1f)
            glowView.nudgeShift = lerp(fromNudge, 0f, p).coerceIn(0f, 1f)
            glowView.flashIntensity = lerp(fromFlash, 0f, p).coerceIn(0f, 1f)
            glowView.rippleProgress = lerp(fromRipple, 1f, p).coerceIn(0f, 1f)
            glowView.orbitAngle = lerp(fromOrbit, if (expanding) fromOrbit + 70f else -45f, p)
        }) {
            applyMorph(target)
            cardHost.setLayerType(View.LAYER_TYPE_NONE, null)
            if (expanding && state is State.Card) {
                deferredCardLink?.let { renderCard(it) }
                deferredCardLink = null
                mainHandler.postDelayed(hideRunnable, CARD_TIMEOUT_MS)
            } else if (!expanding && state == State.Hidden) {
                applyIdleViewport()
                glowView.reset()
                startBreathing()
                callbacks.onDismissed()
            }
        }
    }

    private fun applyMorph(value: Float) {
        morphProgress = value
        surface.setProgress(value)
        val g = surface.geometry
        // Content is laid out once at its final position; the growing outline reveals and clips it.
        cardHost.alpha = g.contentAlpha
        cardHost.scaleX = g.contentScale
        cardHost.scaleY = g.contentScale
        val actionable = state is State.Card && value >= 0.9f
        if (parseButton.isEnabled != actionable) parseButton.isEnabled = actionable
        if (cancelButton.isEnabled != actionable) cancelButton.isEnabled = actionable
        val idle = !expandedViewport
        if (glowView.isClickable != idle) glowView.isClickable = idle
        val accessibility = if (idle) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        if (glowView.importantForAccessibility != accessibility) glowView.importantForAccessibility = accessibility
    }

    private fun springAnimation(durationMs: Long, update: (Float) -> Unit, finished: () -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (ValueAnimator.areAnimatorsEnabled()) durationMs else 0L
            interpolator = SPRING_INTERPOLATOR
            addUpdateListener { update(it.animatedValue as Float) }
            addListener(object : Animator.AnimatorListener {
                private var cancelled = false
                override fun onAnimationStart(animation: Animator) = Unit
                override fun onAnimationRepeat(animation: Animator) = Unit
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) finished() }
            })
            start()
        }

    // ---------- Stable screen anchor / two window resizes, never a resize per frame ----------

    private fun calcWindowY(): Int =
        (dpF(CUTOUT_CENTER_Y_DP) - dpF(WINDOW_SIZE_DP) / 2f).roundToInt() - statusBarHeight()

    private fun ensureAttached(): Boolean {
        if (attached) return true
        val size = dpF(WINDOW_SIZE_DP).roundToInt()
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = calcWindowY()
        }
        disableMoveAnimation(params)
        if (runCatching { windowManager.addView(this, params) }.isFailure) return false
        windowParams = params
        attached = true
        glowView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        startBreathing()
        return true
    }

    /**
     * Opening/closing resizes this TOP|CENTER overlay, which moves its frame's left edge. By default
     * WindowManager then slides the surface from the old position (~150ms): the island flew in from
     * the right on open and the ring flew back from the left after closing. Jump instead.
     */
    private fun disableMoveAnimation(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            params.setCanPlayMoveAnimation(false)
        } else runCatching {
            // PRIVATE_FLAG_NO_MOVE_ANIMATION (1 << 6): the pre-34 equivalent of the public setter.
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            field.setInt(params, field.getInt(params) or 0x00000040)
        }
    }

    private fun prepareExpandedViewport() {
        val params = windowParams ?: return
        val requestedWidth = minOf(screenSize.x, dp(440))
        val haloWidth = dpF(WINDOW_SIZE_DP).roundToInt()
        // Equal parity keeps Gravity.CENTER's integer rounding from moving the ring half a pixel.
        val viewportWidth = requestedWidth - ((requestedWidth - haloWidth) and 1)
        val contentWidth = (viewportWidth - dp(28)).coerceAtLeast(1)
        val bodyTop = (dpF(WINDOW_SIZE_DP) / 2f - dpF(SEED_RADIUS_DP)).roundToInt()
        cardHost.layoutParams = LayoutParams(contentWidth, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = bodyTop
        }
        cardHost.measure(
            MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        // Geometry is configured in onLayout from ACTUAL bounds, atomically with the centred halo.
        val bodyBottom = bodyTop + cardHost.measuredHeight.toFloat()
        val seedBottom = dpF(WINDOW_SIZE_DP) / 2f + dpF(SEED_RADIUS_DP)
        // Only the height overshoots; reserve its exact maximum so the rebound never clips.
        val viewportHeight = (bodyBottom + (bodyBottom - seedBottom) * IslandMorphGeometry.MAX_HEIGHT_OVERSHOOT + dp(8)).roundToInt()
        expandedViewport = true
        glowView.isClickable = false
        glowView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        params.width = viewportWidth
        params.height = viewportHeight
        params.y = calcWindowY() // EXACTLY the idle y: do not move to statusBarHeight()+4.
        runCatching { windowManager.updateViewLayout(this, params) }
    }

    private fun afterViewportLayout(start: () -> Unit) {
        val params = windowParams ?: return
        if (width == params.width && height == params.height && !isLayoutRequested && surface.active) start()
        else pendingMorph = start
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (expandedViewport) {
            val anchorX = glowView.left + glowView.width / 2f
            val anchorY = glowView.top + glowView.height / 2f
            val cardLeft = cardHost.left.toFloat()
            val cardTop = cardHost.top.toFloat()
            val cardRight = cardHost.right.toFloat()
            val cardBottom = cardHost.bottom.toFloat()
            // Content scales around the halo centre x and the island's top edge.
            cardHost.pivotX = anchorX - cardLeft
            cardHost.pivotY = 0f
            surface.geometry.configure(
                anchorX, anchorY, dpF(SEED_RADIUS_DP),
                cardLeft, cardTop, cardRight, cardBottom, dpF(ISLAND_RADIUS_DP)
            )
            surface.active = true
            applyMorph(morphProgress)
        } else surface.active = false
        val start = pendingMorph
        pendingMorph = null
        start?.invoke()
    }

    private fun applyIdleViewport() {
        val params = windowParams ?: return
        val size = dpF(WINDOW_SIZE_DP).roundToInt()
        expandedViewport = false
        // Remove the morphing body before shrinking the overlay; this prevents a final black seed
        // frame from covering the restored resident halo.
        surface.active = false
        params.width = size
        params.height = size
        params.y = calcWindowY()
        runCatching { windowManager.updateViewLayout(this, params) }
        applyMorph(0f)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(screenSize)
        if (!attached) return
        if (expandedViewport) prepareExpandedViewport() else applyIdleViewport()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // This callback may arrive from addView; all child fields are already initialised then.
        if (visibility != VISIBLE) breathAnimator?.cancel()
        else if (attached && !idleFeedbackRunning && !expandedViewport) startBreathing()
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(30)
    }

    private fun renderCard(link: IslandLink) {
        if (renderedPlatform != link.platform) {
            renderedPlatform = link.platform
            val (icon, tile) = when (link.platform) {
                MediaPlatform.Douyin -> R.drawable.ic_island_douyin to 0xFF2F6F62.toInt()
                MediaPlatform.Bilibili -> R.drawable.ic_island_bilibili to 0xFFD45C78.toInt()
                MediaPlatform.Twitter -> R.drawable.ic_island_x to 0xFF3D586E.toInt()
                MediaPlatform.Direct -> R.drawable.ic_island_link to 0xFF3A3A3C.toInt()
            }
            platformIcon.setImageResource(icon)
            platformIcon.background = iconTileDrawable(tile)
            platformIcon.contentDescription = link.displayName
            cardTitle.text = IslandCardText.title(link.platform)
        }
        val summary = IslandCardText.summary(link.cleanedUrl.ifBlank { link.originalUrl })
        if (cardSummary.text.toString() != summary) cardSummary.text = summary
    }

    // ---------- Existing multi-level haptics (not an animation clock) ----------

    private fun getVibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    private fun dispatchVibration(effect: VibrationEffect) {
        runCatching {
            val vibrator = getVibrator() ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                val attrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, attrs)
            }
        }
    }

    private fun hapticClick() {
        runCatching {
            glowView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val composed = runCatching {
                val v = getVibrator() ?: return@runCatching false
                if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                    dispatchVibration(VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f).compose())
                    true
                } else false
            }.getOrDefault(false)
            if (composed) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dispatchVibration(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else dispatchVibration(VibrationEffect.createOneShot(22L, 240))
    }

    private fun hapticTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dispatchVibration(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else dispatchVibration(VibrationEffect.createOneShot(12L, 160))
    }

    private fun hapticDoublePulse() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val composed = runCatching {
                val v = getVibrator() ?: return@runCatching false
                if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                    dispatchVibration(VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.85f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 48).compose())
                    true
                } else false
            }.getOrDefault(false)
            if (composed) return
        }
        dispatchVibration(VibrationEffect.createWaveform(longArrayOf(0, 18, 48, 28), intArrayOf(0, 185, 0, 255), -1))
    }

    private fun hapticEmptyDoubleTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val composed = runCatching {
                val v = getVibrator() ?: return@runCatching false
                if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                    dispatchVibration(VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.9f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.65f, 55).compose())
                    true
                } else false
            }.getOrDefault(false)
            if (composed) return
        }
        dispatchVibration(VibrationEffect.createWaveform(longArrayOf(0, 12, 50, 12), intArrayOf(0, 190, 0, 130), -1))
    }

    private fun iconTileDrawable(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpF(10f)
    }
}
