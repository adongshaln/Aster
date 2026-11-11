package com.adong.adchat.data.rp.helper

import org.json.JSONArray
import org.json.JSONObject

/**
 * Small YAML subset parser for MVU initvar files and command values: block maps and lists by
 * indentation, flow collections (`[a, b]`, `{a: 1}`, plain JSON / JSON5-ish), block scalars `|` / `>`
 * (with `-` / `+` chomping), quoted strings with escapes, comments and plain scalars.
 *
 * Results use org.json types: [JSONObject], [JSONArray], [String], [Int]/[Long]/[Double], [Boolean]
 * and [JSONObject.NULL]. Malformed input degrades to strings instead of throwing where possible.
 */
object RpYaml {
    /** Parses [text]; returns null for empty documents or when parsing fails. */
    fun parse(text: String): Any? = runCatching { BlockParser(text).document() }.getOrNull()

    /** Parses [text] and returns it only if the document is a mapping. */
    fun parseObject(text: String): JSONObject? = parse(text) as? JSONObject

    /** Parses one inline (flow) value such as `[1, 2]`, `{a: b}`, `"x"` or `12`. */
    fun parseFlow(text: String): Any? = runCatching {
        val parser = FlowParser(text.trim())
        val value = parser.value(FlowContext.Top)
        parser.skipWs()
        if (parser.pos < parser.s.length) null else value
    }.getOrNull()

    /** Plain scalar resolution (YAML 1.2 core schema subset). */
    fun scalar(raw: String): Any {
        val s = raw.trim()
        return when {
            s.isEmpty() || s == "~" || s == "null" || s == "Null" || s == "NULL" -> JSONObject.NULL
            s == "true" || s == "True" || s == "TRUE" -> true
            s == "false" || s == "False" || s == "FALSE" -> false
            INT.matches(s) -> s.toIntOrNull() ?: s.toLongOrNull() ?: s.toDouble()
            FLOAT.matches(s) -> s.toDouble()
            else -> s
        }
    }

    private val INT = Regex("""[-+]?\d+""")
    private val FLOAT = Regex("""[-+]?(\d+\.\d*|\.\d+|\d+)([eE][-+]?\d+)?""")

