package com.adong.adchat.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.adong.adchat.media.MediaPlatform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 灵动链接岛视图（Liquid Chrono-Corona Island v2）：
 *
 * - **定位基准**：采用高精度浮点圆心定位公式，无论光环窗口扩展到多大（64dp），
 *   其几何中心始终 100% 锁死在前置摄像头挖孔中心（25.5dp）；
 * - **常驻呼吸**：3.8 秒一周期的温润正弦呼吸（透明度 0.58 ↔ 0.90，硬件层零重绘）；
 * - **按压破障外扩与冲击波**：按下瞬间触发清脆重击震动 + 环体弹性外扩至 1.68x + 即时扩散波纹，
 *   并对快速轻点（Tap）提供最短 150ms 峰值保持保护，彻底解决手指遮挡与短按反馈不明显的问题；
 * - **三态明确反馈**：
 *   1. 识别到新链接：双侧流光星轨聚拢 -> 顶点聚变闪光 + 多阶脉冲震动 -> 双层极光冲击波 -> 展开卡片；
 *   2. 剪贴板无链接或已处理过：触发琥珀珊瑚金双闪扩散涟漪 + 清脆双轻敲震动（明确告知用户已点中）；
 *   3. 卡片按钮交互：配备按压弹性缩放（0.93x）与多级物理触感。
 */
class IslandView(context: Context, private val callbacks: Callbacks) : FrameLayout(context) {

    interface Callbacks {
        /** 用户选择解析，跳转下载页。 */
        fun onParseRequested(link: IslandLink)
        /** 一条链接被展示（卡片展开）。 */
        fun onLinkShown(link: IslandLink)
        /** 卡片被收起。 */
        fun onDismissed()
    }

    companion object {
        // 严格采用真机校准的圆心定位参数（红米 Turbo 5 Max: density=3.25）
        /** 挖孔中心距屏幕顶部的距离（dp），圆环与挖孔中心重合。 */
        private const val CUTOUT_CENTER_Y_DP = 25.5f
        /** 悬浮窗边长（dp）：64dp 画布容纳外扩光环与冲击波。 */
        private const val WINDOW_SIZE_DP = IslandGlowView.WINDOW_SIZE_DP

        private const val CARD_TIMEOUT_MS = 12_000L
        /** 常驻呼吸周期（3.8s 正弦呼吸，只改硬件层 alpha，零重绘）。 */
        private const val BREATH_PERIOD_MS = 3_800L
        /** 事件触发星冕聚变动画时长（ms）。 */
        private const val EVENT_FEEDBACK_MS = 740L
        /** 快速轻点（Tap）时的最小按压外扩保持时间（ms），防止手指秒抬导致动画来不及展开。 */
        private const val MIN_TAP_HOLD_MS = 150L

        // 二阶欠阻尼物理弹簧参数：ω_n ≈ 19.5 rad/s, ζ ≈ 0.34（更具爆发力与Q弹过冲感）
        private const val SPRING_K = 380f
        private const val SPRING_C = 13.2f
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

    private var state: State = State.Hidden
    private var attached = false
    private var windowParams: WindowManager.LayoutParams? = null
    private val hideRunnable = Runnable { collapse() }
    private val releaseSpringRunnable = Runnable { animateSpringPress(pressed = false) }
    /** 上一次已触发处理的链接（规范化后的 URL），同一链接只触发一次。 */
    private var lastHandledLink: String? = null
    private var lastPressDownUptimeMs: Long = 0L

    private val glowView: IslandGlowView
    private val pillHost: View
    private val cardHost: LinearLayout
    private val grabberView: View
    private val platformTag: TextView
    private val cardTitle: TextView
    private val cardUrl: TextView
    private val parseButton: TextView
    private val cancelButton: TextView

    // 动画编排器
    private var breathAnimator: ValueAnimator? = null
    private var springAnimator: ValueAnimator? = null
    private var pressWaveAnimator: ValueAnimator? = null
    private var eventAnimator: ValueAnimator? = null
    private var nudgeAnimator: ValueAnimator? = null

    // 连续时间物理弹簧状态（保留实时位置与速度，快速连点丝滑不跳帧）
    private var springPos = 1f
    private var springVel = 0f
    private var springTarget = 1f
    private var angularVel = 0f

    init {
        clipChildren = false
        clipToPadding = false

        glowView = IslandGlowView(context).apply {
            isHapticFeedbackEnabled = true
            setOnTouchListener { _, event -> handlePillTouch(event) }
        }
        pillHost = glowView

        val card = buildCard()
        cardHost = card.host
        grabberView = card.grabber
        platformTag = card.tag
        cardTitle = card.title
        cardUrl = card.url
        parseButton = card.parse
        cancelButton = card.cancel

        addView(cardHost, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            leftMargin = dp(14)
            rightMargin = dp(14)
        })
        addView(pillHost)
        cardHost.visibility = View.GONE
    }

