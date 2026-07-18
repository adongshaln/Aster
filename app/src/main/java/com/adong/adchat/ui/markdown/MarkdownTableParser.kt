package com.adong.adchat.ui.markdown

internal enum class MarkdownTableAlignment {
    Start,
    Center,
    End
}

internal data class MarkdownTable(
    val header: List<String>,
    val alignments: List<MarkdownTableAlignment>,
    val rows: List<List<String>>
) {
    val columnCount: Int get() = header.size
}

internal data class MarkdownTableMatch(
    val table: MarkdownTable,
    val nextLineIndex: Int
)

private val TABLE_DELIMITER_CELL = Regex("^:?-{3,}:?$")
private val HTML_LINE_BREAK = Regex("(?i)<br\\s*/?>")
private const val MAX_TABLE_COLUMNS = 16

/**
 * Parses a GitHub-flavoured Markdown table beginning at [headerIndex]. The parser is deliberately
 * tolerant of model output: missing edge pipes, uneven rows, escaped pipes, inline-code pipes and
 * HTML line breaks are all handled without dropping content.
 */
internal fun parseMarkdownTableAt(lines: List<String>, headerIndex: Int): MarkdownTableMatch? {
    if (headerIndex !in lines.indices || headerIndex + 1 !in lines.indices) return null

    val headerCells = splitMarkdownTableRow(lines[headerIndex]) ?: return null
    val delimiterCells = splitMarkdownTableRow(lines[headerIndex + 1]) ?: return null
    if (headerCells.isEmpty() || delimiterCells.isEmpty()) return null
    if (!delimiterCells.all(::isDelimiterCell)) return null

    val columnCount = maxOf(headerCells.size, delimiterCells.size).coerceAtMost(MAX_TABLE_COLUMNS)
    if (columnCount == 1 && !hasEdgePipe(lines[headerIndex])) return null

    val normalizedHeader = normalizeRow(headerCells, columnCount)
    val alignments = normalizeRow(delimiterCells, columnCount).map(::delimiterAlignment)
    val bodyRows = mutableListOf<List<String>>()
    var cursor = headerIndex + 2

    while (cursor < lines.size) {
        val line = lines[cursor]
        if (line.isBlank()) break
        val cells = splitMarkdownTableRow(line) ?: break
        if (cells.isEmpty() || cells.all { it.isBlank() } || cells.all(::isDelimiterCell)) break
        bodyRows += normalizeRow(cells, columnCount)
        cursor++
    }

    return MarkdownTableMatch(
        table = MarkdownTable(
            header = normalizedHeader,
            alignments = alignments,
            rows = bodyRows
        ),
        nextLineIndex = cursor
    )
}

internal fun containsMarkdownTable(text: String): Boolean {
    val lines = text.lines()
    for (index in 0 until lines.lastIndex) {
        if (parseMarkdownTableAt(lines, index) != null) return true
    }
    return false
}

internal fun markdownTableToTsv(table: MarkdownTable): String = buildString {
    append(table.header.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') })
    table.rows.forEach { row ->
        append('\n')
        append(row.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') })
    }
}

internal fun splitMarkdownTableRow(line: String): List<String>? {
    val source = line.trim()
    if (source.isEmpty()) return null

    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var separatorCount = 0
    var codeFenceLength = 0
    var index = 0

    while (index < source.length) {
        val char = source[index]
        when {
            char == '\\' && index + 1 < source.length && source[index + 1] == '|' -> {
                cell.append('|')
                index += 2
            }
            char == '`' -> {
                val runStart = index
                while (index < source.length && source[index] == '`') index++
                val runLength = index - runStart
                if (codeFenceLength == 0) codeFenceLength = runLength
                else if (codeFenceLength == runLength) codeFenceLength = 0
                repeat(runLength) { cell.append('`') }
            }
            char == '|' && codeFenceLength == 0 -> {
                cells += normalizeCell(cell.toString())
                cell.clear()
                separatorCount++
                index++
            }
            else -> {
                cell.append(char)
                index++
            }
        }
    }
    cells += normalizeCell(cell.toString())

    if (separatorCount == 0) return null
    if (source.startsWith('|') && cells.firstOrNull().isNullOrBlank()) cells.removeAt(0)
    if (source.endsWith('|') && cells.lastOrNull().isNullOrBlank()) cells.removeAt(cells.lastIndex)
    return cells
}

private fun normalizeCell(value: String): String = value
    .replace(HTML_LINE_BREAK, "\n")
    .trim()

private fun normalizeRow(cells: List<String>, columnCount: Int): List<String> {
    if (columnCount <= 0) return emptyList()
    if (cells.size == columnCount) return cells
    if (cells.size < columnCount) return cells + List(columnCount - cells.size) { "" }
    if (columnCount == 1) return listOf(cells.joinToString(" | "))
    return cells.take(columnCount - 1) + cells.drop(columnCount - 1).joinToString(" | ")
}

private fun isDelimiterCell(value: String): Boolean = TABLE_DELIMITER_CELL.matches(
    value.filterNot(Char::isWhitespace)
)

private fun delimiterAlignment(value: String): MarkdownTableAlignment {
    val marker = value.filterNot(Char::isWhitespace)
    return when {
        marker.startsWith(':') && marker.endsWith(':') -> MarkdownTableAlignment.Center
        marker.endsWith(':') -> MarkdownTableAlignment.End
        else -> MarkdownTableAlignment.Start
    }
}

private fun hasEdgePipe(line: String): Boolean {
    val value = line.trim()
    return value.startsWith('|') || value.endsWith('|')
}
