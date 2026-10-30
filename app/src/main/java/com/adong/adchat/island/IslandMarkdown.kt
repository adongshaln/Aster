package com.adong.adchat.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.adong.adchat.ui.markdown.MarkdownTable
import com.adong.adchat.ui.markdown.MarkdownTableAlignment
import com.adong.adchat.ui.markdown.emphasisSpanAllowed
import com.adong.adchat.ui.markdown.isChapterHeading
import com.adong.adchat.ui.markdown.isSceneBreak
import com.adong.adchat.ui.markdown.isStarredProseLine
import com.adong.adchat.ui.markdown.joinProseLine
import com.adong.adchat.ui.markdown.linkAt
import com.adong.adchat.ui.markdown.nextPlainEnd
import com.adong.adchat.ui.markdown.parseMarkdownTableAt
import com.adong.adchat.ui.markdown.readingSpans
import com.adong.adchat.ui.markdown.ReadingSpan
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Paper-and-ink Markdown blocks and inline typography for Star Island overlays.
 * Works directly on Android View/Spannable primitives so the window-manager overlay stays lightweight
 * and animates at 120Hz inside [IslandSurfaceView].
 */
internal sealed class IslandMarkdownBlock {
    data class Paragraph(val text: String) : IslandMarkdownBlock()
    data class Heading(val text: String, val level: Int) : IslandMarkdownBlock()
    data class ListItem(val marker: String, val text: String, val ordered: Boolean) : IslandMarkdownBlock()
    data class Quote(val text: String) : IslandMarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : IslandMarkdownBlock()
    data class Table(val table: MarkdownTable) : IslandMarkdownBlock()
    data object Divider : IslandMarkdownBlock()
}

internal object IslandMarkdown {
    private val QUOTE_PREFIX = Regex("""^\s*>\s?""")
    private val HEADING_LINE = Regex("""^(#{1,6})\s+(.*)$""")
    private val ORDERED_ITEM = Regex("""^(\d+[.)])\s+(.*)$""")
    private val CODE_FENCE = Regex("""^\s*(`{3,}|~{3,})(.*)$""")
    private val INLINE_TOKENS = listOf("**", "__", "~~", "`", "*", "_")