    // ---------- 公共接口 ----------

    /** 挂载悬浮窗（常驻镜头光环形态）。 */
    fun attach() {
        mainHandler.post { ensureAttached() }
    }

    /** 外部（分享入口 / 短链展开）直接推一条链接进来，触发星冕特效后展开询问卡片。 */
    fun show(link: IslandLink) {
        mainHandler.post {
            ensureAttached()
            if (state is State.Card) {
                state = State.Card(link)
                renderCard(link)
                hapticTick()
                mainHandler.removeCallbacks(hideRunnable)
                mainHandler.postDelayed(hideRunnable, CARD_TIMEOUT_MS)
            } else {
                playEventFeedbackThenExpand(link)
            }
        }
    }

    /**
     * 短链展开完成后静默更新卡片文本：不触发 onLinkShown、不重播展开动画、不重置超时。
     */
    fun updateCard(link: IslandLink) {
        mainHandler.post {
            ensureAttached()
            if (state is State.Card) {
                state = State.Card(link)
                renderCard(link)
            } else {
                expand(link)
            }
        }
    }

    /** 缩回挖孔光环态。 */
    fun collapse() {
        mainHandler.post {
            setWindowFocusable(false)
            if (state == State.Hidden) return@post
            state = State.Hidden
            mainHandler.removeCallbacks(hideRunnable)
            animateCardCollapse {
                cardHost.visibility = View.GONE
                applyPillWindowParams()
                glowView.reset()
                startBreathing()
                callbacks.onDismissed()
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            setWindowFocusable(false)
            state = State.Hidden
            breathAnimator?.cancel()
            springAnimator?.cancel()
            pressWaveAnimator?.cancel()
            eventAnimator?.cancel()
            nudgeAnimator?.cancel()
            mainHandler.removeCallbacksAndMessages(null)
            if (attached) {
                runCatching { windowManager.removeView(this@IslandView) }
                attached = false
            }
        }
    }

    // ---------- 高定黑曜石悬浮卡片构建 ----------

    private data class CardParts(
        val host: LinearLayout,
        val grabber: View,
        val tag: TextView,
        val title: TextView,
        val url: TextView,
        val parse: TextView,
        val cancel: TextView
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCard(): CardParts {
        val host = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            background = cardDrawable(0xFF38BDF8.toInt())
            elevation = dpF(12f)
        }

        // 顶部灵动岛金属流光导引条
        val grabber = View(context).apply {
            background = grabberDrawable(0xFF38BDF8.toInt())
        }
        host.addView(grabber, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })

        // 标题行：平台高定徽章 + 标题文本
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

        headerRow.addView(tag, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(9) })

