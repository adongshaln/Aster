package com.adong.adchat.data.rp

import com.adong.adchat.data.TavernRegexScript
import com.adong.adchat.data.rp.prompt.RpMacroContext
import com.adong.adchat.data.rp.prompt.RpMacros
import com.adong.adchat.data.rp.prompt.RpRegex
import com.adong.adchat.data.story.StoryProsePresentation
import com.adong.adchat.data.story.StoryProsePresenter
import com.adong.adchat.data.story.StoryThoughtParser

/**
 * Presentation only for RP messages: display-surface regex (preset + allowed card scripts), macros,
 * and card frontends kept as HTML for the sandbox. SillyTavern renders all messages in one page, so a
 * regex-injected <style> styles every later message; per-message sandboxes instead receive the <style>
 * blocks of the scripts that actually matched this message, never every script's CSS.
 */
object RpPresentation {
    private val styleBlock = Regex("(?is)<style[^>]*>(.*?)</style>")
    /** Optional reading-surface flattening for cards designed on a dark page. Off by default. */
    const val FLATTEN_GLASS = "*{box-shadow:none!important;backdrop-filter:none!important;-webkit-backdrop-filter:none!important}"

    fun build(
        content: String,
        role: String,
        depth: Int,
        scripts: List<TavernRegexScript>,
        macros: RpMacroContext,
        flattenGlass: Boolean = false
    ): StoryProsePresentation {
        val split = if (role == "assistant") StoryThoughtParser.split(content) else null
        val body = split?.body ?: content
        val output = RpRegex.display(body, role, depth, scripts, macros)
        val text = RpMacros.expand(output.text, macros)
        val matched = output.matchedScriptIds.toSet()
        val css = scripts.filter { it.id in matched }
            .flatMap { script -> styleBlock.findAll(script.replaceString).map { it.groupValues[1] } }
            .joinToString("\n")
        val extra = listOfNotNull(FLATTEN_GLASS.takeIf { flattenGlass }, css.takeIf { it.isNotBlank() }).joinToString("\n")
        val blocks = StoryProsePresenter.nativeBlocks(text, preserveHtmlFragments = true).map { block ->
            val html = block.html ?: return@map block
            val closed = closeDetails(html)
            block.copy(html = if (extra.isNotBlank() && !closed.contains(extra)) injectStyle(closed, extra) else closed)
        }
        return StoryProsePresentation(
            blocks = blocks,
            thinking = split?.thinking?.joinToString("\n\n") { it.text }.orEmpty(),
            thinkingIncomplete = split?.thinking?.any { it.incomplete } == true,
            planning = split?.planning?.joinToString("\n\n") { it.text }.orEmpty(),
            planningIncomplete = split?.planning?.any { it.incomplete } == true
        )
    }

    private val headOpen = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val detailsOpen = Regex("(<details\\b[^>]*?)\\s+open(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))?(?=[\\s>/])", RegexOption.IGNORE_CASE)

    /** Collapsible sections start closed in the reading surface; the reader opens them. */
    internal fun closeDetails(html: String): String = if (!html.contains("<details", true)) html else detailsOpen.replace(html) { it.groupValues[1] }

    /** Shared styles go inside <head> when the block is a full document, keeping its doctype first. */
    private fun injectStyle(html: String, css: String): String {
        val tag = "<style>$css</style>"
        headOpen.find(html)?.let { return html.replaceRange(it.range.last + 1, it.range.last + 1, tag) }
        return "$tag\n$html"
    }
}
