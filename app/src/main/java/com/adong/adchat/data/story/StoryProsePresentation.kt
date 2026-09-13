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
    val showPlanningStatus: Boolean = false,
    val thinking: String = "",
    val thinkingIncomplete: Boolean = false
)

/** Presentation only: never writes cleaned text back to message history or request context. */
object StoryProsePresenter {
    private val fence = Regex("```[^\\n]*\\n[\\s\\S]*?(?:```|$)")
    private val htmlMarker = Regex("</?(?:html|body|style|details|div|span|p|section|article|options|current_event|progress|tucao|konatan_chat|h[1-6]|br)\\b", RegexOption.IGNORE_CASE)

    fun present(raw: String, preset: TavernPreset?, role: String, depth: Int,
                regexEnabled: Boolean, streaming: Boolean): StoryProsePresentation {
        val split = if (role == "assistant") StoryThoughtParser.split(raw) else StoryThoughtSplit(raw, emptyList(), emptyList())
        val output = if (preset != null && !streaming) {
            TavernPresetRuntime.display(preset, split.body, role, depth, regexEnabled)
        } else TavernRegexOutput(split.body, 0, emptyList())
        return StoryProsePresentation(
            planning = split.planning.joinToString("\n\n") { it.text },
            planningIncomplete = split.planning.any { it.incomplete },
            blocks = nativeBlocks(output.text), skippedScripts = output.skippedScripts,
            showPlanningStatus = preset != null || split.planning.isNotEmpty(),
            thinking = split.thinking.joinToString("\n\n") { it.text },
            thinkingIncomplete = split.thinking.any { it.incomplete }
        )
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
