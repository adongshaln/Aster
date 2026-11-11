package com.adong.adchat.data.rp

import org.json.JSONArray
import org.json.JSONObject

/*
 * Shared contracts between the RP prompt engine, the tavern_helper frontend runtime and the session
 * model. Keep these types small and stable; each track owns its own implementation files.
 */

/** SillyTavern generation types; world-info `triggers` and preset `injection_trigger` filter on these. */
enum class RpGenerationType(val wire: String) {
    Normal("normal"), Continue("continue"), Impersonate("impersonate"),
    Swipe("swipe"), Regenerate("regenerate"), Quiet("quiet")
}

/** One chat message as the prompt engine sees it: the active swipe, macros not yet expanded. */
data class RpPromptMessage(
    val id: Long,
    val role: String,
    val content: String,
    val name: String = ""
)

/** power_user.persona_description_positions. */
enum class RpPersonaPosition { InPrompt, TopAuthorsNote, BottomAuthorsNote, AtDepth, None }

/** Resolved persona for one request ({{user}} name + description and where it is injected). */
data class RpPersonaContext(
    val name: String = "",
    val description: String = "",
    val position: RpPersonaPosition = RpPersonaPosition.InPrompt,
    val depth: Int = 2,
    val role: String = "system"
)

/** extension_prompt_types: 0 = after main prompt (IN_PROMPT), 1 = in chat at depth, 2 = before main. */
enum class RpNotePosition { AfterMain, InChat, BeforeMain }

/** Author's Note (chat_metadata note_*). interval 0 disables; 1 = every turn. */
data class RpAuthorsNote(
    val text: String = "",
    val position: RpNotePosition = RpNotePosition.InChat,
    val depth: Int = 4,
    val interval: Int = 1,
    val role: String = "system"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text).put("position", position.name).put("depth", depth)
        .put("interval", interval).put("role", role)

    companion object {
        fun fromJson(obj: JSONObject?): RpAuthorsNote = if (obj == null) RpAuthorsNote() else RpAuthorsNote(
            text = obj.optString("text"),
            position = runCatching { RpNotePosition.valueOf(obj.optString("position")) }.getOrDefault(RpNotePosition.InChat),
            depth = obj.optInt("depth", 4).coerceIn(0, 1000),
            interval = obj.optInt("interval", 1).coerceAtLeast(0),
            role = obj.optString("role", "system").ifBlank { "system" }
        )
    }
}

/** TavernHelper / SillyTavern variable scopes. Chat == chat_metadata.variables ({{getvar}}). */
enum class RpVarScope { Global, Character, Chat, Message, Script }

/**
 * Variable tables shared by macros ({{getvar}}/{{setvar}}/{{get_message_variable}}), MVU and the
 * tavern_helper JS API. Message floors are keyed by the stable [RpPromptMessage.id] and swipe index,
 * never by list position, so deleting a message never shifts other floors.
 *
 * [table] returns a defensive copy; mutate it and write back with [replace].
 */
interface RpVariableStore {
    fun table(scope: RpVarScope, messageId: Long? = null, swipeId: Int = 0, scriptId: String? = null): JSONObject
    fun replace(scope: RpVarScope, table: JSONObject, messageId: Long? = null, swipeId: Int = 0, scriptId: String? = null)
}

/** Pure in-memory store for tests and for sessions without a persisted helper runtime. */
class InMemoryRpVariableStore : RpVariableStore {
    private val tables = mutableMapOf<String, JSONObject>()

    private fun key(scope: RpVarScope, messageId: Long?, swipeId: Int, scriptId: String?) = when (scope) {
        RpVarScope.Message -> "message:${messageId ?: -1}:$swipeId"
        RpVarScope.Script -> "script:${scriptId.orEmpty()}"
        else -> scope.name
    }

    override fun table(scope: RpVarScope, messageId: Long?, swipeId: Int, scriptId: String?): JSONObject =
        JSONObject(tables[key(scope, messageId, swipeId, scriptId)]?.toString() ?: "{}")

    override fun replace(scope: RpVarScope, table: JSONObject, messageId: Long?, swipeId: Int, scriptId: String?) {
        tables[key(scope, messageId, swipeId, scriptId)] = JSONObject(table.toString())
    }
}

