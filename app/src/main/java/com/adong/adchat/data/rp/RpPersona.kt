package com.adong.adchat.data.rp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A user identity ({{user}}), SillyTavern persona: name, description and where it is injected.
 * [connections] holds card keys ([RpCardKey]) this persona is bound to; [lorebook] is an optional
 * world-book id scanned alongside the card book.
 */
data class RpPersona(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val position: RpPersonaPosition = RpPersonaPosition.InPrompt,
    val depth: Int = 2,
    val role: String = "system",
    val avatarUri: String? = null,
    val lorebook: String? = null,
    val connections: Set<String> = emptySet(),
    val title: String = ""
) {
    fun toContext(fallbackName: String = ""): RpPersonaContext = RpPersonaContext(
        name = name.ifBlank { fallbackName.ifBlank { DEFAULT_NAME } },
        description = description,
        position = position,
        depth = depth.coerceIn(0, 1000),
        role = role.takeIf { it == "user" || it == "assistant" } ?: "system"
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("description", description)
        .put("position", position.name)
        .put("depth", depth)
        .put("role", role)
        .put("avatar_uri", avatarUri ?: JSONObject.NULL)
        .put("lorebook", lorebook ?: JSONObject.NULL)
        .put("connections", JSONArray(connections.toList()))
        .put("title", title)

    companion object {
        const val DEFAULT_NAME = "用户"

        fun fromJson(obj: JSONObject): RpPersona = RpPersona(
            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = obj.optString("name"),
            description = obj.optString("description"),
            position = runCatching { RpPersonaPosition.valueOf(obj.optString("position")) }.getOrDefault(RpPersonaPosition.InPrompt),
            depth = obj.optInt("depth", 2),
            role = obj.optString("role", "system").ifBlank { "system" },
            avatarUri = obj.optString("avatar_uri").takeIf { it.isNotBlank() && !obj.isNull("avatar_uri") },
            lorebook = obj.optString("lorebook").takeIf { it.isNotBlank() && !obj.isNull("lorebook") },
            connections = obj.optJSONArray("connections")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
            }.orEmpty(),
            title = obj.optString("title")
        )
    }
}

/** How the active persona was chosen (ST lock types). */
enum class RpPersonaSource { Chat, Character, Default, Fallback }

data class RpResolvedPersona(val persona: RpPersona, val source: RpPersonaSource) {
    fun toContext(): RpPersonaContext = persona.toContext()
}

/** Persona library snapshot: personas plus the default id. Pure resolution lives here. */
data class RpPersonaLibrary(val personas: List<RpPersona> = emptyList(), val defaultId: String? = null) {
    fun find(id: String?): RpPersona? = id?.let { wanted -> personas.firstOrNull { it.id == wanted } }

    val defaultPersona: RpPersona? get() = find(defaultId)

