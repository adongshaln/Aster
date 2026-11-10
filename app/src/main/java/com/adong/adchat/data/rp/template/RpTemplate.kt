package com.adong.adchat.data.rp.template

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native subset of the SillyTavern "Prompt Template" extension (EJS in prompts). A template is split
 * into text and `<% %>` tags, compiled to a small statement tree and run by [RpTplInterpreter] against
 * a host-provided scope. Supported tags: `<% code %>`, `<%= expr %>` (escaped on render, raw in
 * prompts — like the extension), `<%- expr %>` (raw), `<%# comment %>`, `<%%` literal, and the
 * whitespace controls `<%_ _%>` / `-%>`. The script language is a JavaScript subset: let/const/var,
 * if/else, for…of / for…in / C-style for, while, break/continue, functions are host-only, plus the
 * usual expressions (member/index access, calls, arrays/objects, template literals, ternary, ?? || &&,
 * comparisons, arithmetic, typeof, ! and unary -).
 *
 * Unknown syntax fails the whole template: callers keep the original text (never a half-rendered one).
 */
object RpTemplate {
    /** True when [text] contains an EJS tag worth evaluating. */
    fun hasTags(text: String): Boolean = text.contains("<%")

    fun compile(source: String): RpTplProgram = RpTplParser(RpTplLexer.tokenize(splitTags(escapeRanges(source)))).program()

    /**
     * Renders [source] with [scope]. [escapeHtml] applies to `<%=` (render surface); prompts pass false.
     * Returns null on any compile/runtime error so callers can fall back to the raw text.
     */
    fun render(source: String, scope: RpTplScope, escapeHtml: Boolean = false): String? {
        if (!hasTags(source)) return source
        return runCatching {
            val program = cache.getOrPut(source) { compile(source) }
            RpTplInterpreter(scope, escapeHtml).run(program)
        }.getOrElse {
            scope.onError(it)
            null
        }
    }

    private val cache = object : LinkedHashMap<String, RpTplProgram>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RpTplProgram>?) = size > 256
    }

    /** `<#escape-ejs>…<#/escape-ejs>`: tags inside become literal (`<%%` / `%%>`). */
    private fun escapeRanges(source: String): String =
        Regex("<#escape-ejs>([\\s\\S]*?)<#/escape-ejs>").replace(source) {
            it.groupValues[1].replace("<%", "<%%").replace("%>", "%%>")
        }

    /** Text/tag segments; text becomes `__out` appends so the parser only sees code. */
    internal sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Code(val code: String) : Segment
        data class Out(val expr: String, val escape: Boolean) : Segment
    }

    internal fun splitTags(source: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val text = StringBuilder()
        var i = 0
        var trimNextNewline = false
        var slurpNextSpace = false
        fun flushText() {
            if (text.isNotEmpty()) out += Segment.Text(text.toString())
            text.clear()
        }
        while (i < source.length) {
            if (source.startsWith("<%%", i)) { text.append("<%"); i += 3; continue }
            if (source.startsWith("%%>", i)) { text.append("%>"); i += 3; continue }
            if (!source.startsWith("<%", i)) {
                var c = source[i]
                if (trimNextNewline) {
                    trimNextNewline = false
                    if (c == '\r' && source.getOrNull(i + 1) == '\n') { i += 2; continue }
                    if (c == '\n') { i++; continue }
                }
                if (slurpNextSpace && (c == ' ' || c == '\t')) { i++; continue }
                slurpNextSpace = false
                text.append(c)
                i++
                continue
            }
            trimNextNewline = false
            slurpNextSpace = false
            var start = i + 2
            var kind = ' '
            when (source.getOrNull(start)) {
                '=', '-', '#', '_' -> { kind = source[start]; start++ }
            }
            if (kind == '_') {
                // <%_ strips whitespace before the tag on the same line.
                var k = text.length
                while (k > 0 && (text[k - 1] == ' ' || text[k - 1] == '\t')) k--
                text.setLength(k)
            }
            val end = findClose(source, start)
            require(end >= 0) { "未闭合的 <% 标签" }
            var body = source.substring(start, end)
            var closeLen = 2
            when {
                body.endsWith("-") -> { body = body.dropLast(1); trimNextNewline = true }
                body.endsWith("_") -> { body = body.dropLast(1); slurpNextSpace = true; trimNextNewline = true }
            }
            flushText()
            when (kind) {
                '=' -> out += Segment.Out(body, escape = true)
                '-' -> out += Segment.Out(body, escape = false)
                '#' -> Unit
                else -> out += Segment.Code(body)
            }
            i = end + closeLen
        }
        flushText()
        return out
    }

    /** Finds the closing `%>` while skipping string literals inside the tag. */
    private fun findClose(source: String, from: Int): Int {
        var i = from
        var quote: Char? = null
        while (i < source.length - 1) {
            val c = source[i]
            if (quote != null) {
                if (c == '\\') { i += 2; continue }
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'' || c == '`') {
                quote = c
            } else if (c == '%' && source[i + 1] == '>') {
                return i
            }
            i++
        }
        return -1
    }
}

