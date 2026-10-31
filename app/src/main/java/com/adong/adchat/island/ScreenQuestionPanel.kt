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
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
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
        private val QUICK_PROMPTS = listOf(
            "提取文字" to "提取并整理选区内的全部文字，保留原有层级与排版",
            "解释内容" to "用简明易懂的中文解释选区里的关键内容与要点",
            "翻译中文" to "将选区中的外文准确翻译为自然流畅的简体中文",
            "总结要点" to "提炼选区内容的核心结论与要点列表",
        )
    }

    private val density = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).roundToInt()
    private fun dpF(n: Float) = n * density
    private var palette = IslandPalette.Light
    private var rendering = false
    private var working = false
    private var hasConversation = false
    private var isCompact = false
    private var renderedPreview: android.graphics.Bitmap? = null
    private var renderedHistory: List<com.adong.adchat.data.ChatMessage>? = null
    private var renderedQuestion = ""
    private var renderedAnswerBody = ""
    private var titleTypeface: Typeface = Typeface.SERIF

    private val title = label("问问这里", 17f)
    private val modeBadge = label("极速模式", 11f).apply {
        typeface = IslandTypefaces.regular
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }
    private val collapse = pillButton("收起", onCollapse)
    private val close = pillButton("结束") { callbacks.onScreenQuestionClosed() }
    private val preview = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        contentDescription = "将发送的选中区域"
    }
    private val description = label("已锁定屏幕选区 · 极速直答", 13f).apply {
        typeface = IslandTypefaces.regular
        setLineSpacing(dpF(2f), 1f)
    }
    private val previewMeta = label("仅在内存中处理选区 · 支持 Markdown 排版与连续追问", 11.5f)
    private val previewRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(12), dp(8))
    }

    private val quickChips = mutableListOf<TextView>()
    private val quickChipsRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val quickChipsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(quickChipsRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private val historyContainer = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
    }
    private val activeTurnContainer = LinearLayout(context).apply {
        orientation = VERTICAL
    }
    private val activeUserRole = label("你", 11f).apply {
        typeface = IslandTypefaces.bold
    }
    private val activeUserQuestion = label("", 13.5f).apply {
        setLineSpacing(dpF(2.5f), 1f)
        setTextIsSelectable(true)
    }
    private val activeUserBubble = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(activeUserRole, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(activeUserQuestion, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })
    }
    private val activeAssistantRole = label("星岛 · 极速回答", 11f).apply {
        typeface = IslandTypefaces.bold
    }
    private val answerMarkdown = IslandMarkdownView(context, palette)
    private val activeAssistantCard = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(activeAssistantRole, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(answerMarkdown, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
    }
    private val conversation = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(historyContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(activeTurnContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
        hint = "想了解这里的什么？可点上方快捷指令或直接提问…"
        textSize = 14f
        typeface = IslandTypefaces.regular
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
        val titleWrap = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(modeBadge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        }
        header.addView(titleWrap, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
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
        previewRow.addView(preview, LayoutParams(dp(52), dp(52)))
        previewRow.addView(previewTexts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        addView(previewRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(11)
        })

        QUICK_PROMPTS.forEachIndexed { index, (chipLabel, promptText) ->
            val chip = pillButton(chipLabel, compact = true) {
                if (!working) {
                    input.setText(promptText)
                    input.setSelection(input.length())
                    callbacks.onScreenQuestionRequested(promptText)
                }
            }
            quickChips += chip
            quickChipsRow.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)).apply {
                if (index > 0) marginStart = dp(7)
            })
        }
        addView(quickChipsScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(9)
        })

        activeTurnContainer.addView(activeUserBubble, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        activeTurnContainer.addView(activeAssistantCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
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
        composer.addView(send, LayoutParams(dp(64), dp(42)).apply { marginStart = dp(8) })
        addView(composer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        setSendEnabled(false)
        applyPalette(palette)
    }

    private fun loadTitleTypeface() {
        titleTypeface = IslandTypefaces.bold
        title.typeface = titleTypeface
    }

    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        typeface = IslandTypefaces.regular
    }

    private fun pillButton(
        value: String,
        onClick: () -> Unit = {},
        primary: Boolean = false,
        compact: Boolean = false,
    ) = label(value, if (compact) 12f else 13f).apply {
        gravity = Gravity.CENTER
        typeface = IslandTypefaces.regular
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
        listOf(title, description, activeUserQuestion).forEach { it.setTextColor(next.ink) }
        listOf(previewMeta, status).forEach { it.setTextColor(next.muted) }
        activeUserRole.setTextColor(next.muted)
        activeAssistantRole.setTextColor(next.clay)
        modeBadge.setTextColor(next.clay)
        modeBadge.background = pillDrawable(
            fillColor = IslandMarkdown.blendColor(next.well, next.clay, 0.14f),
            strokeColor = IslandMarkdown.blendColor(next.edge, next.clay, 0.35f),
            radiusDp = 11,
        )
        listOf(collapse, close, copy, stop, configure).forEach { btn ->
            btn.setTextColor(next.ink)
            btn.background = pillDrawable(next.well, next.edge, radiusDp = 17)
        }
        quickChips.forEach { chip ->
            chip.setTextColor(next.ink)
            chip.background = pillDrawable(
                fillColor = IslandMarkdown.blendColor(next.well, next.clay, 0.08f),
                strokeColor = next.edge,
                radiusDp = 15,
            )
        }
        previewRow.background = pillDrawable(next.well, next.edge, radiusDp = 16)
        preview.background = pillDrawable(next.paper, next.edge, radiusDp = 11)
        activeUserBubble.background = pillDrawable(
            fillColor = IslandMarkdown.blendColor(next.paper, next.clay, 0.09f),
            strokeColor = IslandMarkdown.blendColor(next.edge, next.clay, 0.25f),
            radiusDp = 14,
        )
        activeAssistantCard.background = pillDrawable(
            fillColor = next.paper,
            strokeColor = next.edge,
            radiusDp = 15,
        )
        answerMarkdown.applyPalette(next)
        input.setTextColor(next.ink)
        input.setHintTextColor(next.muted)
        input.background = pillDrawable(next.well, next.edge, radiusDp = 21)
        scroll.background = pillDrawable(next.well, next.edge, radiusDp = 18)
        send.setTextColor(next.onClay)
        send.background = pillDrawable(next.clay, strokeColor = 0, radiusDp = 21)
        renderedHistory?.let { rebuildHistoryViews(it) }
    }

    private fun pillDrawable(fillColor: Int, strokeColor: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(fillColor)
        if (strokeColor != 0) setStroke(max(1, dp(1)), strokeColor)
        cornerRadius = dpF(radiusDp.toFloat())
    }

    fun setCompact(compact: Boolean) {
        isCompact = compact
        previewRow.visibility = if (compact && hasConversation) GONE else VISIBLE
        quickChipsScroll.visibility = if (hasConversation || compact) GONE else VISIBLE
        setPadding(dp(18), dp(if (compact) 34 else 46), dp(18), dp(if (compact) 12 else 16))
    }

    private fun rebuildHistoryViews(messages: List<com.adong.adchat.data.ChatMessage>) {
        historyContainer.removeAllViews()
        messages.forEachIndexed { index, message ->
            val isUser = message.role == "user"
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = if (isUser) {
                    pillDrawable(
                        fillColor = IslandMarkdown.blendColor(palette.paper, palette.clay, 0.09f),
                        strokeColor = IslandMarkdown.blendColor(palette.edge, palette.clay, 0.25f),
                        radiusDp = 14,
                    )
                } else {
                    pillDrawable(fillColor = palette.paper, strokeColor = palette.edge, radiusDp = 15)
                }
            }
            val roleLabel = label(if (isUser) "你" else "星岛", 11f).apply {
                typeface = IslandTypefaces.bold
                setTextColor(if (isUser) palette.muted else palette.clay)
            }
            card.addView(roleLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            if (isUser) {
                val userText = label(message.content, 13.5f).apply {
                    setTextColor(palette.ink)
                    setLineSpacing(dpF(2.5f), 1f)
                    setTextIsSelectable(true)
                }
                card.addView(userText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(3)
                })
            } else {
                val md = IslandMarkdownView(context, palette).apply {
                    setMarkdown(message.content)
                }
                card.addView(md, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(5)
                })
            }
            historyContainer.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (index == 0) 0 else dp(8)
                bottomMargin = if (index == messages.lastIndex) dp(8) else 0
            })
        }
    }

    fun render(session: ScreenQuestionSession) {
        rendering = true
        try {
            if (renderedPreview !== session.preview) {
                renderedPreview = session.preview
                preview.setImageBitmap(session.preview)
            }
            val widthPx = session.attachment.width
            val heightPx = session.attachment.height
            description.text = if (widthPx > 0 && heightPx > 0) {
                "已锁定选区（${widthPx}×${heightPx}） · 极速直答"
            } else {
                "已锁定屏幕选区 · 极速直答"
            }
            working = session.task?.phase == StarIslandPhase.Working
            modeBadge.text = if (working) "极速生成中" else "极速模式"
            if (input.text.toString() != session.draft) {
                input.setText(session.draft)
                input.setSelection(input.length())
            }
            val task = session.task
            hasConversation = session.history.isNotEmpty() || session.question.isNotBlank() || task != null
            scroll.visibility = if (hasConversation) VISIBLE else GONE
            tools.visibility = if (hasConversation) VISIBLE else GONE
            quickChipsScroll.visibility = if (hasConversation || isCompact) GONE else VISIBLE
            previewRow.visibility = if (isCompact && hasConversation) GONE else VISIBLE

            if (renderedHistory !== session.history) {
                renderedHistory = session.history
                rebuildHistoryViews(session.history)
                val visibility = if (session.history.isEmpty()) GONE else VISIBLE
                if (historyContainer.visibility != visibility) {
                    TransitionManager.beginDelayedTransition(conversation, AutoTransition().apply {
                        duration = 200L
                        interpolator = SPRING_INTERPOLATOR
                    })
                    historyContainer.visibility = visibility
                }
            }
            if (hasConversation) {
                activeUserBubble.visibility = if (session.question.isNotBlank()) VISIBLE else GONE
                if (activeUserQuestion.text.toString() != session.question) {
                    activeUserQuestion.text = session.question
                }
                val isPlaceholder = task?.output.isNullOrBlank()
                val rawAnswer = buildString {
                    if (task != null) {
                        append(task.output.ifBlank { task.detail })
                        if (task.output.isNotBlank() && task.detail.isNotBlank() &&
                            task.phase in setOf(StarIslandPhase.Error, StarIslandPhase.Stopped)
                        ) {
                            append("\n\n> ").append(task.detail)
                        }
                    }
                }.trim()
                activeAssistantCard.visibility = if (rawAnswer.isNotEmpty()) VISIBLE else GONE
                val newQuestion = renderedQuestion != session.question
                renderedQuestion = session.question
                val nearEnd = !scroll.canScrollVertically(1) || conversation.height - scroll.height - scroll.scrollY < dp(32)
                if (renderedAnswerBody != rawAnswer) {
                    renderedAnswerBody = rawAnswer
                    answerMarkdown.setMarkdown(rawAnswer, placeholder = isPlaceholder)
                    if (newQuestion || nearEnd) scroll.post { scroll.fullScroll(FOCUS_DOWN) }
                }
            } else {
                renderedQuestion = ""
                renderedAnswerBody = ""
                answerMarkdown.setMarkdown("")
            }
            status.text = when (task?.phase) {
                StarIslandPhase.Working -> "正在极速回答…"
                StarIslandPhase.Complete -> "支持 Markdown 排版 · 可继续追问"
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
