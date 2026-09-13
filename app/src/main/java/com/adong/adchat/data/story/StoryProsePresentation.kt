package com.adong.adchat.data.story

import com.adong.adchat.data.TavernPreset
import com.adong.adchat.data.TavernPresetRuntime
import com.adong.adchat.data.TavernRegexOutput
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

data class StoryProseBlock(val text: String, val title: String? = null)

data class StoryProsePresentation(
    val planning: String = "",
    val planningIncomplete: Boolean = false,
    val blocks: List<StoryProseBlock> = emptyList(),
    val skippedScripts: List<String> = emptyList(),
    val showPlanningStatus: Boolean = false
)

/** Presentation only: never writes cleaned text back to message history or request context. */
object StoryProsePresenter {
    private val planningTag = Regex("<(/?)(konatan_planning~|think|thinking)\\s*>", RegexOption.IGNORE_CASE)
    private val fence = Regex("```[^\\n]*\\n[\\s\\S]*?(?:```|$)")
    private val htmlMarker = Regex("</?(?:html|body|style|details|div|span|p|section|article|options|current_event|progress|tucao|konatan_chat|h[1-6]|br)\\b", RegexOption.IGNORE_CASE)

    fun present(raw: String, preset: TavernPreset?, role: String, depth: Int,
                regexEnabled: Boolean, streaming: Boolean): StoryProsePresentation {
        val codeRanges = fence.findAll(raw).map { it.range }.toList()
        val tags = planningTag.findAll(raw).filter { tag -> codeRanges.none { tag.range.first in it } }.toList()
        val body = StringBuilder()
        val planning = mutableListOf<String>()
        var cursor = 0
        var incomplete = false
        if (role == "assistant") for (tag in tags) {
            if (tag.range.first < cursor) continue
            if (tag.groupValues[1].isEmpty()) {
                body.append(raw.substring(cursor, tag.range.first))
                val end = tags.firstOrNull { it.range.first > tag.range.first && it.groupValues[1] == "/" &&
                    it.groupValues[2].equals(tag.groupValues[2], ignoreCase = true) }
                planning += raw.substring(tag.range.last + 1, end?.range?.first ?: raw.length)
                cursor = end?.range?.last?.plus(1) ?: raw.length
                incomplete = incomplete || end == null
            } else if (cursor == 0 && tag.groupValues[2].equals("konatan_planning~", true)) {
                // An assistant prefill may contain the opening tag, outside the returned text.
                planning += raw.substring(0, tag.range.first)
                cursor = tag.range.last + 1
            }
        }
        body.append(raw.substring(cursor))
        val output = if (preset != null && !streaming) {
            TavernPresetRuntime.display(preset, body.toString(), role, depth, regexEnabled)
        } else TavernRegexOutput(body.toString(), 0, emptyList())
        return StoryProsePresentation(planning.joinToString("\n\n").trim(), incomplete,
            nativeBlocks(output.text), output.skippedScripts, preset != null || planning.isNotEmpty())
    }

    /** HTML is parsed as data. No WebView, JavaScript, CSS, or external resource loading. */
    fun nativeBlocks(source: String): List<StoryProseBlock> {
        val result = mutableListOf<StoryProseBlock>()
        var cursor = 0
        for (match in fence.findAll(source)) {
            appendFragment(source.substring(cursor, match.range.first), result)
            val code = match.value.removePrefix("```")
            val language = code.substringBefore('\n').trim()
            val content = code.substringAfter('\n').removeSuffix("```")
            if (language.lowercase() in setOf("html", "htm") || (language.isEmpty() && htmlMarker.containsMatchIn(content))) {
                appendFragment(content, result)
            } else result += StoryProseBlock(match.value)
            cursor = match.range.last + 1
        }
        appendFragment(source.substring(cursor), result)
        return result
    }

    private fun appendFragment(source: String, result: MutableList<StoryProseBlock>) {
        if (source.isBlank()) return
        if (!htmlMarker.containsMatchIn(source)) {
            result += StoryProseBlock(source.trim())
            return
        }
        val document = Jsoup.parseBodyFragment(source)
        document.select("script,style,link,meta,head,iframe,object,embed").remove()
        val text = StringBuilder()
        fun flush() {
            text.toString().trim().takeIf(String::isNotEmpty)?.let { result += StoryProseBlock(it) }
            text.clear()
        }
        fun readable(node: Node): String = when (node) {
            is TextNode -> node.wholeText
            is Element -> when (node.normalName()) {
                "br" -> "\n"
                "img" -> node.attr("alt")
                else -> node.childNodes().joinToString("") { readable(it) } +
                    if (node.isBlock) "\n" else ""
            }
            else -> ""
        }
        fun visit(node: Node) {
            if (node is Element) {
                val name = node.normalName()
                val labels = mapOf("options" to "后续选项", "current_event" to "当前事件",
                    "progress" to "剧情进展", "tucao" to "旁白", "konatan_chat" to "闲聊")
                if (name == "details" || name in labels) {
                    flush()
                    val summary = node.children().firstOrNull { it.normalName() == "summary" }
                    val title = when {
                        summary?.hasClass("k-sum-s") == true -> "剧情摘要"
                        summary?.hasClass("tucao-s") == true -> "补充对话"
                        node.hasClass("kz-w") -> "事件记录"
                        else -> summary?.text()?.takeIf(String::isNotBlank) ?: labels[name] ?: "补充内容"
                    }
                    val content = node.childNodes().filter { it !== summary }.joinToString("") { readable(it) }.trim()
                    if (content.isNotEmpty()) result += StoryProseBlock(content, title)
                    return
                }
                if (name == "br") { text.append('\n'); return }
                if (node.isBlock) text.append('\n')
                if (name == "li") text.append("• ")
                node.childNodes().forEach { visit(it) }
                if (node.isBlock) text.append('\n')
            } else if (node is TextNode) text.append(node.wholeText)
        }
        document.body().childNodes().forEach { visit(it) }
        flush()
    }
}
