package com.adong.adchat.data.rp.template

import com.adong.adchat.data.rp.RpPromptMessage
import com.adong.adchat.data.rp.RpVarPath
import com.adong.adchat.data.rp.RpVarScope
import com.adong.adchat.data.rp.RpVariableStore
import com.adong.adchat.data.rp.helper.RpYaml
import com.adong.adchat.data.rp.template.RpTplValues.normalize
import com.adong.adchat.data.rp.template.RpTplValues.toNumber
import com.adong.adchat.data.rp.template.RpTplValues.toStr
import com.adong.adchat.data.story.StoryWorldBook
import org.json.JSONArray
import org.json.JSONObject

/** Which stage a template runs in (ST-Prompt-Template `runType`). */
enum class RpTplStage(val wire: String) { Generate("generate"), Render("render"), Preparation("preparation") }

/**
 * Inputs for one template evaluation. [messageFloor] addresses message-scope variables (the floor being
 * rendered, or the latest floor while generating); [prompts] collects `injectPrompt` across one request.
 */
data class RpTplContext(
    val stage: RpTplStage,
    val variables: RpVariableStore,
    val messages: List<RpPromptMessage>,
    val worldBook: StoryWorldBook,
    val userName: String,
    val charName: String,
    val chatId: String,
    val model: String = "",
    val generationType: String = "",
    /** Message being rendered (render stage), else null. */
    val messageIndex: Int? = null,
    val messageFloor: Pair<Long, Int>? = null,
    val isUser: Boolean = false,
    val isSystem: Boolean = false,
    val prompts: RpTplPromptBag = RpTplPromptBag(),
    /** ST macro expansion used by `getwi` content and `substitudeMacros`. */
    val macros: (String) -> String = { it },
    val characterJson: () -> JSONObject = { JSONObject() },
    val extra: Map<String, Any?> = emptyMap()
)

/** `injectPrompt` / `getPromptsInjected` store shared across all templates of one request. */
class RpTplPromptBag {
    private val prompts = mutableMapOf<String, MutableList<Pair<Int, String>>>()
    fun inject(key: String, prompt: String, order: Int) { prompts.getOrPut(key) { mutableListOf() } += order to prompt }
    fun get(key: String): String = prompts[key].orEmpty().sortedBy { it.first }.joinToString("\n") { it.second }
    fun has(key: String): Boolean = prompts[key].orEmpty().isNotEmpty()
}

/**
 * The ST-Prompt-Template function set on top of our variable store. Scopes map: `global` → Global,
 * `local` → Chat, `message` → the current floor; `cache` (the default read scope) is the merged view
 * global → chat → message floors, like the extension's `variables`.
 */
class RpTplHost(private val ctx: RpTplContext) : RpTplScope {
    private val defines = mutableMapOf<String, Any?>()
    var lastError: Throwable? = null
        private set

    override fun onError(error: Throwable) { lastError = error }

    /** Merged variable tree (`variables`): global → local(chat) → message floor. Fresh per read. */
    fun merged(): JSONObject {
        val out = JSONObject()
        fun mergeInto(dst: JSONObject, src: JSONObject) {
            src.keys().forEach { k ->
                val a = dst.opt(k); val b = src.opt(k)
                if (a is JSONObject && b is JSONObject) mergeInto(a, b) else dst.put(k, b)
            }
        }
        mergeInto(out, ctx.variables.table(RpVarScope.Global))
        mergeInto(out, ctx.variables.table(RpVarScope.Chat))
        ctx.messageFloor?.let { (id, swipe) -> mergeInto(out, ctx.variables.table(RpVarScope.Message, id, swipe)) }
        return out
    }

    private data class Target(val scope: RpVarScope, val id: Long?, val swipe: Int)

    private fun target(scope: String): Target? = when (scope) {
        "global" -> Target(RpVarScope.Global, null, 0)
        "local", "chat" -> Target(RpVarScope.Chat, null, 0)
        "message" -> ctx.messageFloor?.let { Target(RpVarScope.Message, it.first, it.second) } ?: Target(RpVarScope.Chat, null, 0)
        else -> null
    }

    private fun options(raw: Any?): JSONObject = when (raw) {
        is JSONObject -> raw
        is String -> when (raw) {
            "nx", "xx", "n", "nxs", "xxs" -> JSONObject().put("flags", raw)
            "global", "local", "message", "cache", "initial" -> JSONObject().put("scope", raw)
            "old", "new", "fullcache" -> JSONObject().put("results", raw)
            else -> JSONObject()
        }
        else -> JSONObject()
    }

