package com.adong.adchat.data.rp

import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.TavernCharacterContext
import com.adong.adchat.data.TokenUsage
import com.adong.adchat.data.story.ImportedCharacterCard
import com.adong.adchat.data.story.StoryCharacterCard
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** How the session's character/world context was authored. Both paths fill the same card structure. */
enum class RpSource { CharacterCard, Text }

/**
 * Unified RP setup. Whether imported from a Tavern character card or typed as plain text, the
 * character/world fields live in one [StoryCharacterCard] (stored raw, macros unexpanded) so prompt
 * assembly, world book, memory and generation share a single path.
 *
 * [opening]/[openings]/[openingIndex] and [userPersona] are import sources and legacy fields only:
 * greetings live as swipes of message 0 and the user identity lives in [RpPersona].
 */
data class RpConfig(
    val source: RpSource = RpSource.Text,
    val card: StoryCharacterCard = StoryCharacterCard(name = "", description = "", personality = "", scenario = ""),
    val opening: String = "",
    val userPersona: String = "",
    val presetId: String? = null,
    val openings: List<String> = emptyList(),
    val openingIndex: Int = 0
) {
    /**
     * Greetings in swipe order. The selected [opening] wins over its slot in [openings] when the user
     * edited it in the config panel; a plain-text setup yields its single opening.
     */
    val greetings: List<String>
        get() {
            val edited = opening.takeIf { it.isNotBlank() }
            if (openings.isEmpty()) return listOfNotNull(edited)
            if (edited == null || edited in openings) return openings.filter { it.isNotBlank() }
            return openings.toMutableList().apply {
                this[openingIndex.coerceIn(0, lastIndex)] = edited
            }.filter { it.isNotBlank() }
        }

    fun toCharacterContext(worldBefore: String = "", worldAfter: String = "", userName: String = ""): TavernCharacterContext =
        TavernCharacterContext(
            name = card.macroName,
            description = card.description,
            personality = card.personality,
            scenario = card.scenario,
            persona = userPersona,
            userName = userName,
            mesExample = card.mesExample,
            wiBefore = worldBefore,
            wiAfter = worldAfter
        )

    fun toJson(): JSONObject = JSONObject()
        .put("source", source.name)
        .put("card", JSONObject(card.toJson()))
        .put("opening", opening)
        .put("user_persona", userPersona)
        .put("preset_id", presetId ?: JSONObject.NULL)
        .put("openings", JSONArray(openings))
        .put("opening_index", openingIndex)

    companion object {
        fun fromImported(
            imported: ImportedCharacterCard,
            userPersona: String = "",
            presetId: String? = null
        ): RpConfig {
            val greetings = imported.greetings.ifEmpty { listOf(imported.opening).filter { it.isNotBlank() } }
            return RpConfig(
                source = RpSource.CharacterCard,
                card = imported.card,
                opening = greetings.firstOrNull().orEmpty().ifBlank { imported.opening },
                openings = greetings,
                openingIndex = 0,
                userPersona = userPersona,
                presetId = presetId
            )
        }

        fun fromJson(obj: JSONObject): RpConfig = RpConfig(
            source = runCatching { RpSource.valueOf(obj.optString("source")) }.getOrDefault(RpSource.Text),
            card = StoryCharacterCard.decode(obj.optJSONObject("card") ?: JSONObject()),
            opening = obj.optString("opening"),
            userPersona = obj.optString("user_persona"),
            presetId = obj.optString("preset_id").takeIf { it.isNotBlank() && !obj.isNull("preset_id") },
            openings = obj.optJSONArray("openings")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
            }.orEmpty(),
            openingIndex = obj.optInt("opening_index", 0)
        )
    }
}

/** Stable identity of a character card, used for persona connections and the Character variable scope. */
object RpCardKey {
    fun of(card: StoryCharacterCard): String {
        val name = card.name.trim()
        if (name.isEmpty()) return ""
        val seed = listOf(name, card.creator.trim(), card.creationDate?.toString().orEmpty()).joinToString("\u0000")
        return "card:" + sha256(seed).take(24)
    }
}

/** One generated (or authored) alternative of a message, SillyTavern `swipes[i]` + `swipe_info[i]`. */
data class RpSwipe(
    val content: String,
    val reasoning: String = "",
    val model: String = "",
    val usage: TokenUsage? = null,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val interrupted: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("content", content)
        .put("reasoning", reasoning)
        .put("model", model)
        .put("usage", usage?.let(::encodeRpUsage) ?: JSONObject.NULL)
        .put("started_at", startedAt)
        .put("finished_at", finishedAt)
        .put("interrupted", interrupted)

    companion object {
        fun fromJson(obj: JSONObject): RpSwipe = RpSwipe(
            content = obj.optString("content"),
            reasoning = obj.optString("reasoning"),
            model = obj.optString("model"),
            usage = obj.optJSONObject("usage")?.let(::decodeRpUsage),
            startedAt = obj.optLong("started_at"),
            finishedAt = obj.optLong("finished_at"),
            interrupted = obj.optBoolean("interrupted")
        )
    }
}

