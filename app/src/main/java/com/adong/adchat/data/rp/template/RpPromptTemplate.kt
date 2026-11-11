package com.adong.adchat.data.rp.template

import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.rp.helper.RpYaml
import com.adong.adchat.data.story.StoryWorldBook
import com.adong.adchat.data.story.StoryWorldBookEntry
import org.json.JSONObject

/**
 * Built-in Prompt Template support (SillyTavern ST-Prompt-Template), the app's own way:
 *
 * - **Generate**: after RpPromptEngine assembles the request, every message's `<% %>` runs, then
 *   `[GENERATE:BEFORE]`/`[GENERATE:AFTER]` and `[GENERATE:{idx}:BEFORE|AFTER]` entries (or the
 *   `@@generate_before`/`@@generate_after` decorators) are injected.
 * - **Render**: a displayed message runs its `<% %>` and gets `[RENDER:BEFORE|AFTER]` entries added
 *   (display only, never stored).
 * - **Initial variables**: `[InitialVariables]` / `@@initial_variables` entries (JSON or YAML object)
 *   seed the first floor.
 *
 * Special entries are taken out of the normal world-info scan (they are directives, not lore).
 * `@@if <expr>` drops an entry when the expression is false. A failed template keeps its raw text.
 */
object RpPromptTemplate {
    private val SPECIAL = Regex("^\\[(GENERATE|RENDER)(?::(\\d+))?:(BEFORE|AFTER)]|^\\[InitialVariables]", RegexOption.IGNORE_CASE)
    private val DECORATOR_LINE = Regex("^@@([a-z_]+)(?:\\s+(.*))?$")
    private val KNOWN = setOf(
        "activate", "dont_activate", "message_formatting", "generate_before", "generate_after", "render_before",
        "render_after", "dont_preload", "preload", "only_preload", "initial_variables", "always_enabled", "private",
        "if", "iframe", "preprocessing"
    )

    enum class Kind { GenerateBefore, GenerateAfter, RenderBefore, RenderAfter, InitialVariables }

    data class Decorated(val decorators: Map<String, String>, val body: String)

    data class Special(val entry: StoryWorldBookEntry, val kind: Kind, val index: Int?, val decorated: Decorated)

    /** Reads `@@name arg` lines at the start of an entry (each on its own line, no blank lines). */
    fun decorators(content: String): Decorated {
        if (!content.startsWith("@@")) return Decorated(emptyMap(), content)
        val lines = content.split("\n")
        val found = linkedMapOf<String, String>()
        var i = 0
        while (i < lines.size && lines[i].startsWith("@@")) {
            val line = lines[i].trimEnd('\r')
            if (line.startsWith("@@@")) break
            DECORATOR_LINE.find(line)?.let { m -> if (m.groupValues[1] in KNOWN) found[m.groupValues[1]] = m.groupValues[2].trim() }
            i++
        }
        val rest = lines.drop(i).joinToString("\n").let { if (it.startsWith("@@@")) it.substring(1) else it }
        return Decorated(found, rest)
    }

    fun classify(entry: StoryWorldBookEntry): Special? {
        val decorated = decorators(entry.content)
        SPECIAL.find(entry.comment.trim())?.let { m ->
            if (m.value.equals("[InitialVariables]", true)) return Special(entry, Kind.InitialVariables, null, decorated)
            val generate = m.groupValues[1].equals("GENERATE", true)
            val before = m.groupValues[3].equals("BEFORE", true)
            val kind = when {
                generate && before -> Kind.GenerateBefore
                generate -> Kind.GenerateAfter
                before -> Kind.RenderBefore
                else -> Kind.RenderAfter
            }
            return Special(entry, kind, m.groupValues[2].toIntOrNull(), decorated)
        }
        val d = decorated.decorators
        val kind = when {
            "initial_variables" in d -> Kind.InitialVariables
            "generate_before" in d -> Kind.GenerateBefore
            "generate_after" in d -> Kind.GenerateAfter
            "render_before" in d -> Kind.RenderBefore
            "render_after" in d -> Kind.RenderAfter
            else -> return null
        }
        val index = (d["generate_before"] ?: d["generate_after"])?.toIntOrNull()
        return Special(entry, kind, index, decorated)
    }

    /** World book without the template directive entries, for the regular world-info scan. */
    fun scanBook(book: StoryWorldBook): StoryWorldBook {
        if (book.entries.none { classify(it) != null || decorators(it.content).decorators.isNotEmpty() }) return book
        return book.copy(entries = book.entries.mapNotNull { entry ->
            if (classify(entry) != null) null
            else decorators(entry.content).let { d -> if ("only_preload" in d.decorators) null else entry }
        })
    }

    /** Whether any template feature is in play (cheap check so plain chats skip the passes). */
    fun active(book: StoryWorldBook, messages: List<ChatMessage>, system: String): Boolean =
        RpTemplate.hasTags(system) || messages.any { RpTemplate.hasTags(it.content) } ||
            book.entries.any { it.enabled && classify(it) != null }