/** Host bindings visible to templates: variables, functions and objects. */
interface RpTplScope {
    /** Global names (`variables`, `getvar`, `user`, …). Null = undefined. */
    fun resolve(name: String): Any?
    fun onError(error: Throwable) {}
}

/** A host function callable from templates. */
fun interface RpTplFunction {
    fun call(args: List<Any?>): Any?
}

/** JS `undefined` (distinct from null). */
object RpTplUndefined { override fun toString() = "undefined" }

/** Value helpers shared by the interpreter and host bindings (JS-ish semantics on org.json values). */
object RpTplValues {
    fun truthy(v: Any?): Boolean = when (v) {
        null, RpTplUndefined, JSONObject.NULL -> false
        is Boolean -> v
        is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
        is String -> v.isNotEmpty()
        else -> true
    }

    fun toNumber(v: Any?): Double = when (v) {
        null, JSONObject.NULL -> 0.0
        RpTplUndefined -> Double.NaN
        is Boolean -> if (v) 1.0 else 0.0
        is Number -> v.toDouble()
        is String -> v.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
        else -> Double.NaN
    }

    fun toStr(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        RpTplUndefined -> "undefined"
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Float -> toStr(v.toDouble())
        is JSONArray -> (0 until v.length()).joinToString(",") { i -> v.opt(i).let { if (it == null || it == JSONObject.NULL) "" else toStr(it) } }
        is JSONObject -> "[object Object]"
        is List<*> -> v.joinToString(",") { if (it == null) "" else toStr(it) }
        else -> v.toString()
    }

    /** Output form for `<%= %>`/`print`: undefined/null print nothing, like EJS. */
    fun output(v: Any?): String = when (v) {
        null, RpTplUndefined, JSONObject.NULL -> ""
        else -> toStr(v)
    }

    fun normalize(v: Any?): Any? = when (v) {
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Float -> v.toDouble()
        else -> v
    }

    fun typeOf(v: Any?): String = when (v) {
        RpTplUndefined -> "undefined"
        null, JSONObject.NULL -> "object"
        is Boolean -> "boolean"
        is Number -> "number"
        is String -> "string"
        is RpTplFunction -> "function"
        else -> "object"
    }

    fun looseEquals(a: Any?, b: Any?): Boolean {
        val x = normalize(a); val y = normalize(b)
        val xn = x == null || x == JSONObject.NULL || x == RpTplUndefined
        val yn = y == null || y == JSONObject.NULL || y == RpTplUndefined
        if (xn || yn) return xn && yn
        if (x is Double || y is Double || x is Boolean || y is Boolean) {
            if (x is String && y is String) return x == y
            return toNumber(x) == toNumber(y)
        }
        return strictEquals(x, y)
    }

    fun strictEquals(a: Any?, b: Any?): Boolean {
        val x = normalize(a); val y = normalize(b)
        if (x === RpTplUndefined || y === RpTplUndefined) return x === y
        val xn = x == null || x == JSONObject.NULL
        val yn = y == null || y == JSONObject.NULL
        if (xn || yn) return xn && yn
        return when {
            x is Double && y is Double -> x == y
            x is String && y is String -> x == y
            x is Boolean && y is Boolean -> x == y
            else -> x === y
        }
    }
}
