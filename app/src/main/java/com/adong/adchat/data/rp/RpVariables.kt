package com.adong.adchat.data.rp

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persistent [RpVariableStore] for one session.
 * - Chat, Message floors and Script tables: `<root>/vars/<sessionId>.json`
 * - Global: `<root>/vars/global.json`, shared by every session
 * - Character: `<root>/vars/character/<cardKey>.json`, shared by sessions of the same card
 *
 * Message floors are keyed `"<messageId>:<swipeId>"`, so deleting or inserting messages never shifts
 * other floors; callers drop floors of deleted messages via [dropFloors]. Writes are write-through and
 * atomic. Thread-safe; the helper runtime calls it off the main thread.
 */
class RpSessionVariables internal constructor(
    private val root: File,
    val sessionId: String,
    private val characterKey: String
) : RpVariableStore {
    constructor(context: Context, sessionId: String, characterKey: String) :
        this(File(context.applicationContext.filesDir, "rp"), sessionId, characterKey)

    private val dir = File(root, "vars")
    private val sessionFile = File(dir, RpStore.safeName(sessionId) + ".json")
    private val globalFile = File(dir, "global.json")
    private val characterFile = File(dir, "character/" + RpStore.safeName(characterKey.ifBlank { "none" }) + ".json")

    private val session: JSONObject = read(sessionFile)
    private val global: JSONObject = shared(globalFile)
    private val character: JSONObject = shared(characterFile)

    override fun table(scope: RpVarScope, messageId: Long?, swipeId: Int, scriptId: String?): JSONObject = synchronized(LOCK) {
        val source = when (scope) {
            RpVarScope.Global -> global
            RpVarScope.Character -> character
            RpVarScope.Chat -> session.optJSONObject(CHAT)
            RpVarScope.Message -> session.optJSONObject(FLOORS)?.optJSONObject(floorKey(messageId, swipeId))
            RpVarScope.Script -> session.optJSONObject(SCRIPTS)?.optJSONObject(scriptId.orEmpty())
        }
        JSONObject(source?.toString() ?: "{}")
    }

    override fun replace(scope: RpVarScope, table: JSONObject, messageId: Long?, swipeId: Int, scriptId: String?) = synchronized(LOCK) {
        val copy = JSONObject(table.toString())
        when (scope) {
            RpVarScope.Global -> { clear(global); merge(global, copy); write(globalFile, global) }
            RpVarScope.Character -> { clear(character); merge(character, copy); write(characterFile, character) }
            RpVarScope.Chat -> { session.put(CHAT, copy); write(sessionFile, session) }
            RpVarScope.Message -> {
                if (messageId == null) return@synchronized
                child(session, FLOORS).put(floorKey(messageId, swipeId), copy)
                write(sessionFile, session)
            }
            RpVarScope.Script -> { child(session, SCRIPTS).put(scriptId.orEmpty(), copy); write(sessionFile, session) }
        }
    }

    /** True when the floor has its own table (ST: `variables[swipe_id]` is an object). */
    fun hasFloor(messageId: Long, swipeId: Int): Boolean = synchronized(LOCK) {
        session.optJSONObject(FLOORS)?.has(floorKey(messageId, swipeId)) == true
    }

    /**
     * TavernHelper message-iframe view: global → character → chat → every floor up to and including
     * [untilMessageId] (active swipe), deep-merged in chat order. Hidden floors still contribute.
     */
    fun merged(messages: List<RpMessage>, untilMessageId: Long? = null): JSONObject = synchronized(LOCK) {
        val result = JSONObject()
        deepMerge(result, global)
        deepMerge(result, character)
        session.optJSONObject(CHAT)?.let { deepMerge(result, it) }
        val floors = session.optJSONObject(FLOORS)
        if (floors != null && untilMessageId != null) {
            for (message in messages) {
                floors.optJSONObject(floorKey(message.id, message.currentIndex))?.let { deepMerge(result, it) }
                if (message.id == untilMessageId) break
            }
        }
        result
    }

    /** Latest floor (message id, swipe) carrying its own table, scanning from the end; MVU getLastValidVariable. */
    fun latestFloor(messages: List<RpMessage>, predicate: (JSONObject) -> Boolean = { true }): Pair<Long, Int>? = synchronized(LOCK) {
        val floors = session.optJSONObject(FLOORS) ?: return@synchronized null
        messages.asReversed().firstNotNullOfOrNull { message ->
            floors.optJSONObject(floorKey(message.id, message.currentIndex))?.takeIf(predicate)?.let { message.id to message.currentIndex }
        }
    }

    /** Drops every swipe floor of the given messages (after delete / truncate). */
    fun dropFloors(messageIds: Collection<Long>) = synchronized(LOCK) {
        val floors = session.optJSONObject(FLOORS) ?: return@synchronized
        val prefixes = messageIds.map { "$it:" }
        val doomed = floors.keys().asSequence().filter { key -> prefixes.any { key.startsWith(it) } }.toList()
        if (doomed.isEmpty()) return@synchronized
        doomed.forEach(floors::remove)
        write(sessionFile, session)
    }

    /** Re-keys swipe floors after [RpChatOps.deleteSwipe] removed swipe [deleted] of [messageId]. */
    fun onSwipeDeleted(messageId: Long, deleted: Int) = synchronized(LOCK) {
        val floors = session.optJSONObject(FLOORS) ?: return@synchronized
        val prefix = "$messageId:"
        val entries = floors.keys().asSequence().filter { it.startsWith(prefix) }.toList()
            .mapNotNull { key -> key.removePrefix(prefix).toIntOrNull()?.let { it to floors.optJSONObject(key) } }
        if (entries.isEmpty()) return@synchronized
        entries.forEach { (swipe, _) -> floors.remove(floorKey(messageId, swipe)) }
        entries.forEach { (swipe, table) ->
            RpChatOps.swipeIndexAfterDelete(deleted, swipe)?.let { floors.put(floorKey(messageId, it), table) }
        }
        write(sessionFile, session)
    }

    /** Copies chat + floors (+ scripts, localStorage) into another session, e.g. for a branch. */
    fun copyTo(target: RpSessionVariables, messageIds: Collection<Long>) {
        val snapshot = synchronized(LOCK) { JSONObject(session.toString()) }
        val keep = messageIds.map { "$it:" }
        snapshot.optJSONObject(FLOORS)?.let { floors ->
            floors.keys().asSequence().toList().filterNot { key -> keep.any { key.startsWith(it) } }.forEach(floors::remove)
        }
        synchronized(LOCK) {
            snapshot.keys().forEach { key -> target.session.put(key, snapshot.opt(key)) }
            target.write(target.sessionFile, target.session)
        }
    }

    /** tavern_helper localStorage, scoped per session. */
    fun storage(): JSONObject = synchronized(LOCK) { JSONObject(session.optJSONObject(STORAGE)?.toString() ?: "{}") }

    fun replaceStorage(values: JSONObject) = synchronized(LOCK) {
        session.put(STORAGE, JSONObject(values.toString()))
        write(sessionFile, session)
    }

    /** Deletes this session's file (global and character tables are shared and kept). */
    fun deleteSession() = synchronized(LOCK) {
        clear(session)
        sessionFile.delete()
    }

    /**
     * Best-effort import of the old `filesDir/rp_helper/<id>.json` runtime state (floors keyed by list
     * index) once; the legacy file is renamed afterwards so it is never applied twice.
     */
    fun migrateLegacy(legacyFile: File, messages: List<RpMessage>) = synchronized(LOCK) {
        if (!legacyFile.exists()) return@synchronized
        val state = runCatching { JSONObject(legacyFile.readText()) }.getOrNull()
        if (state != null) {
            state.optJSONObject("global")?.let { if (global.length() == 0) { merge(global, it); write(globalFile, global) } }
            state.optJSONObject("character")?.let { if (character.length() == 0) { merge(character, it); write(characterFile, character) } }
            state.optJSONObject("chat")?.let { if (!session.has(CHAT)) session.put(CHAT, it) }
            state.optJSONObject("storage")?.let { if (!session.has(STORAGE)) session.put(STORAGE, it) }
            state.optJSONObject("message")?.let { old ->
                val floors = child(session, FLOORS)
                old.keys().forEach { key ->
                    val message = key.toIntOrNull()?.let(messages::getOrNull) ?: return@forEach
                    val target = floorKey(message.id, message.currentIndex)
                    if (!floors.has(target)) old.optJSONObject(key)?.let { floors.put(target, it) }
                }
            }
            write(sessionFile, session)
        }
        legacyFile.renameTo(File(legacyFile.parentFile, legacyFile.name + ".migrated"))
    }

    private fun write(file: File, value: JSONObject) {
        runCatching { RpStore.atomicWrite(file, value.toString()) }
    }

    companion object {
        private const val CHAT = "chat"
        private const val FLOORS = "floors"
        private const val SCRIPTS = "scripts"
        private const val STORAGE = "storage"
        // Global/character files are shared across instances; one process-wide lock keeps them coherent.
        private val LOCK = Any()

        fun floorKey(messageId: Long?, swipeId: Int): String = "${messageId ?: -1}:${swipeId.coerceAtLeast(0)}"

        /** Legacy helper-state file for a session, for [migrateLegacy]. */
        fun legacyFile(context: Context, sessionId: String): File = File(context.filesDir, "rp_helper/$sessionId.json")

        private val sharedTables = HashMap<String, JSONObject>()

        private fun shared(file: File): JSONObject = synchronized(LOCK) {
            sharedTables.getOrPut(file.absolutePath) { read(file) }
        }

        private fun read(file: File): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

        private fun child(parent: JSONObject, key: String): JSONObject =
            parent.optJSONObject(key) ?: JSONObject().also { parent.put(key, it) }

        private fun clear(obj: JSONObject) {
            obj.keys().asSequence().toList().forEach(obj::remove)
        }

        private fun merge(dst: JSONObject, src: JSONObject) {
            src.keys().forEach { dst.put(it, src.opt(it)) }
        }

        internal fun deepMerge(dst: JSONObject, src: JSONObject) {
            src.keys().forEach { key ->
                val value = src.opt(key)
                val existing = dst.opt(key)
                if (value is JSONObject && existing is JSONObject) deepMerge(existing, value)
                else dst.put(key, when (value) {
                    is JSONObject -> JSONObject(value.toString())
                    is org.json.JSONArray -> org.json.JSONArray(value.toString())
                    else -> value
                })
            }
        }
    }
}
