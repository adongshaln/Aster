package com.adong.adchat.island

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RenderEffect
import android.graphics.Shader
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
 * One overlay, one anchored island. At rest a pen-drawn clay ring sits around the camera. A
 * recognised link pulls a paper tag out of that ring: jelly springs stretch it sideways, then drop
 * it with a bounce, and it closes back along the same path. The ring keeps its position and size
 * throughout, as the tag's eyelet. The window is resized only before opening and after the tag has
 * come to rest closed; one vsync clock samples every spring from absolute time.
 */
class IslandView(context: Context, private val callbacks: Callbacks) : FrameLayout(context) {
    interface Callbacks {
        fun onParseRequested(link: IslandLink)
        fun onLinkShown(link: IslandLink)
        fun onDismissed()
    }

    companion object {
        // Keep the existing device-calibrated resident position.
        private const val CUTOUT_CENTER_Y_DP = 25.5f
        private const val WINDOW_SIZE_DP = 64f
        private const val CARD_TIMEOUT_MS = 12_000L
        private const val BREATH_PERIOD_MS = 5_000L
        private const val MIN_TAP_HOLD_MS = 150L
        /** The open tag's top edge sits this far above the lens centre, clearing the ink ring. */
        private const val TAG_TOP_INSET_DP = 22.5f
        private const val ISLAND_RADIUS_DP = 40f
        private const val CONTENT_BLUR_DP = 6f
        /** Below this width the closing tag is back inside the ring, and the ink gulps once. */
        private const val GULP_WIDTH_DP = 40f
        private const val PRESS_INK = 1.45f
        private const val GULP_KICK = 7f
        private const val BOUNCE_KICK = 5f
        private const val BOUNCE_GAP_S = 0.16
        /** A bounce kick peaks about 43ms later; each haptic tick lands on a visible peak. */
        private const val BOUNCE_PEAK_S = 0.043
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
    private val palette = IslandPalette.forNightMode(IslandPalette.isNight(resources.configuration))
    private fun dp(v: Int): Int = (v * density).roundToInt()
    private fun dpF(v: Float): Float = v * density
    private fun lerp(from: Float, to: Float, p: Float) = from + (to - from) * p

    private var state: State = State.Hidden
    private var attached = false
    private var destroyed = false
    private var windowParams: WindowManager.LayoutParams? = null
    private var expandedViewport = false
    private var pendingMorph: (() -> Unit)? = null
    private var bodyMoving = false
    private var gulped = true
    private var idleFeedbackRunning = false
    private var renderedPlatform: MediaPlatform? = null
    private var deferredCardLink: IslandLink? = null
    private var lastHandledLink: String? = null
    private var lastPressDownUptimeMs = 0L
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var swipeDismissed = false
    private var blurStep = -1
    private val configuredCard = FloatArray(4) { Float.NaN }
    private val inkSpring = IslandJellySpring(1f, 0.3f, 0.5f)
    private val bounceSpring = IslandJellySpring(1f, 0.22f, 0.45f)
    private var secondBounceAt = Double.NaN
    private val bounceTicksAt = doubleArrayOf(Double.NaN, Double.NaN)
    private var clock: TimeAnimator? = null
    private val hideRunnable: Runnable = Runnable { collapse() }
    private val releaseSpringRunnable = Runnable { pressRing(down = false) }
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

    private val ringView: IslandInkRingView
    private val surface: IslandSurfaceView
    private val cardHost: LinearLayout
    private val platformIcon: ImageView
    private val cardTitle: TextView
    private val cardSummary: TextView
    private val parseButton: TextView
    private val cancelButton: TextView
    private var breathAnimator: ValueAnimator? = null

    internal val displayedLink: IslandLink? get() = (state as? State.Card)?.link
    internal val expansionProgress: Float get() = surface.body.progress
    internal val cardTitleText: CharSequence get() = cardTitle.text
    internal val cardSummaryText: CharSequence get() = cardSummary.text
    internal val islandPalette: IslandPalette get() = palette
    internal val ringInk: Float get() = ringView.ink
    internal val ringBounce: Float get() = ringView.bounce

    init {
        clipChildren = false
        clipToPadding = false
        surface = IslandSurfaceView(context).apply { setPalette(palette) }
        val card = buildCard()
        cardHost = card.host
        platformIcon = card.icon
        cardTitle = card.title
        cardSummary = card.summary
        parseButton = card.parse
        cancelButton = card.cancel
        surface.addView(cardHost)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        ringView = IslandInkRingView(context).apply {
            inkColor = palette.clay
            isHapticFeedbackEnabled = true
            contentDescription = "链接灵动岛，点按读取剪贴板"
            isClickable = true
            setOnTouchListener { _, event -> handlePillTouch(event) }
            setOnClickListener { requestClipboardRead() }
        }
        // The ring is ALWAYS 64dp and centred, even when the window is wider: it never moves.
        addView(ringView, LayoutParams(dpF(WINDOW_SIZE_DP).roundToInt(), dpF(WINDOW_SIZE_DP).roundToInt()).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        })
        cardHost.alpha = 0f
        cardHost.scaleX = IslandJellyBody.CONTENT_REST_SCALE
        cardHost.scaleY = IslandJellyBody.CONTENT_REST_SCALE
        cardHost.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        loadTitleTypeface()
    }