/** Dotted/bracket path helpers shared by macros, MVU and the helper runtime (lodash _.get/_.set subset). */
object RpVarPath {
    fun parts(path: String): List<String> =
        Regex("""[^.\[\]]+""").findAll(path.replace("\"", "").replace("'", "")).map { it.value }.toList()

    fun get(root: Any?, path: String): Any? {
        var node: Any? = root
        for (part in parts(path)) {
            node = when (node) {
                is JSONObject -> if (node.has(part)) node.opt(part) else return null
                is JSONArray -> part.toIntOrNull()?.let { if (it in 0 until node.length()) node.opt(it) else null } ?: return null
                else -> return null
            }
        }
        return node
    }

    fun set(root: JSONObject, path: String, value: Any?) {
        val keys = parts(path)
        if (keys.isEmpty()) return
        var node: Any = root
        keys.dropLast(1).forEachIndexed { i, part ->
            val nextIsIndex = keys[i + 1].toIntOrNull() != null
            node = when (val current = node) {
                is JSONObject -> current.opt(part)?.takeIf { it is JSONObject || it is JSONArray }
                    ?: (if (nextIsIndex) JSONArray() else JSONObject()).also { current.put(part, it) }
                is JSONArray -> {
                    val index = part.toIntOrNull() ?: return
                    current.opt(index)?.takeIf { it is JSONObject || it is JSONArray }
                        ?: (if (nextIsIndex) JSONArray() else JSONObject()).also { current.put(index, it) }
                }
                else -> return
            }
        }
        when (val target = node) {
            is JSONObject -> target.put(keys.last(), value ?: JSONObject.NULL)
            is JSONArray -> keys.last().toIntOrNull()?.let { target.put(it, value ?: JSONObject.NULL) }
        }
    }

    fun unset(root: JSONObject, path: String): Boolean {
        val keys = parts(path)
        if (keys.isEmpty()) return false
        val parent = if (keys.size == 1) root else get(root, keys.dropLast(1).joinToString("."))
        return when (parent) {
            is JSONObject -> parent.remove(keys.last()) != null
            is JSONArray -> keys.last().toIntOrNull()?.takeIf { it in 0 until parent.length() }?.let { parent.remove(it); true } ?: false
            else -> false
        }
    }
}

/* ---------------- Prompt engine contract (track A implements, integration calls) ---------------- */

/**
 * Everything needed to assemble one RP request, SillyTavern Chat Completion style. [messages] is the
 * full chat (active swipes, hidden messages already removed), oldest first. For [RpGenerationType.Swipe]
 * / [RpGenerationType.Regenerate] the caller has already dropped the reply being replaced; for
 * [RpGenerationType.Continue] the last message is the assistant reply being continued.
 */
data class RpPromptRequest(
    val card: com.adong.adchat.data.story.StoryCharacterCard,
    val messages: List<RpPromptMessage>,
    val persona: RpPersonaContext,
    val authorsNote: RpAuthorsNote = RpAuthorsNote(),
    val preset: com.adong.adchat.data.TavernPreset? = null,
    val regexEnabled: Boolean = true,
    val type: RpGenerationType = RpGenerationType.Normal,
    val variables: RpVariableStore = InMemoryRpVariableStore(),
    /** Opaque engine state persisted on the session (world-info sticky/cooldown). Never mutated. */
    val timedState: JSONObject = JSONObject(),
    val memorySummary: String = "",
    /** Messages up to and including this id are covered by [memorySummary] and left out. */
    val coveredUntilId: Long? = null,
    /** Input token budget (ModelContextLimits.inputTokens); null = no local trimming. */
    val inputBudgetTokens: Int? = null,
    val modelName: String = "",
    /** Extra instruction appended last (impersonate / quiet /gen prompt). */
    val quietPrompt: String = "",
    val random: kotlin.random.Random = kotlin.random.Random.Default,
    /** Merged active world books (chat/persona/card/global); null = the card's embedded book. */
    val worldBook: com.adong.adchat.data.story.StoryWorldBook? = null
)

