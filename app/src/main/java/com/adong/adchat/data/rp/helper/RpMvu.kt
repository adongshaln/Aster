package com.adong.adchat.data.rp.helper

import com.adong.adchat.data.rp.RpVarPath
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Result of MVU processing for one assistant reply. [variables] null = no change. */
data class RpMvuResult(val variables: JSONObject?, val displayContent: String, val commands: Int)

/** One parsed MVU update command. [type] is canonical: set / insert / delete / add / move. */
data class RpMvuCommand(val type: String, val args: List<String>, val reason: String, val index: Int, val fromPatch: Boolean = false)

/**
 * Native MagVarUpdate core (MVU `updateVariables` / `initCheck`), pure Kotlin.
 *
 * A floor object is `{stat_data, schema, display_data, delta_data, initialized_lorebooks}`; stat_data
 * holds the real values. Schema is generated for MVU-compatibility (`isMvuData`) but not enforced:
 * the AI is trusted to extend objects and arrays.
 */
object RpMvu {
    const val PLACEHOLDER = "<StatusPlaceHolderImpl/>"

    private val COMMAND = Regex("""_\.(set|insert|assign|remove|unset|delete|add|move)\(""")
    private val THINK = Regex("""<(think|thinking|reasoning|analysis|analyze)\b[^>]*>[\s\S]*?(</\1>|$)""", RegexOption.IGNORE_CASE)
    private val PATCH = Regex("""<(json_?patch)>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
    private val STATUS_VAR = Regex("""<(status_current_variable)>[\s\S]*?</\1?>""", RegexOption.IGNORE_CASE)
    private val INITVAR_BLOCK = Regex("""<(initvar)>(?:\s*```.*)?([\s\S]*?)(?:```\s*)?</\1>""", RegexOption.IGNORE_CASE)

    /** True when [floor] looks like MVU data (has both stat_data and schema). */
    fun isMvuData(floor: JSONObject?): Boolean = floor != null && floor.has("stat_data") && floor.has("schema")

    /**
     * Runs the update commands in an assistant reply against [baseline] (the previous valid floor).
     * Returns the new floor object (null when the reply has no commands) and the display text.
     */
    fun process(content: String, baseline: JSONObject?): RpMvuResult {
        val commands = extractCommands(content)
        val hadStat = baseline?.optJSONObject("stat_data") != null
        var floor: JSONObject? = null
        if (commands.isNotEmpty()) {
            floor = baseline?.let { JSONObject(it.toString()) } ?: JSONObject()
            update(floor, commands)
        }
        var display = STATUS_VAR.replace(content, "")
        if ((hadStat || commands.isNotEmpty()) && !display.contains(PLACEHOLDER)) display += "\n\n$PLACEHOLDER"
        return RpMvuResult(floor, display, commands.size)
    }

    /** Applies [content]'s commands to [floor] in place (Mvu.parseMessage semantics). Returns true if stat_data changed. */
    fun applyTo(floor: JSONObject, content: String): Boolean = update(floor, extractCommands(content))

    /**
     * Builds the initial floor from `[initvar]` world-info entry contents and the greeting: entries are
     * unwrapped, macro-expanded, parsed (YAML/JSON) and deep-merged; an `<initvar>` block in the greeting
     * replaces stat_data; `_.set` commands in the greeting are applied last. Empty object when nothing applies.
     */
    fun initial(initvarEntries: List<String>, greeting: String, macros: (String) -> String): JSONObject {
        var stat = JSONObject()
        var any = false
        for (entry in initvarEntries) {
            val parsed = parseInitvar(macros(unwrapInitvar(entry))) ?: continue
            stat = correctlyMerge(stat, parsed)
            any = true
        }
        INITVAR_BLOCK.findAll(greeting).lastOrNull()?.let { m ->
            parseInitvar(macros(unwrapInitvar(m.groupValues[2])))?.let { stat = it; any = true }
        }
        val floor = JSONObject()
        if (any) {
            floor.put("stat_data", stat)
            floor.put("schema", schemaOf(stat))
            floor.put("initialized_lorebooks", JSONObject())
        }
        val greetingCommands = extractCommands(macros(greeting))
        if (greetingCommands.isNotEmpty()) update(floor, greetingCommands)
        if (floor.has("stat_data")) cleanUpMetadata(floor.get("stat_data"))
        return floor
    }

    /** Strips `<initvar>` tags and a surrounding code fence. */
    fun unwrapInitvar(text: String): String {
        var s = text.trim()
        Regex("""^<initvar>([\s\S]*?)</initvar>$""", RegexOption.IGNORE_CASE).find(s)?.let { s = it.groupValues[1].trim() }
        Regex("""^```[^\n]*\n([\s\S]*?)\n?```$""").find(s)?.let { s = it.groupValues[1] }
        return s
    }

    private fun parseInitvar(text: String): JSONObject? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("{")) runCatching { return JSONObject(t) }
        return RpYaml.parseObject(t)
    }

    /* ---------------- extraction ---------------- */

    /** Extracts JSONPatch and `_.xxx(...);` commands, ordered by source position. */
    fun extractCommands(text: String): List<RpMvuCommand> {
        val masked = StringBuilder(text)
        fun mask(range: IntRange) { for (k in range) if (masked[k] != '\n') masked.setCharAt(k, ' ') }
        val results = mutableListOf<RpMvuCommand>()
        PATCH.findAll(text).forEach { m ->
            mask(m.range)
            results += patchCommands(m.groupValues[2], m.range.first)
        }
        THINK.findAll(masked.toString()).forEach { mask(it.range) }
        val scan = masked.toString()
        var i = 0
        while (i < text.length) {
            val m = COMMAND.find(scan, i) ?: break
            val type = m.groupValues[1]
            val open = m.range.last + 1
            val close = matchingParen(text, open)
            if (close == -1) { i = open; continue }
            var end = close + 1
            if (end >= text.length || text[end] != ';') { i = close + 1; continue }
            end++
            var reason = ""
            Regex("""^[ \t]*//(.*)""").find(text.substring(end))?.let { reason = it.groupValues[1].trim(); end += it.value.length }
            val params = splitParams(text.substring(open, close))
            val valid = when (type) {
                "set", "insert", "assign", "move" -> params.size >= 2
                "remove", "unset", "delete" -> params.isNotEmpty()
                "add" -> params.size == 2
                else -> false
            }
            if (valid) {
                val canonical = when (type) { "assign" -> "insert"; "remove", "unset" -> "delete"; else -> type }
                results += RpMvuCommand(canonical, params, reason, m.range.first)
            }
            i = end
        }
        return results.sortedBy { it.index }
    }

    private fun patchCommands(raw: String, index: Int): List<RpMvuCommand> {
        val body = raw.trim().let { s -> Regex("""^```[^\n]*\n([\s\S]*?)\n?```$""").find(s)?.groupValues?.get(1) ?: s }
        val parsed = runCatching { JSONArray(body) }.getOrNull() ?: (RpYaml.parse(body) as? JSONArray) ?: return emptyList()
        val out = mutableListOf<RpMvuCommand>()
        for (k in 0 until parsed.length()) {
            val op = parsed.optJSONObject(k) ?: continue
            val path = pointerToPath(op.optString("path"))
            val value = op.opt("value")
            fun lit(v: Any?) = when (v) { null -> "null"; is String -> JSONObject.quote(v); else -> v.toString() }
            when (op.optString("op")) {
                "replace" -> out += RpMvuCommand("set", listOf(path, lit(value)), "json_patch", index, true)
                "delta" -> out += RpMvuCommand("add", listOf(path, lit(value)), "json_patch", index, true)
                "add", "insert" -> {
                    val parts = RpVarPath.parts(path)
                    val last = parts.lastOrNull() ?: continue
                    val key = if (last.all { it.isDigit() }) last else JSONObject.quote(last)
                    out += RpMvuCommand("insert", listOf(parts.dropLast(1).joinToString("."), key, lit(value)), "json_patch", index, true)
                }
                "remove" -> out += RpMvuCommand("delete", listOf(path), "json_patch", index, true)
                "move" -> out += RpMvuCommand("move", listOf(pointerToPath(op.optString("from")), path), "json_patch", index, true)
            }
        }
        return out
    }

    private fun pointerToPath(pointer: String): String =
        pointer.trim().removePrefix("/").split('/').filter { it.isNotEmpty() }
            .joinToString(".") { it.replace("~1", "/").replace("~0", "~") }

    private fun matchingParen(s: String, start: Int): Int {
        var depth = 1
        var quote = 0.toChar()
        for (k in start until s.length) {
            val c = s[k]
            val prev = if (k > 0) s[k - 1] else ' '
            if ((c == '"' || c == '\'' || c == '`') && prev != '\\') {
                if (quote == 0.toChar()) quote = c else if (c == quote) quote = 0.toChar()
            }
            if (quote == 0.toChar()) {
                if (c == '(') depth++ else if (c == ')') { depth--; if (depth == 0) return k }
            }
        }
        return -1
    }

    /** Splits command arguments on top-level commas (quotes, brackets and parens respected). */
    fun splitParams(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote = 0.toChar()
        var depth = 0
        for (k in s.indices) {
            val c = s[k]
            if ((c == '"' || c == '\'' || c == '`') && (k == 0 || s[k - 1] != '\\')) {
                if (quote == 0.toChar()) quote = c else if (c == quote) quote = 0.toChar()
            }
            if (quote == 0.toChar()) {
                when (c) {
                    '[', '{', '(' -> depth++
                    ']', '}', ')' -> depth--
                    ',' -> if (depth == 0) { out += cur.toString().trim(); cur.clear(); continue }
                }
            }
            cur.append(c)
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    /* ---------------- values ---------------- */

    /** MVU `parseCommandValue`: literals, JSON, relaxed JSON/YAML, arithmetic, else a trimmed string. */
    fun parseValue(raw: String): Any? {
        val t = raw.trim()
        when (t) {
            "true" -> return true
            "false" -> return false
            "null" -> return JSONObject.NULL
            "undefined" -> return null
        }
        if (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) RpYaml.parseFlow(t)?.let { return it }
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            runCatching { return if (t.startsWith("{")) JSONObject(t) else JSONArray(t) }
            RpYaml.parseFlow(t)?.let { if (it is JSONObject || it is JSONArray) return it }
        }
        if (t.length >= 2 && ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith("`") && t.endsWith("`")))) {
            if (t[0] == '`') return t.substring(1, t.length - 1)
            RpYaml.parseFlow(t)?.let { return it }
        }
        Arith.eval(t)?.let { return number(it) }
        val y = RpYaml.scalar(t)
        if (y !is String) return y
        return trimQuotes(t)
    }

    fun trimQuotes(s: String): String = Regex("""^[\\"'` ]*(.*?)[\\"'` ]*$""", RegexOption.DOT_MATCHES_ALL).replace(s, "$1")

    private fun number(d: Double): Number {
        val r = String.format(java.util.Locale.ROOT, "%.12g", d).toDouble()
        return if (r == Math.rint(r) && kotlin.math.abs(r) < 9e15) {
            val l = r.toLong(); if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
        } else r
    }

    private fun toNumber(v: Any?): Number? = when (v) {
        is Number -> v
        is String -> v.trim().toDoubleOrNull()?.let { number(it) }
        is Boolean -> if (v) 1 else 0
        else -> null
    }

    private fun isVwd(v: Any?): Boolean =
        v is JSONArray && v.length() == 2 && v.opt(1) is String && v.opt(0) !is JSONArray

    private fun stringify(v: Any?): String = trimQuotes(
        when (v) {
            null -> "undefined"
            is String -> JSONObject.quote(v)
            else -> v.toString()
        }
    )

    /* ---------------- execution ---------------- */

    private fun update(floor: JSONObject, commands: List<RpMvuCommand>): Boolean {
        val stat = floor.optJSONObject("stat_data") ?: JSONObject().also { floor.put("stat_data", it) }
        val before = stat.toString()
        val display = JSONObject(stat.toString())
        val delta = JSONObject()
        var root = stat
        for (cmd in commands) {
            val path = if (cmd.fromPatch) cmd.args[0] else fixPath(trimQuotes(cmd.args[0]))
            val reasonStr = if (cmd.reason.isNotEmpty()) "(${cmd.reason})" else ""
            val shown: String? = runCatching { execute(root, cmd, path, reasonStr) { root = it } }.getOrNull()
            if (shown != null && path.isNotEmpty()) {
                RpVarPath.set(display, path, shown)
                RpVarPath.set(delta, path, shown)
            }
        }
        floor.put("stat_data", root)
        floor.put("display_data", display)
        floor.put("delta_data", delta)
        val changed = root.toString() != before
        if (changed || !floor.has("schema")) floor.put("schema", schemaOf(root))
        if (!floor.has("initialized_lorebooks")) floor.put("initialized_lorebooks", JSONObject())
        return changed
    }

    private fun has(root: JSONObject, path: String): Boolean {
        val parts = RpVarPath.parts(path)
        if (parts.isEmpty()) return false
        val parent = if (parts.size == 1) root else RpVarPath.get(root, parts.dropLast(1).joinToString("."))
        return when (parent) {
            is JSONObject -> parent.has(parts.last())
            is JSONArray -> parts.last().toIntOrNull()?.let { it in 0 until parent.length() } ?: false
            else -> false
        }
    }

    private fun parentOf(root: JSONObject, path: String): Any? {
        val parts = RpVarPath.parts(path)
        return if (parts.size <= 1) root else RpVarPath.get(root, parts.dropLast(1).joinToString("."))
    }

    /** Returns the display string for a successful command, or null when skipped. */
    private fun execute(root: JSONObject, cmd: RpMvuCommand, path: String, reason: String, replaceRoot: (JSONObject) -> Unit): String? {
        val args = cmd.args
        when (cmd.type) {
            "set" -> {
                val newValue = parseValue(args.last())
                if (path.isEmpty()) {
                    (newValue as? JSONObject)?.let(replaceRoot) ?: return null
                    return "->"
                }
                if (!has(root, path)) return null
                val old = RpVarPath.get(root, path)
                if (isVwd(old)) {
                    old as JSONArray
                    val prev = old.opt(0)
                    old.put(0, if (prev is Number && newValue != JSONObject.NULL && newValue != null) toNumber(newValue) ?: newValue else newValue ?: JSONObject.NULL)
                    return "${stringify(prev)}->${stringify(old.opt(0))} $reason".trim()
                }
                val stored = if (old is Number && newValue is String) toNumber(newValue) ?: newValue else newValue
                RpVarPath.set(root, path, stored)
                return "${stringify(old)}->${stringify(stored)} $reason".trim()
            }
            "add" -> {
                if (!has(root, path)) return null
                val old = RpVarPath.get(root, path)
                val vwd = isVwd(old) && (old as JSONArray).opt(0) !is JSONObject
                val current = if (vwd) (old as JSONArray).opt(0) else old
                val deltaValue = parseValue(args[1])
                val next: Any = when {
                    current is String && current.toDoubleOrNull() == null && parseDate(current) != null -> {
                        val ms = (deltaValue as? Number)?.toLong() ?: return null
                        ISO_MILLIS.format(parseDate(current)!!.plusMillis(ms))
                    }
                    current is Number -> number(current.toDouble() + ((deltaValue as? Number)?.toDouble() ?: return null))
                    current is Boolean -> !current
                    else -> return null
                }
                if (vwd) (old as JSONArray).put(0, next) else RpVarPath.set(root, path, next)
                return "${stringify(current)}->${stringify(next)} $reason".trim()
            }
            "insert" -> {
                val existing = if (path.isEmpty()) root else RpVarPath.get(root, path)
                if (existing != null && existing != JSONObject.NULL && existing !is JSONObject && existing !is JSONArray) return null
                if (args.size == 2) {
                    val value = parseValue(args[1]) ?: return null
                    var collection = existing
                    if (collection !is JSONObject && collection !is JSONArray) {
                        if (path.isNotEmpty() && parentOf(root, path).let { it !is JSONObject && it !is JSONArray }) return null
                        collection = if (value is JSONArray) JSONArray() else JSONObject()
                        RpVarPath.set(root, path, collection)
                    }
                    when (collection) {
                        is JSONArray -> collection.put(value)
                        is JSONObject -> if (value is JSONObject) deepMerge(collection, value) else return null
                    }
                } else {
                    val key = parseValue(args[1])
                    val value = parseValue(args[2]) ?: JSONObject.NULL
                    var collection = existing
                    if (collection is JSONArray && (key is Number || key == "-")) {
                        val idx = if (key == "-") collection.length() else (key as Number).toInt().let { if (it < 0) collection.length() else it }
                        arrayInsert(collection, idx.coerceIn(0, collection.length()), value)
                    } else {
                        if (collection !is JSONObject) {
                            if (path.isNotEmpty() && collection == null && parentOf(root, path).let { it !is JSONObject && it !is JSONArray }) return null
                            collection = JSONObject().also { RpVarPath.set(root, path, it) }
                        }
                        val k = if (key is String) trimQuotes(key) else key.toString()
                        collection.put(k, value)
                    }
                }
                return "INSERTED $reason".trim()
            }
            "delete" -> {
                val parts = RpVarPath.parts(path)
                if (args.size == 1) {
                    if (!has(root, path)) return null
                    RpVarPath.unset(root, path)
                    return "REMOVED $reason".trim()
                }
                val collection = if (parts.isEmpty()) root else RpVarPath.get(root, path)
                val target = parseValue(args[1])
                when (collection) {
                    is JSONArray -> {
                        val idx = if (target is Number) target.toInt() else
                            (0 until collection.length()).firstOrNull { same(collection.opt(it), target) } ?: -1
                        if (idx !in 0 until collection.length()) return null
                        collection.remove(idx)
                    }
                    is JSONObject -> {
                        val key = if (target is Number) collection.keys().asSequence().toList().getOrNull(target.toInt()) else
                            (target as? String)?.let(::trimQuotes) ?: target?.toString()
                        if (key == null || !collection.has(key)) return null
                        collection.remove(key)
                    }
                    else -> return null
                }
                return "REMOVED $reason".trim()
            }
            "move" -> {
                val to = if (cmd.fromPatch) args[1] else fixPath(trimQuotes(args[1]))
                if (!has(root, path) || to.isEmpty()) return null
                val value = RpVarPath.get(root, path)
                RpVarPath.unset(root, path)
                val toParts = RpVarPath.parts(to)
                val parent = parentOf(root, to)
                if (parent is JSONArray && (toParts.last() == "-" || toParts.last().toIntOrNull() != null)) {
                    val idx = toParts.last().toIntOrNull() ?: parent.length()
                    arrayInsert(parent, idx.coerceIn(0, parent.length()), value)
                } else RpVarPath.set(root, to, value)
                return "MOVED $path->$to $reason".trim()
            }
        }
        return null
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is JSONObject || a is JSONArray -> a.toString() == b?.toString()
        else -> a == b
    }

    private fun arrayInsert(arr: JSONArray, index: Int, value: Any?) {
        val items = (0 until arr.length()).map { arr.opt(it) }.toMutableList()
        items.add(index, value ?: JSONObject.NULL)
        while (arr.length() > 0) arr.remove(arr.length() - 1)
        items.forEach { arr.put(it) }
    }

    private val ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private fun parseDate(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()

    /** MVU `pathFix`: normalizes bracket parts; quoted/non-numeric brackets become dotted keys. */
    fun fixPath(path: String): String = path.replace(Regex("""\[([^\]]*)]""")) { m ->
        val inner = m.groupValues[1].trim().let { if (it.length >= 2 && (it[0] == '"' || it[0] == '\'') && it.last() == it[0]) it.substring(1, it.length - 1) else it }
        if (inner.isEmpty()) "[]" else ".$inner"
    }.removePrefix(".")

    /** lodash mergeWith that replaces arrays (MVU `correctlyMerge`). Mutates and returns [target]. */
    fun correctlyMerge(target: JSONObject, source: JSONObject): JSONObject = deepMerge(target, source)

    private fun deepMerge(target: JSONObject, source: JSONObject): JSONObject {
        for (key in source.keys()) {
            val s = source.opt(key)
            val t = target.opt(key)
            if (s is JSONObject && t is JSONObject) deepMerge(t, s)
            else target.put(key, if (s is JSONObject) JSONObject(s.toString()) else if (s is JSONArray) JSONArray(s.toString()) else s)
        }
        return target
    }

    /** Minimal MVU schema: types, extensible flags from `$meta` / `$__META_EXTENSIBLE__$`. */
    fun schemaOf(data: Any?, parentRecursive: Boolean = false): JSONObject = when (data) {
        is JSONArray -> {
            val ext = (0 until data.length()).any { data.opt(it) == "\$__META_EXTENSIBLE__\$" }
            JSONObject().put("type", "array").put("extensible", ext || parentRecursive)
                .put("elementType", if (data.length() > 0) schemaOf(data.opt(0), parentRecursive) else JSONObject().put("type", "any"))
        }
        is JSONObject -> {
            val meta = data.optJSONObject("\$meta")
            val recursive = parentRecursive || meta?.optBoolean("recursiveExtensible") == true
            val ext = recursive || meta?.optBoolean("extensible") == true
            val props = JSONObject()
            for (key in data.keys()) {
                if (key == "\$meta") continue
                props.put(key, schemaOf(data.opt(key), recursive).put("required", !ext))
            }
            JSONObject().put("type", "object").put("properties", props).put("extensible", ext).put("recursiveExtensible", recursive)
                .also { s -> meta?.opt("template")?.let { s.put("template", it) } }
        }
        is String -> JSONObject().put("type", "string")
        is Number -> JSONObject().put("type", "number")
        is Boolean -> JSONObject().put("type", "boolean")
        else -> JSONObject().put("type", "any")
    }

    private fun cleanUpMetadata(data: Any?) {
        when (data) {
            is JSONArray -> {
                var k = data.length()
                while (k-- > 0) {
                    val item = data.opt(k)
                    if (item == "\$__META_EXTENSIBLE__\$" || (item is JSONObject && item.optBoolean("\$arrayMeta"))) data.remove(k)
                    else cleanUpMetadata(item)
                }
            }
            is JSONObject -> {
                data.remove("\$meta")
                data.keys().asSequence().toList().forEach { cleanUpMetadata(data.opt(it)) }
            }
        }
    }

    /** Tiny arithmetic evaluator: + - * / % ^, parentheses, unary minus, Math.xxx/math.xxx basics. */
    internal object Arith {
        fun eval(s: String): Double? {
            if (s.isBlank() || !Regex("""[\d\s+\-*/%^().,a-zA-Z_]+""").matches(s) || !s.any { it.isDigit() } ||
                Regex("""^\d{4}-\d{1,2}-\d{1,2}""").containsMatchIn(s.trim())) return null
            return runCatching {
                val p = P(s.replace("Math.", "").replace("math.", ""))
                val v = p.expr(); p.ws()
                if (p.i != p.s.length || v.isNaN() || v.isInfinite()) null else v
            }.getOrNull()
        }

        private class P(val s: String) {
            var i = 0
            fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
            fun expr(): Double {
                var v = term()
                while (true) { ws(); if (i >= s.length) return v
                    when (s[i]) { '+' -> { i++; v += term() }; '-' -> { i++; v -= term() }; else -> return v } }
            }
            fun term(): Double {
                var v = power()
                while (true) { ws(); if (i >= s.length) return v
                    when (s[i]) { '*' -> { i++; v *= power() }; '/' -> { i++; v /= power() }; '%' -> { i++; v %= power() }; else -> return v } }
            }
            fun power(): Double {
                val b = unary(); ws()
                if (i < s.length && s[i] == '^') { i++; return Math.pow(b, power()) }
                return b
            }
            fun unary(): Double { ws()
                if (i < s.length && s[i] == '-') { i++; return -unary() }
                if (i < s.length && s[i] == '+') { i++; return unary() }
                return atom()
            }
            fun atom(): Double { ws()
                if (s[i] == '(') { i++; val v = expr(); ws(); require(s[i] == ')'); i++; return v }
                if (s[i].isLetter()) {
                    val st = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    val name = s.substring(st, i); ws()
                    when (name) { "PI", "pi" -> return Math.PI; "E", "e" -> return Math.E }
                    require(i < s.length && s[i] == '('); i++
                    val args = mutableListOf(expr()); ws()
                    while (s[i] == ',') { i++; args += expr(); ws() }
                    require(s[i] == ')'); i++
                    return when (name) {
                        "abs" -> Math.abs(args[0]); "floor" -> Math.floor(args[0]); "ceil" -> Math.ceil(args[0])
                        "round" -> Math.round(args[0]).toDouble(); "sqrt" -> Math.sqrt(args[0])
                        "min" -> args.min(); "max" -> args.max(); "pow" -> Math.pow(args[0], args[1])
                        else -> error("fn")
                    }
                }
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] in "+-")) {
                    i += 2; while (i < s.length && s[i].isDigit()) i++
                }
                return s.substring(st, i).toDouble()
            }
        }
    }
}
