package com.adong.adchat.ui.markdown

internal const val NAME_MARK_OPEN = "*+-"
internal const val NAME_MARK_CLOSE = "*+-"

internal const val NARRATIVE_NAME_MARK_INSTRUCTION =
    "写小说、故事或叙事正文时，人物姓名用 *+-姓名*+- 包住，只包名字本身。普通问答、代码和标题不要使用这组符号。"

internal const val COVER_IMAGE_INSTRUCTION =
    "当用户在找书籍、番号、视频或漫画时，只有你确实找到了作品，并且检索到了封面的图片直链，才在说明后单独一行放 ![](https://真实地址)。不要编造图片地址；找不到封面就不要放图。"

private val CHAPTER_HEADING = Regex(
    """^(第\s*[0-9０-９一二三四五六七八九十百千零两〇]+\s*[章节回卷部篇幕]|序章|楔子|尾声|终章|番外|后记|前言|Chapter\s+\d+).*$""",
    RegexOption.IGNORE_CASE
)

internal fun isChapterHeading(line: String): Boolean {
    val text = line.trim()
    if (text.isEmpty() || text.length > 40) return false
    return CHAPTER_HEADING.matches(text)
}

internal fun isSceneBreak(line: String): Boolean {
    val value = line.filterNot(Char::isWhitespace)
    if (value in setOf("***", "——", "……", "...", "◇", "◆", "※")) return true
    if (value.length >= 3 && value.all { it == '-' || it == '*' || it == '＊' || it == '※' || it == '·' || it == '•' }) return true
    return false
}

/** `* 他低声说*` is emphasis, not a Markdown bullet. */
internal fun isStarredProseLine(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.length > 2 && trimmed.startsWith("*") && trimmed.endsWith("*") && !trimmed.startsWith("**")
}

/** Closed emphasis is real formatting. Only an empty, huge, or line-breaking span stays literal, so one stray star cannot italicize the rest of the chapter. */
internal fun emphasisSpanAllowed(inner: String): Boolean {
    if (inner.isBlank() || inner.length > 400) return false
    if (inner.contains('\n')) return false
    if (inner.all { it.isWhitespace() || it == '*' || it == '_' }) return false
    return true
}

/** A single source newline stays in the same paragraph and reflows. Blank lines still split paragraphs. */
internal fun joinProseLine(paragraph: StringBuilder, line: String) {
    val next = line.trim()
    if (next.isEmpty()) return
    if (paragraph.isNotEmpty()) {
        val previous = paragraph.last()
        val start = next.first()
        if (previous.isWhitespace() || start.isWhitespace()) {
            // already separated
        } else if (paragraphBreakNeedsSpace(previous, start)) {
            paragraph.append(' ')
        }
    }
    paragraph.append(next)
}

private fun paragraphBreakNeedsSpace(previous: Char, next: Char): Boolean {
    fun latin(char: Char) = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9'
    if (latin(next) || latin(previous)) return true
    return false
}

internal sealed class ReadingSpan {
    data class Text(val value: String) : ReadingSpan()
    data class Name(val value: String) : ReadingSpan()
}

private val NAME_MARKS = listOf("*+-" to "*+-", "/.." to "../")

/** Hides name marks and keeps the name as its own span. An unclosed mark stays literal. */
internal fun readingSpans(text: String): List<ReadingSpan> {
    val normalized = text.replace('＊', '*').replace('﹡', '*')
    if (NAME_MARKS.none { normalized.contains(it.first) }) return listOf(ReadingSpan.Text(normalized))
    val result = mutableListOf<ReadingSpan>()
    var index = 0
    while (index < normalized.length) {
        val opening = NAME_MARKS.mapNotNull { mark ->
            val at = normalized.indexOf(mark.first, index)
            if (at < 0) null else Triple(mark.first, mark.second, at)
        }.minByOrNull { it.third }
        if (opening == null) {
            result += ReadingSpan.Text(normalized.substring(index))
            break
        }
        val (open, close, start) = opening
        if (start > index) result += ReadingSpan.Text(normalized.substring(index, start))
        val end = normalized.indexOf(close, start + open.length)
        if (end < 0) {
            result += ReadingSpan.Text(normalized.substring(start))
            break
        }
        val name = normalized.substring(start + open.length, end)
        if (name.isBlank()) result += ReadingSpan.Text(normalized.substring(start, end + close.length))
        else result += ReadingSpan.Name(name)
        index = end + close.length
    }
    return result
}
