package com.adong.adchat.data.rp.helper

import com.adong.adchat.data.rp.RpHelperMessageUpdate
import com.adong.adchat.data.rp.RpSlashEnv
import com.adong.adchat.data.rp.RpVarScope
import org.json.JSONArray
import org.json.JSONObject

/** One parsed /command: name, named args (raw, unexpanded) and the raw unnamed text. */
data class RpSlashCall(val name: String, val named: Map<String, String>, val unnamed: String)

/**
 * STscript subset interpreter (SillyTavern slash commands) used by `triggerSlash` and card buttons.
 * Commands are split on unquoted `|`; `{{pipe}}` and macros are expanded per command right before it
 * runs; `{: … :}` closures stay raw and run recursively. Unknown commands pass the pipe through.
 * Returns the final pipe value.
 */
object RpSlashCommands {
    private const val MAX_LOOPS = 1000

    private class Returned(val value: String) : RuntimeException(null, null, false, false)

    suspend fun run(script: String, env: RpSlashEnv): String = try {
        runBlock(script, env, "", emptyMap())
    } catch (r: Returned) {
        r.value
    }

    private suspend fun runBlock(script: String, env: RpSlashEnv, initialPipe: String, scope: Map<String, String>): String {
        var pipe = initialPipe
        for (segment in split(script)) {
            val call = parse(segment) ?: continue
            pipe = execute(call, env, pipe, scope)
        }
        return pipe
    }

    /* ---------------- parsing ---------------- */