/**
 * A chat floor. [id] is stable for the life of the message (variables, memory and helper floors key on
 * it); [swipes] is never empty and [swipeId] selects the active alternative. Hidden messages stay visible
 * in the UI but are left out of prompts and world-info scans. [extra] is treated as immutable.
 */
data class RpMessage(
    val id: Long = RpIds.next(),
    val role: String,
    val name: String = "",
    val swipes: List<RpSwipe> = listOf(RpSwipe("")),
    val swipeId: Int = 0,
    val hidden: Boolean = false,
    val isError: Boolean = false,
    val errorText: String = "",
    val sentAt: Long = System.currentTimeMillis(),
    val extra: JSONObject = JSONObject()
) {
    val safeSwipes: List<RpSwipe> get() = swipes.ifEmpty { listOf(RpSwipe("")) }
    val currentIndex: Int get() = swipeId.coerceIn(0, safeSwipes.lastIndex)
    val current: RpSwipe get() = safeSwipes[currentIndex]
    val content: String get() = current.content
    val swipeCount: Int get() = safeSwipes.size
    val isUser: Boolean get() = role == "user"

    /** Replaces the active swipe. */
    fun withCurrent(swipe: RpSwipe): RpMessage =
        copy(swipes = safeSwipes.toMutableList().apply { this[currentIndex] = swipe }, swipeId = currentIndex)

    fun withContent(text: String): RpMessage = withCurrent(current.copy(content = text))

    fun toChatMessage(profileName: String = ""): ChatMessage = ChatMessage(
        id = id,
        role = role,
        content = if (isError) errorText.ifBlank { content } else content,
        reasoning = current.reasoning,
        isError = isError,
        isInterrupted = current.interrupted,
        profileName = profileName,
        model = current.model,
        usage = current.usage
    )

    fun toPromptMessage(): RpPromptMessage = RpPromptMessage(id = id, role = role, content = content, name = name)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("role", role)
        .put("name", name)
        .put("swipes", JSONArray(safeSwipes.map { it.toJson() }))
        .put("swipe_id", currentIndex)
        .put("hidden", hidden)
        .put("is_error", isError)
        .put("error_text", errorText)
        .put("sent_at", sentAt)
        .put("extra", extra)

    companion object {
        /** Decodes both the swipe format and the legacy single-content [ChatMessage] format. */
        fun fromJson(obj: JSONObject): RpMessage {
            val id = obj.optLong("id", 0L).takeIf { it != 0L } ?: RpIds.next()
            val swipesJson = obj.optJSONArray("swipes")
            if (swipesJson != null && swipesJson.length() > 0) {
                val swipes = (0 until swipesJson.length()).map { i ->
                    swipesJson.optJSONObject(i)?.let(RpSwipe::fromJson) ?: RpSwipe(swipesJson.optString(i))
                }
                return RpMessage(
                    id = id,
                    role = obj.optString("role").ifBlank { "assistant" },
                    name = obj.optString("name"),
                    swipes = swipes,
                    swipeId = obj.optInt("swipe_id", 0).coerceIn(0, swipes.lastIndex),
                    hidden = obj.optBoolean("hidden"),
                    isError = obj.optBoolean("is_error"),
                    errorText = obj.optString("error_text"),
                    sentAt = obj.optLong("sent_at", 0L),
                    extra = obj.optJSONObject("extra") ?: JSONObject()
                )
            }
            val legacy = decodeRpMessage(obj)
            return RpMessage(
                id = id,
                role = legacy.role.ifBlank { "assistant" },
                swipes = listOf(
                    RpSwipe(
                        content = if (legacy.isError) "" else legacy.content,
                        reasoning = legacy.reasoning,
                        model = legacy.model,
                        usage = legacy.usage,
                        interrupted = legacy.isInterrupted || legacy.isStopped
                    )
                ),
                isError = legacy.isError,
                errorText = if (legacy.isError) legacy.content else "",
                sentAt = 0L
            )
        }
    }
}

/** Process-wide monotonic message ids (millisecond-based, never repeats even in tight loops). */
object RpIds {
    private val last = AtomicLong(0)

