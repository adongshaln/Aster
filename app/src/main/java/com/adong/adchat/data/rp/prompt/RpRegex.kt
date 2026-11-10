package com.adong.adchat.data.rp.prompt

import com.adong.adchat.data.TavernPreset
import com.adong.adchat.data.TavernRegexScript
import com.adong.adchat.data.rp.RpCardExtensions
import com.adong.adchat.data.story.StoryCharacterCard
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** Display-surface output: the rendered text plus ids of scripts whose pattern matched. */
data class RpRegexDisplay(val text: String, val matchedScriptIds: List<String>)

/**
 * SillyTavern regex extension (getRegexedString / runRegexScript). Three surfaces:
 * - ingest: scripts with neither markdownOnly nor promptOnly; applied once when a message is stored.
 * - prompt: promptOnly scripts, at request assembly only.
 * - display: markdownOnly scripts, at render only.
 * Placements: 1 user input, 2 AI output, 3 slash/narrator, 5 world info, 6 reasoning.
 */
object RpRegex {
    const val USER_INPUT = 1
    const val AI_OUTPUT = 2
    const val SLASH_COMMAND = 3
    const val WORLD_INFO = 5
    const val REASONING = 6

    private enum class Surface { Ingest, Prompt, Display }
    internal data class Compiled(val regex: Regex, val global: Boolean)
    private val cache = ConcurrentHashMap<String, Result<Compiled>>()
    private const val MAX_OUTPUT_CHARS = 2_000_000

    /** Script order: preset scripts, then card-scoped scripts (only when the user allowed them). */
    fun scripts(preset: TavernPreset?, card: StoryCharacterCard?, cardAllowed: Boolean = true): List<TavernRegexScript> =
        preset?.regexScripts.orEmpty() + (if (cardAllowed && card != null) RpCardExtensions.scripts(card) else emptyList())

    /** Placement for a chat role: user → 1, assistant → 2, system/narrator → 3. */
    fun placementFor(role: String): Int = when (role) {
        "user" -> USER_INPUT
        "assistant" -> AI_OUTPUT
        else -> SLASH_COMMAND
    }

    fun ingest(text: String, role: String, scripts: List<TavernRegexScript>, macros: RpMacroContext, isEdit: Boolean = false): String =
        run(text, placementFor(role), null, scripts, macros, Surface.Ingest, isEdit).first

    /** Prompt surface for chat history; [depth] counts from the newest message (0), -1 for the message being continued. */
    fun prompt(text: String, role: String, depth: Int?, scripts: List<TavernRegexScript>, macros: RpMacroContext): String =
        run(text, placementFor(role), depth, scripts, macros, Surface.Prompt, false).first

    fun display(text: String, role: String, depth: Int?, scripts: List<TavernRegexScript>, macros: RpMacroContext): RpRegexDisplay =
        run(text, placementFor(role), depth, scripts, macros, Surface.Display, false).let { RpRegexDisplay(it.first, it.second) }

    /** World-info entry content (prompt surface); [depth] is the entry depth for at-depth entries, else null. */
    fun worldInfo(text: String, depth: Int?, scripts: List<TavernRegexScript>, macros: RpMacroContext): String =
        run(text, WORLD_INFO, depth, scripts, macros, Surface.Prompt, false).first

    /** Reasoning text on the given surface: ingest when [isPrompt]/[isDisplay] are both false. */
    fun reasoning(text: String, depth: Int?, scripts: List<TavernRegexScript>, macros: RpMacroContext, isPrompt: Boolean = false, isDisplay: Boolean = false): String {
        val surface = when { isPrompt -> Surface.Prompt; isDisplay -> Surface.Display; else -> Surface.Ingest }
        return run(text, REASONING, depth, scripts, macros, surface, false).first
    }

    /** Runs one script unconditionally (/regex name=…). */
    fun runScript(script: TavernRegexScript, text: String, macros: RpMacroContext): String =
        apply(script, text, macros) ?: text

    private fun run(
        text: String,
        placement: Int,
        depth: Int?,
        scripts: List<TavernRegexScript>,
        macros: RpMacroContext,
        surface: Surface,
        isEdit: Boolean
    ): Pair<String, List<String>> {
        if (text.isEmpty()) return text to emptyList()
        var result = text
        val matched = mutableListOf<String>()
        for (raw in scripts) {
            val script = migrate(raw)
            if (script.disabled) continue
            val gate = when (surface) {
                Surface.Display -> script.markdownOnly
                Surface.Prompt -> script.promptOnly
                Surface.Ingest -> !script.markdownOnly && !script.promptOnly
            }
            if (!gate) continue
            if (isEdit && !script.runOnEdit) continue
            if (depth != null) {
                val min = script.minDepth
                val max = script.maxDepth
                if (min != null && min >= -1 && depth < min) continue
                if (max != null && max >= 0 && depth > max) continue
            }
            if (placement !in script.placement) continue
            val replaced = apply(script, result, macros) ?: continue
            matched += script.id
            result = replaced
        }
        return result to matched
    }

