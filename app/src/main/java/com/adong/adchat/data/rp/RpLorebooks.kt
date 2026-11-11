package com.adong.adchat.data.rp

import android.content.Context
import com.adong.adchat.data.story.StoryWorldBook
import com.adong.adchat.data.story.StoryWorldBookEntry
import com.adong.adchat.data.story.WorldLogic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Where a library book came from; card books are kept so the user can reuse them on other chats. */
enum class RpLorebookSource { Imported, Card, Created }

/** A named world book in the library. [book] keeps the raw entry data (macros unexpanded). */
data class RpLorebook(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val book: StoryWorldBook,
    val source: RpLorebookSource = RpLorebookSource.Imported,
    /** Card key ([RpCardKey]) for card-sourced books, so re-imports update instead of duplicating. */
    val cardKey: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("book", JSONObject(book.toJson()))
        .put("source", source.name).put("card_key", cardKey).put("updated_at", updatedAt)

    companion object {
        fun fromJson(obj: JSONObject): RpLorebook = RpLorebook(
            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = obj.optString("name"),
            book = StoryWorldBook.fromJson(obj.optJSONObject("book") ?: JSONObject()),
            source = runCatching { RpLorebookSource.valueOf(obj.optString("source")) }.getOrDefault(RpLorebookSource.Imported),
            cardKey = obj.optString("card_key"),
            updatedAt = obj.optLong("updated_at")
        )
    }
}

/** SillyTavern world_info_character_strategy. */
enum class RpWorldStrategy { Evenly, CharacterFirst, GlobalFirst }

/**
 * World-book library + global activation (SillyTavern `selected_world_info`). One JSON file per book
 * under `filesDir/rp/lorebooks/`, plus `settings.json` for the globally enabled ids and strategy.
 * Chat books are bound per session ([RpSession.chatLorebookIds]); the card's embedded book is saved
 * here as a [RpLorebookSource.Card] book and toggled per session ([RpSession.cardBookEnabled]).
 */
class RpLorebookStore internal constructor(private val dir: File) {
    constructor(context: Context) : this(File(File(context.applicationContext.filesDir, "rp"), "lorebooks"))

    private val settingsFile = File(dir, "settings.json")
    @Volatile private var cache: List<RpLorebook>? = null

    @Synchronized
    fun list(): List<RpLorebook> = cache ?: run {
        dir.mkdirs()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != settingsFile.name }.orEmpty()
            .mapNotNull { runCatching { RpLorebook.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
            .also { cache = it }
    }

    fun get(id: String): RpLorebook? = list().firstOrNull { it.id == id }

    @Synchronized
    fun save(book: RpLorebook): RpLorebook {
        dir.mkdirs()
        val stamped = book.copy(updatedAt = System.currentTimeMillis())
        write(File(dir, RpStore.safeName(stamped.id) + ".json"), stamped.toJson().toString())
        cache = (list().filterNot { it.id == stamped.id } + stamped).sortedByDescending { it.updatedAt }
        return stamped
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, RpStore.safeName(id) + ".json").delete()
        cache = list().filterNot { it.id == id }
        setGlobal(id, false)
    }

    /** Saves (or refreshes) the card's embedded book in the library; returns its id, or null if empty. */
    fun saveCardBook(cardKey: String, cardName: String, book: StoryWorldBook): String? {
        if (book.entries.isEmpty() || cardKey.isBlank()) return null
        val existing = list().firstOrNull { it.source == RpLorebookSource.Card && it.cardKey == cardKey }
        val name = book.name.ifBlank { "$cardName 的世界书" }
        return save((existing ?: RpLorebook(name = name, book = book, source = RpLorebookSource.Card, cardKey = cardKey))
            .copy(name = existing?.name ?: name, book = book)).id
    }

    fun cardBookId(cardKey: String): String? =
        list().firstOrNull { it.source == RpLorebookSource.Card && it.cardKey == cardKey }?.id

    /* ---- global activation ---- */

    private fun settings(): JSONObject = runCatching { JSONObject(settingsFile.readText()) }.getOrDefault(JSONObject())

