package com.adong.adchat.data.rp.helper

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.adong.adchat.data.rp.RpHelperActions
import com.adong.adchat.data.rp.RpHelperMessage
import com.adong.adchat.data.rp.RpHelperMessageUpdate
import com.adong.adchat.data.rp.RpHelperScript
import com.adong.adchat.data.rp.RpScriptButton
import com.adong.adchat.data.rp.RpSlashEnv
import com.adong.adchat.data.rp.RpVarScope
import com.adong.adchat.data.rp.RpVariableStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-session tavern_helper (JS-Slash-Runner) host. Every card frame (message iframes and the hidden
 * script engine) talks to this object: scoped variables, ST-shaped chat access, slash commands,
 * quiet generation, script buttons and a cross-frame event bus. Message floors are addressed by chat
 * index in JS and mapped to stable message ids here, so deletions never shift other floors.
 *
 * [revisions] bumps when a floor's variables or text change from script side, letting the UI re-render
 * the affected card. Sync [rpc] runs on Chromium's bridge thread; chat mutations hop to the main thread.
 */
class RpTavernHelper(
    val sessionId: String,
    private val variables: RpVariableStore,
    private val actions: RpHelperActions,
    private val scope: CoroutineScope,
    private val macros: (String) -> String,
    private val card: () -> JSONObject = { JSONObject() },
    private val worldEntries: () -> JSONArray = { JSONArray() },
    private val storage: Storage = Storage.Memory()
) : HelperHost {
    /** Persistent localStorage table for card frames. */
    interface Storage {
        fun read(): JSONObject
        fun write(values: JSONObject)

        class Memory : Storage {
            private var values = JSONObject()
            override fun read() = JSONObject(values.toString())
            override fun write(values: JSONObject) { this.values = JSONObject(values.toString()) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val emitters = CopyOnWriteArrayList<Pair<Any, (String) -> Unit>>()
    private val lock = Any()

    val buttons = mutableStateListOf<RpScriptButton>()
    val revisions = mutableStateMapOf<Long, Int>()

    fun revision(messageId: Long): Int = revisions[messageId] ?: 0

    fun touch(messageId: Long) {
        main.post { revisions[messageId] = (revisions[messageId] ?: 0) + 1 }
    }

    /** Seeds the button bar from the enabled scripts' declared buttons (JSR `button.buttons`). */
    fun setScripts(scripts: List<RpHelperScript>) {
        val declared = scripts.filter { it.enabled }.flatMap { it.buttons }.filter { it.visible }
        main.post {
            buttons.clear()
            buttons.addAll(declared)
        }
    }

    fun clickButton(button: RpScriptButton) = emitEvent(button.eventId)

    /** Pushes a tavern event to every frame. Args are JSON-encodable values. */
    fun emitEvent(name: String, vararg args: Any?) {
        val payload = JSONObject().put("t", "e").put("name", name).put("args", JSONArray(args.map(::wrap)))
        deliver(payload.toString())
    }

    override fun broadcast(json: String) {
        val event = runCatching { JSONObject(json) }.getOrNull() ?: return
        val name = event.optString("type").takeIf { it.isNotBlank() } ?: return
        val payload = JSONObject().put("t", "e").put("name", name)
            .put("args", event.optJSONArray("args") ?: JSONArray()).put("from", event.optString("from"))
        deliver(payload.toString())
    }

    override fun addEmitter(emit: (String) -> Unit): Any {
        val token = Any()
        emitters.add(token to emit)
        return token
    }

    override fun removeEmitter(token: Any) {
        emitters.removeAll { it.first === token }
    }

    private fun deliver(payload: String) {
        val js = helperDeliverJs(payload)
        main.post { emitters.forEach { (_, emit) -> runCatching { emit(js) } } }
    }

    override fun rpc(json: String): String = runCatching {
        dispatch(JSONObject(json)).toString()
    }.getOrElse { JSONObject().put("ok", false).put("error", it.message.orEmpty()).toString() }

    override fun rpcAsync(json: String, reply: (String) -> Unit) {
        val request = runCatching { JSONObject(json) }.getOrNull()
        if (request == null) {
            reply(JSONObject().put("error", "bad request").toString())
            return
        }
        scope.launch {
            val result = runCatching { dispatchAsync(request) }
            reply(
                result.fold(
                    onSuccess = { JSONObject().put("result", it ?: JSONObject.NULL) },
                    onFailure = { JSONObject().put("error", it.message ?: "失败") }
                ).toString()
            )
        }
    }

    private suspend fun dispatchAsync(req: JSONObject): Any? = when (req.optString("op")) {
        "slash" -> RpSlashCommands.run(req.optString("command"), RpSlashEnv(actions, variables, macros))
        "generate" -> actions.generate(
            req.optJSONObject("config")?.let { config ->
                config.optString("user_input").ifBlank { config.optString("prompt") }.ifBlank {
                    config.optJSONArray("ordered_prompts")?.let(::flattenPrompts).orEmpty()
                }
            }.orEmpty(),
            (req.optJSONObject("config") ?: JSONObject()).put("raw", req.optBoolean("raw"))
        )
        "chat.set" -> { onMain { setMessages(req.optJSONArray("items") ?: JSONArray()) }; true }
        "chat.create" -> {
            val items = req.optJSONArray("items") ?: JSONArray()
            val pairs = (0 until items.length()).mapNotNull { i ->
                items.optJSONObject(i)?.let { it.optString("role", "assistant") to it.optString("message") }
            }
            val before = if (req.isNull("insert_before")) null else req.optInt("insert_before")
            onMain { actions.createMessages(pairs, before) }
            true
        }
        "chat.delete" -> {
            val ids = req.optJSONArray("ids") ?: JSONArray()
            val indices = (0 until ids.length()).map { ids.optInt(it) }
            onMain { actions.deleteMessages(indices) }
            true
        }
        else -> JSONObject(dispatch(req).toString())
    }

    private fun dispatch(req: JSONObject): JSONObject = when (req.optString("op")) {
        "vars.get" -> JSONObject().put("variables", readScope(req.optJSONObject("option")))
        "vars.replace" -> { writeScope(req.optJSONObject("option"), req.optJSONObject("variables") ?: JSONObject()); ok() }
        "vars.all" -> JSONObject().put("variables", allVariables(if (req.isNull("message_id")) null else req.optInt("message_id"), req.optString("script_id")))
        "chat.get" -> JSONObject().put("messages", chatGet(req))
        "chat.lastId" -> JSONObject().put("id", actions.messages().lastIndex)
        "storage.get" -> {
            val values = storage.read()
            val key = req.optString("key")
            JSONObject().put("value", if (values.has(key)) values.optString(key) else JSONObject.NULL)
        }
        "storage.all" -> JSONObject().put("values", storage.read())
        "storage.set" -> synchronized(lock) { storage.write(storage.read().put(req.optString("key"), req.optString("value"))); ok() }
        "storage.remove" -> synchronized(lock) { storage.write(storage.read().apply { remove(req.optString("key")) }); ok() }
        "storage.clear" -> { storage.write(JSONObject()); ok() }
        "macros" -> JSONObject().put("text", macros(req.optString("text")))
        "context" -> actions.context()
        "char.get" -> JSONObject().put("card", card())
        "world.entries" -> JSONObject().put("entries", worldEntries())
        "toast" -> { main.post { actions.toast(req.optString("text")) }; ok() }
        "buttons.get" -> JSONObject().put("buttons", JSONArray(buttonsOf(req.optString("script_id"))))
        "buttons.set" -> { setButtons(req.optString("script_id"), req.optJSONArray("buttons") ?: JSONArray()); ok() }
        "mvu.parse" -> {
            val floor = req.optJSONObject("old") ?: JSONObject()
            val changed = RpMvu.applyTo(floor, req.optString("message"))
            JSONObject().put("changed", changed).put("data", floor)
        }
        else -> ok()
    }

    /* ---- variables ---- */

    private data class Address(val scope: RpVarScope, val messageId: Long?, val swipeId: Int, val scriptId: String?)

    private fun address(option: JSONObject?): Address? {
        val type = option?.optString("type", "chat") ?: "chat"
        return when (type) {
            "global" -> Address(RpVarScope.Global, null, 0, null)
            "character" -> Address(RpVarScope.Character, null, 0, null)
            "script" -> Address(RpVarScope.Script, null, 0, option?.optString("script_id").orEmpty())
            "message" -> {
                val messages = actions.messages()
                val index = resolveIndex(option?.opt("message_id"), messages.size) ?: return null
                val message = messages[index]
                val swipe = if (option?.has("swipe_id") == true && !option.isNull("swipe_id")) option.optInt("swipe_id") else message.swipeId
                Address(RpVarScope.Message, message.id, swipe, null)
            }
            else -> Address(RpVarScope.Chat, null, 0, null)
        }
    }

    private fun readScope(option: JSONObject?): JSONObject {
        val a = address(option) ?: return JSONObject()
        return variables.table(a.scope, a.messageId, a.swipeId, a.scriptId)
    }

    private fun writeScope(option: JSONObject?, table: JSONObject) {
        val a = address(option) ?: return
        synchronized(lock) { variables.replace(a.scope, table, a.messageId, a.swipeId, a.scriptId) }
        a.messageId?.let(::touch)
    }

    /** JSR getAllVariables: global → character → (script) → chat → floors up to the frame's message. */
    private fun allVariables(messageIndex: Int?, scriptId: String): JSONObject {
        val result = JSONObject()
        fun assign(src: JSONObject) = src.keys().forEach { result.put(it, src.opt(it)) }
        assign(variables.table(RpVarScope.Global))
        assign(variables.table(RpVarScope.Character))
        if (messageIndex == null && scriptId.isNotBlank()) assign(variables.table(RpVarScope.Script, scriptId = scriptId))
        assign(variables.table(RpVarScope.Chat))
        if (messageIndex != null) {
            val messages = actions.messages()
            val until = resolveIndex(messageIndex, messages.size) ?: -1
            for (i in 0..until) assign(variables.table(RpVarScope.Message, messages[i].id, messages[i].swipeId))
        }
        return result
    }

    /* ---- chat ---- */

    private fun chatGet(req: JSONObject): JSONArray {
        val messages = actions.messages()
        val range = parseRange(macros(req.optString("range")), messages.size) ?: return JSONArray()
        val role = req.optString("role", "all")
        val hide = req.optString("hide_state", "all")
        val swipes = req.optBoolean("include_swipes")
        val out = JSONArray()
        for (i in range) {
            val m = messages[i]
            if (role != "all" && role != m.role) continue
            if (hide != "all" && (hide == "hidden") != m.hidden) continue
            val floors = m.swipes.indices.map { variables.table(RpVarScope.Message, m.id, it) }
            val row = JSONObject()
                .put("message_id", i).put("name", m.name).put("role", m.role)
                .put("is_hidden", m.hidden).put("swipe_id", m.swipeId)
            if (swipes) {
                row.put("swipes", JSONArray(m.swipes)).put("swipes_data", JSONArray(floors))
                    .put("swipes_info", JSONArray(m.swipes.map { JSONObject() }))
            } else {
                row.put("message", m.content).put("data", floors.getOrElse(m.swipeId) { JSONObject() }).put("extra", JSONObject())
            }
            out.put(row)
        }
        return out
    }

    private fun setMessages(items: JSONArray) {
        val messages = actions.messages()
        val updates = mutableListOf<RpHelperMessageUpdate>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val index = resolveIndex(item.opt("message_id"), messages.size) ?: continue
            val target = messages[index]
            val swipe = if (item.has("swipe_id")) item.optInt("swipe_id") else null
            item.optJSONObject("data")?.let { data ->
                synchronized(lock) { variables.replace(RpVarScope.Message, data, target.id, swipe ?: target.swipeId) }
            }
            updates += RpHelperMessageUpdate(
                index = index,
                content = if (item.has("message")) item.optString("message") else null,
                swipeId = swipe,
                hidden = if (item.has("is_hidden")) item.optBoolean("is_hidden") else null
            )
            touch(target.id)
        }
        if (updates.isNotEmpty()) actions.setMessages(updates)
    }

    /* ---- buttons ---- */

    private fun buttonsOf(scriptId: String): List<JSONObject> =
        buttons.filter { it.scriptId == scriptId }.map { JSONObject().put("name", it.name).put("visible", it.visible) }

    private fun setButtons(scriptId: String, list: JSONArray) {
        val next = (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { name ->
                RpScriptButton(scriptId, name, list.optJSONObject(i)?.optBoolean("visible", true) ?: true)
            }
        }
        main.post {
            buttons.removeAll { it.scriptId == scriptId }
            buttons.addAll(next)
        }
    }

    private suspend fun <T> onMain(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }

    private fun ok() = JSONObject().put("ok", true)

    companion object {
        /** JSR message_id: non-negative index, or negative counting from the end (-1 = last). */
        fun resolveIndex(raw: Any?, size: Int): Int? {
            if (size == 0) return null
            val value = when (raw) {
                null, JSONObject.NULL, "latest" -> -1
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull() ?: return null
                else -> return null
            }
            val index = if (value < 0) size + value else value
            return index.takeIf { it in 0 until size }
        }

        /** ST string_to_range: "n", "-n", "a-b" (negative parts allowed), clamped to the chat. */
        fun parseRange(raw: String, size: Int): IntRange? {
            if (size == 0) return null
            val text = raw.trim()
            text.toIntOrNull()?.let { n -> return resolveIndex(n, size)?.let { it..it } }
            val match = Regex("""^(-?\d+)\s*-\s*(-?\d+)$""").find(text) ?: return null
            fun at(n: Int) = if (n < 0) size + n else n
            val start = at(match.groupValues[1].toInt()).coerceAtLeast(0)
            val end = at(match.groupValues[2].toInt()).coerceAtMost(size - 1)
            return if (start > end) null else start..end
        }

        private fun wrap(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is Number, is Boolean, is String, is JSONObject, is JSONArray -> value
            is Map<*, *> -> JSONObject(value)
            is Collection<*> -> JSONArray(value)
            else -> value.toString()
        }

        private fun flattenPrompts(prompts: JSONArray): String = (0 until prompts.length()).mapNotNull { i ->
            when (val item = prompts.opt(i)) {
                is JSONObject -> item.optString("content").takeIf { it.isNotBlank() }
                is String -> null
                else -> null
            }
        }.joinToString("\n\n")
    }
}

