package com.adong.adchat.island

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/** Content only; IslandView owns its ink outline, keyboard window and spring motion. */
internal class ScreenQuestionPanel(
    context: Context,
    private val callbacks: IslandView.Callbacks,
    private val onCollapse: () -> Unit,
    private val onInputFocus: () -> Unit,
) : LinearLayout(context) {
    companion object {
        private val SPRING_INTERPOLATOR = android.animation.TimeInterpolator { IslandSpring.progress(it) }
    }

    private val density = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).roundToInt()
    private fun dpF(n: Float) = n * density
    private var palette = IslandPalette.Light
    private var rendering = false
    private var working = false
    private var hasConversation = false
    private var renderedPreview: android.graphics.Bitmap? = null
    private var renderedHistory: List<com.adong.adchat.data.ChatMessage>? = null
    private var renderedQuestion = ""

    private val title = label("问问这里", 17f)
    private val collapse = pillButton("收起", onCollapse)
    private val close = pillButton("结束") { callbacks.onScreenQuestionClosed() }
    private val preview = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        contentDescription = "将发送的选中区域"
    }
    private val description = label("仅发送选区 · 可解释、翻译或直接提问", 13f).apply {
        setLineSpacing(dpF(2f), 1f)
    }
    private val previewMeta = label("使用默认对话模型（需支持图片） · 选区仅暂存于本机", 11.5f)
    private val previewRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(12), dp(8))
    }
    private val answer = label("", 14.5f).apply {
        setLineSpacing(dpF(4f), 1f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setTextIsSelectable(true)
    }
    private val historyText = label("", 14.5f).apply {
        setLineSpacing(dpF(4f), 1f)
        setPadding(dp(14), dp(12), dp(14), 0)
        setTextIsSelectable(true)
        visibility = GONE
    }
    private val conversation = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(historyText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(answer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        visibility = GONE
        addView(conversation, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val status = label("", 12f)
    private val copy = pillButton("复制回答", compact = true) { callbacks.onScreenAnswerCopyRequested() }
    private val stop = pillButton("停止", compact = true) { callbacks.onScreenQuestionStopped() }
    private val configure = pillButton("设置", compact = true) { callbacks.onConfigureRequested() }
    private val tools = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        layoutTransition = smoothLayoutTransition()
    }
    private val send = pillButton("发送", primary = true) { submit() }
    private val input = EditText(context).apply {
        hint = "想了解这里的什么？解释、翻译或直接提问…"
        textSize = 14.5f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        minLines = 1
        maxLines = 3
        filters = arrayOf(InputFilter.LengthFilter(2_000))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnFocusChangeListener { _, focused -> if (focused) onInputFocus() }
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else false
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                setSendEnabled(!working && !s.isNullOrBlank())
                if (!rendering) callbacks.onScreenDraftChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    internal val hasConversationContent: Boolean get() = hasConversation

    init {
        orientation = VERTICAL
        layoutTransition = smoothLayoutTransition()
        setPadding(dp(18), dp(46), dp(18), dp(16))

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        title.typeface = Typeface.SERIF
        loadTitleTypeface()
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(collapse, LayoutParams(LayoutParams.WRAP_CONTENT, dp(34)))
        header.addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, dp(34)).apply { marginStart = dp(8) })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val previewTexts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(description, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(previewMeta, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            })
        }
        previewRow.addView(preview, LayoutParams(dp(54), dp(54)))
        previewRow.addView(previewTexts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        addView(previewRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(10)
        })

        tools.addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        tools.addView(configure, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(6) })
        tools.addView(copy, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(6) })
        tools.addView(stop, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(6) })
        addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val composer = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
        composer.addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(send, LayoutParams(dp(64), dp(44)).apply { marginStart = dp(8) })
        addView(composer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        setSendEnabled(false)
        applyPalette(palette)
    }

    private fun loadTitleTypeface() {
        val appContext = context.applicationContext
        Thread({
            val typeface = IslandTypefaces.title(appContext)
            post { title.typeface = typeface }
        }, "screen-question-title-serif").apply {
            isDaemon = true
            start()
        }
    }

    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun pillButton(
        value: String,
        onClick: () -> Unit = {},
        primary: Boolean = false,
        compact: Boolean = false,
    ) = label(value, if (compact) 12.5f else 13.5f).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        contentDescription = value
        val padH = dp(if (compact) 12 else 14)
        setPadding(padH, 0, padH, 0)
        setOnTouchListener { view, event ->
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
        setOnClickListener { onClick() }
        tag = primary
    }

    private fun pillButton(
        value: String,
        primary: Boolean = false,
        compact: Boolean = false,
        action: () -> Unit,
    ) = pillButton(value, onClick = action, primary = primary, compact = compact)

    private fun smoothLayoutTransition() = LayoutTransition().apply {
        setDuration(200L)
        setInterpolator(LayoutTransition.APPEARING, SPRING_INTERPOLATOR)
        setInterpolator(LayoutTransition.DISAPPEARING, SPRING_INTERPOLATOR)
        setInterpolator(LayoutTransition.CHANGING, SPRING_INTERPOLATOR)
        enableTransitionType(LayoutTransition.CHANGING)
    }

    private fun setSendEnabled(enabled: Boolean) {
        if (send.isEnabled == enabled) return
        send.isEnabled = enabled
        send.animate().cancel()
        send.animate().alpha(if (enabled) 1f else 0.45f)
            .setDuration(if (ValueAnimator.areAnimatorsEnabled()) 180L else 0L)
            .setInterpolator(SPRING_INTERPOLATOR)
            .start()
    }

    private fun submit() {
        val question = input.text.toString().trim()
        if (!working && question.isNotEmpty()) callbacks.onScreenQuestionRequested(question)
    }

    fun applyPalette(next: IslandPalette) {
        palette = next
        listOf(title, description, answer, historyText).forEach { it.setTextColor(next.ink) }
        listOf(previewMeta, status).forEach { it.setTextColor(next.muted) }
        listOf(collapse, close, copy, stop, configure).forEach { btn ->
            btn.setTextColor(next.ink)
            btn.background = pillDrawable(next.well, next.edge, radiusDp = 17)
        }
        previewRow.background = pillDrawable(next.well, strokeColor = 0, radiusDp = 16)
        preview.background = pillDrawable(next.paper, next.edge, radiusDp = 11)
        input.setTextColor(next.ink)
        input.setHintTextColor(next.muted)
        input.background = pillDrawable(next.well, next.edge, radiusDp = 22)
        scroll.background = pillDrawable(next.well, strokeColor = 0, radiusDp = 18)
        send.setTextColor(next.onClay)
        send.background = pillDrawable(next.clay, strokeColor = 0, radiusDp = 22)
    }

    private fun pillDrawable(fillColor: Int, strokeColor: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(fillColor)
        if (strokeColor != 0) setStroke(max(1, dp(1)), strokeColor)
        cornerRadius = dpF(radiusDp.toFloat())
    }

    fun setCompact(compact: Boolean) {
        previewRow.visibility = if (compact && hasConversation) GONE else VISIBLE
        setPadding(dp(18), dp(if (compact) 34 else 46), dp(18), dp(if (compact) 12 else 16))
    }

    fun render(session: ScreenQuestionSession) {
        rendering = true
        try {
            if (renderedPreview !== session.preview) {
                renderedPreview = session.preview
                preview.setImageBitmap(session.preview)
            }
            working = session.task?.phase == StarIslandPhase.Working
            if (input.text.toString() != session.draft) {
                input.setText(session.draft)
                input.setSelection(input.length())
            }
            val task = session.task
            hasConversation = session.history.isNotEmpty() || session.question.isNotBlank() || task != null
            scroll.visibility = if (hasConversation) VISIBLE else GONE
            tools.visibility = if (hasConversation) VISIBLE else GONE

            if (renderedHistory !== session.history) {
                renderedHistory = session.history
                historyText.text = session.history.joinToString("\n\n") { message ->
                    (if (message.role == "user") "你：" else "星岛：") + message.content
                }
                val visibility = if (session.history.isEmpty()) GONE else VISIBLE
                if (historyText.visibility != visibility) {
                    TransitionManager.beginDelayedTransition(conversation, AutoTransition().apply {
                        duration = 200L
                        interpolator = SPRING_INTERPOLATOR
                    })
                    historyText.visibility = visibility
                }
            }
            if (hasConversation) {
                val transcript = buildString {
                    if (session.question.isNotEmpty()) {
                        append("你：").append(session.question).append("\n\n")
                        append(task?.output?.ifBlank { task.detail }.orEmpty())
                        if (!task?.output.isNullOrBlank() && task?.phase in setOf(StarIslandPhase.Error, StarIslandPhase.Stopped)) {
                            append("\n\n").append(task?.detail)
                        }
                    } else if (task != null) {
                        append(task.output.ifBlank { task.detail })
                    }
                }.trim()
                val newQuestion = renderedQuestion != session.question
                renderedQuestion = session.question
                val nearEnd = !scroll.canScrollVertically(1) || conversation.height - scroll.height - scroll.scrollY < dp(32)
                if (answer.text.toString() != transcript) {
                    answer.text = transcript
                    if (newQuestion || nearEnd) scroll.post { scroll.fullScroll(FOCUS_DOWN) }
                }
            } else {
                renderedQuestion = ""
                answer.text = ""
            }
            status.text = when (task?.phase) {
                StarIslandPhase.Working -> "正在回答…"
                StarIslandPhase.Complete -> "可继续追问"
                StarIslandPhase.Stopped -> "已停止，可继续提问"
                StarIslandPhase.Error -> if (task.needsConfiguration) "请检查模型设置" else "未完成，可重新发送"
                null -> ""
            }
            status.contentDescription = task?.detail ?: status.text
            stop.visibility = if (working) VISIBLE else GONE
            configure.visibility = if (task?.needsConfiguration == true) VISIBLE else GONE
            copy.visibility = if (!task?.output.isNullOrBlank()) VISIBLE else GONE
            setSendEnabled(!working && input.text.isNotBlank())
        } finally {
            rendering = false
        }
    }
}