    fun parseBlocks(raw: String): List<IslandMarkdownBlock> {
        if (raw.isBlank()) return emptyList()
        val blocks = mutableListOf<IslandMarkdownBlock>()
        val paragraph = StringBuilder()
        val quote = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks += IslandMarkdownBlock.Paragraph(paragraph.toString().trim())
            }
            paragraph.clear()
        }

        fun flushQuote() {
            if (quote.isNotBlank()) {
                blocks += IslandMarkdownBlock.Quote(quote.toString().trim())
            }
            quote.clear()
        }

        val normalized = normalizeQuoteHeavy(raw.replace("\r\n", "\n").replace('\r', '\n'))
        val lines = normalized.lines()
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            val fenceMatch = CODE_FENCE.find(rawLine)
            if (fenceMatch != null) {
                flushParagraph()
                flushQuote()
                val fenceToken = fenceMatch.groupValues[1]
                val fenceChar = fenceToken.first()
                val fenceLen = fenceToken.length
                val language = fenceMatch.groupValues[2].trim().take(24)
                val codeLines = mutableListOf<String>()
                index++
                while (index < lines.size) {
                    val candidate = lines[index]
                    val trimmed = candidate.trim()
                    if (trimmed.length >= fenceLen && trimmed.all { it == fenceChar }) {
                        index++
                        break
                    }
                    codeLines += candidate
                    index++
                }
                blocks += IslandMarkdownBlock.CodeBlock(
                    language = language,
                    code = codeLines.joinToString("\n").trimEnd(),
                )
                continue
            }

            val tableMatch = parseMarkdownTableAt(lines, index)
            if (tableMatch != null) {
                flushParagraph()
                flushQuote()
                blocks += IslandMarkdownBlock.Table(tableMatch.table)
                index = tableMatch.nextLineIndex
                continue
            }

            val line = rawLine.trimEnd()
            val trimmedStart = line.trimStart()
            when {
                line.isBlank() -> {
                    flushParagraph()
                    flushQuote()
                }
                isSceneBreak(line) -> {
                    flushParagraph()
                    flushQuote()
                    blocks += IslandMarkdownBlock.Divider
                }
                isChapterHeading(line.trim()) -> {
                    flushParagraph()
                    flushQuote()
                    blocks += IslandMarkdownBlock.Heading(line.trim(), level = 2)
                }
                HEADING_LINE.matches(trimmedStart) -> {
                    flushParagraph()
                    flushQuote()
                    val match = checkNotNull(HEADING_LINE.find(trimmedStart))
                    val level = match.groupValues[1].length.coerceIn(1, 6)
                    val title = match.groupValues[2].trim()
                    if (title.isNotEmpty()) blocks += IslandMarkdownBlock.Heading(title, level)
                }
                trimmedStart.startsWith("- ") || trimmedStart.startsWith("• ") ||
                    (trimmedStart.startsWith("* ") && !isStarredProseLine(trimmedStart)) -> {
                    flushParagraph()
                    flushQuote()
                    val body = trimmedStart.drop(2).trim()
                    val (marker, itemText) = when {
                        body.startsWith("[x] ", ignoreCase = true) -> "✓" to body.drop(4).trim()
                        body.startsWith("[ ] ") -> "○" to body.drop(4).trim()
                        else -> "•" to body
                    }
                    blocks += IslandMarkdownBlock.ListItem(marker = marker, text = itemText, ordered = false)
                }
                ORDERED_ITEM.matches(trimmedStart) -> {
                    flushParagraph()
                    flushQuote()
                    val match = checkNotNull(ORDERED_ITEM.find(trimmedStart))
                    blocks += IslandMarkdownBlock.ListItem(
                        marker = match.groupValues[1],
                        text = match.groupValues[2].trim(),
                        ordered = true,
                    )
                }
                QUOTE_PREFIX.containsMatchIn(line) -> {
                    flushParagraph()
                    joinProseLine(quote, line.replaceFirst(QUOTE_PREFIX, "").trimEnd())
                }
                else -> {
                    flushQuote()
                    joinProseLine(paragraph, line.trim())
                }
            }
            index++
        }
        flushParagraph()
        flushQuote()
        return blocks
    }

    fun formatInline(text: String, palette: IslandPalette): Spanned {
        val builder = SpannableStringBuilder()
        readingSpans(text).forEach { span ->
            when (span) {
                is ReadingSpan.Name -> {
                    val start = builder.length
                    builder.append(span.value)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(palette.clay), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is ReadingSpan.Text -> appendInlineMarkdown(builder, span.value, palette)
            }
        }
        val source = builder.toString()
        var cursor = 0
        while (cursor < source.length) {
            val open = source.indexOf('『', cursor)
            if (open < 0) break
            val close = source.indexOf('』', open + 1)
            if (close < 0) break
            builder.setSpan(ForegroundColorSpan(palette.clay), open, close + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = close + 1
        }
        return builder
    }

    private fun appendInlineMarkdown(
        out: SpannableStringBuilder,
        text: String,
        palette: IslandPalette,
    ) {
        var index = 0
        val codeBg = blendColor(palette.well, palette.clay, 0.14f)
        while (index < text.length) {
            val link = linkAt(text, index)
            if (link != null) {
                val start = out.length
                out.append(link.label)
                out.setSpan(ForegroundColorSpan(palette.clay), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(UnderlineSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                index = link.end
                continue
            }
            val token = INLINE_TOKENS.firstOrNull { text.startsWith(it, index) }
            if (token != null) {
                val end = text.indexOf(token, index + token.length)
                val inner = if (end > index + token.length) text.substring(index + token.length, end) else null
                val allowed = inner != null && (token !in setOf("*", "_") || emphasisSpanAllowed(inner))
                if (inner != null && allowed) {
                    val start = out.length
                    if (token == "`") {
                        out.append("\u00A0").append(inner).append("\u00A0")
                        out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(codeBg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(ForegroundColorSpan(palette.clay), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        appendInlineMarkdown(out, inner, palette)
                        val span: Any = when (token) {
                            "**", "__" -> StyleSpan(Typeface.BOLD)
                            "~~" -> StrikethroughSpan()
                            else -> StyleSpan(Typeface.ITALIC)
                        }
                        out.setSpan(span, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    index = end + token.length
                } else {
                    out.append(token)
                    index += token.length
                }
            } else {
                val next = nextPlainEnd(text, index, INLINE_TOKENS)
                out.append(text.substring(index, next))
                index = next
            }
        }
    }

    private fun normalizeQuoteHeavy(text: String): String {
        val lines = text.lines()
        val meaningful = lines.filter { it.isNotBlank() }
        if (meaningful.isEmpty()) return text
        val quoteCount = meaningful.count { QUOTE_PREFIX.containsMatchIn(it) }
        val quoteHeavy = quoteCount >= 3 && quoteCount * 10 >= meaningful.size * 6
        if (!quoteHeavy) return text
        return lines.joinToString("\n") { line ->
            if (QUOTE_PREFIX.containsMatchIn(line)) line.replaceFirst(QUOTE_PREFIX, "") else line
        }
    }

    internal fun blendColor(base: Int, tint: Int, ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        val inv = 1f - t
        val a = ((base ushr 24 and 0xFF) * inv + (tint ushr 24 and 0xFF) * t).roundToInt().coerceIn(0, 255)
        val r = ((base ushr 16 and 0xFF) * inv + (tint ushr 16 and 0xFF) * t).roundToInt().coerceIn(0, 255)
        val g = ((base ushr 8 and 0xFF) * inv + (tint ushr 8 and 0xFF) * t).roundToInt().coerceIn(0, 255)
        val b = ((base and 0xFF) * inv + (tint and 0xFF) * t).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Lightweight native View container that renders Markdown into paper-and-ink blocks.
 * Re-renders only when the text or palette actually changes.
 */
internal class IslandMarkdownView(
    context: Context,
    palette: IslandPalette = IslandPalette.Light,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(n: Int): Int = (n * density).roundToInt()
    private fun dpF(n: Float): Float = n * density

    private var currentPalette: IslandPalette = palette
    private var renderedMarkdown: String? = null
    private var renderedPalette: IslandPalette? = null
    private var renderedPlaceholder: Boolean = false
    private var titleTypeface: Typeface = Typeface.SERIF

    val rawMarkdown: String get() = renderedMarkdown.orEmpty()
    val currentTextColor: Int get() = if (renderedPlaceholder) currentPalette.muted else currentPalette.ink

    init {
        orientation = VERTICAL
        loadSerifTypeface()
    }

    private fun loadSerifTypeface() {
        val appContext = context.applicationContext
        Thread({
            val loaded = IslandTypefaces.title(appContext)
            post {
                if (titleTypeface !== loaded) {
                    titleTypeface = loaded
                    renderedPalette = null
                    renderedMarkdown?.let { setMarkdown(it, renderedPlaceholder) }
                }
            }
        }, "island-markdown-serif").apply {
            isDaemon = true
            start()
        }
    }

    fun applyPalette(next: IslandPalette) {
        if (currentPalette == next && renderedPalette == next) return
        currentPalette = next
        renderedMarkdown?.let { setMarkdown(it, renderedPlaceholder, force = true) }
    }

    fun setMarkdown(markdown: String, placeholder: Boolean = false, force: Boolean = false) {
        if (!force && renderedMarkdown == markdown && renderedPalette == currentPalette && renderedPlaceholder == placeholder) {
            return
        }
        renderedMarkdown = markdown
        renderedPalette = currentPalette
        renderedPlaceholder = placeholder
        contentDescription = markdown
        removeAllViews()

        if (markdown.isBlank()) return
        if (placeholder) {
            addView(buildPlaceholderView(markdown), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return
        }

        val blocks = IslandMarkdown.parseBlocks(markdown)
        if (blocks.isEmpty()) {
            addView(buildParagraphView(markdown), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return
        }

        blocks.forEachIndexed { index, block ->
            val child = when (block) {
                is IslandMarkdownBlock.Heading -> buildHeadingView(block)
                is IslandMarkdownBlock.Paragraph -> buildParagraphView(block.text)
                is IslandMarkdownBlock.ListItem -> buildListItemView(block)
                is IslandMarkdownBlock.Quote -> buildQuoteView(block)
                is IslandMarkdownBlock.CodeBlock -> buildCodeBlockView(block)
                is IslandMarkdownBlock.Table -> buildTableView(block.table)
                IslandMarkdownBlock.Divider -> buildDividerView()
            }
            val topGap = when {
                index == 0 -> 0
                block is IslandMarkdownBlock.Heading -> dp(10)
                block is IslandMarkdownBlock.ListItem && blocks[index - 1] is IslandMarkdownBlock.ListItem -> dp(4)
                else -> dp(7)
            }
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = topGap
            })
        }
    }

    private fun buildPlaceholderView(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13.5f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(currentPalette.muted)
        setLineSpacing(dpF(3f), 1f)
        includeFontPadding = false
    }

    private fun buildHeadingView(block: IslandMarkdownBlock.Heading): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (block.level <= 2) {
            val nib = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(currentPalette.clay)
                    cornerRadius = dpF(2f)
                }
            }
            row.addView(nib, LayoutParams(dp(3), dp(if (block.level == 1) 16 else 14)).apply {
                marginEnd = dp(7)
            })
        }
        val title = TextView(context).apply {
            text = IslandMarkdown.formatInline(block.text, currentPalette)
            textSize = when (block.level) {
                1 -> 16.5f
                2 -> 15.5f
                3 -> 14.5f
                else -> 14f
            }
            typeface = if (block.level <= 2) titleTypeface else Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(currentPalette.ink)
            setLineSpacing(dpF(2.5f), 1f)
            includeFontPadding = false
        }
        row.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun buildParagraphView(text: String): TextView = TextView(context).apply {
        this.text = IslandMarkdown.formatInline(text, currentPalette)
        textSize = 14f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(currentPalette.ink)
        setLineSpacing(dpF(3.5f), 1f)
        includeFontPadding = false
    }

    private fun buildListItemView(block: IslandMarkdownBlock.ListItem): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        val markerView = TextView(context).apply {
            text = block.marker
            textSize = if (block.ordered) 12.5f else 13.5f
            typeface = Typeface.create(if (block.ordered) "monospace" else "sans-serif-medium", Typeface.BOLD)
            setTextColor(currentPalette.clay)
            includeFontPadding = false
            gravity = Gravity.END
            minWidth = dp(if (block.ordered) 20 else 14)
            setPadding(0, dp(1), dp(6), 0)
        }
        val bodyView = TextView(context).apply {
            text = IslandMarkdown.formatInline(block.text, currentPalette)
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(currentPalette.ink)
            setLineSpacing(dpF(3.2f), 1f)
            includeFontPadding = false
        }
        addView(markerView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(bodyView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildQuoteView(block: IslandMarkdownBlock.Quote): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        background = GradientDrawable().apply {
            setColor(IslandMarkdown.blendColor(currentPalette.paper, currentPalette.clay, 0.07f))
            cornerRadius = dpF(10f)
        }
        setPadding(dp(10), dp(7), dp(10), dp(7))
        val bar = View(context).apply {
            background = GradientDrawable().apply {
                setColor(currentPalette.clay)
                cornerRadius = dpF(1.5f)
            }
        }
        val text = TextView(context).apply {
            this.text = IslandMarkdown.formatInline(block.text, currentPalette)
            textSize = 13.5f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(IslandMarkdown.blendColor(currentPalette.ink, currentPalette.muted, 0.3f))
            setLineSpacing(dpF(3f), 1f)
            includeFontPadding = false
        }
        addView(bar, LayoutParams(dp(3), LayoutParams.MATCH_PARENT).apply { marginEnd = dp(9) })
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildCodeBlockView(block: IslandMarkdownBlock.CodeBlock): View = LinearLayout(context).apply {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(IslandMarkdown.blendColor(currentPalette.paper, currentPalette.ink, 0.055f))
            setStroke(max(1, dp(1)), currentPalette.edge)
            cornerRadius = dpF(11f)
        }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        if (block.language.isNotBlank()) {
            val lang = TextView(context).apply {
                text = block.language.uppercase()
                textSize = 10.5f
                letterSpacing = 0.06f
                typeface = Typeface.create("monospace", Typeface.BOLD)
                setTextColor(currentPalette.clay)
                includeFontPadding = false
            }
            addView(lang, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val codeText = TextView(context).apply {
            text = block.code.ifEmpty { " " }
            textSize = 12.5f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(currentPalette.ink)
            setLineSpacing(dpF(2.5f), 1f)
            includeFontPadding = false
        }
        scroll.addView(codeText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildTableView(table: MarkdownTable): View {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            background = GradientDrawable().apply {
                setColor(currentPalette.paper)
                setStroke(max(1, dp(1)), currentPalette.edge)
                cornerRadius = dpF(11f)
            }
        }
        val grid = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        val colWidths = table.header.indices.map { col ->
            val maxChars = maxOf(
                table.header.getOrElse(col) { "" }.length,
                table.rows.maxOfOrNull { it.getOrElse(col) { "" }.length } ?: 0,
            ).coerceIn(4, 24)
            dp((maxChars * 11).coerceIn(64, 196))
        }

        fun buildRow(cells: List<String>, isHeader: Boolean): View = LinearLayout(context).apply {
            orientation = HORIZONTAL
            if (isHeader) {
                background = GradientDrawable().apply {
                    setColor(IslandMarkdown.blendColor(currentPalette.well, currentPalette.clay, 0.12f))
                }
            }
            cells.forEachIndexed { colIndex, cellText ->
                val align = when (table.alignments.getOrElse(colIndex) { MarkdownTableAlignment.Start }) {
                    MarkdownTableAlignment.Start -> Gravity.START or Gravity.CENTER_VERTICAL
                    MarkdownTableAlignment.Center -> Gravity.CENTER
                    MarkdownTableAlignment.End -> Gravity.END or Gravity.CENTER_VERTICAL
                }
                val cell = TextView(context).apply {
                    text = IslandMarkdown.formatInline(cellText, currentPalette)
                    textSize = 12.5f
                    typeface = Typeface.create("sans-serif", if (isHeader) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(currentPalette.ink)
                    gravity = align
                    includeFontPadding = false
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                }
                addView(cell, LayoutParams(colWidths.getOrElse(colIndex) { dp(88) }, LayoutParams.WRAP_CONTENT))
            }
        }

        grid.addView(buildRow(table.header, isHeader = true))
        table.rows.forEach { rowCells ->
            val divider = View(context).apply { setBackgroundColor(currentPalette.edge) }
            grid.addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, max(1, dp(1))))
            grid.addView(buildRow(rowCells, isHeader = false))
        }
        scroll.addView(grid, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun buildDividerView(): View = View(context).apply {
        background = object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = currentPalette.edge
                strokeWidth = max(1f, dpF(1f))
            }
            private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = currentPalette.clay
                style = Paint.Style.FILL
            }
            override fun draw(canvas: Canvas) {
                val cy = bounds.exactCenterY()
                val cx = bounds.exactCenterX()
                val gap = dpF(12f)
                canvas.drawLine(bounds.left.toFloat(), cy, (cx - gap).coerceAtLeast(bounds.left.toFloat()), cy, paint)
                canvas.drawCircle(cx, cy, dpF(2.2f), dotPaint)
                canvas.drawLine((cx + gap).coerceAtMost(bounds.right.toFloat()), cy, bounds.right.toFloat(), cy, paint)
            }
            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
                dotPaint.alpha = alpha
            }
            override fun setColorFilter(colorFilter: ColorFilter?) {
                paint.colorFilter = colorFilter
                dotPaint.colorFilter = colorFilter
            }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        minimumHeight = dp(10)
    }
}