    /**
     * ST `loadPersonaForCurrentChat`: chat lock → persona connected to the card (first) → default →
     * fallback built from the app-wide user name. A dangling chat lock is ignored.
     */
    fun resolve(session: RpSession, fallbackName: String): RpResolvedPersona {
        find(session.personaId)?.let { return RpResolvedPersona(it, RpPersonaSource.Chat) }
        val key = session.cardKey
        if (key.isNotEmpty()) personas.firstOrNull { key in it.connections }?.let { return RpResolvedPersona(it, RpPersonaSource.Character) }
        defaultPersona?.let { return RpResolvedPersona(it, RpPersonaSource.Default) }
        return RpResolvedPersona(
            RpPersona(id = FALLBACK_ID, name = fallbackName.ifBlank { RpPersona.DEFAULT_NAME }),
            RpPersonaSource.Fallback
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("default_id", defaultId ?: JSONObject.NULL)
        .put("personas", JSONArray(personas.map { it.toJson() }))

    companion object {
        const val FALLBACK_ID = "fallback"

        fun fromJson(obj: JSONObject): RpPersonaLibrary {
            val array = obj.optJSONArray("personas") ?: JSONArray()
            val personas = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(RpPersona::fromJson) }
            return RpPersonaLibrary(
                personas = personas,
                defaultId = obj.optString("default_id").takeIf { it.isNotBlank() && !obj.isNull("default_id") }
            )
        }
    }
}

/**
 * Persona library persisted as `<root>/personas.json` (atomic writes). Mutations are synchronous and
 * small; call from any thread.
 */
class RpPersonaStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(File(context.applicationContext.filesDir, "rp"), "personas.json"))

    private var cache: RpPersonaLibrary? = null

    @Synchronized
    fun library(): RpPersonaLibrary = cache ?: runCatching { RpPersonaLibrary.fromJson(JSONObject(file.readText())) }
        .getOrDefault(RpPersonaLibrary()).also { cache = it }

    fun list(): List<RpPersona> = library().personas

    fun get(id: String): RpPersona? = library().find(id)

    @Synchronized
    fun upsert(persona: RpPersona): RpPersona {
        val current = library()
        val personas = current.personas.toMutableList()
        val index = personas.indexOfFirst { it.id == persona.id }
        if (index >= 0) personas[index] = persona else personas += persona
        write(current.copy(personas = personas, defaultId = current.defaultId ?: persona.id))
        return persona
    }

    @Synchronized
    fun delete(id: String) {
        val current = library()
        val personas = current.personas.filterNot { it.id == id }
        write(current.copy(personas = personas, defaultId = current.defaultId?.takeIf { it != id } ?: personas.firstOrNull()?.id))
    }

    @Synchronized
    fun setDefault(id: String?) {
        val current = library()
        write(current.copy(defaultId = id?.takeIf { wanted -> current.personas.any { it.id == wanted } }))
    }

    /** Binds or unbinds a persona to a card (character-level lock). A card connects to one persona at a time. */
    @Synchronized
    fun connect(personaId: String, cardKey: String, connected: Boolean = true) {
        if (cardKey.isBlank()) return
        val current = library()
        val personas = current.personas.map { persona ->
            when {
                persona.id == personaId && connected -> persona.copy(connections = persona.connections + cardKey)
                cardKey in persona.connections -> persona.copy(connections = persona.connections - cardKey)
                else -> persona
            }
        }
        write(current.copy(personas = personas))
    }

    fun resolve(session: RpSession, fallbackName: String): RpResolvedPersona = library().resolve(session, fallbackName)

    fun context(session: RpSession, fallbackName: String): RpPersonaContext = resolve(session, fallbackName).toContext()

    /**
     * One-time migration of legacy [RpConfig.userPersona] text. With no library yet the text becomes
     * the default persona; when it differs from the resolved persona it becomes a session-locked
     * persona. The config field is cleared so this never runs twice. Returns the (possibly) updated session.
     */
    @Synchronized
    fun migrateLegacy(session: RpSession, fallbackName: String): RpSession {
        val text = session.config.userPersona.trim()
        if (text.isEmpty()) return session
        val cleared = session.copy(config = session.config.copy(userPersona = ""))
        val current = library()
        if (current.personas.isEmpty()) {
            val persona = RpPersona(id = UUID.randomUUID().toString(), name = fallbackName.ifBlank { RpPersona.DEFAULT_NAME }, description = text)
            write(RpPersonaLibrary(listOf(persona), persona.id))
            return cleared
        }
        val resolved = current.resolve(session, fallbackName).persona
        if (resolved.description.trim() == text) return cleared
        current.personas.firstOrNull { it.description.trim() == text }?.let { return cleared.copy(personaId = it.id) }
        val persona = RpPersona(
            name = resolved.name.ifBlank { fallbackName.ifBlank { RpPersona.DEFAULT_NAME } },
            description = text,
            title = session.title.ifBlank { "旧剧情身份" }
        )
        write(current.copy(personas = current.personas + persona))
        return cleared.copy(personaId = persona.id)
    }

    private fun write(library: RpPersonaLibrary) {
        cache = library
        runCatching { RpStore.atomicWrite(file, library.toJson().toString()) }
    }
}
