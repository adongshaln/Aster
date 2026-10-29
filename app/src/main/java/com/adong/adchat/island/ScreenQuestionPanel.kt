package com.adong.adchat.island

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Content only; IslandView owns its ink outline, keyboard window and spring motion. */
internal class ScreenQuestionPanel(
    context: Context,
    private val callbacks: IslandView.Callbacks,
    private val onCollapse: () -> Unit,
    private val onInputFocus: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).roundToInt()
    private var palette = IslandPalette.Light
    private var rendering = false
    private var working = false
    private var renderedPreview: android.graphics.Bitmap? = null
    private var renderedHistory: List<com.adong.adchat.data.ChatMessage>? = null
    private var renderedQuestion = ""
    private val title = label("问问这里", 18f)
    private val collapse = button("收起", onCollapse)
    private val close = button("结束") { callbacks.onScreenQuestionClosed() }
    private val preview = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "将发送的选中区域" }
    private val description = label("仅发送选中区域\n使用默认对话模型，需支持图片", 12f)
    private val previewRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val answer = label("想了解选中区域的什么？\n可以解释内容、翻译文字，也可以直接提问。", 15f).apply {
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setTextIsSelectable(true)
    }
    private val historyText = label("", 15f).apply {
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(12), dp(12), dp(12), 0)
        setTextIsSelectable(true)
        visibility = GONE
    }
    private val conversation = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(historyText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(answer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        addView(conversation, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val status = label("", 12f)
    private val copy = button("复制回答") { callbacks.onScreenAnswerCopyRequested() }
    private val stop = button("停止") { callbacks.onScreenQuestionStopped() }
    private val configure = button("设置") { callbacks.onConfigureRequested() }
    private val send = button("发送") { submit() }
    private val input = EditText(context).apply {
        hint = "对这个区域提问…"
        textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        minLines = 1
        maxLines = 3
        filters = arrayOf(InputFilter.LengthFilter(2_000))
        setPadding(dp(12), dp(9), dp(12), dp(9))
        setOnFocusChangeListener { _, focused -> if (focused) onInputFocus() }
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                send.isEnabled = !working && !s.isNullOrBlank()
                send.alpha = if (send.isEnabled) 1f else 0.45f
                if (!rendering) callbacks.onScreenDraftChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    init {
        orientation = VERTICAL
        setPadding(dp(18), dp(47), dp(18), dp(16))
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        title.typeface = Typeface.create("serif", Typeface.BOLD)
        header.addView(title, LayoutParams(0, dp(44), 1f))
        header.addView(collapse, LayoutParams(dp(48), dp(44)))
        header.addView(close, LayoutParams(dp(48), dp(44)))
        addView(header)
        previewRow.addView(preview, LayoutParams(dp(70), dp(58)))
        previewRow.addView(description, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        addView(previewRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(64)).apply { bottomMargin = dp(10) })
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val tools = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        tools.addView(status, LayoutParams(0, dp(40), 1f))
        tools.addView(configure, LayoutParams(dp(48), dp(40)))
        tools.addView(copy, LayoutParams(dp(72), dp(40)))
        tools.addView(stop, LayoutParams(dp(48), dp(40)))
        addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
        val composer = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
        composer.addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(send, LayoutParams(dp(56), dp(46)).apply { marginStart = dp(8) })
        addView(composer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        applyPalette(palette)
    }

    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun button(value: String, action: () -> Unit) = label(value, 13f).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        contentDescription = value
        setOnClickListener { action() }
    }

    private fun submit() {
        val question = input.text.toString().trim()
        if (!working && question.isNotEmpty()) callbacks.onScreenQuestionRequested(question)
    }

    fun applyPalette(next: IslandPalette) {
        palette = next
        listOf(title, answer, historyText, collapse, close, copy, stop, configure).forEach { it.setTextColor(next.ink) }
        listOf(description, status).forEach { it.setTextColor(next.muted) }
        input.setTextColor(next.ink)
        input.setHintTextColor(next.muted)
        input.background = background(next.well, 20)
        scroll.background = background(next.well, 18)
        preview.background = background(next.well, 10)
        send.setTextColor(next.onClay)
        send.background = background(next.clay, 23)
    }

    private fun background(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    fun setCompact(compact: Boolean) {
        previewRow.visibility = if (compact) GONE else VISIBLE
        setPadding(dp(18), dp(if (compact) 32 else 47), dp(18), dp(if (compact) 10 else 16))
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
            if (renderedHistory !== session.history) {
                renderedHistory = session.history
                historyText.text = session.history.joinToString("\n\n") { message ->
                    (if (message.role == "user") "你：" else "星岛：") + message.content
                }
                historyText.visibility = if (session.history.isEmpty()) GONE else VISIBLE
            }
            val transcript = buildString {
                if (session.question.isNotEmpty()) {
                    append("你：").append(session.question).append("\n\n")
                    append(task?.output?.ifBlank { task.detail }.orEmpty())
                    if (!task?.output.isNullOrBlank() && task?.phase in setOf(StarIslandPhase.Error, StarIslandPhase.Stopped)) {
                        append("\n\n").append(task?.detail)
                    }
                }
            }.trim().ifBlank { "想了解选中区域的什么？\n可以解释内容、翻译文字，也可以直接提问。" }
            val newQuestion = renderedQuestion != session.question
            renderedQuestion = session.question
            val nearEnd = !scroll.canScrollVertically(1) || conversation.height - scroll.height - scroll.scrollY < dp(32)
            if (answer.text.toString() != transcript) {
                answer.text = transcript
                if (newQuestion || nearEnd) scroll.post { scroll.fullScroll(FOCUS_DOWN) }
            }
            status.text = when (task?.phase) {
                StarIslandPhase.Working -> "正在回答…"
                StarIslandPhase.Complete -> "可继续追问"
                StarIslandPhase.Stopped -> "已停止，可继续提问"
                StarIslandPhase.Error -> if (task.needsConfiguration) "请检查模型设置" else "未完成，可重新发送"
                null -> "选区仅暂存于本机"
            }
            status.contentDescription = task?.detail ?: status.text
            stop.visibility = if (working) VISIBLE else GONE
            configure.visibility = if (task?.needsConfiguration == true) VISIBLE else GONE
            copy.visibility = if (!task?.output.isNullOrBlank()) VISIBLE else GONE
            send.isEnabled = !working && input.text.isNotBlank()
            send.alpha = if (send.isEnabled) 1f else 0.45f
        } finally { rendering = false }
    }
}