    /** Splits a script on top-level `|` (outside double quotes and `{: :}` closures; `\|` escapes). */
    fun split(script: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote = false
        var depth = 0
        var i = 0
        while (i < script.length) {
            val c = script[i]
            when {
                c == '\\' && i + 1 < script.length -> { cur.append(c).append(script[i + 1]); i += 2; continue }
                !inQuote && script.startsWith("{:", i) -> { depth++; cur.append("{:"); i += 2; continue }
                !inQuote && depth > 0 && script.startsWith(":}", i) -> { depth--; cur.append(":}"); i += 2; continue }
                c == '"' && depth == 0 -> inQuote = !inQuote
                c == '|' && !inQuote && depth == 0 -> { out += cur.toString(); cur.clear(); i++; continue }
            }
            cur.append(c)
            i++
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Parses one `/name key=value … unnamed` segment; null if it is not a command. */
    fun parse(segment: String): RpSlashCall? {
        val s = segment.trim()
        if (!s.startsWith("/") || s.startsWith("//") || s.startsWith("/#")) return null
        var i = 1
        while (i < s.length && !s[i].isWhitespace()) i++
        val name = s.substring(1, i).lowercase()
        if (name.isEmpty()) return null
        val named = linkedMapOf<String, String>()
        while (true) {
            while (i < s.length && s[i].isWhitespace()) i++
            val m = Regex("""^([\w-]+)=""").find(s.substring(i)) ?: break
            i += m.value.length
            val (value, next) = readValue(s, i)
            named[m.groupValues[1]] = value
            i = next
        }
        var unnamed = s.substring(i).trim()
        // Lenient: named args after a leading closure (`/if … {: :} else={: :}`).
        if (unnamed.startsWith("{:")) {
            val end = closureEnd(unnamed, 0)
            var j = end
            val tail = linkedMapOf<String, String>()
            while (true) {
                while (j < unnamed.length && unnamed[j].isWhitespace()) j++
                val m = Regex("""^([\w-]+)=""").find(unnamed.substring(j)) ?: break
                j += m.value.length
                val (value, next) = readValue(unnamed, j)
                tail[m.groupValues[1]] = value
                j = next
            }
            if (tail.isNotEmpty() && j >= unnamed.length) {
                named.putAll(tail)
                unnamed = unnamed.substring(0, end)
            }
        }
        return RpSlashCall(name, named, unnamed)
    }

    /** Reads a named-arg value at [start]: quoted, closure, bracketed, or up to whitespace. */
    private fun readValue(s: String, start: Int): Pair<String, Int> {
        if (start >= s.length) return "" to start
        return when {
            s[start] == '"' -> {
                val sb = StringBuilder("\"")
                var i = start + 1
                while (i < s.length && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i]); i++ }
                    sb.append(s[i]); i++
                }
                sb.append('"')
                sb.toString() to (i + 1).coerceAtMost(s.length)
            }
            s.startsWith("{:", start) -> {
                val end = closureEnd(s, start)
                s.substring(start, end) to end
            }
            s[start] == '[' || s[start] == '{' -> {
                var depth = 0
                var inQuote = false
                var i = start
                while (i < s.length) {
                    val c = s[i]
                    if (c == '"' && (i == 0 || s[i - 1] != '\\')) inQuote = !inQuote
                    if (!inQuote) { if (c == '[' || c == '{') depth++; if (c == ']' || c == '}') depth-- }
                    i++
                    if (depth == 0) break
                }
                s.substring(start, i) to i
            }
            else -> {
                var i = start
                while (i < s.length && !s[i].isWhitespace()) i++
                s.substring(start, i) to i
            }
        }
    }

    private fun closureEnd(s: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < s.length) {
            if (s.startsWith("{:", i)) { depth++; i += 2; continue }
            if (s.startsWith(":}", i)) { depth--; i += 2; if (depth == 0) return i; continue }
            i++
        }
        return s.length
    }

    private fun closureBody(raw: String): String? {
        val t = raw.trim()
        return if (t.startsWith("{:") && t.endsWith(":}") && closureEnd(t, 0) == t.length) t.substring(2, t.length - 2) else null
    }

    private fun unquote(raw: String): String {
        val t = raw.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length - 1).replace("\\\"", "\"")
        }
        return t
    }

    /** Unescapes `\|`, `\{`, `\}` and similar STscript escapes in plain text. */
    private fun unescape(s: String): String = s.replace(Regex("""\\([|{}:\\])"""), "$1")

    /* ---------------- execution ---------------- */

    private class Args(
        val named: Map<String, String>,
        val text: String,
        /** Unnamed closure body, when the unnamed argument is a closure (or quoted script for /if). */
        val closure: String?,
        val rawUnnamed: String
    )

    private fun expand(raw: String, env: RpSlashEnv, pipe: String, scope: Map<String, String>): String {
        if (closureBody(raw) != null) return raw
        var s = unescape(unquote(raw)).replace("{{pipe}}", pipe, ignoreCase = true)
        scope.forEach { (k, v) -> s = s.replace("{{$k}}", v, ignoreCase = true) }
        return env.macros(s)
    }

    private suspend fun execute(call: RpSlashCall, env: RpSlashEnv, pipe: String, scope: Map<String, String>): String {
        val named = call.named.mapValues { (_, v) -> expand(v, env, pipe, scope) }
        val closure = closureBody(call.unnamed)
        val text = if (closure != null) "" else expand(call.unnamed, env, pipe, scope)
        val a = Args(named, text, closure, call.unnamed)
        val actions = env.actions
        /** Unnamed value, defaulting to the pipe like ST does when the argument is omitted. */
        val value = if (call.unnamed.isEmpty()) pipe else text
        return when (call.name) {
            "send" -> { actions.sendUser(value); "" }
            "trigger" -> { actions.trigger(); "" }
            "sendas" -> { actions.sendAs("assistant", a.named["name"].orEmpty(), value); "" }
            "sys", "sys-gen" -> { actions.sendAs("system", "", value); "" }
            "comment" -> { actions.sendAs("comment", "", value); "" }
            "setinput" -> { actions.setInput(value); "" }
            "echo" -> { actions.toast(value); value }
            "pass", "return" -> if (call.name == "return") throw Returned(value) else value
            "len", "length" -> lenOf(value).toString()
            "trimstart" -> trimStart(value)
            "trimend" -> trimEnd(value)
            "input" -> ""
            "buttons" -> firstLabel(a.named["labels"])
            "gen", "genraw" -> {
                val options = JSONObject().put("quiet", true)
                if (call.name == "genraw") options.put("raw", true)
                a.named["lock"]?.let { options.put("lock", isTrue(it)) }
                a.named["length"]?.toIntOrNull()?.let { options.put("length", it) }
                a.named["as"]?.let { options.put("as", it) }
                a.named["system"]?.let { options.put("system", it) }
                actions.generate(value, options)
            }
            "hide", "unhide" -> {
                val indices = indexList(value, actions.messages().size)
                actions.setMessages(indices.map { RpHelperMessageUpdate(index = it, hidden = call.name == "hide") })
                ""
            }
            "cut" -> {
                val messages = actions.messages()
                val indices = indexList(value, messages.size)
                val cut = messages.filter { it.index in indices }.joinToString("\n") { it.content }
                if (indices.isNotEmpty()) actions.deleteMessages(indices)
                cut
            }
            "del", "delete" -> {
                val messages = actions.messages()
                val n = value.trim().toIntOrNull() ?: 0
                if (n > 0) actions.deleteMessages(messages.takeLast(n).map { it.index })
                ""
            }
            "setvar", "setglobalvar" -> {
                val scopeVar = if (call.name == "setvar") RpVarScope.Chat else RpVarScope.Global
                setVar(env, scopeVar, a.named["key"] ?: a.named["name"] ?: return pipe, value, a.named["index"])
                value
            }
            "getvar", "getglobalvar" -> {
                val scopeVar = if (call.name == "getvar") RpVarScope.Chat else RpVarScope.Global
                val key = a.named["key"] ?: a.named["name"] ?: text.ifEmpty { return "" }
                asText(getVar(env, scopeVar, key, a.named["index"]))
            }
            "addvar", "addglobalvar", "incvar", "incglobalvar", "decvar", "decglobalvar" -> {
                val scopeVar = if ("global" in call.name) RpVarScope.Global else RpVarScope.Chat
                val delta: Any = when {
                    call.name.startsWith("inc") -> 1
                    call.name.startsWith("dec") -> -1
                    else -> value
                }
                val key = when {
                    call.name.startsWith("add") -> a.named["key"] ?: a.named["name"] ?: return pipe
                    else -> a.named["key"] ?: a.named["name"] ?: text.ifEmpty { return pipe }
                }
                asText(addVar(env, scopeVar, key, delta))
            }
            "flushvar", "flushglobalvar" -> {
                val scopeVar = if (call.name == "flushvar") RpVarScope.Chat else RpVarScope.Global
                val key = a.named["key"] ?: a.named["name"] ?: text
                val table = env.variables.table(scopeVar)
                table.remove(key)
                env.variables.replace(scopeVar, table)
                ""
            }
            "if" -> {
                val left = a.named["left"] ?: a.named["a"]
                val right = a.named["right"] ?: a.named["b"]
                val result = runCatching { evalBoolean(a.named["rule"], left?.let { operand(env, it) }, right?.let { operand(env, it) }) }
                    .getOrDefault(false)
                val body = if (result) closure ?: scriptText(call.unnamed) else a.named["else"]?.let { closureBody(it) ?: scriptText(it) }
                if (body.isNullOrBlank()) pipe else runBlock(body, env, pipe, scope)
            }
            "times" -> {
                val countRaw = a.named["count"] ?: call.unnamed.substringBefore(' ').let { expand(it, env, pipe, scope) }
                val count = countRaw.trim().toDoubleOrNull()?.toInt()?.coerceIn(0, MAX_LOOPS) ?: 0
                val bodyRaw = call.unnamed.substringAfter(' ', "").trim()
                val body = closureBody(bodyRaw) ?: scriptText(bodyRaw) ?: return pipe
                var result = pipe
                for (n in 0 until count) {
                    val inner = body.replace("{{timesIndex}}", n.toString(), ignoreCase = true)
                    result = runBlock(inner, env, result, scope + ("timesIndex" to n.toString()))
                }
                result
            }
            else -> pipe
        }
    }

    private fun scriptText(raw: String): String? {
        val t = unquote(raw)
        return t.takeIf { it.trim().startsWith("/") }?.let(::unescape)
    }

    private fun isTrue(s: String) = s.trim().lowercase() in setOf("on", "true", "1")
    private fun isFalse(s: String) = s.trim().lowercase() in setOf("off", "false", "0")

    private fun firstLabel(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return ""
        val first = arr.opt(0) ?: return ""
        return if (first is JSONObject) first.optString("text") else first.toString()
    }

    /** Parses `n`, `a-b` or comma lists into message indices within [0, size). */
    private fun indexList(raw: String, size: Int): List<Int> {
        val out = linkedSetOf<Int>()
        raw.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val range = Regex("""^(-?\d+)\s*-\s*(-?\d+)$""").find(part)
            if (range != null) {
                val a = range.groupValues[1].toInt()
                val b = range.groupValues[2].toInt()
                for (k in minOf(a, b)..maxOf(a, b)) if (k in 0 until size) out += k
            } else part.toIntOrNull()?.let { k -> (if (k < 0) size + k else k).takeIf { it in 0 until size }?.let(out::add) }
        }
        return out.toList()
    }

    private fun lenOf(value: String): Int {
        val t = value.trim()
        if (t.startsWith("[")) runCatching { return JSONArray(t).length() }
        if (t.startsWith("{")) runCatching { return JSONObject(t).length() }
        return value.length
    }

    private val SENTENCE_END = Regex("""[.!?。！？…"”』」*)\]）】~]""")

    /** ST /trimend: drops a trailing unfinished sentence. */
    fun trimEnd(text: String): String {
        val t = text.trimEnd()
        val last = SENTENCE_END.findAll(t).lastOrNull() ?: return t
        return t.substring(0, last.range.last + 1).trimEnd()
    }

    /** ST /trimstart: drops a leading partial sentence (text before the first sentence end). */
    fun trimStart(text: String): String {
        val t = text.trimStart()
        if (t.isEmpty() || t[0].isUpperCase() || !t[0].isLetter()) return t
        val first = Regex("""[.!?。！？…]\s*""").find(t) ?: return t
        return t.substring(first.range.last + 1).trimStart().ifEmpty { t }
    }

    /* ---------------- variables (ST chat_metadata.variables semantics) ---------------- */

    private fun numeric(v: Any?): Any? = when (v) {
        is String -> if (v.isNotBlank() && v.trim().toDoubleOrNull() != null) normalize(v.trim().toDouble()) else v
        else -> v
    }

    private fun normalize(d: Double): Number =
        if (d == Math.rint(d) && kotlin.math.abs(d) < 9e15) d.toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } else d

    private fun asText(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is Double -> normalize(v).toString()
        else -> v.toString()
    }

    private fun structured(v: Any?): Any? = when (v) {
        is JSONObject, is JSONArray -> v
        is String -> v.trim().let { t ->
            if (t.startsWith("[")) runCatching { JSONArray(t) }.getOrNull()
            else if (t.startsWith("{")) runCatching { JSONObject(t) }.getOrNull() else null
        }
        else -> null
    }

    /** getLocalVariable: optional index into a JSON value; numeric strings become numbers. */
    fun getVar(env: RpSlashEnv, scope: RpVarScope, key: String, index: String? = null): Any? {
        var v: Any? = env.variables.table(scope).opt(key)
        if (index != null) {
            val container = structured(v)
            v = when (container) {
                is JSONArray -> index.trim().toIntOrNull()?.let { container.opt(it) }
                is JSONObject -> container.opt(index)
                else -> v
            }
            if (v is JSONObject || v is JSONArray) v = v.toString()
        }
        if (v is JSONObject || v is JSONArray) return v.toString()
        return numeric(v) ?: ""
    }

    fun setVar(env: RpSlashEnv, scope: RpVarScope, key: String, value: Any?, index: String? = null) {
        if (key.isEmpty()) return
        val table = env.variables.table(scope)
        if (index != null) {
            val existing = table.opt(key)
            val numIndex = index.trim().toIntOrNull()
            val container = structured(existing) ?: if (numIndex != null) JSONArray() else JSONObject()
            val converted = numeric(value) ?: JSONObject.NULL
            when (container) {
                is JSONArray -> if (numIndex != null) container.put(numIndex, converted) else return
                is JSONObject -> container.put(index, converted)
            }
            table.put(key, if (existing is JSONObject || existing is JSONArray) container else container.toString())
        } else {
            table.put(key, value ?: JSONObject.NULL)
        }
        env.variables.replace(scope, table)
    }

    /** addLocalVariable: array append, numeric add, else string concatenation. */
    fun addVar(env: RpSlashEnv, scope: RpVarScope, key: String, value: Any): Any {
        val table = env.variables.table(scope)
        val raw = table.opt(key)
        val arr = structured(raw) as? JSONArray
        if (arr != null) {
            arr.put(value)
            table.put(key, if (raw is JSONArray) arr else arr.toString())
            env.variables.replace(scope, table)
            return arr.toString()
        }
        val current = numeric(raw).let { if (it == null || it == JSONObject.NULL || it == "") 0 else it }
        val inc = value.toString().trim().toDoubleOrNull()
        val cur = (current as? Number)?.toDouble() ?: current.toString().trim().toDoubleOrNull()
        val result: Any = if (inc == null || cur == null || value.toString().isBlank()) {
            (if (current == 0) "" else current.toString()) + value
        } else normalize(cur + inc)
        table.put(key, result)
        env.variables.replace(scope, table)
        return result
    }

    /** parseBooleanOperands: numeric literal, chat variable, global variable, else the string itself. */
    private fun operand(env: RpSlashEnv, raw: String): Any {
        if (raw.isEmpty()) return ""
        raw.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let { return it }
        for (scope in listOf(RpVarScope.Chat, RpVarScope.Global)) {
            val table = env.variables.table(scope)
            if (table.has(raw)) return getVar(env, scope, raw).let { if (it is Number) it.toDouble() else it ?: "" }
        }
        return raw
    }

    /** ST evalBoolean. */
    fun evalBoolean(rule: String?, a: Any?, b: Any?): Boolean {
        requireNotNull(a) { "Left operand is not provided" }
        if (b == null) {
            val truthy = when {
                isTrue(a.toString()) -> true
                isFalse(a.toString()) -> false
                a is Double -> a != 0.0
                else -> a.toString().isNotEmpty()
            }
            return when (rule) { null, "" -> truthy; "not" -> !truthy; else -> error("rule") }
        }
        val r = rule?.ifBlank { null } ?: "eq"
        if (a is Double && b is Double) {
            when (r) {
                "gt" -> return a > b; "gte" -> return a >= b; "lt" -> return a < b; "lte" -> return a <= b
                "eq" -> return a == b; "neq" -> return a != b
                "in", "nin" -> Unit
                else -> error("rule")
            }
        }
        fun str(v: Any) = (if (v is Double) asText(v) else v.toString()).lowercase()
        val sa = str(a)
        val sb = str(b)
        return when (r) {
            "in" -> sa.contains(sb)
            "nin" -> !sa.contains(sb)
            "eq" -> sa == sb
            "neq" -> sa != sb
            else -> error("rule")
        }
    }
}