    fun globalIds(): Set<String> = settings().optJSONArray("global")?.let { a ->
        (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.toSet()
    }.orEmpty()

    @Synchronized
    fun setGlobal(id: String, enabled: Boolean) {
        val ids = globalIds().toMutableSet().apply { if (enabled) add(id) else remove(id) }
        dir.mkdirs()
        write(settingsFile, settings().put("global", JSONArray(ids.toList())).toString())
    }

    fun strategy(): RpWorldStrategy =
        runCatching { RpWorldStrategy.valueOf(settings().optString("strategy")) }.getOrDefault(RpWorldStrategy.CharacterFirst)

    @Synchronized
    fun setStrategy(strategy: RpWorldStrategy) {
        dir.mkdirs()
        write(settingsFile, settings().put("strategy", strategy.name).toString())
    }

    /**
     * Books active for a session in ST scan order: chat → persona → (character, global by strategy).
     * A book listed in several places is used once, at its first position (ST skips duplicates).
     */
    fun activeBooks(session: RpSession, personaLorebook: String? = null): List<StoryWorldBook> {
        val seen = LinkedHashSet<String>()
        fun take(id: String?): StoryWorldBook? = id?.takeIf { seen.add(it) }?.let(::get)?.book
        val chat = session.chatLorebookIds.mapNotNull(::take)
        val persona = listOfNotNull(take(personaLorebook))
        // The card's own book: the library copy (user-edited) when saved, else the embedded one.
        val character = if (!session.cardBookEnabled) emptyList() else {
            val libraryId = cardBookId(session.cardKey)
            listOfNotNull(if (libraryId != null) take(libraryId) else session.config.card.worldBook.takeIf { it.entries.isNotEmpty() })
        }
        val global = globalIds().mapNotNull(::take)
        val rest = when (strategy()) {
            RpWorldStrategy.GlobalFirst -> global + character
            else -> character + global
        }
        return chat + persona + rest
    }

    private fun write(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        /**
         * Merges books into one scan input. Entry uids are re-based per book so timed effects and
         * dedupe never collide; for [RpWorldStrategy.Evenly] character and global entries interleave
         * by order (the engine sorts by insertion order anyway, so plain concatenation suffices).
         */
        fun merge(books: List<StoryWorldBook>): StoryWorldBook {
            if (books.isEmpty()) return StoryWorldBook()
            if (books.size == 1) return books.first()
            val entries = books.flatMapIndexed { bookIndex, book ->
                book.entries.mapIndexed { i, entry ->
                    val uid = if (entry.uid >= 0) entry.uid else i
                    entry.copy(uid = bookIndex * 100_000 + uid)
                }
            }
            val first = books.first()
            return first.copy(
                entries = entries,
                tokenBudget = books.mapNotNull { it.tokenBudget }.maxOrNull(),
                bookScanDepth = books.mapNotNull { it.bookScanDepth }.maxOrNull(),
                recursiveScanning = books.mapNotNull { it.recursiveScanning }.takeIf { it.isNotEmpty() }?.any { it }
            )
        }

        /**
         * Parses a world book file: SillyTavern native lorebook (`entries` map with key/keysecondary/
         * order/disable…) or a character_book (V2 entries array, as embedded in cards).
         */
        fun parseFile(raw: String, fallbackName: String): RpLorebook {
            val root = JSONObject(raw.trim().removePrefix("﻿"))
            // A whole character card: take its embedded book.
            root.optJSONObject("data")?.optJSONObject("character_book")?.let { return parseFile(it.toString(), fallbackName) }
            val entriesNode = root.opt("entries")
            val native = entriesNode is JSONObject || (entriesNode is JSONArray && (0 until entriesNode.length())
                .any { entriesNode.optJSONObject(it)?.has("keysecondary") == true || entriesNode.optJSONObject(it)?.has("disable") == true })
            val book = if (native) parseNative(root) else parseCharacterBook(root)
            require(book.entries.isNotEmpty()) { "文件里没有世界书条目" }
            val name = root.optString("name").ifBlank { fallbackName.substringBeforeLast('.') }.ifBlank { "导入的世界书" }
            return RpLorebook(name = name, book = book.copy(name = name))
        }

        private fun parseCharacterBook(root: JSONObject): StoryWorldBook {
            val wrapper = JSONObject().put("data", JSONObject().put("name", "x").put("description", "x").put("character_book", root))
            return com.adong.adchat.data.story.CharacterCardFile.parse(wrapper.toString().toByteArray()).rawCard.worldBook
        }

        /** SillyTavern world-info file (world-info.js entry format). */
        private fun parseNative(root: JSONObject): StoryWorldBook {
            val node = root.opt("entries")
            val items: List<JSONObject> = when (node) {
                is JSONObject -> node.keys().asSequence().mapNotNull { node.optJSONObject(it) }.toList()
                is JSONArray -> (0 until node.length()).mapNotNull { node.optJSONObject(it) }
                else -> emptyList()
            }.sortedBy { it.optInt("displayIndex", it.optInt("uid")) }
            val entries = items.mapIndexed { index, e ->
                val logic = e.optInt("selectiveLogic", 0)
                val position = e.optInt("position", 0)
                StoryWorldBookEntry(
                    keys = e.optJSONArray("key").strings(),
                    secondaryKeys = e.optJSONArray("keysecondary").strings(),
                    logic = WorldLogic.fromSt(logic),
                    content = e.optString("content"),
                    enabled = !e.optBoolean("disable", false),
                    constant = e.optBoolean("constant", false),
                    insertionOrder = e.optInt("order", 100),
                    beforeChar = position == 0,
                    caseSensitive = e.optBoolean("caseSensitive", false),
                    comment = e.optString("comment"),
                    uid = e.optInt("uid", index),
                    position = position,
                    depth = e.optInt("depth", 4),
                    role = e.optInt("role", 0),
                    probability = e.optInt("probability", 100),
                    useProbability = e.optBoolean("useProbability", true),
                    group = e.optString("group"),
                    groupOverride = e.optBoolean("groupOverride", false),
                    groupWeight = e.optInt("groupWeight", 100),
                    useGroupScoring = e.nullableBool("useGroupScoring"),
                    sticky = e.nullableInt("sticky"),
                    cooldown = e.nullableInt("cooldown"),
                    delay = e.nullableInt("delay"),
                    scanDepth = e.nullableInt("scanDepth"),
                    matchWholeWords = e.nullableBool("matchWholeWords"),
                    caseSensitiveOverride = e.nullableBool("caseSensitive"),
                    excludeRecursion = e.optBoolean("excludeRecursion", false),
                    preventRecursion = e.optBoolean("preventRecursion", false),
                    delayUntilRecursion = when (val d = e.opt("delayUntilRecursion")) {
                        true -> 1
                        is Number -> d.toInt().coerceAtLeast(0)
                        else -> 0
                    },
                    ignoreBudget = e.optBoolean("ignoreBudget", false),
                    matchPersonaDescription = e.optBoolean("matchPersonaDescription", false),
                    matchCharacterDescription = e.optBoolean("matchCharacterDescription", false),
                    matchCharacterPersonality = e.optBoolean("matchCharacterPersonality", false),
                    matchCharacterDepthPrompt = e.optBoolean("matchCharacterDepthPrompt", false),
                    matchScenario = e.optBoolean("matchScenario", false),
                    matchCreatorNotes = e.optBoolean("matchCreatorNotes", false),
                    outletName = e.optString("outletName"),
                    triggers = e.optJSONArray("triggers").strings(),
                    selective = e.optBoolean("selective", true),
                    selectiveLogic = logic,
                    automationId = e.optString("automationId")
                )
            }
            return StoryWorldBook(entries = entries, recursive = false, recursiveScanning = null)
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotEmpty) }

        private fun JSONObject.nullableInt(key: String): Int? =
            if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toInt()

        private fun JSONObject.nullableBool(key: String): Boolean? =
            if (!has(key) || isNull(key)) null else opt(key) as? Boolean
    }
}