    fun attach(): Unit = onMain { ensureAttached() }

    fun show(link: IslandLink): Unit = onMain {
        if (!ensureAttached()) return@onMain
        val previous = displayedLink
        deferredCardLink = null
        state = State.Card(link)
        renderCard(link)
        mainHandler.removeCallbacks(hideRunnable)
        cancelClipboardRead()
        cancelIdleFeedback()
        settleRing()
        setWindowFocusable(false)
        cardHost.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        // Already open and resting: refresh the content only. Anything else (closed, opening,
        // closing) retargets the springs from their current position and velocity.
        val alreadyOpen = previous != null && surface.body.opened && !bodyMoving
        prepareExpandedViewport()
        if (alreadyOpen) {
            hapticTick()
            mainHandler.postDelayed(hideRunnable, CARD_TIMEOUT_MS)
        } else {
            afterViewportLayout { startBody(open = true) }
            hapticDoublePulse()
        }
        // Short-link resolution runs in parallel, never on the drawing/animation path.
        if (previous?.originalUrl != link.originalUrl) callbacks.onLinkShown(link)
    }

    /** Late or stale short-link results must NEVER reopen or replace a dismissed/different island. */
    fun updateCard(link: IslandLink): Unit = onMain {
        val shown = displayedLink ?: return@onMain
        if (shown.originalUrl != link.originalUrl) return@onMain
        state = State.Card(link)
        // Avoid rerasterising the hardware-cached text layer while the tag is moving.
        if (bodyMoving || pendingMorph != null) deferredCardLink = link
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
        // Closed before the tag was ever laid out: nothing to animate back.
        if (surface.active) startBody(open = false) else finishClosed()
    }