data class RpPromptResult(
    val systemPrompt: String,
    /** Final ordered messages, already budget-trimmed; send with trimHistory = false. */
    val messages: List<com.adong.adchat.data.ChatMessage>,
    val generationOptions: com.adong.adchat.data.ChatGenerationOptions,
    /** Updated timed world-info state to persist back on the session. */
    val timedState: JSONObject,
    /** Comments/names of activated world-info entries, for a debug view. */
    val activatedWorldEntries: List<String> = emptyList(),
    val omittedMessages: Int = 0
)

/* ---------------- Frontend host contract (track B consumes, integration implements) ---------------- */

/** A chat floor as the tavern_helper API exposes it (getChatMessages). [index] is message_id. */
data class RpHelperMessage(
    val index: Int,
    val id: Long,
    val role: String,
    val name: String,
    val content: String,
    val swipes: List<String>,
    val swipeId: Int,
    val hidden: Boolean
)

/** setChatMessages item: null fields are left unchanged. */
data class RpHelperMessageUpdate(
    val index: Int,
    val content: String? = null,
    val swipeId: Int? = null,
    val hidden: Boolean? = null
)

/**
 * Session-side operations the tavern_helper runtime and slash commands call into. Implemented by the
 * RP ViewModel. Non-suspend members are invoked on the main thread; suspend members may run long
 * (they return the generated text, or "" on failure).
 */
interface RpHelperActions {
    fun messages(): List<RpHelperMessage>
    fun setMessages(updates: List<RpHelperMessageUpdate>)
    /** Inserts messages (role, content) before [insertBefore] (null = append). */
    fun createMessages(items: List<Pair<String, String>>, insertBefore: Int?)
    fun deleteMessages(indices: List<Int>)
    /** Appends a user message without generating (/send). */
    fun sendUser(text: String)
    /** Appends a message with an explicit role/name (/sendas, /sys, /comment). */
    fun sendAs(role: String, name: String, text: String)
    /** Starts a normal generation from the current chat (/trigger). */
    fun trigger()
    /** Replaces the composer draft (/setinput). */
    fun setInput(text: String)
    fun toast(text: String)
    /** SillyTavern.getContext()-like snapshot: name1, name2, characterId, chatId, … */
    fun context(): JSONObject
    /** Quiet generation (/gen, generate(), generateRaw()); never written into the chat. */
    suspend fun generate(prompt: String, options: JSONObject): String
}

/* ---------------- World info contract (track A1 implements, A2 prompt assembly calls) ---------------- */

data class RpWorldScanInput(
    val book: com.adong.adchat.data.story.StoryWorldBook,
    /** Chat texts newest first, already "Name: message" prefixed when include_names applies. */
    val chatNewestFirst: List<String>,
    val characterName: String,
    val personaDescription: String = "",
    val characterDescription: String = "",
    val characterPersonality: String = "",
    val characterDepthPrompt: String = "",
    val scenario: String = "",
    val creatorNotes: String = "",
    val type: RpGenerationType = RpGenerationType.Normal,
    /** Chat length (message count) used for timed effects / delay. */
    val chatLength: Int,
    val budgetTokens: Int,
    val timedState: JSONObject = JSONObject(),
    /** Macro expansion applied to keys and content. */
    val macros: (String) -> String = { it },
    val random: kotlin.random.Random = kotlin.random.Random.Default,
    val dryRun: Boolean = false
)

data class RpWorldDepthEntry(val depth: Int, val role: String, val content: String, val order: Int)

data class RpWorldScanResult(
    val before: String = "",
    val after: String = "",
    val anTop: List<String> = emptyList(),
    val anBottom: List<String> = emptyList(),
    val emTop: List<String> = emptyList(),
    val emBottom: List<String> = emptyList(),
    val depth: List<RpWorldDepthEntry> = emptyList(),
    val outlets: Map<String, String> = emptyMap(),
    val activated: List<String> = emptyList(),
    val timedState: JSONObject = JSONObject()
)

/* ---------------- Slash command contract (track B1 implements, B2 host calls) ---------------- */

/** Environment for /commands; variables use the same store as macros. */
class RpSlashEnv(
    val actions: RpHelperActions,
    val variables: RpVariableStore,
    val macros: (String) -> String
)
