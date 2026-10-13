package com.adong.adchat.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.adong.adchat.media.MediaPlatform
import kotlin.math.roundToInt

/**
 * 灵动岛视图：
 * - 平时：发光圆环（外径 28dp，中心与挖孔中心重合）叠在前摄挖孔上，
 *   只有 3.5s 一周期的透明度呼吸，不遮挡屏幕其他区域的触摸；
 * - 点击圆环：窗口临时获焦读剪贴板 → 识别到媒体链接后光弧扫一圈换色，
 *   窗口放大为全屏宽 × 屏高 1/5 的询问卡片；
 * - [解析] 跳转 Aster 下载页自动填入链接；[不用] 缩回挖孔。
 *
 * 光圈渲染与动画编排见 [IslandGlowView] 与 docs/GLOW_ISLAND_SPEC.md。
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
        // 按红米 Turbo 5 Max（density=3.25）实测校准：挖孔宽 66px ≈ 20.3dp，
        // 挖孔中心距屏幕顶部 76px ≈ 23.4dp；圆环外径只比挖孔大一圈。
        /** 圆环外径：挖孔约 20dp，只比它大一圈。 */
        private const val PILL_SIZE_DP = IslandGlowView.RING_OUTER_DP
        /** 挖孔中心距屏幕顶部的距离（dp），圆环与挖孔中心重合。 */
        private const val CUTOUT_CENTER_Y_DP = 25.5f
        /** 悬浮窗边长：光圈外径 + 光晕 + 黑垫，按涟漪最大范围定。 */
        private const val WINDOW_SIZE_DP = IslandGlowView.WINDOW_SIZE_DP
        private const val CARD_HEIGHT_RATIO = 0.2f
        private const val CARD_TIMEOUT_MS = 12_000L
        /** 待机呼吸周期（正弦，只改 View.alpha，不触发 onDraw）。 */
        private const val BREATH_PERIOD_MS = 3_500L
        /** 识别扫描时长：亮弧绕一圈，收束最亮帧换色。 */
        private const val SCAN_DURATION_MS = 500L
        // 自写阻尼弹簧（无 androidx.dynamicanimation 依赖）：
        // k = 260 s⁻²、c = 16.1 s⁻¹ → 阻尼比 ζ = c/(2√k) ≈ 0.5，超调约 16%，约 400ms 收敛。
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
    private val screenSize = Point().also { windowManager.defaultDisplay.getRealSize(it) }
    private fun dp(v: Int) = (v * density).toInt()

    private var state: State = State.Hidden
    private var attached = false
    private var windowParams: WindowManager.LayoutParams? = null
    private val hideRunnable = Runnable { collapse() }
    /** 上一次已触发处理的链接（规范化后的 URL），同一链接只触发一次。 */
    private var lastHandledLink: String? = null

    private val glowView: IslandGlowView
    private val pillHost: View
    private val cardHost: LinearLayout
    private val cardTitle: TextView
    private val cardUrl: TextView
    private val parseButton: TextView
    private val cancelButton: TextView

    init {
        glowView = IslandGlowView(context).apply {
            setOnTouchListener { _, event -> handlePillTouch(event) }
        }
        pillHost = glowView
        val card = buildCard()
        cardHost = card.host
        cardTitle = card.title
        cardUrl = card.url
        parseButton = card.parse
        cancelButton = card.cancel
        addView(cardHost)
        addView(pillHost)
        cardHost.visibility = View.GONE
    }

    // ---------- 公共接口 ----------

    /** 挂载悬浮窗（隐形胶囊形态）。 */
    fun attach() {
        mainHandler.post { ensureAttached() }
    }

    /** 外部（分享入口 / 短链展开）直接推一条链接进来，展开询问卡片。 */
    fun show(link: IslandLink) {
        mainHandler.post {
            ensureAttached()
            expand(link)
        }
    }

    /**
     * 短链展开完成后静默更新卡片文本：不触发 onLinkShown、不重播展开动画、
     * 不重置超时（expand 里的 hideRunnable 仍在计时）。
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

    /** 缩回挖孔。 */
    fun collapse() {
        mainHandler.post {
            setWindowFocusable(false)
            if (state == State.Hidden) return@post
            state = State.Hidden
            mainHandler.removeCallbacks(hideRunnable)
            animateCardCollapse {
                cardHost.visibility = View.GONE
                applyPillWindowParams()
                // 复位光圈参数并重启待机呼吸
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
            scanAnimator?.cancel()
            mainHandler.removeCallbacksAndMessages(null)
            if (attached) {
                runCatching { windowManager.removeView(this@IslandView) }
                attached = false
            }
        }
    }

    // ---------- 构建 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCard(): CardParts {
        val host = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(14))
            background = cardDrawable()
        }
        val title = TextView(context).apply {
            textSize = 16f
            setTextColor(0xFF1F1E1D.toInt())
            paint.isFakeBoldText = true
            maxLines = 1
        }
        val url = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF87867F.toInt())
            maxLines = 1
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val parse = actionButton("解析", accent = true) {
            (state as? State.Card)?.let { callbacks.onParseRequested(it.link) }
        }
        val cancel = actionButton("不用了", accent = false) { collapse() }
        buttonRow.addView(cancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(10) })
        buttonRow.addView(parse, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        host.addView(title)
        host.addView(url, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        host.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        return CardParts(host, title, url, parse, cancel)
    }

    private data class CardParts(
        val host: LinearLayout,
        val title: TextView,
        val url: TextView,
        val parse: TextView,
        val cancel: TextView
    )

    private fun actionButton(text: String, accent: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (accent) 0xFFFFFFFF.toInt() else 0xFF1F1E1D.toInt())
            if (accent) paint.isFakeBoldText = true
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = buttonDrawable(accent)
            setOnClickListener { onClick() }
        }
    }

    // ---------- 交互 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun handlePillTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 提前切为可获焦：等系统把焦点切到本窗口后才能读剪贴板
                setWindowFocusable(true)
                // 按下：线宽弹簧外扩 + 光晕增强 + 触感，同一帧生效
                pressVisual(toPressed = true)
                hapticTick()
            }
            MotionEvent.ACTION_UP -> {
                pressVisual(toPressed = false)
                // 延迟 200ms 等焦点真正切换过来，再读剪贴板
                mainHandler.postDelayed({ readClipboardAndHandle() }, 200)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressVisual(toPressed = false)
                setWindowFocusable(false)
            }
        }
        return true
    }

    /** 读取剪贴板并处理识别结果；无论成败最后都恢复不可获焦。 */
    private fun readClipboardAndHandle() {
        val clip = currentClipText()
        val link = clip?.let { IslandLinkParser.parse(it) }
        if (link != null) {
            // 同一链接只触发一次：与上次相同则静默忽略（不展开也不闪烁）
            val key = link.cleanedUrl.ifBlank { link.originalUrl }
            if (key == lastHandledLink) {
                setWindowFocusable(false)
                return
            }
            lastHandledLink = key
            // 识别叙事：亮弧绕一圈（约 0.5s），收束最亮帧换色后展开卡片。
            // 动作并行启动，不阻塞读剪贴板流程。
            scanThenExpand(link)
        } else {
            flashNoLink()
        }
        setWindowFocusable(false)
    }

    /** 临时切换窗口焦点标志（读剪贴板需要窗口处于“使用中”状态）。 */
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

    private fun expand(link: IslandLink) {
        state = State.Card(link)
        renderCard(link)
        applyCardWindowParams()
        cardHost.visibility = View.VISIBLE
        cardHost.alpha = 0f
        cardHost.scaleY = 0.4f
        cardHost.pivotY = 0f
        cardHost.animate().alpha(1f).scaleY(1f)
            .setDuration(240)
            .start()
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, CARD_TIMEOUT_MS)
        callbacks.onLinkShown(link)
    }

    private fun flashNoLink() {
        // 没识别到链接：光圈快速暗一亮一提示，不展开；结束重启呼吸。
        breathAnimator?.cancel()
        scanAnimator?.cancel()
        ValueAnimator.ofFloat(1f, 0.35f, 1f).apply {
            duration = 420
            addUpdateListener { anim -> glowView.alpha = anim.animatedValue as Float }
            doOnAnimEnd { startBreathing() }
            start()
        }
    }

    // ---------- 光圈动画编排（只改 IslandGlowView 参数，不写渲染逻辑） ----------

    /** 待机呼吸：只改 View.alpha（配合 LAYER_TYPE_HARDWARE 不触发 onDraw）。 */
    private var breathAnimator: ValueAnimator? = null
    /** 按下/回弹的弹簧帧驱动。 */
    private var springAnimator: ValueAnimator? = null
    /** 识别扫描弧。 */
    private var scanAnimator: ValueAnimator? = null
    /** 弹簧当前状态（连点保留速度，不跳变）。 */
    private var springX = 1f
    private var springV = 0f
    private var springTarget = 1f

    private fun startBreathing() {
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0.75f, 1f).apply {
            duration = BREATH_PERIOD_MS / 2
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener { anim -> glowView.alpha = anim.animatedValue as Float }
            // 从当前 alpha 平滑起步，避免中断后跳变
            setFloatValues(glowView.alpha, 1f)
            start()
        }
    }

    /** 按下 / 回弹：阻尼弹簧驱动线宽与光晕。连点保留当前速度。 */
    private fun pressVisual(toPressed: Boolean) {
        springTarget = if (toPressed) 1.4f else 1f
        val targetGlow = if (toPressed) 1f else 0.2f
        // 先更新目标再 cancel：cancel 不回调 doOnAnimEnd，弹簧状态（位置/速度）原样保留，连点不跳
        springAnimator?.cancel()
        springAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            var lastT = 0f
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val dt = (t - lastT).coerceIn(0.001f, 0.05f)
                lastT = t
                // 连续时间阻尼弹簧：v += (-k(x-target) - c·v)·dt
                springV += (-SPRING_K * (springX - springTarget) - SPRING_C * springV) * dt
                springX += springV * dt
                glowView.lineWidthScale = springX.coerceIn(0.8f, 2f)
                glowView.glow = targetGlow + (0.2f - targetGlow) *
                    ((springX - 1f) / 0.4f).coerceIn(0f, 1f)
            }
            doOnAnimEnd {
                springX = springTarget
                springV = 0f
                glowView.lineWidthScale = springTarget
                glowView.glow = if (springTarget > 1.2f) 1f else 0.2f
            }
            start()
        }
    }

    /** 识别：亮弧绕一圈（强减速），收束最亮帧硬切色，随即展开卡片。 */
    private fun scanThenExpand(link: IslandLink) {
        breathAnimator?.cancel()
        springAnimator?.cancel()
        scanAnimator?.cancel()
        glowView.alpha = 1f
        glowView.glow = 0.6f
        glowView.arcIndeterminate = true
        scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SCAN_DURATION_MS
            interpolator = android.view.animation.DecelerateInterpolator(2.5f)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                glowView.headAngle = 360f * t
                glowView.progress = t
                glowView.glow = 0.6f + 0.4f * t
            }
            doOnAnimEnd {
                // 最亮的瞬间换色，不出现混浊过渡
                glowView.colorMix = 1f
                glowView.progress = -1f
                glowView.arcIndeterminate = false
                glowView.headAngle = 0f
                expand(link)
            }
            start()
        }
    }

    /** 动画结束回调：正常结束才回调 onEnd（被 cancel 时不回调，避免参数吸附）。 */
    private inline fun ValueAnimator.doOnAnimEnd(crossinline onEnd: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            var canceled = false
            override fun onAnimationStart(a: android.animation.Animator) {}
            override fun onAnimationCancel(a: android.animation.Animator) { canceled = true }
            override fun onAnimationRepeat(a: android.animation.Animator) {}
            override fun onAnimationEnd(a: android.animation.Animator) { if (!canceled) onEnd() }
        })
    }

    /** 触感：预定义 TICK/CLICK（API 29+）。悬浮窗里 performHapticFeedback 不可靠。 */
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

    private fun renderCard(link: IslandLink) {
        cardTitle.text = when (link.platform) {
            MediaPlatform.Douyin -> "抖音链接"
            MediaPlatform.Bilibili -> "B 站链接"
            MediaPlatform.Twitter -> "X 链接"
            else -> "链接"
        }
        cardUrl.text = shortDisplay(link.cleanedUrl.ifBlank { link.originalUrl })
    }

    private fun shortDisplay(url: String): String =
        if (url.length <= 56) url else url.take(53) + "…"

    // ---------- 悬浮窗参数 ----------

    /**
     * 窗口平时不可获焦（FLAG_NOT_FOCUSABLE）：系统不会把返回手势等按键
     * 派发到这个置顶窗口，避免全局返回失效。仅在点击胶囊读剪贴板的瞬间
     * 临时移除该标志获焦（“使用中”），读完立即恢复不可获焦。
     */
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
        val params = baseParams(dp(WINDOW_SIZE_DP.toInt()), dp(WINDOW_SIZE_DP.toInt()))
        // 圆环中心与挖孔中心重合
        params.y = dp(CUTOUT_CENTER_Y_DP.roundToInt()) - dp(PILL_SIZE_DP.toInt()) / 2 - statusBarHeight()
        runCatching { windowManager.addView(this, params) }
        windowParams = params
        attached = true
        pillHost.visibility = View.VISIBLE
        sizePill()
        // 静态光晕走硬件层：待机呼吸只改 alpha，不触发 onDraw
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
        params.width = dp(WINDOW_SIZE_DP.toInt())
        params.height = dp(WINDOW_SIZE_DP.toInt())
        // 圆环中心与挖孔中心重合
        params.y = dp(CUTOUT_CENTER_Y_DP.roundToInt()) - dp(PILL_SIZE_DP.toInt()) / 2 - statusBarHeight()
        runCatching { windowManager.updateViewLayout(this, params) }
        pillHost.visibility = View.VISIBLE
    }

    private fun applyCardWindowParams() {
        val params = windowParams ?: return
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = (screenSize.y * CARD_HEIGHT_RATIO).toInt()
        // 卡片让开状态栏和挖孔，从其下缘开始。
        params.y = statusBarHeight() + dp(4)
        runCatching { windowManager.updateViewLayout(this, params) }
        pillHost.visibility = View.GONE
    }

    private fun statusBarHeight(): Int {
        val resources = context.resources
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(30)
    }

    // ---------- 动画 ----------

    private fun animateCardCollapse(onEnd: () -> Unit) {
        if (cardHost.visibility != View.VISIBLE) {
            onEnd()
            return
        }
        cardHost.animate().alpha(0f).scaleY(0.4f)
            .setDuration(200)
            .withEndAction(onEnd)
            .start()
    }

    // ---------- 绘制资源 ----------

    private fun cardDrawable(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFF0EEE6.toInt())
            cornerRadius = dp(28).toFloat()
        }

    private fun buttonDrawable(accent: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            if (accent) {
                setColor(0xFFCC785C.toInt())
            } else {
                setColor(0x00000000)
            }
            cornerRadius = dp(20).toFloat()
        }
}