    fun destroy(): Unit = onMain {
        destroyed = true
        pendingMorph = null
        state = State.Hidden
        mainHandler.removeCallbacksAndMessages(null)
        breathAnimator?.cancel()
        stopClock()
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

    // ---------- The paper tag ----------

    private data class CardParts(
        val host: LinearLayout, val icon: ImageView, val title: TextView,
        val summary: TextView, val parse: TextView, val cancel: TextView
    )

    private fun buildCard(): CardParts {
        val host = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The top inset clears the ink ring, the tag's eyelet, with 6dp to spare.
            setPadding(dp(18), dp(47), dp(18), dp(18))
        }
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.well)
            }
            imageTintList = ColorStateList.valueOf(palette.glyph)
        }
        headerRow.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(context).apply {
            textSize = 16f
            // The app's own serif replaces this once it has loaded off the main thread.
            typeface = Typeface.SERIF
            letterSpacing = 0.0125f
            setTextColor(palette.ink)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val summary = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(palette.muted)
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
            topMargin = dp(16)
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
        setTextColor(if (primary) palette.onClay else palette.ink)
        background = GradientDrawable().apply {
            setColor(if (primary) palette.clay else palette.well)
            // Concentric with the 40dp tag at an 18dp inset.
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

    private fun loadTitleTypeface() {
        val appContext = context.applicationContext
        Thread({
            val typeface = IslandTypefaces.title(appContext)
            mainHandler.post { if (!destroyed) cardTitle.typeface = typeface }
        }, "island-title-serif").apply {
            isDaemon = true
            start()
        }
    }

    private fun renderCard(link: IslandLink) {
        if (renderedPlatform != link.platform) {
            renderedPlatform = link.platform
            platformIcon.setImageResource(
                when (link.platform) {
                    MediaPlatform.Douyin -> R.drawable.ic_island_douyin
                    MediaPlatform.Bilibili -> R.drawable.ic_island_bilibili
                    MediaPlatform.Twitter -> R.drawable.ic_island_x
                    MediaPlatform.Direct -> R.drawable.ic_island_link
                }
            )
            platformIcon.contentDescription = link.displayName
            cardTitle.text = IslandCardText.title(link.platform)
        }
        val summary = IslandCardText.summary(link.cleanedUrl.ifBlank { link.originalUrl })
        if (cardSummary.text.toString() != summary) cardSummary.text = summary
    }

    // ---------- Touch, clipboard and focus ----------

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
            if (state is State.Card && !surface.body.contains(event.x, event.y)) {
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
                pressRing(down = true)
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = SystemClock.uptimeMillis() - lastPressDownUptimeMs
                mainHandler.postDelayed(releaseSpringRunnable, max(0L, MIN_TAP_HOLD_MS - elapsed))
                // performClick is also the accessibility entry point; wait until clipboard focus arrives.
                ringView.performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelClipboardRead()
                mainHandler.removeCallbacks(releaseSpringRunnable)
                pressRing(down = false)
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

    // ---------- The ink ring ----------

    private fun startBreathing() {
        breathAnimator?.cancel()
        if (state != State.Hidden || expandedViewport || windowVisibility != VISIBLE) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            ringView.alpha = 0.95f
            return
        }
        val startAlpha = ringView.alpha
        var entering = true
        breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BREATH_PERIOD_MS
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val p = it.animatedFraction
                // Only this cached 64dp layer changes alpha: the idle ring costs no redraw.
                val wave = 0.95f + 0.05f * cos(2.0 * Math.PI * p).toFloat()
                ringView.alpha = if (entering && p < 0.15f) {
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
        idleFeedbackRunning = false
    }

    /** The ink presses heavier under a finger and eases back on release; it only grows outward. */
    private fun pressRing(down: Boolean) {
        if (state != State.Hidden || expandedViewport) return
        val now = now()
        if (down) {
            cancelIdleFeedback()
            idleFeedbackRunning = true
            ringView.alpha = 1f
        }
        inkSpring.set(
            if (down) PRESS_INK else 1f, now, 0.0,
            if (down) 0.24f else 0.34f,
            if (down) 0.6f else 0.42f
        )
        if (ValueAnimator.areAnimatorsEnabled()) {
            ensureClock()
        } else {
            inkSpring.jump(inkSpring.target, now)
            ringView.ink = inkSpring.target
            if (!down) {
                idleFeedbackRunning = false
                startBreathing()
            }
        }
    }

    /** Nothing new on the clipboard: the ring bounces twice and a tick lands on each peak. */
    private fun playEmptyFeedback() {
        if (state != State.Hidden || expandedViewport) return
        cancelIdleFeedback()
        idleFeedbackRunning = true
        ringView.alpha = 1f
        val now = now()
        inkSpring.set(1f, now, 0.0, 0.34f, 0.5f)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            inkSpring.jump(1f, now)
            ringView.ink = 1f
            hapticBounceTick(first = true)
            mainHandler.postDelayed({ hapticBounceTick(first = false) }, (BOUNCE_GAP_S * 1000).toLong())
            idleFeedbackRunning = false
            startBreathing()
            return
        }
        bounceSpring.set(1f, now, 0.0, 0.22f, 0.45f)
        bounceSpring.kick(BOUNCE_KICK, now)
        secondBounceAt = now + BOUNCE_GAP_S
        bounceTicksAt[0] = now + BOUNCE_PEAK_S
        bounceTicksAt[1] = now + BOUNCE_GAP_S + BOUNCE_PEAK_S
        ensureClock()
    }

    /** Opening interrupts any press or bounce: release the ink and drop the pending second bounce. */
    private fun settleRing() {
        val now = now()
        secondBounceAt = Double.NaN
        bounceTicksAt.fill(Double.NaN)
        ringView.alpha = 1f
        inkSpring.set(1f, now, 0.0, 0.34f, 0.42f)
        if (ValueAnimator.areAnimatorsEnabled()) {
            ensureClock()
        } else {
            inkSpring.jump(1f, now)
            bounceSpring.jump(1f, now)
            ringView.ink = 1f
            ringView.bounce = 1f
        }
    }

    /** The tag has slipped back into the ring: the ink swallows once. */
    private fun gulp(now: Double) {
        inkSpring.set(1f, now, 0.0, 0.3f, 0.42f)
        inkSpring.kick(GULP_KICK, now)
    }

    // ---------- One vsync clock for every spring ----------

    private fun now(): Double = SystemClock.uptimeMillis() / 1000.0

    private fun ensureClock() {
        if (clock != null || destroyed) return
        clock = TimeAnimator().apply {
            setTimeListener { _, _, _ -> onFrame(now()) }
            start()
        }
    }

    private fun stopClock() {
        clock?.cancel()
        clock = null
    }

    private fun onFrame(now: Double) {
        if (!secondBounceAt.isNaN() && now >= secondBounceAt) {
            bounceSpring.kick(BOUNCE_KICK, secondBounceAt)
            secondBounceAt = Double.NaN
        }
        for (i in bounceTicksAt.indices) {
            if (!bounceTicksAt[i].isNaN() && now >= bounceTicksAt[i]) {
                bounceTicksAt[i] = Double.NaN
                hapticBounceTick(first = i == 0)
            }
        }
        ringView.ink = inkSpring.value(now)
        ringView.bounce = bounceSpring.value(now)
        if (bodyMoving) {
            renderBody(now)
            val body = surface.body
            if (!body.opened && !gulped && body.right - body.left < dpF(GULP_WIDTH_DP)) {
                gulped = true
                gulp(now)
            }
            if (body.isAtRest(now)) onBodyAtRest(now)
        }
        val ringAtRest = inkSpring.isSettled(now, 0.002f) && bounceSpring.isSettled(now, 0.002f) &&
            secondBounceAt.isNaN() && bounceTicksAt[0].isNaN() && bounceTicksAt[1].isNaN()
        if (ringAtRest) {
            ringView.ink = inkSpring.target
            ringView.bounce = bounceSpring.target
            if (idleFeedbackRunning && inkSpring.target == 1f) {
                idleFeedbackRunning = false
                startBreathing()
            }
            if (!bodyMoving) stopClock()
        }
    }

    // ---------- The tag's jelly body ----------

    private fun startBody(open: Boolean) {
        val now = now()
        val body = surface.body
        bodyMoving = true
        if (!ValueAnimator.areAnimatorsEnabled()) {
            body.snap(open, now)
            onBodyAtRest(now)
            return
        }
        if (open) body.open(now) else body.close(now)
        gulped = open
        cardHost.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        ensureClock()
    }

    private fun onBodyAtRest(now: Double) {
        if (!bodyMoving) return
        bodyMoving = false
        val body = surface.body
        body.snap(body.opened, now)
        renderBody(now)
        cardHost.setLayerType(View.LAYER_TYPE_NONE, null)
        if (body.opened && state is State.Card) {
            deferredCardLink?.let { renderCard(it) }
            deferredCardLink = null
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, CARD_TIMEOUT_MS)
        } else if (!body.opened && state == State.Hidden) {
            finishClosed()
        }
    }

    private fun finishClosed() {
        applyIdleViewport()
        startBreathing()
        callbacks.onDismissed()
    }

    private fun renderBody(now: Double) {
        if (!surface.active) return
        surface.render(now)
        val body = surface.body
        // Content is laid out once at its final position; the growing paper reveals and clips it.
        cardHost.alpha = body.contentAlpha
        cardHost.scaleX = body.contentScale
        cardHost.scaleY = body.contentScale
        applyContentBlur(1f - body.contentAlpha)
        val actionable = state is State.Card && body.opened && body.contentAlpha > 0.9f
        if (parseButton.isEnabled != actionable) parseButton.isEnabled = actionable
        if (cancelButton.isEnabled != actionable) cancelButton.isEnabled = actionable
    }

    private fun applyContentBlur(amount: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Quantised, so a RenderEffect is rebuilt only when the radius visibly changes.
        val step = (amount.coerceIn(0f, 1f) * 12f).roundToInt()
        if (step == blurStep) return
        blurStep = step
        val radius = dpF(CONTENT_BLUR_DP) * step / 12f
        cardHost.setRenderEffect(
            if (step == 0) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        )
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
        ringView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
        val ringWidth = dpF(WINDOW_SIZE_DP).roundToInt()
        // Equal parity keeps Gravity.CENTER's integer rounding from moving the ring half a pixel.
        val viewportWidth = requestedWidth - ((requestedWidth - ringWidth) and 1)
        val contentWidth = (viewportWidth - dp(28)).coerceAtLeast(1)
        val tagTop = (dpF(WINDOW_SIZE_DP) / 2f - dpF(TAG_TOP_INSET_DP)).roundToInt()
        cardHost.layoutParams = LayoutParams(contentWidth, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = tagTop
        }
        cardHost.measure(
            MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        // Geometry is configured in onLayout from ACTUAL bounds, atomically with the centred ring.
        // Reserve the drop's bounce plus the paper's shadow so neither ever clips.
        val tagHeight = cardHost.measuredHeight.toFloat()
        val travel = (tagHeight - dpF(IslandInkRing.CORE_RADIUS_DP) * 2f).coerceAtLeast(0f)
        val viewportHeight = (tagTop + tagHeight + travel * IslandJellyBody.MAX_HEIGHT_EXCESS + dp(28)).roundToInt()
        expandedViewport = true
        ringView.isClickable = false
        ringView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        params.width = viewportWidth
        params.height = viewportHeight
        params.y = calcWindowY() // EXACTLY the idle y: the ring must not move.
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
            val anchorX = ringView.left + ringView.width / 2f
            val anchorY = ringView.top + ringView.height / 2f
            val cardLeft = cardHost.left.toFloat()
            val cardTop = cardHost.top.toFloat()
            val cardRight = cardHost.right.toFloat()
            val cardBottom = cardHost.bottom.toFloat()
            // Content scales around the ring's centre x and the tag's top edge.
            cardHost.pivotX = anchorX - cardLeft
            cardHost.pivotY = 0f
            val reshaped = configuredCard[0] != cardLeft || configuredCard[1] != cardTop ||
                configuredCard[2] != cardRight || configuredCard[3] != cardBottom
            configuredCard[0] = cardLeft
            configuredCard[1] = cardTop
            configuredCard[2] = cardRight
            configuredCard[3] = cardBottom
            val body = surface.body
            body.configure(
                anchorX, anchorY, dpF(IslandInkRing.CORE_RADIUS_DP),
                cardLeft, cardTop, cardRight, cardBottom, dpF(ISLAND_RADIUS_DP)
            )
            surface.active = true
            val now = now()
            if (reshaped && body.opened) {
                // A late font or text change resized the open card: retarget from the current motion.
                startBody(open = true)
            }
            renderBody(now)
        } else surface.active = false
        val start = pendingMorph
        pendingMorph = null
        start?.invoke()
    }

    private fun applyIdleViewport() {
        val params = windowParams ?: return
        val size = dpF(WINDOW_SIZE_DP).roundToInt()
        expandedViewport = false
        // Remove the tag before shrinking the overlay so no last frame of paper covers the ring.
        surface.active = false
        cardHost.alpha = 0f
        parseButton.isEnabled = false
        cancelButton.isEnabled = false
        ringView.isClickable = true
        ringView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        params.width = size
        params.height = size
        params.y = calcWindowY()
        runCatching { windowManager.updateViewLayout(this, params) }
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

    // ---------- Haptics (not an animation clock) ----------

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
            ringView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
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

    /** One of the two "nothing new" ticks; the second is a little softer. */
    private fun hapticBounceTick(first: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val composed = runCatching {
                val v = getVibrator() ?: return@runCatching false
                if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                    dispatchVibration(VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, if (first) 0.9f else 0.7f).compose())
                    true
                } else false
            }.getOrDefault(false)
            if (composed) return
        }
        hapticTick()
    }
}
