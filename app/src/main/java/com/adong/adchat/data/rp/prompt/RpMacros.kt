package com.adong.adchat.data.rp.prompt

import com.adong.adchat.data.rp.InMemoryRpVariableStore
import com.adong.adchat.data.rp.RpPromptMessage
import com.adong.adchat.data.rp.RpVarPath
import com.adong.adchat.data.rp.RpVarScope
import com.adong.adchat.data.rp.RpVariableStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Everything SillyTavern's substituteParams env sees. Card fields are raw (macros unexpanded); they
 * are expanded lazily with card replacement off, like ST's baseChatReplace.
 */
data class RpMacroContext(
    val user: String = "",
    val char: String = "",
    /** {{persona}}: persona description. */
    val persona: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    /** Raw mes_example. */
    val mesExamples: String = "",
    val charPrompt: String = "",
    val charJailbreak: String = "",
    val charDepthPrompt: String = "",
    val creatorNotes: String = "",
    val charVersion: String = "",
    /** Chat for lastMessage / lastUserMessage / lastCharMessage, oldest first. */
    val messages: List<RpPromptMessage> = emptyList(),
    val model: String = "",
    val variables: RpVariableStore = InMemoryRpVariableStore(),
    /** World-info outlets ({{outlet::name}}). */
    val outlets: Map<String, String> = emptyMap(),
    /** Stable chat/session id seeding {{pick}}. */
    val chatId: String = "",
    /** Variables of the latest message floor that has any (TavernHelper message scope). */
    val messageVariables: () -> JSONObject? = { null },
    /** Extra env macros ({{lastChatMessage}} …); override built-ins of the same name. */
    val dynamic: Map<String, String> = emptyMap(),
    val random: Random = Random.Default,
    val now: () -> Date = { Date() },
    val locale: Locale = Locale.getDefault()
)

/** SillyTavern legacy macro engine (substituteParams/evaluateMacros): pre → env → post single passes. */
object RpMacros {
    private class Step(val regex: Regex, val replace: (MatchResult, String) -> String)

    fun expand(text: String, ctx: RpMacroContext): String = expand(text, ctx, null, true, null)

    /**
     * [original] feeds {{original}} once per call (card override of main / jailbreak).
     * [postProcess] transforms every macro result (escaped regex substitution).
     */
    fun expand(
        text: String,
        ctx: RpMacroContext,
        original: String?,
        replaceCharacterCard: Boolean = true,
        postProcess: ((String) -> String)? = null
    ): String {
        if (text.isEmpty()) return ""
        val post = postProcess ?: { it }
        var content = text
        val raw = text
        for (step in steps(ctx, raw, original, replaceCharacterCard)) {
            if (content.isEmpty()) break
            if (!step.regex.pattern.startsWith("<") && !step.regex.pattern.startsWith("(?i)<") && "{{" !in content) break
            content = runCatching {
                val current = content
                step.regex.replace(current) { m -> post(step.replace(m, current)) }
            }.getOrDefault(content)
        }
        return content
    }

