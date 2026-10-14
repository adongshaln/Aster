package com.adong.adchat.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.adong.adchat.media.MediaPlatform
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 灵动链接岛（Glow & Dynamic Link Island）容器视图：
 *
 * 1. 待机态（Ring）：
 *    - 发光圆环与前摄挖孔同心对齐（优先读取系统 [ android.view.DisplayCutout ] 真实坐标，
 *      回退至红米 Turbo 5 Max 实测校准值）；
 *    - 3.5s 正弦透明度呼吸（仅改硬件层 View.alpha，零 onDraw 开销）；
 *    - 息屏、窗口不可见或系统关闭动画时自动挂起动画，极致省电。
 *
 * 2. 交互与识别叙事（Touch -> Scan -> Flash & Ripple）：
 *    - 按下瞬间：触感反馈（與视觉峰值同步 < 16ms）+ 连续时间阻尼弹簧（ζ ≈ 0.5）驱动线宽外扩；
 *    - 抬手识链：临时获焦读取剪贴板，识别到链接后启动 480ms 强减速彗星扫描弧；
 *    - 收束高潮：在扫描弧收束最亮帧瞬切翡翠绿 + 白芯闪光 + 外扩涟漪，光圈停留展示换色高潮后
 *      丝滑展开暗色曜石灵动岛卡片。
 *
 * 3. 展开态（Obsidian Dynamic Capsule Card）：
 *    - 摒弃生硬的浅色大方块，采用与 OLED 挖孔一脉相承的「曜石黑 + 琥珀/翡翠微光描边」悬浮胶囊卡；
 *    - 支持上滑手势快速收起、点击外部收起、以及平滑进度追踪。
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
        // 默认挖孔校准参数（红米 Turbo 5 Max: density=3.25，挖孔中心距屏幕顶约 25.5dp）
        private const val DEFAULT_CUTOUT_CENTER_Y_DP = 25.5f
        private const val WINDOW_SIZE_DP = IslandGlowView.WINDOW_SIZE_DP

        // 展开态灵动胶囊卡尺寸与超时
        private const val CARD_SIDE_MARGIN_DP = 16
        private const val CARD_TOP_OFFSET_DP = 8
        private const val CARD_TIMEOUT_MS = 12_000L

        // 动画时序参数
        private const val BREATH_PERIOD_MS = 3_500L
        private const val SCAN_DURATION_MS = 480L
        private const val FLASH_HOLD_MS = 160L

        // 阻尼弹簧参数：k = 260 s⁻², c = 16.1 s⁻¹ -> 阻尼比 ζ ≈ 0.5，带约 16% 灵动过冲
        private const val SPRING_K = 260f
        private const val SPRING_C = 16.1f
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
    private var isScreenInteractive = true
    private var windowParams: WindowManager.LayoutParams? = null
    private val hideRunnable = Runnable { collapse() }

    /** 动态探测到的前摄挖孔中心坐标（屏幕绝对像素），未探测到时使用校准默认值。 */
    private var cutoutCenterX: Int = screenSize.x / 2
    private var cutoutCenterY: Int = dpF(DEFAULT_CUTOUT_CENTER_Y_DP).roundToInt()
    private var cutoutCalibrated = false

    /** 上一次已触发处理的链接（规范化后的 URL），同一链接防重复打扰。 */
    private var lastHandledLink: String? = null

    private val glowView: IslandGlowView
    private val pillHost: View
    private val cardHost: LinearLayout
    private val platformBadge: TextView
    private val cardTitle: TextView
    private val cardUrl: TextView
    private val parseButton: TextView
    private val cancelButton: TextView

    // 动画器
    private var breathAnimator: ValueAnimator? = null
    private var springAnimator: ValueAnimator? = null
    private var scanAnimator: ValueAnimator? = null
    private var flashRippleAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null

    // 弹簧物理状态（连点保留速度，绝不跳变）
    private var springX = 1f
    private var springV = 0f
    private var springTarget = 1f

    // 进度指数平滑状态
    private var smoothedProgress = 0f
    private var targetProgress = -1f

    // 息屏/亮屏广播监听：息屏零耗电
    private var screenReceiverRegistered = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    pauseAllIdleAnimations()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    if (state == State.Hidden && attached) {
                        startBreathing()
                    }
                }
            }
        }
    }

    init {
        clipChildren = false
        clipToPadding = false

        glowView = IslandGlowView(context).apply {
            setOnTouchListener { _, event -> handlePillTouch(event) }
        }
        pillHost = glowView

        val card = buildCard()
        cardHost = card.host
        platformBadge = card.badge
        cardTitle = card.title
        cardUrl = card.url
        parseButton = card.parse
        cancelButton = card.cancel

        addView(cardHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            leftMargin = dp(CARD_SIDE_MARGIN_DP)
            rightMargin = dp(CARD_SIDE_MARGIN_DP)
        })
        addView(pillHost, LayoutParams(dp(WINDOW_SIZE_DP.toInt()), dp(WINDOW_SIZE_DP.toInt())).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        })
        cardHost.visibility = View.GONE
    }

    // ---------- 公共接口 ----------

    /** 挂载灵动岛光圈悬浮窗。 */
    fun attach() {
        mainHandler.post { ensureAttached() }
    }

    /** 外部（分享入口 / 剪贴板监听）直接推一条链接进来，带光效过渡展开询问卡片。 */
    fun show(link: IslandLink) {
        mainHandler.post {
            ensureAttached()
            if (state is State.Card) {
                state = State.Card(link)
                renderCard(link)
                resetCardTimeout()
            } else {
                scanThenExpand(link)
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

    /**
     * 更新下载/解析进度（0..1），内部使用指数平滑追踪，单调递增不抖动。
     * 传 -1f 结束进度展示。
     */
    fun setDownloadProgress(value: Float) {
        mainHandler.post {
            if (value < 0f) {
                targetProgress = -1f
                smoothedProgress = 0f
                progressAnimator?.cancel()
                glowView.progress = -1f
                glowView.arcIndeterminate = false
                return@post
            }
            val clamped = value.coerceIn(0f, 1f)
            // 只增不减，避免下载进度回跳导致弧头抖动
            targetProgress = max(targetProgress, clamped)
            glowView.arcIndeterminate = false
            ensureProgressSmoothing()
        }
    }

    /** 缩回挖孔光圈态。 */
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

    /** 销毁悬浮窗并释放所有动画与广播监听。 */
    fun destroy() {
        mainHandler.post {
            setWindowFocusable(false)
            state = State.Hidden
            unregisterScreenReceiver()
            pauseAllIdleAnimations()
            springAnimator?.cancel()
            scanAnimator?.cancel()
            flashRippleAnimator?.cancel()
            progressAnimator?.cancel()
            mainHandler.removeCallbacksAndMessages(null)
            if (attached) {
                runCatching { windowManager.removeView(this@IslandView) }
                attached = false
            }
        }
    }

    // ---------- 窗口生命周期与省电控制 ----------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerScreenReceiver()
        post { calibrateFromDisplayCutout() }
    }

    override fun onDetachedFromWindow() {
        unregisterScreenReceiver()
        pauseAllIdleAnimations()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && isScreenInteractive) {
            if (state == State.Hidden) startBreathing()
        } else {
            pauseAllIdleAnimations()
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        calibrateFromDisplayCutout(insets)
        return super.onApplyWindowInsets(insets)
    }

    /**
     * 自动探测系统 DisplayCutout（API 28+），精确对齐前摄挖孔中心与内径。
     * 若当前 ROM 对悬浮窗未下发 Cutout Insets，则平滑回退至实测校准值。
     */
    private fun calibrateFromDisplayCutout(insets: WindowInsets? = rootWindowInsets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || insets == null) return
        val cutout = insets.displayCutout ?: return
        val rects = cutout.boundingRects
        if (rects.isEmpty()) return
        // 取最靠近屏幕顶部的挖孔区域
        val topHole = rects.minByOrNull { it.top } ?: return
        if (topHole.width() in 20..240 && topHole.centerY() in 10..220) {
            cutoutCenterX = topHole.centerX()
            cutoutCenterY = topHole.centerY()
            // 动态适配圆环内缘：镜头半径 + 1.5dp 纯黑熄屏间隙
            val holeRadiusDp = (max(topHole.width(), topHole.height()) / 2f) / density
            glowView.innerEdgeDp = (holeRadiusDp + 1.5f).coerceIn(9.5f, 16f)
            if (!cutoutCalibrated) {
                cutoutCalibrated = true
                if (state == State.Hidden) {
                    applyPillWindowParams()
                }
            }
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            context.registerReceiver(screenStateReceiver, filter)
            screenReceiverRegistered = true
        }
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { context.unregisterReceiver(screenStateReceiver) }
        screenReceiverRegistered = false
    }

    private fun pauseAllIdleAnimations() {
        breathAnimator?.cancel()
        breathAnimator = null
    }

    // ---------- 曜石灵动胶囊卡片构建 ----------

    private data class CardParts(
        val host: LinearLayout,
        val badge: TextView,
        val title: TextView,
        val url: TextView,
        val parse: TextView,
        val cancel: TextView
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCard(): CardParts {
        val host = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = cardDrawable(accentColor = 0xFF34D399.toInt())
            elevation = dpF(12f)
        }

        // 顶部行：平台胶囊徽章 + 标题 + 副标题提示
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(context).apply {
            textSize = 11f
            setTextColor(0xFF06281E.toInt())
            paint.isFakeBoldText = true
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = badgeDrawable(0xFF34D399.toInt())
        }

        val title = TextView(context).apply {
            textSize = 15.5f
            setTextColor(0xFFF5F5F7.toInt())
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        headerRow.addView(badge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) })

        headerRow.addView(title, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))

        // 中部行：清洗后的单行 URL 预览框
        val url = TextView(context).apply {
            textSize = 12.5f
            setTextColor(0xFFA1A1AA.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = urlBoxDrawable()
        }

        // 底部行：操作按钮（[忽略] + [立即解析]）
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        val cancel = actionButton("忽略", accent = false) { collapse() }
        val parse = actionButton("立即解析", accent = true) {
            hapticTick()
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

        host.addView(headerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        host.addView(url, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        host.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        // 支持上滑手势收起卡片
        var downY = 0f
        host.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (downY - ev.rawY > dp(24)) {
                        collapse()
                        true
                    } else false
                }
                else -> false
            }
        }

        return CardParts(host, badge, title, url, parse, cancel)
    }

    private fun actionButton(text: String, accent: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13.5f
            setTextColor(if (accent) 0xFF090D0B.toInt() else 0xFFD4D4D8.toInt())
            paint.isFakeBoldText = accent
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = buttonDrawable(accent)
            setOnClickListener { onClick() }
        }
    }

    // ---------- 触摸与剪贴板交互 ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && state is State.Card) {
            collapse()
            return true
        }
        return super.onTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handlePillTouch(event: MotionEvent): Boolean {
        if (state is State.Card) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 提前切为可获焦：等系统把窗口置为焦点态后才能读取 Android 10+ 剪贴板
                setWindowFocusable(true)
                // 按下瞬间：触感反馈与弹簧线宽外扩同帧触发（相差 < 16ms）
                hapticTick()
                pressVisual(toPressed = true)
            }
            MotionEvent.ACTION_UP -> {
                pressVisual(toPressed = false)
                // 延迟 180ms 等待窗口焦点切换完成再读剪贴板
                mainHandler.postDelayed({ readClipboardAndHandle() }, 180)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressVisual(toPressed = false)
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
                // 同一链接已处理过：轻闪反馈，不重复展开打扰
                flashNoLink()
                setWindowFocusable(false)
                return
            }
            lastHandledLink = key
            scanThenExpand(link)
        } else {
            flashNoLink()
        }
        setWindowFocusable(false)
    }

    private fun setWindowFocusable(focusable: Boolean) {
        val params = windowParams ?: return
        val newFlags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (newFlags != params.flags) {
            params.flags = newFlags
            runCatching { windowManager.updateViewLayout(this, params) }
        }
    }

    private fun currentClipText(): String? = runCatching {
        val item = clipboard.primaryClip?.getItemAt(0) ?: return null
        item.coerceToText(context)?.toString()
    }.getOrNull()

    // ---------- 光圈动画编排（只改 IslandGlowView 状态参数） ----------

    private fun areAnimationsEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ValueAnimator.areAnimatorsEnabled()
        } else true

    /** 待机呼吸：只改 View.alpha（配合 LAYER_TYPE_HARDWARE 不触发 onDraw）。 */
    private fun startBreathing() {
        breathAnimator?.cancel()
        if (!isScreenInteractive || !areAnimationsEnabled() || state != State.Hidden) {
            glowView.alpha = 1f
            return
        }
        breathAnimator = ValueAnimator.ofFloat(glowView.alpha, 0.74f, 1f).apply {
            duration = BREATH_PERIOD_MS
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = null // 使用三角函数映射正弦呼吸
            addUpdateListener { anim ->
                val frac = anim.animatedFraction
                // 0.75 <-> 1.0 平滑正弦呼吸
                val breath = 0.875f + 0.125f * kotlin.math.cos(2.0 * Math.PI * frac).toFloat()
                glowView.alpha = breath
            }
            start()
        }
    }

    /**
     * 按下 / 回弹：真实时间步长阻尼弹簧（ζ ≈ 0.5），连点保留当前速度 v，绝不跳变。
     * 修复了原实现中 glow 随 springX 反向插值的公式错误。
     */
    private fun pressVisual(toPressed: Boolean) {
        springTarget = if (toPressed) 1.42f else 1f
        if (!areAnimationsEnabled()) {
            springX = springTarget
            springV = 0f
            glowView.lineWidthScale = springTarget
            glowView.glow = if (toPressed) 1f else 0.22f
            return
        }
        if (toPressed) {
            breathAnimator?.cancel()
            glowView.alpha = 1f
        }
        springAnimator?.cancel()
        springAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 550
            var lastTimeNs = System.nanoTime()
            addUpdateListener {
                val nowNs = System.nanoTime()
                val dt = ((nowNs - lastTimeNs) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
                lastTimeNs = nowNs

                // 连续时间阻尼弹簧积分：a = -k(x - target) - c·v
                val accel = -SPRING_K * (springX - springTarget) - SPRING_C * springV
                springV += accel * dt
                springX += springV * dt

                glowView.lineWidthScale = springX.coerceIn(0.85f, 1.85f)
                // 线宽从 1.0 -> 1.42 时，光晕同步从 0.22 -> 1.0 正向增强
                val norm = ((springX - 1f) / 0.42f).coerceIn(0f, 1.25f)
                glowView.glow = (0.22f + 0.78f * norm).coerceIn(0.15f, 1f)
            }
            doOnAnimEnd {
                springX = springTarget
                springV = 0f
                glowView.lineWidthScale = springTarget
                glowView.glow = if (toPressed) 1f else 0.22f
                if (!toPressed && state == State.Hidden && scanAnimator?.isRunning != true) {
                    startBreathing()
                }
            }
            start()
        }
    }

    /**
     * 识别叙事：
     * 1. 亮弧绕一圈（480ms 强减速 + 不确定弧长伸缩）；
     * 2. 收束最亮瞬间：硬切翡翠绿（colorMix = 1f）+ 白芯闪光（flash = 1f）+ 外扩涟漪（rippleT 0->1）；
     * 3. 保持光环可见并展开下方曜石灵动胶囊卡，让用户清晰看到「换色收束 -> 卡片生长」的完整叙事。
     */
    private fun scanThenExpand(link: IslandLink) {
        breathAnimator?.cancel()
        springAnimator?.cancel()
        scanAnimator?.cancel()
        flashRippleAnimator?.cancel()

        if (!areAnimationsEnabled()) {
            glowView.colorMix = 1f
            expand(link)
            return
        }

        glowView.alpha = 1f
        glowView.colorMix = 0f
        glowView.glow = 0.65f
        glowView.arcIndeterminate = true

        scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SCAN_DURATION_MS
            interpolator = DecelerateInterpolator(2.4f)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                glowView.headAngle = 360f * t
                glowView.progress = t
                glowView.glow = 0.65f + 0.35f * t
                glowView.lineWidthScale = 1f + 0.22f * kotlin.math.sin(Math.PI * t).toFloat()
            }
            doOnAnimEnd {
                // 最亮帧瞬切翡翠绿 + 触发收束闪光与涟漪，绝不出现混浊过渡色
                glowView.colorMix = 1f
                glowView.progress = -1f
                glowView.arcIndeterminate = false
                glowView.headAngle = 0f
                glowView.flash = 1f
                glowView.rippleT = 0f
                hapticTick()

                // 展开卡片，同时光圈在顶部播放 360ms 的换色闪光与涟漪余韵
                expand(link)
                flashRippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 380L
                    interpolator = DecelerateInterpolator(1.8f)
                    addUpdateListener { fAnim ->
                        val ft = fAnim.animatedValue as Float
                        glowView.flash = (1f - ft * 1.4f).coerceIn(0f, 1f)
                        glowView.rippleT = ft
                        glowView.glow = 1f - 0.55f * ft
                    }
                    doOnAnimEnd {
                        glowView.flash = 0f
                        glowView.rippleT = -1f
                    }
                    start()
                }
            }
            start()
        }
    }

    private fun expand(link: IslandLink) {
        state = State.Card(link)
        renderCard(link)
        applyCardWindowParams()

        // 保持顶部光环可见（作为灵动岛的发光锚点），卡片从挖孔下方丝滑生长展开
        pillHost.visibility = View.VISIBLE
        cardHost.visibility = View.VISIBLE
        cardHost.alpha = 0f
        cardHost.scaleX = 0.55f
        cardHost.scaleY = 0.35f
        cardHost.pivotX = (screenSize.x / 2f) - dp(CARD_SIDE_MARGIN_DP)
        cardHost.pivotY = 0f
        cardHost.translationY = -dpF(12f)

        cardHost.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator(0.85f))
            .start()

        resetCardTimeout()
        callbacks.onLinkShown(link)
    }

    private fun resetCardTimeout() {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, CARD_TIMEOUT_MS)
    }

    private fun flashNoLink() {
        breathAnimator?.cancel()
        scanAnimator?.cancel()
        if (!areAnimationsEnabled()) return
        ValueAnimator.ofFloat(1f, 0.3f, 1f).apply {
            duration = 380
            addUpdateListener { anim -> glowView.alpha = anim.animatedValue as Float }
            doOnAnimEnd { startBreathing() }
            start()
        }
    }

    /** 进度值指数平滑追踪：避免下载进度跳变引起弧头抖动。 */
    private fun ensureProgressSmoothing() {
        if (progressAnimator?.isRunning == true) return
        progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                if (targetProgress < 0f) {
                    cancel()
                    return@addUpdateListener
                }
                val diff = targetProgress - smoothedProgress
                smoothedProgress += diff * 0.18f
                glowView.progress = smoothedProgress.coerceIn(0f, 1f)
                glowView.headAngle = (glowView.headAngle + 2.2f) % 360f
                if (abs(diff) < 0.002f && targetProgress >= 0.999f) {
                    glowView.progress = 1f
                    cancel()
                }
            }
            start()
        }
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

    private fun hapticTick() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }

    // ---------- 卡片内容与主题着色 ----------

    private fun renderCard(link: IslandLink) {
        val (badgeText, titleText, accentColor) = when (link.platform) {
            MediaPlatform.Douyin -> Triple("抖音", "检测到抖音视频链接", 0xFF34D399.toInt())
            MediaPlatform.Bilibili -> Triple("Bilibili", "检测到 B 站视频链接", 0xFF38BDF8.toInt())
            MediaPlatform.Twitter -> Triple("X / Twitter", "检测到 X 媒体链接", 0xFFA78BFA.toInt())
            else -> Triple("LINK", "检测到可解析媒体链接", 0xFFFBBF24.toInt())
        }
        platformBadge.text = badgeText
        platformBadge.background = badgeDrawable(accentColor)
        cardTitle.text = titleText
        cardHost.background = cardDrawable(accentColor)
        parseButton.background = buttonDrawable(accent = true, accentColor = accentColor)
        cardUrl.text = shortDisplay(link.cleanedUrl.ifBlank { link.originalUrl })
    }

    private fun shortDisplay(url: String): String =
        if (url.length <= 58) url else url.take(55) + "…"

    // ---------- 悬浮窗布局参数（全屏绝对坐标体系，精准对齐挖孔） ----------

    private fun baseParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

    private fun pillWindowTopY(): Int {
        val halfWin = dpF(WINDOW_SIZE_DP / 2f).roundToInt()
        return cutoutCenterY - halfWin
    }

    private fun ensureAttached() {
        if (attached) return
        val sizePx = dpF(WINDOW_SIZE_DP).roundToInt()
        val params = baseParams(sizePx, sizePx).apply {
            x = cutoutCenterX - screenSize.x / 2
            y = pillWindowTopY()
        }
        runCatching { windowManager.addView(this, params) }
        windowParams = params
        attached = true
        pillHost.visibility = View.VISIBLE
        sizePill()
        // 静态光晕走硬件层：待机呼吸只改 View.alpha，不触发 onDraw
        pillHost.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        startBreathing()
    }

    private fun sizePill() {
        val sizePx = dpF(WINDOW_SIZE_DP).roundToInt()
        val lp = (pillHost.layoutParams as? LayoutParams) ?: LayoutParams(sizePx, sizePx)
        lp.width = sizePx
        lp.height = sizePx
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.topMargin = 0
        pillHost.layoutParams = lp
    }

    private fun applyPillWindowParams() {
        val params = windowParams ?: return
        val sizePx = dpF(WINDOW_SIZE_DP).roundToInt()
        params.width = sizePx
        params.height = sizePx
        params.x = cutoutCenterX - screenSize.x / 2
        params.y = pillWindowTopY()
        sizePill()
        runCatching { windowManager.updateViewLayout(this, params) }
        pillHost.visibility = View.VISIBLE
    }

    private fun applyCardWindowParams() {
        val params = windowParams ?: return
        val topY = pillWindowTopY()
        val ringSizePx = dpF(WINDOW_SIZE_DP).roundToInt()

        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.x = 0
        params.y = topY

        // 卡片排布在发光圆环正下方，形成「光圈 + 悬浮灵动胶囊」一体化视觉
        val cardLp = cardHost.layoutParams as LayoutParams
        cardLp.topMargin = ringSizePx + dp(CARD_TOP_OFFSET_DP)
        cardHost.layoutParams = cardLp

        runCatching { windowManager.updateViewLayout(this, params) }
    }

    private fun animateCardCollapse(onEnd: () -> Unit) {
        if (cardHost.visibility != View.VISIBLE) {
            onEnd()
            return
        }
        cardHost.animate()
            .alpha(0f)
            .scaleX(0.6f)
            .scaleY(0.35f)
            .translationY(-dpF(10f))
            .setDuration(200)
            .withEndAction(onEnd)
            .start()
    }

    // ---------- 曜石质感 Drawable 构建 ----------

    private fun cardDrawable(accentColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // 曜石深邃暗底，与前摄挖孔自然融为一体
            setColor(0xF2121316.toInt())
            cornerRadius = dpF(26f)
            // 1dp 柔和发光描边（呼应光圈换色）
            val strokeColor = (accentColor and 0x00FFFFFF) or (0x55 shl 24)
            setStroke(max(1, dp(1)), strokeColor)
        }

    private fun badgeDrawable(accentColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(accentColor)
            cornerRadius = dpF(10f)
        }

    private fun urlBoxDrawable(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFF1C1E22.toInt())
            cornerRadius = dpF(12f)
            setStroke(max(1, (density * 0.8f).roundToInt()), 0x22FFFFFF)
        }

    private fun buttonDrawable(
        accent: Boolean,
        accentColor: Int = 0xFF34D399.toInt()
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            if (accent) {
                setColor(accentColor)
            } else {
                setColor(0x1FFFFFFF)
            }
            cornerRadius = dpF(18f)
        }
}
