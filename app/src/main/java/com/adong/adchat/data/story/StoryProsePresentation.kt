package com.adong.adchat.data.story

import com.adong.adchat.data.TavernPreset
import com.adong.adchat.data.TavernPresetRuntime
import com.adong.adchat.data.TavernRegexOutput
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

data class StoryProseBlock(val text: String, val title: String? = null, val html: String? = null)

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
    private val fence = Regex("```([^\\n`]*)\\n([\\s\\S]*?)(?:^ {0,3}```[ \\t]*$|\\z)", RegexOption.MULTILINE)
    private val htmlMarker = Regex("</?(?:html|body|style|details|div|span|p|section|article|options|current_event|progress|tucao|konatan_chat|h[1-6]|br)\\b", RegexOption.IGNORE_CASE)
    private val substantialHtml = Regex("<style|<!doctype|class=[\"']", RegexOption.IGNORE_CASE)
    private val containerOpen = Regex("<(div|details|style|section|article|table|body|html)\\b", RegexOption.IGNORE_CASE)
    private val containerClose = Regex("</(div|details|style|section|article|table|body|html)\\s*>", RegexOption.IGNORE_CASE)
    private val bridgeToken = com.adong.adchat.ui.components.HtmlPreviewDocument.PANEL_TOKEN
    private val bridgeScript = Regex("<script[^>]*>[^<]*${Regex.escape(bridgeToken)}[^<]*</script>", RegexOption.IGNORE_CASE)
    private val echoedInputPrefix = Regex("^【(?:LAST_USER_INPUT|HISTORICAL_USER_INPUT)】\\s*")
    private val inlineLead = Regex("^<(?:em|i|b|strong|q|u|s|small|mark|span|font|a|ruby|br)\\b", RegexOption.IGNORE_CASE)
    private val panelTitle = Regex("(?is)<title[^>]*>(.*?)</title>")

    private fun generatedPanelDocument(document: String, rp: Boolean = false): Boolean {
        // RP card frontends often title their status bar "状态面板"; only Aster's own marker counts there.
        if (rp) return document.contains(com.adong.adchat.ui.components.HtmlPreviewDocument.PANEL_CAPTURE_MARKER) &&
            document.contains(bridgeToken)
        val lower = document.lowercase()
        if (!lower.contains("<html") && !lower.contains("<!doctype")) return false
        val title = panelTitle.find(document)?.groupValues?.get(1)?.trim().orEmpty()
        return title.contains("面板")
    }

    fun present(raw: String, preset: TavernPreset?, role: String, depth: Int,
                regexEnabled: Boolean, streaming: Boolean): StoryProsePresentation {
        val body = if (role == "assistant") echoedInputPrefix.replace(raw.trimStart(), "") else raw
        val split = if (role == "assistant") StoryThoughtParser.split(body) else StoryThoughtSplit(raw, emptyList(), emptyList())
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
    fun nativeBlocks(source: String, preserveHtmlFragments: Boolean = false): List<StoryProseBlock> {
        val result = mutableListOf<StoryProseBlock>()
        var cursor = 0
        for (match in fence.findAll(source)) {
            appendSegment(source.substring(cursor, match.range.first), result, preserveHtmlFragments)
            val language = match.groupValues[1].trim()
            val content = match.groupValues[2].removeSuffix("```").trimEnd('\n')
            if (language.lowercase() in setOf("html", "htm") || (language.isEmpty() && htmlMarker.containsMatchIn(content))) {
                val document = stripBridgeArtifacts(content)
                when {
                    generatedPanelDocument(document, preserveHtmlFragments) -> Unit
                    preserveHtmlFragments && com.adong.adchat.ui.components.HtmlPreviewDocument.isFrontend(document) ->
                        result += StoryProseBlock("", html = document)
                    document.length >= 80 && substantialHtml.containsMatchIn(document) -> result += StoryProseBlock("", html = document)
                    else -> appendSegment(content, result, preserveHtmlFragments)
                }
            } else if (language.isEmpty()) {
                // 卡作者常用无语言标签的 fence 包裹开场白；阅读面按正文/HTML 处理，不折叠成代码块
                appendSegment(content, result, preserveHtmlFragments)
            } else result += StoryProseBlock(match.groupValues[0])
            cursor = match.range.last + 1
        }
        appendSegment(source.substring(cursor), result, preserveHtmlFragments)
        return result
    }

    private fun appendSegment(source: String, result: MutableList<StoryProseBlock>, preserveHtmlFragments: Boolean) {
        if (!preserveHtmlFragments) {
            appendFragment(source, result)
            return
        }
        // Separate prose paragraphs from HTML islands so narrative text keeps native reading while
        // card frontends (multi-line details bubbles included) stay intact for the sandbox.
        val prose = StringBuilder()
        val html = StringBuilder()
        var balance = 0
        fun flushProse() {
            if (prose.isNotBlank()) appendFragment(prose.toString(), result)
            prose.clear()
        }
        fun flushHtml() {
            if (html.isNotBlank()) {
                val fragment = html.toString().trim()
                if (generatedPanelDocument(fragment, true)) { html.clear(); return }
                if (!substantialHtml.containsMatchIn(fragment) && !containerOpen.containsMatchIn(fragment)) {
                    appendFragment(fragment, result)
                } else {
                    val last = result.lastOrNull()
                    if (last?.html != null) result[result.lastIndex] = last.copy(html = last.html + "\n" + fragment)
                    else result += StoryProseBlock("", html = fragment)
                }
            }
            html.clear()
        }
        source.lineSequence().forEach { line ->
            val trimmed = line.trim()
            val opens = containerOpen.findAll(line).count()
            val closes = containerClose.findAll(line).count()
            // A line opening with an inline tag (<em>, <q>, <b>…) is prose, not an HTML island.
            val htmlish = balance > 0 || (trimmed.startsWith("<") && (opens > 0 || substantialHtml.containsMatchIn(trimmed) ||
                !inlineLead.containsMatchIn(trimmed)))
            if (htmlish) {
                flushProse()
                html.append(line).append('\n')
                balance = (balance + opens - closes).coerceAtLeast(0)
                if (balance == 0) flushHtml()
            } else {
                if (html.isNotBlank()) flushHtml()
                prose.append(line).append('\n')
            }
        }
        flushHtml()
        flushProse()
    }

    /** Remove our own panel-bridge scripts so a re-presented bridged document stays stable. */
    private fun stripBridgeArtifacts(source: String): String {
        if (!source.contains(bridgeToken)) return source.trim()
        return bridgeScript.replace(source, "").trim()
    }

    private fun appendFragment(source: String, result: MutableList<StoryProseBlock>) {
        if (source.isBlank()) return
        if (generatedPanelDocument(source)) return
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
