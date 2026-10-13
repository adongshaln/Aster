package com.adong.adchat.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.adong.adchat.media.MediaPlatform

/**
 * 灵动岛视图（隐形形态）：
 * - 平时：窗口只有胶囊大小，叠在前置摄像头挖孔上（纯黑，视觉零存在），
 *   不遮挡屏幕其他区域的触摸；
 * - 点击胶囊：窗口获焦（“使用中”）→ 读剪贴板 → 识别到媒体链接后
 *   窗口放大为全屏宽 × 屏高 1/5 的询问卡片；
 * - [解析] 跳转 Aster 下载页自动填入链接；[不用] 缩回挖孔。
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
        /** 与常见挖孔尺寸接近：点击目标即摄像头位置。 */
        private const val PILL_WIDTH_DP = 62f
        private const val PILL_HEIGHT_DP = 30f
        private const val CARD_HEIGHT_RATIO = 0.2f
        private const val CARD_TIMEOUT_MS = 12_000L
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

    private val pillHost: LinearLayout
    private val cardHost: LinearLayout
    private val cardTitle: TextView
    private val cardUrl: TextView
    private val parseButton: TextView
    private val cancelButton: TextView

    init {
        pillHost = buildPill()
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

    /** 缩回挖孔。 */
    fun collapse() {
        mainHandler.post {
            if (state == State.Hidden) return@post
            state = State.Hidden
            mainHandler.removeCallbacks(hideRunnable)
            animateCardCollapse {
                cardHost.visibility = View.GONE
                applyPillWindowParams()
                callbacks.onDismissed()
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            state = State.Hidden
            mainHandler.removeCallbacksAndMessages(null)
            if (attached) {
                runCatching { windowManager.removeView(this@IslandView) }
                attached = false
            }
        }
    }

    // ---------- 构建 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPill(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // 纯黑 + 与挖孔同尺寸：叠在摄像头上视觉上即隐形
            background = pillDrawable()
            setOnTouchListener { _, event -> handlePillTouch(event) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCard(): CardParts {
        val host = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(14))
            background = cardDrawable()
        }
        val title = TextView(context).apply {
            textSize = 16f
            setTextColor(0xFFF6F3EE.toInt())
            maxLines = 1
        }
        val url = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFFB9B0A6.toInt())
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
        ).apply { topMargin = dp(6) })
        host.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
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
            setTextColor(if (accent) 0xFF302C28.toInt() else 0xFFF6F3EE.toInt())
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = buttonDrawable(accent)
            setOnClickListener { onClick() }
        }
    }

    // ---------- 交互 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun handlePillTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            // 此刻窗口已获焦（“使用中”），直接读剪贴板
            val clip = currentClipText()
            val link = clip?.let { IslandLinkParser.parse(it) }
            if (link != null) {
                expand(link)
            } else {
                flashNoLink()
            }
        }
        return true
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
        // 没识别到链接：轻微闪一下提示，不展开
        pillAnimator?.cancel()
        pillAnimator = ValueAnimator.ofFloat(1f, 0.45f, 1f).apply {
            duration = 420
            addUpdateListener { anim -> pillHost.alpha = anim.animatedValue as Float }
            start()
        }
    }

    private var pillAnimator: ValueAnimator? = null

    private fun renderCard(link: IslandLink) {
        cardTitle.text = when (link.platform) {
            MediaPlatform.Douyin -> "检测到抖音链接"
            MediaPlatform.Bilibili -> "检测到B站链接"
            MediaPlatform.Twitter -> "检测到 X 链接"
            else -> "检测到链接"
        }
        cardUrl.text = shortDisplay(link.cleanedUrl.ifBlank { link.originalUrl })
    }

    private fun shortDisplay(url: String): String =
        if (url.length <= 56) url else url.take(53) + "…"

    // ---------- 悬浮窗参数 ----------

    /**
     * 窗口不加 FLAG_NOT_FOCUSABLE：胶囊被点击瞬间获焦，处于“使用中”，
     * 才能读到剪贴板。平时窗口只有胶囊大小，不会挡住其他区域触摸。
     */
    private fun baseParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

    private fun ensureAttached() {
        if (attached) return
        val params = baseParams(dp(PILL_WIDTH_DP.toInt()), dp(PILL_HEIGHT_DP.toInt()))
        runCatching { windowManager.addView(this, params) }
        windowParams = params
        attached = true
        pillHost.visibility = View.VISIBLE
        sizePill()
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
        params.width = dp(PILL_WIDTH_DP.toInt())
        params.height = dp(PILL_HEIGHT_DP.toInt())
        params.y = 0
        runCatching { windowManager.updateViewLayout(this, params) }
        pillHost.visibility = View.VISIBLE
    }

    private fun applyCardWindowParams() {
        val params = windowParams ?: return
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = (screenSize.y * CARD_HEIGHT_RATIO).toInt()
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

    private fun pillDrawable(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            cornerRadius = dp((PILL_HEIGHT_DP / 2).toInt()).toFloat()
        }

    private fun cardDrawable(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0xF2352F2A.toInt())
            cornerRadius = dp(24).toFloat()
        }

    private fun buttonDrawable(accent: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            if (accent) {
                setColor(0xFFE8D5C4.toInt())
            } else {
                setColor(0x33FFFFFF)
            }
            cornerRadius = dp(18).toFloat()
        }
}
