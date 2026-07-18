package com.adong.adchat.ui.markdown

internal enum class ReadingAccentKind {
    Quote,
    Bracket
}

internal data class ReadingAccentRange(
    val start: Int,
    val end: Int,
    val kind: ReadingAccentKind
)

internal fun findReadingAccentRanges(
    text: String,
    blockedRanges: List<IntRange> = emptyList()
): List<ReadingAccentRange> {
    val candidates = buildList {
        addAll(findStackedPairs(text, '“', '”', ReadingAccentKind.Quote))
        addAll(findStackedPairs(text, '‘', '’', ReadingAccentKind.Quote))
        addAll(findSymmetricPairs(text, '"', ReadingAccentKind.Quote))
        addAll(findSymmetricPairs(text, '\'', ReadingAccentKind.Quote))
        addAll(findStackedPairs(text, '「', '」', ReadingAccentKind.Bracket))
        addAll(findStackedPairs(text, '[', ']', ReadingAccentKind.Bracket))
    }
    return candidates
        .flatMap { range -> subtractBlocked(range, blockedRanges) }
        .filter { it.start < it.end }
        .distinct()
        .sortedWith(
            compareBy<ReadingAccentRange> { if (it.kind == ReadingAccentKind.Quote) 0 else 1 }
                .thenBy(ReadingAccentRange::start)
                .thenBy(ReadingAccentRange::end)
        )
}

private fun findStackedPairs(
    text: String,
    opening: Char,
    closing: Char,
    kind: ReadingAccentKind
): List<ReadingAccentRange> {
    val openings = ArrayDeque<Int>()
    val result = mutableListOf<ReadingAccentRange>()
    text.forEachIndexed { index, char ->
        when (char) {
            opening -> openings.addLast(index)
            closing -> openings.removeLastOrNull()?.let { start ->
                if (index > start + 1) result += ReadingAccentRange(start, index + 1, kind)
            }
        }
    }
    return result
}

private fun findSymmetricPairs(
    text: String,
    delimiter: Char,
    kind: ReadingAccentKind
): List<ReadingAccentRange> {
    val result = mutableListOf<ReadingAccentRange>()
    var openingIndex: Int? = null
    text.forEachIndexed { index, char ->
        if (char != delimiter || isEscaped(text, index)) return@forEachIndexed
        val start = openingIndex
        if (start == null) {
            openingIndex = index
        } else {
            if (index > start + 1) result += ReadingAccentRange(start, index + 1, kind)
            openingIndex = null
        }
    }
    return result
}

private fun isEscaped(text: String, index: Int): Boolean {
    var slashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] == '\\') {
        slashCount += 1
        cursor -= 1
    }
    return slashCount % 2 == 1
}

private fun subtractBlocked(
    range: ReadingAccentRange,
    blockedRanges: List<IntRange>
): List<ReadingAccentRange> {
    var segments = listOf(range.start until range.end)
    blockedRanges.forEach { blocked ->
        val blockedStart = blocked.first
        val blockedEnd = blocked.last + 1
        segments = segments.flatMap { segment ->
            val segmentStart = segment.first
            val segmentEnd = segment.last + 1
            if (blockedEnd <= segmentStart || blockedStart >= segmentEnd) {
                listOf(segment)
            } else {
                buildList {
                    if (blockedStart > segmentStart) add(segmentStart until blockedStart.coerceAtMost(segmentEnd))
                    if (blockedEnd < segmentEnd) add(blockedEnd.coerceAtLeast(segmentStart) until segmentEnd)
                }
            }
        }
    }
    return segments.filterNot(IntRange::isEmpty).map { segment ->
        range.copy(start = segment.first, end = segment.last + 1)
    }
}