    internal fun stripComment(line: String): String {
        var quote = 0.toChar()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quote != 0.toChar()) {
                if (c == '\\' && quote == '"') i++ else if (c == quote) quote = 0.toChar()
            } else if (c == '"' || c == '\'') {
                if (i == 0 || line[i - 1].isWhitespace() || line[i - 1] in "[{,:-") quote = c
            } else if (c == '#' && (i == 0 || line[i - 1].isWhitespace())) {
                return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    private fun bracketBalance(s: String): Int {
        var depth = 0
        var quote = 0.toChar()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != 0.toChar()) {
                if (c == '\\' && quote == '"') i++ else if (c == quote) quote = 0.toChar()
            } else when (c) {
                '"', '\'' -> quote = c
                '[', '{' -> depth++
                ']', '}' -> depth--
            }
            i++
        }
        return depth
    }

    private class BlockParser(text: String) {
        val lines: MutableList<String> = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "  ")
            .split('\n').toMutableList()
        var i = 0

        fun isSkippable(line: String): Boolean {
            val t = line.trim()
            return t.isEmpty() || t.startsWith("#") || t == "---" || t == "..." || t.startsWith("%YAML")
        }

        fun skipBlank() { while (i < lines.size && isSkippable(lines[i])) i++ }
        fun indentOf(line: String) = line.length - line.trimStart(' ').length
        fun content(line: String) = stripComment(line).trim()

        fun document(): Any? {
            skipBlank()
            if (i >= lines.size) return null
            return block(0)
        }

        fun block(minIndent: Int): Any? {
            skipBlank()
            if (i >= lines.size) return null
            val ind = indentOf(lines[i])
            if (ind < minIndent) return null
            val t = content(lines[i])
            return when {
                isListItem(t) -> list(ind)
                t.startsWith("[") || t.startsWith("{") -> flowFrom(t, lines.size)
                mapKey(t) != null -> map(ind)
                else -> plainMultiline(t, ind - 1)
            }
        }

        fun isListItem(t: String) = t == "-" || t.startsWith("- ")

        /** Returns (key, rest) when [t] is a `key: value` line. */
        fun mapKey(t: String): Pair<String, String>? {
            if (t.isEmpty() || t[0] in "[{#" || isListItem(t)) return null
            if (t[0] == '"' || t[0] == '\'') {
                val parser = FlowParser(t)
                val key = runCatching { parser.quoted() }.getOrNull() ?: return null
                parser.skipWs()
                if (parser.pos >= t.length || t[parser.pos] != ':') return null
                return key to t.substring(parser.pos + 1).trim()
            }
            var j = 0
            while (j < t.length) {
                if (t[j] == ':' && (j + 1 == t.length || t[j + 1] == ' ')) {
                    val key = t.substring(0, j).trim()
                    return if (key.isEmpty()) null else key to t.substring(j + 1).trim()
                }
                j++
            }
            return null
        }

        fun map(ind: Int): JSONObject {
            val obj = JSONObject()
            while (true) {
                skipBlank()
                if (i >= lines.size || indentOf(lines[i]) != ind) break
                val t = content(lines[i])
                val (key, rest) = mapKey(t) ?: break
                if (key == "<<") { i++; continue }
                obj.put(key, value(rest, ind, allowSiblingList = true) ?: JSONObject.NULL)
            }
            return obj
        }

        fun list(ind: Int): JSONArray {
            val arr = JSONArray()
            while (true) {
                skipBlank()
                if (i >= lines.size || indentOf(lines[i]) != ind) break
                val t = content(lines[i])
                if (!isListItem(t)) break
                val rest = t.removePrefix("-").trimStart()
                val restIndent = ind + (t.length - t.removePrefix("-").length) + (t.removePrefix("-").length - rest.length)
                when {
                    rest.isEmpty() -> { i++; arr.put(block(ind + 1) ?: JSONObject.NULL) }
                    isListItem(rest) || (mapKey(rest) != null && !rest.startsWith("[") && !rest.startsWith("{")) -> {
                        lines[i] = " ".repeat(restIndent) + rest
                        arr.put(block(restIndent) ?: JSONObject.NULL)
                    }
                    else -> arr.put(value(rest, ind, allowSiblingList = false) ?: JSONObject.NULL)
                }
            }
            return arr
        }

        /** Value after `key:` or `- ` on line [i]; consumes the line and any nested lines. */
        fun value(rest: String, ind: Int, allowSiblingList: Boolean): Any? {
            if (rest.isEmpty()) {
                i++
                skipBlank()
                if (i >= lines.size) return null
                val next = indentOf(lines[i])
                return when {
                    next > ind -> block(ind + 1)
                    allowSiblingList && next == ind && isListItem(content(lines[i])) -> list(ind)
                    else -> null
                }
            }
            if (rest[0] == '|' || rest[0] == '>') {
                if (Regex("""[|>][-+]?\d?""").matches(rest)) return blockScalar(rest, ind)
            }
            if (rest[0] == '[' || rest[0] == '{') return flowFrom(rest, ind)
            if (rest[0] == '"' || rest[0] == '\'') {
                val parsed = parseFlow(rest)
                if (parsed != null) { i++; return parsed }
                return multilineQuoted(rest)
            }
            if (rest.startsWith("&") || rest.startsWith("!")) {
                val stripped = rest.substringAfter(' ', "")
                return value(stripped, ind, allowSiblingList)
            }
            return plainMultiline(rest, ind)
        }

        fun plainMultiline(first: String, ind: Int): Any {
            i++
            val parts = mutableListOf(first)
            while (i < lines.size && !isSkippable(lines[i]) && indentOf(lines[i]) > ind) {
                val t = content(lines[i])
                if (mapKey(t) != null || isListItem(t)) break
                parts += t
                i++
            }
            return if (parts.size == 1) scalar(first) else parts.joinToString(" ")
        }

        fun multilineQuoted(first: String): Any {
            val q = first[0]
            val sb = StringBuilder(first)
            i++
            while (i < lines.size) {
                sb.append(' ').append(lines[i].trim())
                i++
                parseFlow(sb.toString())?.let { return it }
            }
            return first.trim(q)
        }

        fun flowFrom(first: String, ind: Int): Any? {
            val sb = StringBuilder(first)
            i++
            while (bracketBalance(sb.toString()) > 0 && i < lines.size) {
                sb.append('\n').append(stripComment(lines[i]))
                i++
            }
            return parseFlow(sb.toString()) ?: sb.toString()
        }

        fun blockScalar(header: String, ind: Int): String {
            val folded = header[0] == '>'
            val chomp = header.drop(1).firstOrNull { it == '-' || it == '+' }
            val explicit = header.firstOrNull { it.isDigit() }?.digitToInt()
            i++
            val raw = mutableListOf<String>()
            var blockIndent = explicit?.let { ind + it } ?: -1
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) { raw += ""; i++; continue }
                val li = indentOf(line)
                if (blockIndent < 0) { if (li <= ind) break; blockIndent = li }
                if (li < blockIndent) break
                raw += line.substring(blockIndent)
                i++
            }
            val trailing = raw.reversed().takeWhile { it.isEmpty() }.size
            val body = raw.dropLast(trailing)
            val text = if (!folded) body.joinToString("\n") else buildString {
                body.forEachIndexed { idx, l ->
                    if (idx > 0) {
                        val prev = body[idx - 1]
                        append(
                            when {
                                l.isEmpty() -> "\n"
                                prev.isEmpty() || l.startsWith(" ") || prev.startsWith(" ") -> if (prev.isEmpty()) "" else "\n"
                                else -> " "
                            }
                        )
                    }
                    append(l)
                }
            }
            return when (chomp) {
                '-' -> text
                '+' -> text + "\n".repeat(trailing + 1)
                else -> if (body.isEmpty()) "" else "$text\n"
            }
        }
    }

    private enum class FlowContext { Top, Seq, MapKey, MapValue }

    private class FlowParser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c.isWhitespace()) pos++
                else if (c == '#' && (pos == 0 || s[pos - 1].isWhitespace())) {
                    while (pos < s.length && s[pos] != '\n') pos++
                } else if (c == '/' && pos + 1 < s.length && s[pos + 1] == '/') {
                    while (pos < s.length && s[pos] != '\n') pos++
                } else break
            }
        }

        fun value(ctx: FlowContext): Any? {
            skipWs()
            if (pos >= s.length) return JSONObject.NULL
            return when (s[pos]) {
                '[' -> seq()
                '{' -> map()
                '"', '\'' -> quoted()
                else -> plain(ctx)
            }
        }

        fun seq(): JSONArray {
            pos++
            val arr = JSONArray()
            while (true) {
                skipWs()
                if (pos >= s.length) error("unterminated [")
                if (s[pos] == ']') { pos++; return arr }
                arr.put(value(FlowContext.Seq) ?: JSONObject.NULL)
                skipWs()
                if (pos < s.length && s[pos] == ',') pos++
                else if (pos < s.length && s[pos] != ']') error("expected ,")
            }
        }

        fun map(): JSONObject {
            pos++
            val obj = JSONObject()
            while (true) {
                skipWs()
                if (pos >= s.length) error("unterminated {")
                if (s[pos] == '}') { pos++; return obj }
                val key = value(FlowContext.MapKey)
                skipWs()
                val v: Any? = if (pos < s.length && s[pos] == ':') { pos++; value(FlowContext.MapValue) } else JSONObject.NULL
                obj.put(if (key == JSONObject.NULL) "null" else key.toString(), v ?: JSONObject.NULL)
                skipWs()
                if (pos < s.length && s[pos] == ',') pos++
                else if (pos < s.length && s[pos] != '}') error("expected ,")
            }
        }

        fun quoted(): String {
            val q = s[pos++]
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                if (q == '\'' && c == '\'') {
                    if (pos < s.length && s[pos] == '\'') { sb.append('\''); pos++; continue }
                    return sb.toString()
                }
                if (q == '"' && c == '"') return sb.toString()
                if (q == '"' && c == '\\' && pos < s.length) {
                    when (val e = s[pos++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); '0' -> sb.append('\u0000')
                        'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                        'x' -> { sb.append(s.substring(pos, pos + 2).toInt(16).toChar()); pos += 2 }
                        '\n' -> Unit
                        else -> sb.append(e)
                    }
                    continue
                }
                sb.append(c)
            }
            error("unterminated string")
        }

        fun plain(ctx: FlowContext): Any {
            val start = pos
            while (pos < s.length) {
                val c = s[pos]
                if (ctx != FlowContext.Top && (c == ',' || c == ']' || c == '}')) break
                if (ctx == FlowContext.MapKey && c == ':') break
                if (c == '\n' && ctx == FlowContext.Top) break
                pos++
            }
            return scalar(s.substring(start, pos))
        }
    }
}