        headerRow.addView(title, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))

        // 链接展示舱
        val url = TextView(context).apply {
            textSize = 12.5f
            setTextColor(0xFFCBD5E1.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = urlCapsuleDrawable()
        }

        // 底部按钮行
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        val cancel = actionButton("收起", accent = false) {
            hapticTick()
            collapse()
        }
        val parse = actionButton("立即解析", accent = true) {
            hapticClick()
            (state as? State.Card)?.let { callbacks.onParseRequested(it.link) }
        }

        buttonRow.addView(cancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(10) })

        buttonRow.addView(parse, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        host.addView(headerRow)
        host.addView(url, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        host.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        // 支持上滑轻扫收起卡片（带触感反馈）
        var touchDownY = 0f
        host.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownY = ev.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (touchDownY - ev.rawY > dp(20)) {
                        hapticTick()
                        collapse()
                        true
                    } else false
                }
                else -> false
            }
        }

        return CardParts(host, grabber, tag, title, url, parse, cancel)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun actionButton(text: String, accent: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13.5f
            setTextColor(if (accent) 0xFF050B12.toInt() else 0xFFE2E8F0.toInt())
            if (accent) paint.isFakeBoldText = true
            setPadding(dp(20), dp(9), dp(20), dp(9))
            background = buttonDrawable(accent, 0xFF38BDF8.toInt())
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(75L).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(190L)
                            .setInterpolator(OvershootInterpolator(2.2f))
                            .start()
                    }
                }
                false
            }
            setOnClickListener { onClick() }
        }
    }

    // ---------- 交互与剪贴板识链 ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && state is State.Card) {
            collapse()
            return true
        }
        return super.onTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handlePillTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mainHandler.removeCallbacks(releaseSpringRunnable)
                lastPressDownUptimeMs = SystemClock.uptimeMillis()
                // 提前切为可获焦：等系统把焦点切到本窗口后才能读剪贴板
                setWindowFocusable(true)
                // 按下瞬间：清脆重击震动 + 弹簧大幅外扩 + 按压冲击波同步触发
                hapticClick()
                animateSpringPress(pressed = true)
                playPressShockwave()
            }
            MotionEvent.ACTION_UP -> {
                // 保证快速轻点（Tap）也有至少 MIN_TAP_HOLD_MS 的峰值外扩展示时间，不被瞬间截断
                val elapsed = SystemClock.uptimeMillis() - lastPressDownUptimeMs
                val releaseDelay = max(0L, MIN_TAP_HOLD_MS - elapsed)
                mainHandler.postDelayed(releaseSpringRunnable, releaseDelay)
                // 延迟 180ms 等焦点真正切换过来，再读剪贴板
                mainHandler.postDelayed({ readClipboardAndHandle() }, 180L)
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(releaseSpringRunnable)
                animateSpringPress(pressed = false)
                setWindowFocusable(false)
            }
        }
        return true
    }

    private fun readClipboardAndHandle() {
        val clip = currentClipText()
        val link = clip?.let { IslandLinkParser.parse(it) }
        if (link != null) {
            val key = link.cleanedUrl.ifBlank { link.originalUrl }
            if (key == lastHandledLink) {
                // 同一链接已处理过：触发琥珀金双闪 + 双轻敲震动，也可再次展开方便用户重新操作
                playSubtleEmptyNudge()
                setWindowFocusable(false)
                return
            }
            lastHandledLink = key
            playEventFeedbackThenExpand(link)
        } else {
            playSubtleEmptyNudge()
        }
        setWindowFocusable(false)
    }

    private fun setWindowFocusable(focusable: Boolean) {
        val params = windowParams ?: return
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager.updateViewLayout(this, params) }
    }

    private fun currentClipText(): String? = runCatching {
        val item = clipboard.primaryClip?.getItemAt(0) ?: return null
        item.coerceToText(context)?.toString()
    }.getOrNull()

    // ---------- 核心动画编排（常驻呼吸 / 按压冲击波 / 星冕聚变 / 空点双闪） ----------

    /**
     * 1. 常驻呼吸灯效果：
     * 仅改变硬件加速层 View.alpha（0.58 ↔ 0.90），温润醒目且零重绘开销。
     */
    private fun startBreathing() {
        breathAnimator?.cancel()
        if (state != State.Hidden) return
        val startAlpha = glowView.alpha
        breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BREATH_PERIOD_MS
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener { anim ->
                val p = anim.animatedFraction
                // 余弦平滑呼吸波：在 0.58 .. 0.90 之间起伏
                val wave = 0.74f + 0.16f * cos(2.0 * Math.PI * p).toFloat()
                glowView.alpha = if (p < 0.15f) {
                    startAlpha + (wave - startAlpha) * (p / 0.15f)
                } else wave
            }
            start()
        }
    }

    /**
     * 2. 按下瞬间即时扩散光波（Press Shockwave）：
     * 哪怕手指遮住挖孔中心，冲击波也会迅速向外扩散至 64dp 窗口边缘，提供即时视觉确认。
     */
    private fun playPressShockwave() {
        if (eventAnimator?.isRunning == true) return
        pressWaveAnimator?.cancel()
        pressWaveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                if (eventAnimator?.isRunning == true || nudgeAnimator?.isRunning == true) return@addUpdateListener
                val p = anim.animatedFraction
                glowView.rippleProgress = p
                glowView.flashIntensity = (1f - p) * 0.72f
            }
            doOnAnimEnd {
                if (eventAnimator?.isRunning != true && nudgeAnimator?.isRunning != true) {
                    glowView.rippleProgress = 0f
                    glowView.flashIntensity = 0f
                }
            }
            start()
        }
    }

    /**
     * 3. 点击物理弹簧阻尼效果：
     * 连续时间二阶欠阻尼谐振弹簧（目标外扩 1.68x），同时赋予流光高角速度冲量。
     */
    private fun animateSpringPress(pressed: Boolean) {
        if (eventAnimator?.isRunning == true) return
        if (pressed) {
            nudgeAnimator?.cancel()
            breathAnimator?.cancel()
            glowView.alpha = 1f
            glowView.nudgeShift = 0f
            springTarget = 1.68f
            // 给初始外扩速度冲量，使按下第 1~2 帧即明显外扩
            springVel = max(springVel, 6.5f)
            angularVel = 360f
        } else {
            springTarget = 1.0f
            angularVel = max(angularVel, 150f)
        }

        springAnimator?.cancel()
        springAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 640L
            var lastNs = System.nanoTime()
            addUpdateListener {
                val nowNs = System.nanoTime()
                val dt = ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
                lastNs = nowNs

                // 二阶弹簧微分方程积分：a = -k(x - target) - c*v
                val accel = -SPRING_K * (springPos - springTarget) - SPRING_C * springVel
                springVel += accel * dt
                springPos += springVel * dt

                // 角动量流体阻尼衰减
                angularVel *= exp(-4.0f * dt)
                glowView.orbitAngle = (glowView.orbitAngle + angularVel * dt) % 360f

                glowView.springScale = springPos.coerceIn(0.84f, 2.05f)
                val norm = ((springPos - 1f) / 0.68f).coerceIn(0f, 1.35f)
                glowView.glowIntensity = (IslandGlowView.BASE_GLOW_INTENSITY + 0.72f * norm)
                    .coerceIn(0.28f, 1.15f)
            }
            doOnAnimEnd {
                springPos = springTarget
                springVel = 0f
                glowView.springScale = springTarget
                glowView.glowIntensity = if (pressed) 1.0f else IslandGlowView.BASE_GLOW_INTENSITY
                if (!pressed && state == State.Hidden &&
                    eventAnimator?.isRunning != true && nudgeAnimator?.isRunning != true
                ) {
                    startBreathing()
                }
            }
            start()
        }
    }

    /**
     * 4. 触发事件特殊反馈（「星冕聚变 · 极光涟漪」）：
     * - 0.00..0.38：双侧流光星轨从镜头底部对称加速攀升至顶点，同时色温渐变为极光冰青；
     * - 0.38：顶点碰撞触发多阶脉冲重击触感 + 星芒高光全环爆发 + 弹簧强力外扩；
     * - 0.38..1.00：双层同心光波涟漪向 64dp 窗口边缘扩散消散，随后丝滑展开悬浮卡片。
     */
    private fun playEventFeedbackThenExpand(link: IslandLink) {
        mainHandler.removeCallbacks(releaseSpringRunnable)
        breathAnimator?.cancel()
        springAnimator?.cancel()
        pressWaveAnimator?.cancel()
        nudgeAnimator?.cancel()
        eventAnimator?.cancel()

        glowView.alpha = 1f
        glowView.nudgeShift = 0f
        glowView.colorShift = 0.35f
        glowView.convergeT = 0f
        glowView.flashIntensity = 0.25f
        glowView.rippleProgress = 0f

        var climaxTriggered = false
        eventAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EVENT_FEEDBACK_MS
            interpolator = null
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                if (t < 0.38f) {
                    // 第一幕：双侧流光加速聚拢 + 色温向极光青过渡
                    val p = t / 0.38f
                    val eased = p * p * (3f - 2f * p)
                    glowView.convergeT = eased
                    glowView.colorShift = 0.35f + 0.65f * eased
                    glowView.glowIntensity = 0.55f + 0.55f * eased
                    glowView.springScale = 1.12f + 0.36f * sin(Math.PI * eased).toFloat()
                    glowView.orbitAngle -= 3.6f
                } else {
                    // 第二 & 第三幕：顶点聚变闪光 + 极光换色 + 双层同心冲击波
                    if (!climaxTriggered) {
                        climaxTriggered = true
                        glowView.convergeT = 0f
                        glowView.colorShift = 1f
                        hapticDoublePulse()
                    }
                    val p = (t - 0.38f) / 0.62f
                    val decel = 1f - (1f - p) * (1f - p)
                    glowView.rippleProgress = decel
                    glowView.flashIntensity = exp(-3.6f * p)
                    // 阻尼余振外扩（峰值达 1.65x，极具视觉张力）
                    val dampedWave = exp(-4.2f * p) * cos(2.0 * Math.PI * p).toFloat()
                    glowView.springScale = 1f + 0.62f * dampedWave
                    glowView.glowIntensity = (1.15f - 0.68f * decel).coerceAtLeast(IslandGlowView.BASE_GLOW_INTENSITY)
                    glowView.orbitAngle += 2.6f * (1f - p)
                }
            }
            doOnAnimEnd {
                glowView.reset()
                expand(link)
            }
            start()
        }
    }

    /**
     * 5. 未识别到新链接时的明确反馈（「琥珀流金 · 双闪回响」）：
     * 解决原版向内微缩且无震动导致用户以为“没点上”的问题：
     * 切换为琥珀珊瑚橙色温 + 两次弹性外扩脉冲 + 向外扩散波纹 + 清脆双轻敲触感。
     */
    private fun playSubtleEmptyNudge() {
        if (eventAnimator?.isRunning == true) return
        mainHandler.removeCallbacks(releaseSpringRunnable)
        breathAnimator?.cancel()
        springAnimator?.cancel()
        pressWaveAnimator?.cancel()
        nudgeAnimator?.cancel()

        hapticEmptyDoubleTick()

        val baseOrbit = glowView.orbitAngle
        glowView.alpha = 1f
        glowView.colorShift = 0f
        nudgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560L
            interpolator = DecelerateInterpolator(1.3f)
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                // 琥珀珊瑚金双闪色温包络
                val colorEnv = sin(Math.PI * (1f - (1f - t) * (1f - t))).toFloat().coerceIn(0f, 1f)
                glowView.nudgeShift = colorEnv
                // 双峰外扩搏动（明确向外扩张而非向内缩进挖孔）
                val pulseWave = exp(-3.2f * t) * abs(sin(2.0 * Math.PI * 1.15 * t)).toFloat()
                glowView.springScale = 1f + 0.45f * pulseWave
                glowView.glowIntensity = IslandGlowView.BASE_GLOW_INTENSITY + 0.65f * (1f - t)
                glowView.flashIntensity = 0.55f * (1f - t)
                glowView.rippleProgress = t
                glowView.orbitAngle = baseOrbit + 210f * t
            }
            doOnAnimEnd {
                glowView.reset()
                startBreathing()
            }
            start()
        }
    }

    private fun expand(link: IslandLink) {
        state = State.Card(link)
        renderCard(link)
        applyCardWindowParams()
        cardHost.visibility = View.VISIBLE
        cardHost.alpha = 0f
        cardHost.scaleX = 0.52f
        cardHost.scaleY = 0.34f
        cardHost.translationY = -dpF(16f)
        cardHost.pivotX = (screenSize.x - dp(28)) / 2f
        cardHost.pivotY = 0f
        cardHost.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(290L)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, CARD_TIMEOUT_MS)
        callbacks.onLinkShown(link)
    }

    private inline fun ValueAnimator.doOnAnimEnd(crossinline onEnd: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            var canceled = false
            override fun onAnimationStart(a: android.animation.Animator) {}
            override fun onAnimationCancel(a: android.animation.Animator) { canceled = true }
            override fun onAnimationRepeat(a: android.animation.Animator) {}
            override fun onAnimationEnd(a: android.animation.Animator) { if (!canceled) onEnd() }
        })
    }

    // ---------- 多维物理触感引擎（需 AndroidManifest 声明 VIBRATE 权限） ----------

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
                val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
                vibrator.vibrate(effect, attrs)
            } else {
                @Suppress("DEPRECATION")
                val audioAttrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, audioAttrs)
            }
        }
    }

    /** 按下光环或点击主按钮：饱满清脆的单次重击触感 */
    private fun hapticClick() {
        runCatching {
            glowView.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val composed = runCatching {
                val v = getVibrator() ?: return@runCatching false
                if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                    dispatchVibration(
                        VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                            .compose()
                    )
                    true
                } else false
            }.getOrDefault(false)
            if (composed) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dispatchVibration(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            dispatchVibration(VibrationEffect.createOneShot(22L, 240))
        }
    }

    /** 辅助操作或轻扫收起：轻巧刻度触感 */
    private fun hapticTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val composed = runCatching {
                val v = getVibrator() ?: return@runCatching false
                if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                    dispatchVibration(
                        VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.85f)
                            .compose()
                    )
                    true
                } else false
            }.getOrDefault(false)
            if (composed) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dispatchVibration(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            dispatchVibration(VibrationEffect.createOneShot(12L, 160))
        }
    }

    /** 识别到新链接星冕聚变爆发：具有明显节奏感的双阶高能脉冲震动 */
    private fun hapticDoublePulse() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val composed = runCatching {
                val v = getVibrator() ?: return@runCatching false
                if (v.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                        VibrationEffect.Composition.PRIMITIVE_CLICK
                    )
                ) {
                    dispatchVibration(
                        VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.85f)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 48)
                            .compose()
                    )
                    true
                } else false
            }.getOrDefault(false)
            if (composed) return
        }
        dispatchVibration(
            VibrationEffect.createWaveform(
                longArrayOf(0L, 18L, 48L, 28L),
                intArrayOf(0, 185, 0, 255),
                -1
            )
        )
    }

    /** 空点或重复链接提示：清脆的双轻敲触感（明确反馈已点中但无新链接） */
    private fun hapticEmptyDoubleTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val composed = runCatching {
                val v = getVibrator() ?: return@runCatching false
                if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                    dispatchVibration(
                        VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.9f)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.65f, 55)
                            .compose()
                    )
                    true
                } else false
            }.getOrDefault(false)
            if (composed) return
        }
        dispatchVibration(
            VibrationEffect.createWaveform(
                longArrayOf(0L, 12L, 50L, 12L),
                intArrayOf(0, 190, 0, 130),
                -1
            )
        )
    }

    // ---------- 卡片渲染 ----------

    private fun renderCard(link: IslandLink) {
        val (tagText, titleText, accentColor) = when (link.platform) {
            MediaPlatform.Douyin -> Triple("DOUYIN", "识别到抖音视频链接", 0xFF34D399.toInt())
            MediaPlatform.Bilibili -> Triple("BILIBILI", "识别到 B 站视频链接", 0xFF38BDF8.toInt())
            MediaPlatform.Twitter -> Triple("X / MEDIA", "识别到 X 媒体链接", 0xFFA78BFA.toInt())
            else -> Triple("LINK", "识别到可解析媒体链接", 0xFFE5C384.toInt())
        }
        grabberView.background = grabberDrawable(accentColor)
        platformTag.text = tagText
        platformTag.background = tagDrawable(accentColor)
        cardTitle.text = titleText
        cardHost.background = cardDrawable(accentColor)
        parseButton.background = buttonDrawable(accent = true, accentColor = accentColor)
        cardUrl.text = shortDisplay(link.cleanedUrl.ifBlank { link.originalUrl })
    }

    private fun shortDisplay(url: String): String =
        if (url.length <= 56) url else url.take(53) + "…"

    // ---------- 悬浮窗参数（高精度浮点圆心定位，消除取整偏差） ----------

    private fun calcPillWindowY(): Int {
        val centerPx = dpF(CUTOUT_CENTER_Y_DP)
        val halfWinPx = dpF(WINDOW_SIZE_DP) / 2f
        return (centerPx - halfWinPx).roundToInt() - statusBarHeight()
    }

    private fun baseParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

    private fun ensureAttached() {
        if (attached) return
        val winSizePx = dpF(WINDOW_SIZE_DP).roundToInt()
        val params = baseParams(winSizePx, winSizePx)
        params.y = calcPillWindowY()
        runCatching { windowManager.addView(this, params) }
        windowParams = params
        attached = true
        pillHost.visibility = View.VISIBLE
        sizePill()
        pillHost.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        startBreathing()
    }

    private fun sizePill() {
        val lp = pillHost.layoutParams as? LayoutParams ?: LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        lp.width = LayoutParams.MATCH_PARENT
        lp.height = LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        pillHost.layoutParams = lp
    }

    private fun applyPillWindowParams() {
        val params = windowParams ?: return
        val winSizePx = dpF(WINDOW_SIZE_DP).roundToInt()
        params.width = winSizePx
        params.height = winSizePx
        params.y = calcPillWindowY()
        runCatching { windowManager.updateViewLayout(this, params) }
        pillHost.visibility = View.VISIBLE
    }

    private fun applyCardWindowParams() {
        val params = windowParams ?: return
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        // 卡片让开状态栏和挖孔，从其下缘开始
        params.y = statusBarHeight() + dp(4)
        runCatching { windowManager.updateViewLayout(this, params) }
        pillHost.visibility = View.GONE
    }

    private fun statusBarHeight(): Int {
        val resources = context.resources
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(30)
    }

    // ---------- 卡片收起动画与绘制资源 ----------

    private fun animateCardCollapse(onEnd: () -> Unit) {
        if (cardHost.visibility != View.VISIBLE) {
            onEnd()
            return
        }
        cardHost.animate()
            .alpha(0f)
            .scaleX(0.60f)
            .scaleY(0.36f)
            .translationY(-dpF(12f))
            .setDuration(210L)
            .withEndAction {
                cardHost.translationY = 0f
                onEnd()
            }
            .start()
    }

    private fun grabberDrawable(accentColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val tinted = (accentColor and 0x00FFFFFF) or (0x88 shl 24)
            setColor(tinted)
            cornerRadius = dpF(2f)
        }

    private fun cardDrawable(accentColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xF710131A.toInt())
            cornerRadius = dpF(26f)
            val borderCol = (accentColor and 0x00FFFFFF) or (0x66 shl 24)
            setStroke(max(1, (density * 1.2f).roundToInt()), borderCol)
        }

    private fun tagDrawable(accentColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accentColor)
            cornerRadius = dpF(8f)
        }

    private fun urlCapsuleDrawable(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFF181C26.toInt())
            cornerRadius = dpF(12f)
            setStroke(max(1, (density * 0.8f).roundToInt()), 0x29FFFFFF)
        }

    private fun buttonDrawable(accent: Boolean, accentColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            if (accent) {
                setColor(accentColor)
            } else {
                setColor(0x1FFFFFFF)
                setStroke(max(1, (density * 0.8f).roundToInt()), 0x26FFFFFF)
            }
            cornerRadius = dpF(18f)
        }
}