    private fun specials(book: StoryWorldBook, kind: Kind, index: Int? = null): List<Special> =
        book.entries.asSequence().filter { it.enabled || "always_enabled" in decorators(it.content).decorators }
            .mapNotNull(::classify).filter { it.kind == kind && it.index == index }
            .sortedBy { it.entry.insertionOrder }.toList()

    /** Evaluates special entries of one kind into text; `@@if` gates each. */
    private fun evaluate(list: List<Special>, ctx: RpTplContext, escape: Boolean): String = list.mapNotNull { special ->
        val host = RpTplHost(ctx)
        val condition = special.decorated.decorators["if"]
        if (condition != null && !ifPasses(condition, host)) return@mapNotNull null
        val body = ctx.macros(special.decorated.body)
        val text = RpTemplate.render(body, host, escape) ?: body
        val iframe = special.decorated.decorators["iframe"]
        when {
            iframe != null && ctx.stage == RpTplStage.Render -> wrapFrame(text, iframe)
            else -> text
        }.takeIf { it.isNotBlank() }
    }.joinToString("\n")

    private fun ifPasses(expression: String, host: RpTplHost): Boolean =
        RpTemplate.render("<%- ($expression) ? '1' : '' %>", host) == "1"

    /** `@@iframe [title]`: rendered through the HTML sandbox, collapsed under the title when given. */
    private fun wrapFrame(html: String, title: String): String {
        val body = if (html.contains("<html", true)) html else "<!doctype html><html><head><meta charset=\"utf-8\"></head><body>$html</body></html>"
        val fenced = "```html\n$body\n```"
        return if (title.isBlank()) fenced else "<details><summary>$title</summary>\n\n$fenced\n\n</details>"
    }

    /**
     * Generate pass over the final request. Runs `<% %>` inside each message (system prompt included as
     * index 0 when present), injects `[GENERATE:idx:*]` around message idx and `[GENERATE:BEFORE|AFTER]`
     * at the start of the first / end of the last message.
     */
    fun generate(system: String, messages: List<ChatMessage>, ctx: RpTplContext): Pair<String, List<ChatMessage>> {
        val book = ctx.worldBook
        val all = buildList {
            if (system.isNotBlank()) add(ChatMessage(role = "system", content = system))
            addAll(messages)
        }
        val before = evaluate(specials(book, Kind.GenerateBefore), ctx, escape = false)
        val processed = all.mapIndexed { idx, message ->
            val host = RpTplHost(ctx)
            val body = RpTemplate.render(message.content, host) ?: message.content
            val pre = evaluate(specials(book, Kind.GenerateBefore, idx), ctx, false)
            val post = evaluate(specials(book, Kind.GenerateAfter, idx), ctx, false)
            message.copy(content = listOf(pre, body, post).filter { it.isNotEmpty() }.joinToString("\n"))
        }.toMutableList()
        val after = evaluate(specials(book, Kind.GenerateAfter), ctx, escape = false)
        if (before.isNotBlank() && processed.isNotEmpty()) processed[0] = processed[0].let { it.copy(content = before + "\n" + it.content) }
        if (after.isNotBlank() && processed.isNotEmpty()) processed[processed.lastIndex] = processed.last().let { it.copy(content = it.content + "\n" + after) }
        return if (system.isNotBlank()) processed.first().content to processed.drop(1) else "" to processed
    }

    /** Render pass for one displayed message: its `<% %>` plus `[RENDER:BEFORE|AFTER]` entries. */
    fun render(content: String, ctx: RpTplContext): String {
        val book = ctx.worldBook
        val before = evaluate(specials(book, Kind.RenderBefore), ctx, escape = true)
        val after = evaluate(specials(book, Kind.RenderAfter), ctx, escape = true)
        if (before.isEmpty() && after.isEmpty() && !RpTemplate.hasTags(content)) return content
        val unescaped = content.replace("&lt;%", "<%").replace("%&gt;", "%>")
        val body = RpTemplate.render(unescaped, RpTplHost(ctx), escapeHtml = true) ?: content
        return listOf(before, body, after).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** `[InitialVariables]` entries merged into one object (JSON first, then YAML), or null. */
    fun initialVariables(book: StoryWorldBook, macros: (String) -> String): JSONObject? {
        val list = specials(book, Kind.InitialVariables)
        if (list.isEmpty()) return null
        val out = JSONObject()
        list.forEach { special ->
            val text = macros(special.decorated.body).trim()
            val parsed = runCatching { JSONObject(text) }.getOrNull() ?: RpYaml.parseObject(text) ?: return@forEach
            deepMerge(out, parsed)
        }
        return out.takeIf { it.length() > 0 }
    }

    private fun deepMerge(dst: JSONObject, src: JSONObject) {
        src.keys().forEach { k ->
            val a = dst.opt(k); val b = src.opt(k)
            if (a is JSONObject && b is JSONObject) deepMerge(a, b) else dst.put(k, b)
        }
    }
}