    fun getvar(key: Any?, opts: JSONObject, defaultScope: String = "cache"): Any? {
        val scope = opts.optString("scope", defaultScope)
        val root: JSONObject = if (scope == "cache" || scope == "initial") merged()
            else target(scope)?.let { ctx.variables.table(it.scope, it.id, it.swipe) } ?: merged()
        if (key == null || key == JSONObject.NULL) return root
        val value = RpVarPath.get(root, toStr(key))
        val defaults = if (opts.has("defaults")) normalize(opts.opt("defaults")) else RpTplUndefined
        return if (value == null || value == JSONObject.NULL) defaults else normalize(value)
    }

    fun setvar(key: Any?, value: Any?, opts: JSONObject, defaultScope: String = "message"): Any? {
        val t = target(opts.optString("scope", defaultScope)) ?: return RpTplUndefined
        val table = ctx.variables.table(t.scope, t.id, t.swipe)
        val path = if (key == null || key == JSONObject.NULL) null else toStr(key)
        val old = path?.let { RpVarPath.get(table, it) }
        val exists = old != null && old != JSONObject.NULL
        when (opts.optString("flags", "n")) {
            "nx", "nxs" -> if (exists) return RpTplUndefined
            "xx", "xxs" -> if (!exists) return RpTplUndefined
        }
        val stored = RpTplInterpreter.wrapJson(value)
        if (path == null) {
            ctx.variables.replace(t.scope, stored as? JSONObject ?: return RpTplUndefined, t.id, t.swipe)
        } else {
            if (opts.optBoolean("merge") && old is JSONObject && stored is JSONObject) {
                stored.keys().forEach { old.put(it, stored.opt(it)) }
                RpVarPath.set(table, path, old)
            } else RpVarPath.set(table, path, stored)
            ctx.variables.replace(t.scope, table, t.id, t.swipe)
        }
        return when (opts.optString("results", "new")) {
            "old" -> normalize(old) ?: RpTplUndefined
            "fullcache" -> merged()
            else -> normalize(stored)
        }
    }

    private fun incvar(key: Any?, delta: Double, opts: JSONObject): Any? {
        val current = getvar(key, JSONObject().put("scope", opts.optString("scope", "message").let { if (it == "cache") "message" else it }))
        val base = toNumber(if (current == RpTplUndefined) 0.0 else current).let { if (it.isNaN()) 0.0 else it }
        return setvar(key, base + delta, opts)
    }

    private fun delvar(key: Any?, opts: JSONObject): Any? {
        val t = target(opts.optString("scope", "message")) ?: return RpTplUndefined
        val table = ctx.variables.table(t.scope, t.id, t.swipe)
        val removed = RpVarPath.unset(table, toStr(key))
        if (removed) ctx.variables.replace(t.scope, table, t.id, t.swipe)
        return removed
    }

    private fun getwi(args: List<Any?>): Any? {
        // getwi(title[, data]) or getwi(lorebook, title[, data]); our books are merged, so the book name is ignored.
        val title = if (args.size >= 2 && args[1] !is JSONObject) args[1] else args.firstOrNull()
        val entries = ctx.worldBook.entries
        val entry = when (title) {
            is Double -> entries.firstOrNull { it.uid == title.toInt() }
            else -> {
                val name = toStr(title)
                entries.firstOrNull { it.comment == name } ?: entries.firstOrNull { it.entryName == name }
                    ?: entries.firstOrNull { it.comment.contains(name) }
            }
        } ?: return ""
        val content = ctx.macros(entry.content)
        return RpTemplate.render(content, RpTplHost(ctx).also { child -> child.defines.putAll(defines) }) ?: content
    }

    private fun chatMessages(args: List<Any?>): JSONArray {
        val list = ctx.messages
        val roleFilter = args.lastOrNull()?.let { if (it is String && it in setOf("user", "assistant", "system")) it else null }
        val numbers = args.filterIsInstance<Double>().map { it.toInt() }
        val picked = when (numbers.size) {
            0 -> list
            1 -> list.takeLast(numbers[0].coerceAtLeast(0))
            else -> {
                fun at(i: Int) = if (i < 0) list.size + i else i
                val from = at(numbers[0]).coerceIn(0, list.size); val to = at(numbers[1]).coerceIn(0, list.size)
                if (from >= to) emptyList() else list.subList(from, to)
            }
        }
        return JSONArray(picked.filter { roleFilter == null || it.role == roleFilter }.map { it.content })
    }

