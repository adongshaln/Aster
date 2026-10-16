package com.adong.adchat.island

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
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
import android.widget.LinearLayout
import android.widget.TextView
import com.adong.adchat.media.MediaPlatform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One overlay, one anchored island. The original 64dp breathing halo stays unchanged at idle.
 * The notification grows out of its black centre, rather than moving the window below the cutout
 * and launching a separate popup. Window size changes only BEFORE opening / AFTER closing;
 * all in-flight geometry, content and halo channels share one vsync-driven spring clock.
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
    private val grabberView: View
    private val platformTag: TextView
    private val cardTitle: TextView
    private val cardUrl: TextView
    private val parseButton: TextView
    private val cancelButton: TextView
    private var breathAnimator: ValueAnimator? = null
    private var feedbackAnimator: ValueAnimator? = null
    private var morphAnimator: ValueAnimator? = null

    internal val displayedLink: IslandLink? get() = (state as? State.Card)?.link
    internal val expansionProgress: Float get() = morphProgress

    init {
        clipChildren = false
        clipToPadding = false
        surface = IslandSurfaceView(context)
        val card = buildCard()
        cardHost = card.host
        grabberView = card.grabber
        platformTag = card.tag
        cardTitle = card.title
        cardUrl = card.url
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
        val host: LinearLayout, val grabber: View, val tag: TextView,
        val title: TextView, val url: TextView, val parse: TextView, val cancel: TextView
    )

    private fun buildCard(): CardParts {
        val host = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            // The outer black body is shared with the halo; keep the existing card skin for the
            // content portion so expansion changes geometry, not the established visual language.
            background = cardDrawable(0xFF38BDF8.toInt())
            elevation = dpF(12f)
        }
        val grabber = View(context).apply { background = grabberDrawable(0xFF38BDF8.toInt()) }
        host.addView(grabber, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tag = TextView(context).apply {
            textSize = 11f
            setTextColor(0xFF060A10.toInt())
            paint.isFakeBoldText = true
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = tagDrawable(0xFF38BDF8.toInt())
        }
        val title = TextView(context).apply {
            textSize = 15.5f
            setTextColor(0xFFF8FAFC.toInt())
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerRow.addView(tag, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(9)
        })
        headerRow.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val url = TextView(context).apply {
            textSize = 12.5f
            setTextColor(0xFFCBD5E1.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = urlCapsuleDrawable()
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancel = actionButton("收起", accent = false) { hapticTick(); collapse() }
        val parse = actionButton("立即解析", accent = true) {
            hapticClick()
            displayedLink?.let { callbacks.onParseRequested(it) }
        }
        buttonRow.addView(cancel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(10)
        })
        buttonRow.addView(parse, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        host.addView(headerRow)
        host.addView(url, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        host.addView(buttonRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        return CardParts(host, grabber, tag, title, url, parse, cancel)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun actionButton(text: String, accent: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = 13.5f
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minWidth = dp(72)
        setTextColor(if (accent) 0xFF050B12.toInt() else 0xFFE2E8F0.toInt())
        if (accent) paint.isFakeBoldText = true
        setPadding(dp(20), dp(9), dp(20), dp(9))
        background = buttonDrawable(accent, 0xFF38BDF8.toInt())
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
            glowView.alpha = lerp(fromAlpha, if (expanding) 0f else 1f, p).coerceIn(0f, 1f)
            val closingRecoil = if (!expanding) surface.geometry.haloRecoil else 0f
            glowView.springScale = lerp(fromScale, if (expanding) 1.28f else 1f, p) + closingRecoil
            glowView.glowIntensity = lerp(fromGlow, IslandGlowView.BASE_GLOW_INTENSITY, p)
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
        // Content has no independent popup/scale-in animator. It follows the same spring geometry.
        cardHost.alpha = ((value - 0.3f) / 0.7f).coerceIn(0f, 1f)
        cardHost.translationY = -dpF(14f) * (1f - value)
        val scale = 1f - 0.04f * (1f - value)
        cardHost.scaleX = scale
        cardHost.scaleY = scale
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
        if (runCatching { windowManager.addView(this, params) }.isFailure) return false
        windowParams = params
        attached = true
        glowView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        startBreathing()
        return true
    }

    private fun prepareExpandedViewport() {
        val params = windowParams ?: return
        val requestedWidth = minOf(screenSize.x, dp(440))
        val haloWidth = dpF(WINDOW_SIZE_DP).roundToInt()
        // Equal parity keeps Gravity.CENTER's integer rounding from moving the ring half a pixel.
        val viewportWidth = requestedWidth - ((requestedWidth - haloWidth) and 1)
        val contentWidth = (viewportWidth - dp(28)).coerceAtLeast(1)
        val contentTop = dpF(WINDOW_SIZE_DP).roundToInt() + dp(4)
        cardHost.layoutParams = LayoutParams(contentWidth, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = contentTop
        }
        cardHost.measure(
            MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        cardHost.pivotX = contentWidth / 2f
        cardHost.pivotY = 0f
        val bodyBottom = contentTop + cardHost.measuredHeight.toFloat()
        // Geometry is configured in onLayout from ACTUAL bounds, atomically with the centred halo.
        // Reserve room for 2% overshoot so it never clips at the surface's lower/right edge.
        val viewportHeight = (bodyBottom * 1.04f + dp(8)).roundToInt()
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
            surface.geometry.configure(
                width.toFloat(), glowView.height.toFloat(), dpF(13f),
                cardHost.width.toFloat(), cardHost.bottom.toFloat(), dpF(32f)
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
        val (tag, title, accent) = when (link.platform) {
            MediaPlatform.Douyin -> Triple("DOUYIN", "识别到抖音视频链接", 0xFF34D399.toInt())
            MediaPlatform.Bilibili -> Triple("BILIBILI", "识别到 B 站视频链接", 0xFF38BDF8.toInt())
            MediaPlatform.Twitter -> Triple("X / MEDIA", "识别到 X 媒体链接", 0xFFA78BFA.toInt())
            else -> Triple("LINK", "识别到可解析媒体链接", 0xFFE5C384.toInt())
        }
        if (renderedPlatform != link.platform) {
            renderedPlatform = link.platform
            grabberView.background = grabberDrawable(accent)
            platformTag.text = tag
            platformTag.background = tagDrawable(accent)
            cardTitle.text = title
            parseButton.background = buttonDrawable(true, accent)
        }
        val url = link.cleanedUrl.ifBlank { link.originalUrl }
        val display = if (url.length <= 56) url else url.take(53) + "…"
        if (cardUrl.text.toString() != display) cardUrl.text = display
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

    private fun cardDrawable(accentColor: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0xF710131A.toInt())
        cornerRadius = dpF(26f)
        val borderColor = (accentColor and 0x00FFFFFF) or (0x66 shl 24)
        setStroke(max(1, (density * 1.2f).roundToInt()), borderColor)
    }

    private fun grabberDrawable(accent: Int) = GradientDrawable().apply {
        setColor((accent and 0x00FFFFFF) or (0x88 shl 24))
        cornerRadius = dpF(2f)
    }

    private fun tagDrawable(accent: Int) = GradientDrawable().apply {
        setColor(accent)
        cornerRadius = dpF(8f)
    }

    private fun urlCapsuleDrawable() = GradientDrawable().apply {
        setColor(0xFF181C26.toInt())
        cornerRadius = dpF(12f)
        setStroke(max(1, (density * 0.8f).roundToInt()), 0x29FFFFFF)
    }

    private fun buttonDrawable(accent: Boolean, accentColor: Int) = GradientDrawable().apply {
        setColor(if (accent) accentColor else 0x1FFFFFFF)
        if (!accent) setStroke(max(1, (density * 0.8f).roundToInt()), 0x26FFFFFF)
        cornerRadius = dpF(18f)
    }
}