    private fun steps(ctx: RpMacroContext, raw: String, original: String?, card: Boolean): List<Step> = buildList {
        // ---- pre-env ----
        add(Step(Regex("<USER>", RegexOption.IGNORE_CASE)) { _, _ -> ctx.user })
        add(Step(Regex("<BOT>", RegexOption.IGNORE_CASE)) { _, _ -> ctx.char })
        add(Step(Regex("<CHAR>", RegexOption.IGNORE_CASE)) { _, _ -> ctx.char })
        add(Step(Regex("<CHARIFNOTGROUP>", RegexOption.IGNORE_CASE)) { _, _ -> ctx.char })
        add(Step(Regex("<GROUP>", RegexOption.IGNORE_CASE)) { _, _ -> ctx.char })
        add(Step(macro("roll[ :]([^}]+)")) { m, _ -> roll(m.groupValues[1], ctx.random) })
        val vars = RpMacroVariables(ctx.variables)
        add(Step(macro("setvar::([^:]+)::([^}]*)")) { m, _ -> vars.set(RpVarScope.Chat, m.groupValues[1].trim(), m.groupValues[2]); "" })
        add(Step(macro("addvar::([^:]+)::([^}]+)")) { m, _ -> vars.add(RpVarScope.Chat, m.groupValues[1].trim(), m.groupValues[2]); "" })
        add(Step(macro("incvar::([^}]+)")) { m, _ -> vars.add(RpVarScope.Chat, m.groupValues[1].trim(), "1") })
        add(Step(macro("decvar::([^}]+)")) { m, _ -> vars.add(RpVarScope.Chat, m.groupValues[1].trim(), "-1") })
        add(Step(macro("getvar::([^}]+)")) { m, _ -> vars.get(RpVarScope.Chat, m.groupValues[1].trim()) })
        add(Step(macro("setglobalvar::([^:]+)::([^}]*)")) { m, _ -> vars.set(RpVarScope.Global, m.groupValues[1].trim(), m.groupValues[2]); "" })
        add(Step(macro("addglobalvar::([^:]+)::([^}]+)")) { m, _ -> vars.add(RpVarScope.Global, m.groupValues[1].trim(), m.groupValues[2]); "" })
        add(Step(macro("incglobalvar::([^}]+)")) { m, _ -> vars.add(RpVarScope.Global, m.groupValues[1].trim(), "1") })
        add(Step(macro("decglobalvar::([^}]+)")) { m, _ -> vars.add(RpVarScope.Global, m.groupValues[1].trim(), "-1") })
        add(Step(macro("getglobalvar::([^}]+)")) { m, _ -> vars.get(RpVarScope.Global, m.groupValues[1].trim()) })
        add(Step(macro("newline")) { _, _ -> "\n" })
        add(Step(Regex("(?:\\r?\\n)*\\{\\{trim\\}\\}(?:\\r?\\n)*", RegexOption.IGNORE_CASE)) { _, _ -> "" })
        add(Step(macro("noop")) { _, _ -> "" })

        // ---- env (insertion order matters: names last so they resolve inside card fields) ----
        val env = LinkedHashMap<String, () -> String>()
        if (original != null) {
            var used = false
            env["original"] = { if (used) "" else { used = true; original } }
        }
        if (card) {
            fun field(value: String): () -> String = { expand(value.trim(), ctx, null, false).replace("\r", "") }
            env["charPrompt"] = field(ctx.charPrompt)
            env["charInstruction"] = field(ctx.charJailbreak)
            env["charJailbreak"] = field(ctx.charJailbreak)
            env["description"] = field(ctx.description)
            env["personality"] = field(ctx.personality)
            env["scenario"] = field(ctx.scenario)
            env["persona"] = field(ctx.persona)
            env["mesExamples"] = { RpExamples.parseMesExamples(field(ctx.mesExamples)()).joinToString("") }
            env["mesExamplesRaw"] = field(ctx.mesExamples)
            env["charVersion"] = { ctx.charVersion }
            env["char_version"] = { ctx.charVersion }
            env["charDepthPrompt"] = field(ctx.charDepthPrompt)
            env["creatorNotes"] = field(ctx.creatorNotes)
        }
        env["user"] = { ctx.user }
        env["char"] = { ctx.char }
        env["group"] = { ctx.char }
        env["charIfNotGroup"] = { ctx.char }
        env["groupNotMuted"] = { ctx.char }
        env["notChar"] = { ctx.user }
        env["model"] = { ctx.model }
        ctx.dynamic.forEach { (key, value) -> env[key] = { value } }
        env.forEach { (key, value) -> add(Step(macro(Regex.escape(key))) { _, _ -> value() }) }

        // ---- post-env ----
        val messages = ctx.messages
        add(Step(macro("lastMessage")) { _, _ -> messages.lastOrNull()?.content.orEmpty() })
        add(Step(macro("lastMessageId")) { _, _ -> if (messages.isEmpty()) "" else messages.lastIndex.toString() })
        add(Step(macro("lastUserMessage")) { _, _ -> messages.lastOrNull { it.role == "user" }?.content.orEmpty() })
        add(Step(macro("lastCharMessage")) { _, _ -> messages.lastOrNull { it.role == "assistant" }?.content.orEmpty() })
        add(Step(macro("lastSwipeId")) { _, _ -> if (messages.isEmpty()) "" else "1" })
        add(Step(macro("currentSwipeId")) { _, _ -> if (messages.isEmpty()) "" else "1" })
        add(Step(macro("allChatRange")) { _, _ -> if (messages.isEmpty()) "" else "0-${messages.lastIndex}" })
        add(Step(macro("reverse:(.+?)")) { m, _ -> m.groupValues[1].reversed() })
        add(Step(Regex("\\{\\{//([\\s\\S]*?)\\}\\}", RegexOption.MULTILINE)) { _, _ -> "" })
        add(Step(macro("time")) { _, _ -> DateFormat.getTimeInstance(DateFormat.SHORT, ctx.locale).format(ctx.now()) })
        add(Step(macro("date")) { _, _ -> DateFormat.getDateInstance(DateFormat.LONG, ctx.locale).format(ctx.now()) })
        add(Step(macro("weekday")) { _, _ -> SimpleDateFormat("EEEE", ctx.locale).format(ctx.now()) })
        add(Step(macro("isotime")) { _, _ -> SimpleDateFormat("HH:mm", Locale.US).format(ctx.now()) })
        add(Step(macro("isodate")) { _, _ -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(ctx.now()) })
        add(Step(macro("time_UTC([-+]\\d+)")) { m, _ ->
            val format = DateFormat.getTimeInstance(DateFormat.SHORT, ctx.locale)
            val hours = m.groupValues[1].toIntOrNull() ?: 0
            format.timeZone = TimeZone.getTimeZone(String.format(Locale.US, "GMT%+03d:00", hours))
            format.format(ctx.now())
        })
        add(Step(macro("idle_duration")) { _, _ -> "just now" })
        add(Step(macro("outlet::(.+?)")) { m, _ -> ctx.outlets[m.groupValues[1].trim()].orEmpty() })
        add(Step(macro("banned \"(.*?)\"")) { _, _ -> "" })
        add(Step(macro("random\\s?::?([^}]+)")) { m, _ ->
            val list = choices(m.groupValues[1])
            if (list.isEmpty()) "" else list[ctx.random.nextInt(list.size)]
        })
        val rawHash = stringHash(raw)
        val chatHash = stringHash(ctx.chatId)
        add(Step(macro("pick\\s?::?([^}]+)")) { m, _ ->
            val list = choices(m.groupValues[1])
            if (list.isEmpty()) "" else {
                val seed = stringHash("$chatHash-$rawHash-${m.range.first}")
                list[Random(seed).nextInt(list.size)]
            }
        })
        // ---- TavernHelper macro-likes ----
        add(Step(macro("get_(message|chat|character|preset|global)_variable::(.*?)")) { m, _ ->
            val value = helperValue(ctx, m.groupValues[1], m.groupValues[2])
            if (value is String) value else jsonValue(value)
        })
        add(Step(Regex("^(.*)\\{\\{format_(message|chat|character|preset|global)_variable::(.*?)\\}\\}",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))) { m, _ ->
            val prefix = m.groupValues[1]
            val value = helperValue(ctx, m.groupValues[2], m.groupValues[3])
            val body = if (value is String) value else RpYaml.dump(value).trimEnd()
            prefix + body.replace("\n", "\n" + " ".repeat(prefix.length))
        })
    }

    private fun macro(body: String) = Regex("\\{\\{$body\\}\\}", RegexOption.IGNORE_CASE)

    private fun choices(list: String): List<String> =
        if ("::" in list) list.split("::")
        else list.replace("\\,", "\u0000").split(',').map { it.trim().replace('\u0000', ',') }

    private fun roll(formulaRaw: String, random: Random): String {
        var formula = formulaRaw.trim()
        if (formula.all(Char::isDigit) && formula.isNotEmpty()) formula = "1d$formula"
        val m = Regex("^(\\d*)d(\\d+)\\s*([+-]\\s*\\d+)?$", RegexOption.IGNORE_CASE).find(formula) ?: return ""
        val count = m.groupValues[1].ifEmpty { "1" }.toIntOrNull()?.takeIf { it in 1..1000 } ?: return ""
        val sides = m.groupValues[2].toIntOrNull()?.takeIf { it >= 1 } ?: return ""
        val mod = m.groupValues[3].replace(" ", "").takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0
        return ((1..count).sumOf { random.nextInt(1, sides + 1).toLong() } + mod).toString()
    }

    private fun helperValue(ctx: RpMacroContext, type: String, path: String): Any? {
        val root: JSONObject = when (type.lowercase()) {
            "message" -> ctx.messageVariables() ?: JSONObject()
            "chat" -> ctx.variables.table(RpVarScope.Chat)
            "character" -> ctx.variables.table(RpVarScope.Character)
            "global" -> ctx.variables.table(RpVarScope.Global)
            else -> JSONObject()
        }
        val unescaped = path.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        return omitDollar(RpVarPath.get(root, unescaped))
    }

    private fun omitDollar(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out ->
            value.keys().forEach { key -> if (!key.startsWith("$")) out.put(key, omitDollar(value.opt(key)) ?: JSONObject.NULL) }
        }
        is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(omitDollar(value.opt(i)) ?: JSONObject.NULL) }
        JSONObject.NULL -> null
        else -> value
    }

    internal fun jsonValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Number -> jsNumber(value)
        else -> value.toString()
    }

    internal fun jsNumber(value: Number): String {
        val d = value.toDouble()
        return if (d.isFinite() && d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    /** ST getStringHash (cyrb53), returned as a 53-bit value. */
    internal fun stringHash(value: String, seed: Int = 0): Long {
        var h1 = 0xdeadbeef.toInt() xor seed
        var h2 = 0x41c6ce57 xor seed
        for (ch in value) {
            h1 = (h1 xor ch.code) * -1640531535 // 2654435761
            h2 = (h2 xor ch.code) * 1597334677
        }
        h1 = ((h1 xor (h1 ushr 16)) * -2048144789) xor ((h2 xor (h2 ushr 13)) * -1028477387)
        h2 = ((h2 xor (h2 ushr 16)) * -2048144789) xor ((h1 xor (h1 ushr 13)) * -1028477387)
        return 4294967296L * (2097151 and h2) + (h1.toLong() and 0xffffffffL)
    }
}

/** chat_metadata.variables / global variables with ST add/inc/dec semantics. */
internal class RpMacroVariables(private val store: RpVariableStore) {
    fun get(scope: RpVarScope, name: String): String {
        val value = store.table(scope).opt(name)
        return when (value) {
            null, JSONObject.NULL -> ""
            is String -> value.trim().toDoubleOrNull()?.takeIf { value.isNotBlank() }?.let(RpMacros::jsNumber) ?: value
            is Number -> RpMacros.jsNumber(value)
            else -> value.toString()
        }
    }

    fun set(scope: RpVarScope, name: String, value: Any) {
        val table = store.table(scope)
        table.put(name, value)
        store.replace(scope, table)
    }

    fun add(scope: RpVarScope, name: String, value: String): String {
        val existing = get(scope, name)
        val current = existing.ifEmpty { "0" }
        runCatching { JSONArray(current) }.getOrNull()?.takeIf { current.trimStart().startsWith("[") }?.let { array ->
            array.put(value)
            set(scope, name, array.toString())
            return array.toString()
        }
        val increment = value.trim().toDoubleOrNull()
        val base = current.trim().toDoubleOrNull()
        if (increment == null || base == null) {
            val joined = existing + value
            set(scope, name, joined)
            return joined
        }
        val sum = base + increment
        val stored: Any = if (sum == Math.rint(sum) && kotlin.math.abs(sum) < 1e15) sum.toLong() else sum
        set(scope, name, stored)
        return RpMacros.jsNumber(sum)
    }
}

/** Minimal YAML dump for format_*_variable (block style, double-quoted strings). */
internal object RpYaml {
    fun dump(value: Any?): String = StringBuilder().also { write(it, value, 0, false) }.toString()

    private fun write(out: StringBuilder, value: Any?, indent: Int, inline: Boolean) {
        val pad = " ".repeat(indent)
        when (value) {
            is JSONObject -> {
                if (value.length() == 0) { out.append("{}\n"); return }
                if (inline) out.append('\n')
                value.keys().forEach { key ->
                    out.append(pad).append(key).append(':')
                    val child = value.opt(key)
                    if (child is JSONObject && child.length() > 0 || child is JSONArray && child.length() > 0) write(out, child, indent + 2, true)
                    else { out.append(' '); write(out, child, indent + 2, false) }
                }
            }
            is JSONArray -> {
                if (value.length() == 0) { out.append("[]\n"); return }
                if (inline) out.append('\n')
                for (i in 0 until value.length()) {
                    out.append(pad).append("- ")
                    val child = value.opt(i)
                    if (child is JSONObject && child.length() > 0) {
                        val nested = StringBuilder().also { write(it, child, indent + 2, false) }.toString()
                        out.append(nested.trimStart())
                    } else write(out, child, indent + 2, false)
                }
            }
            null, JSONObject.NULL -> out.append("null\n")
            is String -> out.append(JSONObject.quote(value).replace("\\/", "/")).append('\n')
            is Number -> out.append(RpMacros.jsNumber(value)).append('\n')
            else -> out.append(value.toString()).append('\n')
        }
    }
}
