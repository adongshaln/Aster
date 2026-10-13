package com.adong.adchat.ui.markdown

internal sealed class InlinePiece {
    data class Prose(val text: String) : InlinePiece()
    data class RemoteImage(val url: String, val alt: String) : InlinePiece()
    data class PixivArtwork(val pageUrl: String, val id: String) : InlinePiece()
}

private val MARKDOWN_IMAGE = Regex("""!\[([^\]\n]*)]\((https?://[^)\s]+)\)""")
private val WRAPPED_IMAGE = Regex("""\*\+-+(https?://[^\s*]+)\*\+-+""")
private val BARE_IMAGE = Regex(
    """https?://[^\s<>'"()\]]+\.(?:jpe?g|png|webp|gif|avif)(?:\?[^\s<>'"()\]]*)?""",
    RegexOption.IGNORE_CASE
)
private val PIXIV_MARKDOWN = Regex(
    """\[([^\]\n]*)]\((https?://(?:www\.)?pixiv\.net/(?:[a-z]{2}/)?artworks/(\d+))\)""",
    RegexOption.IGNORE_CASE
)
private val PIXIV_BARE = Regex(
    """https?://(?:www\.)?pixiv\.net/(?:[a-z]{2}/)?artworks/(\d+)""",
    RegexOption.IGNORE_CASE
)

private data class ImageHit(val start: Int, val end: Int, val url: String, val alt: String)

/** A URL the model wrapped onto the next line is one address again. */
internal fun repairWrappedUrls(text: String): String {
    val broken = Regex("""(https?://\S+)\s*\n\s*(/\S+)""")
    var current = text
    repeat(4) {
        val next = broken.replace(current) { match -> match.groupValues[1] + match.groupValues[2] }
        if (next == current) return current
        current = next
    }
    return current
}

/** Pulls real image addresses out of prose so they can be shown instead of left as raw markup. */
internal fun splitInlineImages(text: String): List<InlinePiece> {
    val source = repairWrappedUrls(text).replace(Regex("""(https?://\S+)\s*\n\s*(\S+)""")) { match ->
        val joined = match.groupValues[1] + match.groupValues[2]
        if (BARE_IMAGE.containsMatchIn(joined)) joined else match.value
    }
    val hits = mutableListOf<ImageHit>()
    MARKDOWN_IMAGE.findAll(source).forEach { match ->
        hits += ImageHit(match.range.first, match.range.last + 1, match.groupValues[2], match.groupValues[1])
    }
    WRAPPED_IMAGE.findAll(source).forEach { match ->
        hits += ImageHit(match.range.first, match.range.last + 1, match.groupValues[1], "")
    }
    BARE_IMAGE.findAll(source).forEach { match ->
        hits += ImageHit(match.range.first, match.range.last + 1, match.value, "")
    }
    val ordered = hits.sortedWith(compareBy<ImageHit> { it.start }.thenByDescending { it.end })
    val taken = mutableListOf<ImageHit>()
    for (hit in ordered) {
        if (taken.any { hit.start < it.end && hit.end > it.start }) continue
        taken += hit
    }
    taken.sortBy { it.start }
    val pieces = mutableListOf<InlinePiece>()
    var cursor = 0
    taken.forEach { hit ->
        if (hit.start > cursor) pieces += InlinePiece.Prose(source.substring(cursor, hit.start))
        pieces += InlinePiece.RemoteImage(hit.url, hit.alt)
        cursor = hit.end
    }
    if (cursor < source.length) pieces += InlinePiece.Prose(source.substring(cursor))
    return pieces.filterNot { it is InlinePiece.Prose && it.text.isBlank() }.flatMap { piece ->
        if (piece is InlinePiece.Prose) splitPixivArtworks(piece.text) else listOf(piece)
    }
}

private data class PixivHit(val start: Int, val end: Int, val pageUrl: String, val id: String)

private fun splitPixivArtworks(text: String): List<InlinePiece> {
    val hits = mutableListOf<PixivHit>()
    PIXIV_MARKDOWN.findAll(text).forEach { match ->
        hits += PixivHit(match.range.first, match.range.last + 1, match.groupValues[2], match.groupValues[3])
    }
    PIXIV_BARE.findAll(text).forEach { match ->
        hits += PixivHit(match.range.first, match.range.last + 1, match.value, match.groupValues[1])
    }
    val ordered = hits.sortedWith(compareBy<PixivHit> { it.start }.thenByDescending { it.end })
    val taken = mutableListOf<PixivHit>()
    for (hit in ordered) {
        if (taken.any { hit.start < it.end && hit.end > it.start }) continue
        taken += hit
    }
    taken.sortBy { it.start }
    if (taken.isEmpty()) return listOf(InlinePiece.Prose(text))
    val pieces = mutableListOf<InlinePiece>()
    var cursor = 0
    taken.forEach { hit ->
        if (hit.start > cursor) pieces += InlinePiece.Prose(text.substring(cursor, hit.start))
        pieces += InlinePiece.PixivArtwork(hit.pageUrl, hit.id)
        cursor = hit.end
    }
    if (cursor < text.length) pieces += InlinePiece.Prose(text.substring(cursor))
    return pieces.filterNot { it is InlinePiece.Prose && it.text.isBlank() }
}

internal data class TextLink(val end: Int, val label: String, val url: String)

private val MARKDOWN_LINK = Regex("""^\[([^\]\n]+)]\((https?://[^)\s]+)\)""")
private val BARE_LINK = Regex("""^https?://[^\s<>'"()\]]+""")
private val LINK_TRAILING = ".,;:!?，。！？、）)]》」』"

/** A markdown link or a bare http(s) address starting at [index]. */
internal fun linkAt(text: String, index: Int): TextLink? {
    val slice = text.substring(index)
    MARKDOWN_LINK.find(slice)?.let { match ->
        if (match.range.first != 0) return@let
        return TextLink(index + match.range.last + 1, match.groupValues[1], match.groupValues[2])
    }
    val bare = BARE_LINK.find(slice) ?: return null
    if (bare.range.first != 0) return null
    val raw = bare.value.trimEnd { it in LINK_TRAILING }
    if (raw.length < "https://a".length) return null
    return TextLink(index + raw.length, raw, raw)
}

/** Plain text ends at the next emphasis token or at an http(s) address, so a link mid-paragraph is not skipped. */
internal fun nextPlainEnd(text: String, start: Int, tokens: List<String>): Int {
    var end = text.length
    for (token in tokens) {
        val at = text.indexOf(token, start)
        if (at in start until end) end = at
    }
    for (scheme in listOf("https://", "http://")) {
        val at = text.indexOf(scheme, start)
        if (at in start until end) end = at
    }
    var bracket = text.indexOf('[', start)
    while (bracket in start until end) {
        val tail = text.substring(bracket)
        if (MARKDOWN_LINK.containsMatchIn(tail) && MARKDOWN_LINK.find(tail)?.range?.first == 0) {
            end = bracket
            break
        }
        bracket = text.indexOf('[', bracket + 1)
    }
    return end.coerceAtLeast(start + 1).coerceAtMost(text.length)
}