    /** ST migrateSettings: placement 0 (markdown) → all placements on both surfaces; 4 (sendAs) → 3. */
    internal fun migrate(script: TavernRegexScript): TavernRegexScript {
        var placement = script.placement
        var markdownOnly = script.markdownOnly
        var promptOnly = script.promptOnly
        if (0 in placement) {
            placement = if (placement.size == 1) setOf(1, 2, 3, 5, 6) else placement - 0
            markdownOnly = true; promptOnly = true
        }
        if (4 in placement) placement = if (placement.size == 1) setOf(3) else placement - 4
        return if (placement == script.placement && markdownOnly == script.markdownOnly && promptOnly == script.promptOnly) script
        else script.copy(placement = placement, markdownOnly = markdownOnly, promptOnly = promptOnly)
    }

    /** Returns the replaced text, or null when the pattern did not match / is invalid. */
    private fun apply(script: TavernRegexScript, input: String, macros: RpMacroContext): String? {
        if (script.findRegex.isEmpty() || input.isEmpty()) return null
        val find = when (script.substituteRegex) {
            1 -> RpMacros.expand(script.findRegex, macros)
            2 -> RpMacros.expand(script.findRegex, macros, null, true, ::sanitizeRegexMacro)
            else -> script.findRegex
        }
        val compiled = cache.getOrPut(find) { runCatching { compile(find) } }.getOrNull() ?: return null
        return runCatching { replace(input, compiled, script, macros) }.getOrNull()
    }

    private fun sanitizeRegexMacro(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                '\u000B' -> append("\\v"); '\u000C' -> append("\\f"); '\u0000' -> append("\\0")
                in ".^$*+?{}[]\\/|()" -> append('\\').append(ch)
                else -> append(ch)
            }
        }
    }

    private fun replace(input: String, compiled: Compiled, script: TavernRegexScript, macros: RpMacroContext): String? {
        val iterator = compiled.regex.findAll(input).iterator()
        if (!iterator.hasNext()) return null
        val template = script.replaceString.replace(Regex("\\{\\{match\\}\\}", RegexOption.IGNORE_CASE), "\\$0")
        val trims = script.trimStrings.map { RpMacros.expand(it, macros) }.filter(String::isNotEmpty)
        val output = StringBuilder()
        var cursor = 0
        while (iterator.hasNext()) {
            val match = iterator.next()
            output.append(input, cursor, match.range.first)
            val withGroups = GROUP_REF.replace(template) { ref ->
                val value = ref.groups[1]?.value?.toIntOrNull()?.let { n -> if (n < match.groups.size) match.groups[n]?.value else null }
                    ?: ref.groups[2]?.value?.let { name -> runCatching { (match.groups as? MatchNamedGroupCollection)?.get(name)?.value }.getOrNull() }
                if (value.isNullOrEmpty()) "" else trims.fold(value) { acc, t -> acc.replace(t, "") }
            }
            output.append(RpMacros.expand(withGroups, macros))
            cursor = match.range.last + 1
            require(output.length <= MAX_OUTPUT_CHARS) { "正则替换结果过大" }
            if (!compiled.global) break
        }
        output.append(input, cursor, input.length)
        return output.toString()
    }

    private val GROUP_REF = Regex("\\$(\\d+)|\\$<([^>]+)>")

    internal fun compile(source: String): Compiled {
        var pattern = source
        var flags = ""
        val m = Regex("^/(.+)/([a-z]*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(source)
        if (m != null) {
            val f = m.groupValues[2]
            val validFlags = f.all { it in "gmixXsuUAJ" } && f.toSet().size == f.length
            if (validFlags) { pattern = m.groupValues[1]; flags = f }
        }
        var javaFlags = 0
        if ('i' in flags) javaFlags = javaFlags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if ('m' in flags) javaFlags = javaFlags or Pattern.MULTILINE
        if ('s' in flags) javaFlags = javaFlags or Pattern.DOTALL
        require(flags.none { it in "xXUAJ" }) { "JS 不支持的正则标志" }
        return Compiled(Pattern.compile(jsToJava(pattern), javaFlags).toRegex(), global = 'g' in flags)
    }

    /** Bridges JS regex syntax Java/ICU rejects: `[^]`, literal braces, `\/`. */
    internal fun jsToJava(pattern: String): String {
        val out = StringBuilder(pattern.length + 8)
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    val next = pattern[i + 1]
                    if (next == '/') out.append('/') else out.append(c).append(next)
                    i += 2; continue
                }
                !inClass && pattern.startsWith("[^]", i) -> { out.append("[\\s\\S]"); i += 3; continue }
                !inClass && c == '[' -> { inClass = true; out.append(c); if (pattern.getOrNull(i + 1) == ']') { out.append("\\]"); i++ } }
                inClass && c == ']' -> { inClass = false; out.append(c) }
                inClass && c == '[' -> out.append("\\[")
                !inClass && c == '{' -> {
                    val close = pattern.indexOf('}', i)
                    val body = if (close > i) pattern.substring(i + 1, close) else ""
                    val quantifier = close > i && out.isNotEmpty() && Regex("\\d+(,\\d*)?").matches(body)
                    if (quantifier) { out.append(pattern, i, close + 1); i = close + 1; continue } else out.append("\\{")
                }
                !inClass && c == '}' -> out.append("\\}")
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