    private val functions: Map<String, RpTplFunction> by lazy {
        fun f(block: (List<Any?>) -> Any?) = RpTplFunction(block)
        fun a(args: List<Any?>, i: Int) = args.getOrElse(i) { RpTplUndefined }
        buildMap {
            put("getvar", f { getvar(a(it, 0), options(a(it, 1))) })
            put("getLocalVar", f { getvar(a(it, 0), options(a(it, 1)).put("scope", "local")) })
            put("getGlobalVar", f { getvar(a(it, 0), options(a(it, 1)).put("scope", "global")) })
            put("getMessageVar", f { getvar(a(it, 0), options(a(it, 1)).put("scope", "message")) })
            put("setvar", f { setvar(a(it, 0), a(it, 1), options(a(it, 2))) })
            put("setLocalVar", f { setvar(a(it, 0), a(it, 1), options(a(it, 2)).put("scope", "local")) })
            put("setGlobalVar", f { setvar(a(it, 0), a(it, 1), options(a(it, 2)).put("scope", "global")) })
            put("setMessageVar", f { setvar(a(it, 0), a(it, 1), options(a(it, 2)).put("scope", "message")) })
            for ((name, scope) in listOf("incvar" to null, "incLocalVar" to "local", "incGlobalVar" to "global", "incMessageVar" to "message")) {
                put(name, f { args -> incvar(a(args, 0), toNumber(a(args, 1).let { if (it == RpTplUndefined) 1.0 else it }), options(a(args, 2)).also { o -> scope?.let { o.put("scope", it) } }) })
            }
            for ((name, scope) in listOf("decvar" to null, "decLocalVar" to "local", "decGlobalVar" to "global", "decMessageVar" to "message")) {
                put(name, f { args -> incvar(a(args, 0), -toNumber(a(args, 1).let { if (it == RpTplUndefined) 1.0 else it }), options(a(args, 2)).also { o -> scope?.let { o.put("scope", it) } }) })
            }
            for ((name, scope) in listOf("delvar" to null, "delLocalVar" to "local", "delGlobalVar" to "global", "delMessageVar" to "message")) {
                put(name, f { args -> delvar(a(args, 0), options(a(args, 2)).also { o -> scope?.let { o.put("scope", it) } }) })
            }
            put("getwi", f(::getwi)); put("getWorldInfo", f(::getwi))
            put("getchar", f { ctx.characterJson().optString("description") })
            put("getChara", f { ctx.characterJson().optString("description") })
            put("getCharData", f { ctx.characterJson() })
            put("getChatMessage", f { args ->
                val i = toNumber(a(args, 0)).toInt()
                ctx.messages.getOrNull(if (i < 0) ctx.messages.size + i else i)?.content ?: ""
            })
            put("getChatMessages", f(::chatMessages))
            put("matchChatMessages", f { args ->
                val pattern = toStr(a(args, 0))
                val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                ctx.messages.any { m -> regex?.containsMatchIn(m.content) ?: m.content.contains(pattern) }
            })
            put("define", f { args -> defines[toStr(a(args, 0))] = a(args, 1); RpTplUndefined })
            put("injectPrompt", f { args ->
                ctx.prompts.inject(toStr(a(args, 0)), toStr(a(args, 1)), toNumber(a(args, 2)).let { if (it.isNaN()) 100 else it.toInt() })
                RpTplUndefined
            })
            put("getPromptsInjected", f { ctx.prompts.get(toStr(a(it, 0))) })
            put("hasPromptsInjected", f { ctx.prompts.has(toStr(a(it, 0))) })
            put("parseJSON", f { args -> runCatching { RpTplMethods.parseJson(toStr(a(args, 0))) }.getOrNull() ?: RpYaml.parse(toStr(a(args, 0))) ?: RpTplUndefined })
            put("substitudeMacros", f { ctx.macros(toStr(a(it, 0))) })
            put("evalTemplate", f { args -> RpTemplate.render(toStr(a(args, 0)), RpTplHost(ctx)) ?: "" })
            put("activewi", f { RpTplUndefined }); put("activateWorldInfo", f { RpTplUndefined })
            put("execute", f { "" })
        }
    }

    override fun resolve(name: String): Any? {
        defines[name]?.let { return it }
        functions[name]?.let { return it }
        return when (name) {
            "variables" -> merged()
            "user", "userName" -> ctx.userName
            "char", "charName", "assistantName" -> ctx.charName
            "chatId" -> ctx.chatId
            "model_name", "modelName" -> ctx.model
            "generateType" -> ctx.generationType
            "runType" -> ctx.stage.wire
            "message_id" -> ctx.messageIndex?.toDouble() ?: RpTplUndefined
            "is_user" -> ctx.isUser
            "is_system" -> ctx.isSystem
            "is_last" -> ctx.messageIndex?.let { it == ctx.messages.lastIndex } ?: RpTplUndefined
            "lastMessageId" -> ctx.messages.lastIndex.toDouble()
            "lastUserMessage" -> ctx.messages.lastOrNull { it.role == "user" }?.content ?: ""
            "lastCharMessage" -> ctx.messages.lastOrNull { it.role == "assistant" }?.content ?: ""
            "lastUserMessageId" -> ctx.messages.indexOfLast { it.role == "user" }.toDouble()
            "lastCharMessageId" -> ctx.messages.indexOfLast { it.role == "assistant" }.toDouble()
            "_" -> null
            else -> ctx.extra[name]
        }
    }
}