    fun next(): Long {
        val now = System.currentTimeMillis() * 1000
        return last.updateAndGet { previous -> if (now > previous) now else previous + 1 }
    }

    /** Ensures later ids sort after ids loaded from disk. */
    fun observe(id: Long) {
        last.updateAndGet { previous -> maxOf(previous, id) }
    }
}

/**
 * A single RP session: setup + bound service/model + toggles + conversation. [tainted] mirrors
 * SillyTavern `chat_metadata.tainted` (any generation/edit/delete); while the chat is pristine the
 * greeting swipes cycle and macros in message 0 re-resolve. [metadata] (e.g. `main_chat` for branches)
 * and [timedWorldInfo] are treated as immutable JSON snapshots; copy before changing.
 */
data class RpSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val config: RpConfig = RpConfig(),
    val messages: List<RpMessage> = emptyList(),
    val profileId: String = "",
    val model: String = "",
    val injectionEnabled: Boolean = false,
    val memoryEnabled: Boolean = false,
    val tainted: Boolean = false,
    /** Chat-level persona lock; null follows card connection → default persona. */
    val personaId: String? = null,
    val authorsNote: RpAuthorsNote = RpAuthorsNote(),
    val timedWorldInfo: JSONObject = JSONObject(),
    /** Card regex / tavern_helper script consent; null = not asked yet. */
    val cardRegexAllowed: Boolean? = null,
    val cardScriptsAllowed: Boolean? = null,
    val metadata: JSONObject = JSONObject(),
    /** Chat-bound world books (ST `chat_metadata.world_info`), library ids from [RpLorebookStore]. */
    val chatLorebookIds: List<String> = emptyList(),
    /** Whether the card's own world book takes part in scans for this chat. */
    val cardBookEnabled: Boolean = true,
    /** Drawer organisation, same as chat conversations. */
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val tags: List<String> = emptyList(),
    /** Reasoning effort for this RP (ReasoningPolicy id); blank = the service profile's setting. */
    val reasoningEffort: String = ""
) {
    /** SillyTavern PRISTINE: only the greeting and nothing generated/edited since. */
    val pristine: Boolean get() = !tainted && messages.size <= 1

    val cardKey: String get() = RpCardKey.of(config.card)

    /** Branch parent id (`chat_metadata.main_chat`). */
    val mainChat: String? get() = metadata.optString(META_MAIN_CHAT).takeIf { it.isNotBlank() }

    /** Messages as the prompt engine sees them: active swipe, hidden and error floors removed. */
    fun promptMessages(): List<RpPromptMessage> =
        messages.filter { !it.hidden && !it.isError }.map(RpMessage::toPromptMessage)

    fun chatMessages(): List<ChatMessage> = messages.map { it.toChatMessage() }

    fun indexOf(messageId: Long): Int = messages.indexOfFirst { it.id == messageId }

    fun toJson(): JSONObject = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("id", id)
        .put("title", title)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .put("config", config.toJson())
        .put("messages", JSONArray(messages.map { it.toJson() }))
        .put("profile_id", profileId)
        .put("model", model)
        .put("injection_enabled", injectionEnabled)
        .put("memory_enabled", memoryEnabled)
        .put("tainted", tainted)
        .put("persona_id", personaId ?: JSONObject.NULL)
        .put("authors_note", authorsNote.toJson())
        .put("timed_world_info", timedWorldInfo)
        .put("card_regex_allowed", cardRegexAllowed ?: JSONObject.NULL)
        .put("card_scripts_allowed", cardScriptsAllowed ?: JSONObject.NULL)
        .put("metadata", metadata)
        .put("chat_lorebooks", JSONArray(chatLorebookIds))
        .put("card_book_enabled", cardBookEnabled)
        .put("pinned", pinned)
        .put("favorite", favorite)
        .put("archived", archived)
        .put("tags", JSONArray(tags))
        .put("reasoning_effort", reasoningEffort)

    companion object {
        const val FORMAT_VERSION = 2
        const val META_MAIN_CHAT = "main_chat"

        fun fromJson(obj: JSONObject): RpSession {
            val config = obj.optJSONObject("config")?.let { RpConfig.fromJson(it) } ?: RpConfig()
            val messagesJson = obj.optJSONArray("messages") ?: JSONArray()
            var messages = buildList {
                for (i in 0 until messagesJson.length()) {
                    messagesJson.optJSONObject(i)?.let { add(RpMessage.fromJson(it)) }
                }
            }
            messages = dedupeIds(messages)
            messages.forEach { RpIds.observe(it.id) }
            val legacy = obj.optInt("version", 1) < FORMAT_VERSION
            if (legacy) messages = liftLegacyGreeting(messages, config)
            val createdAt = obj.optLong("created_at", System.currentTimeMillis())
            return RpSession(
                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                title = obj.optString("title"),
                createdAt = createdAt,
                updatedAt = obj.optLong("updated_at", createdAt),
                config = config,
                messages = messages,
                profileId = obj.optString("profile_id"),
                model = obj.optString("model"),
                injectionEnabled = obj.optBoolean("injection_enabled", false),
                memoryEnabled = obj.optBoolean("memory_enabled", false),
                tainted = if (obj.has("tainted")) obj.optBoolean("tainted") else messages.size > 1,
                personaId = obj.optString("persona_id").takeIf { it.isNotBlank() && !obj.isNull("persona_id") },
                authorsNote = RpAuthorsNote.fromJson(obj.optJSONObject("authors_note")),
                timedWorldInfo = obj.optJSONObject("timed_world_info") ?: JSONObject(),
                cardRegexAllowed = obj.optNullableBoolean("card_regex_allowed"),
                cardScriptsAllowed = obj.optNullableBoolean("card_scripts_allowed"),
                metadata = obj.optJSONObject("metadata") ?: JSONObject(),
                chatLorebookIds = obj.optJSONArray("chat_lorebooks")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty(),
                cardBookEnabled = obj.optBoolean("card_book_enabled", true),
                pinned = obj.optBoolean("pinned"),
                favorite = obj.optBoolean("favorite"),
                archived = obj.optBoolean("archived"),
                reasoningEffort = obj.optString("reasoning_effort"),
                tags = obj.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
            )
        }

        /** Old sessions kept the greeting as plain content and alternates in config.openings. */
        private fun liftLegacyGreeting(messages: List<RpMessage>, config: RpConfig): List<RpMessage> {
            val first = messages.firstOrNull() ?: return messages
            val greetings = config.greetings
            if (first.role != "assistant" || first.swipeCount > 1 || greetings.size <= 1) return messages
            val content = first.content.trim()
            val index = greetings.indexOfFirst { it.trim() == content }
            if (index < 0) return messages
            val swipes = greetings.mapIndexed { i, text ->
                if (i == index) first.current else RpSwipe(text, model = first.current.model)
            }
            return listOf(first.copy(swipes = swipes, swipeId = index)) + messages.drop(1)
        }

        private fun dedupeIds(messages: List<RpMessage>): List<RpMessage> {
            val seen = HashSet<Long>()
            return messages.map { message ->
                if (seen.add(message.id)) message else message.copy(id = RpIds.next()).also { seen.add(it.id) }
            }
        }

        private fun JSONObject.optNullableBoolean(key: String): Boolean? =
            if (!has(key) || isNull(key)) null else optBoolean(key)
    }
}

internal fun encodeRpMessage(message: ChatMessage): JSONObject = JSONObject()
    .put("id", message.id)
    .put("role", message.role)
    .put("content", message.content)
    .put("reasoning", message.reasoning)
    .put("is_error", message.isError)
    .put("is_interrupted", message.isInterrupted)
    .put("is_stopped", message.isStopped)
    .put("profile_name", message.profileName)
    .put("model", message.model)
    .put("usage", message.usage?.let(::encodeRpUsage) ?: JSONObject.NULL)

internal fun decodeRpMessage(obj: JSONObject): ChatMessage = ChatMessage(
    id = obj.optLong("id", System.nanoTime()),
    role = obj.optString("role"),
    content = obj.optString("content"),
    reasoning = obj.optString("reasoning"),
    isError = obj.optBoolean("is_error"),
    isInterrupted = obj.optBoolean("is_interrupted"),
    isStopped = obj.optBoolean("is_stopped"),
    profileName = obj.optString("profile_name"),
    model = obj.optString("model"),
    usage = obj.optJSONObject("usage")?.let(::decodeRpUsage)
)

internal fun encodeRpUsage(usage: TokenUsage): JSONObject = JSONObject()
    .put("input", usage.inputTokens)
    .put("cached", usage.cachedTokens)
    .put("output", usage.outputTokens)
    .put("reasoning", usage.reasoningTokens)
    .put("total", usage.totalTokens)

internal fun decodeRpUsage(obj: JSONObject): TokenUsage = TokenUsage(
    inputTokens = obj.optInt("input"),
    cachedTokens = obj.optInt("cached"),
    outputTokens = obj.optInt("output"),
    reasoningTokens = obj.optInt("reasoning"),
    totalTokens = obj.optInt("total")
)

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
