package com.adong.adchat.data.rp

import android.content.Context
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.ApiRepository
import com.adong.adchat.data.ChatGenerationOptions
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.story.StorySummaries
import com.adong.adchat.data.story.StorySummaryPipeline
import org.json.JSONObject

/**
 * Rolling summary bound to a chat prefix. [coveredUntilId] is the last message folded into [summary];
 * [fingerprint] hashes ids, swipe ids, hidden flags and contents of every message up to it, so any
 * delete, edit, swipe change or truncation inside the covered range invalidates the summary.
 */
data class RpMemoryState(
    val summary: String,
    val coveredUntilId: Long?,
    val fingerprint: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("summary", summary)
        .put("covered_until_id", coveredUntilId ?: JSONObject.NULL)
        .put("fingerprint", fingerprint)

    companion object {
        fun fromJson(obj: JSONObject): RpMemoryState = RpMemoryState(
            summary = obj.optString("summary"),
            coveredUntilId = if (obj.isNull("covered_until_id") || !obj.has("covered_until_id")) null else obj.optLong("covered_until_id"),
            fingerprint = obj.optString("fingerprint")
        )
    }
}

/**
 * DB-free per-session memory. State and pipeline node checkpoints live in SharedPreferences so the
 * lightweight RP module never touches the legacy story database.
 */
class RpMemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("aster_rp_memory", Context.MODE_PRIVATE)

    fun state(sessionId: String): RpMemoryState? =
        prefs.getString(key(sessionId, "state"), null)?.let { raw ->
            runCatching { RpMemoryState.fromJson(JSONObject(raw)) }.getOrNull()
        }?.takeIf { it.summary.isNotBlank() }

    /**
     * The state when it still matches [messages]; otherwise clears memory and returns null. Legacy
     * index-based state (`covered` count) is converted once against the current chat.
     */
    fun validState(sessionId: String, messages: List<RpMessage>): RpMemoryState? {
        val state = state(sessionId) ?: migrateLegacy(sessionId, messages) ?: return null
        if (RpMemory.isValid(state, messages)) return state
        clear(sessionId)
        return null
    }

    fun summary(sessionId: String): String? = state(sessionId)?.summary

    fun saveState(sessionId: String, state: RpMemoryState) {
        prefs.edit().putString(key(sessionId, "state"), state.toJson().toString()).apply()
    }

    fun loadNode(sessionId: String, node: String, fingerprint: String): String? {
        val raw = prefs.getString(key(sessionId, "node:$node"), null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            obj.optString("raw").takeIf { obj.optString("fingerprint") == fingerprint }
        }.getOrNull()
    }

    fun saveNode(sessionId: String, node: String, fingerprint: String, raw: String) {
        prefs.edit()
            .putString(key(sessionId, "node:$node"), JSONObject().put("fingerprint", fingerprint).put("raw", raw).toString())
            .apply()
    }

    fun clear(sessionId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("session:$sessionId:") }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun migrateLegacy(sessionId: String, messages: List<RpMessage>): RpMemoryState? {
        val summary = prefs.getString(key(sessionId, "summary"), null)?.takeIf { it.isNotBlank() } ?: return null
        val covered = prefs.getInt(key(sessionId, "covered"), 0)
        prefs.edit().remove(key(sessionId, "summary")).remove(key(sessionId, "covered")).apply()
        val until = messages.getOrNull(covered - 1) ?: return null
        val state = RpMemoryState(summary, until.id, RpMemory.fingerprint(messages, until.id) ?: return null)
        saveState(sessionId, state)
        return state
    }

    private fun key(sessionId: String, suffix: String): String = "session:$sessionId:$suffix"
}

/** A pending summary batch: the pipeline inputs plus the prefix they cover once committed. */
data class RpMemoryPlan(
    val parts: List<String>,
    val coveredUntilId: Long,
    val fingerprint: String,
    /** Number of eligible (visible, non-error) messages folded in, including earlier batches. */
    val coveredCount: Int
)

object RpMemory {
    /** Visible messages always kept verbatim in context, never folded into the summary. */
    const val RECENT_KEEP = 4
    private const val MIN_NEW_MESSAGES = 2
    internal const val CHUNK_CHARS = 24_000
    private const val MAX_PARTS = 16
    private const val SUMMARY_HEADER = "[已有历史摘要，按时间顺序保留重要事件、人物关系与未完线索]\n"

    /** Hash of every message up to and including [untilId]; null when the id is gone. */
    fun fingerprint(messages: List<RpMessage>, untilId: Long): String? {
        val end = messages.indexOfFirst { it.id == untilId }
        if (end < 0) return null
        val text = buildString {
            for (i in 0..end) {
                val m = messages[i]
                append(m.id).append(':').append(m.currentIndex).append(':').append(m.hidden).append(':').append(m.isError)
                append(':').append(m.role).append('\u0000').append(m.content).append('\u0001')
            }
        }
        return sha256(text)
    }

    fun isValid(state: RpMemoryState, messages: List<RpMessage>): Boolean {
        val until = state.coveredUntilId ?: return false
        return fingerprint(messages, until) == state.fingerprint
    }

    /** Messages not covered by a valid [state] (all messages when null/invalid). */
    fun uncovered(messages: List<RpMessage>, state: RpMemoryState?): List<RpMessage> {
        if (state == null || !isValid(state, messages)) return messages
        val index = messages.indexOfFirst { it.id == state.coveredUntilId }
        return messages.drop(index + 1)
    }

    /**
     * Builds the next layered-summary batch from the visible messages after the covered prefix, leaving
     * the last [RECENT_KEEP] verbatim. An invalid [state] is ignored (full rebuild). Returns null when
     * there is nothing worth summarizing yet. A message is folded in only when all of its pieces fit,
     * so a partially sent message is never summarized twice.
     */
    fun plan(messages: List<RpMessage>, state: RpMemoryState?): RpMemoryPlan? {
        val valid = state?.takeIf { isValid(it, messages) }
        val coveredIndex = valid?.coveredUntilId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
        val eligible = messages.withIndex().filter { (_, m) -> !m.hidden && !m.isError && m.content.isNotBlank() }
        val alreadyCovered = eligible.count { it.index <= coveredIndex }
        val pending = eligible.filter { it.index > coveredIndex }
        val candidates = pending.dropLast(RECENT_KEEP)
        if (candidates.size < MIN_NEW_MESSAGES) return null

        val summaryParts = valid?.summary?.takeIf { it.isNotBlank() }?.let { pieces(SUMMARY_HEADER + it) }.orEmpty()
            .take(MAX_PARTS - 1)
        val budget = MAX_PARTS - summaryParts.size
        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()
        var lastFolded: Int? = null
        var folded = 0

        for ((index, message) in candidates) {
            val label = if (message.isUser) "用户" else "角色"
            val line = "[$label]\n${message.content.trim()}\n\n"
            if (buffer.length + line.length <= CHUNK_CHARS) {
                if (buffer.isEmpty() && chunks.size >= budget) break
                buffer.append(line)
            } else {
                val bufferChunks = if (buffer.isNotBlank()) 1 else 0
                val lineChunks = if (line.length <= CHUNK_CHARS) 1 else pieces(line).size
                if (chunks.size + bufferChunks + lineChunks > budget) {
                    // Oversized first message that can never fit: fold a clipped version instead of stalling.
                    if (lastFolded != null || chunks.size + bufferChunks >= budget) break
                    val room = budget - chunks.size - bufferChunks
                    flushTo(chunks, buffer)
                    chunks += pieces(clip(line, room * CHUNK_CHARS - 64)).take(room)
                    lastFolded = index
                    folded++
                    break
                }
                flushTo(chunks, buffer)
                if (line.length <= CHUNK_CHARS) {
                    if (chunks.size >= budget) break
                    buffer.append(line)
                } else chunks += pieces(line)
            }
            lastFolded = index
            folded++
        }
        if (buffer.isNotBlank()) {
            if (chunks.size < budget) flushTo(chunks, buffer) else {
                // Should not happen (budget checked above); drop the unsent tail from coverage.
                return null
            }
        }
        val until = lastFolded ?: return null
        if (chunks.isEmpty()) return null
        val parts = summaryParts + chunks
        if (parts.size > MAX_PARTS || parts.any { it.isBlank() || it.length > 29_000 }) return null
        val untilId = messages[until].id
        return RpMemoryPlan(parts, untilId, fingerprint(messages, untilId) ?: return null, alreadyCovered + folded)
    }

    private fun flushTo(chunks: MutableList<String>, buffer: StringBuilder) {
        if (buffer.isNotBlank()) chunks += buffer.toString().trim()
        buffer.setLength(0)
    }

    /** Splits text into ≤ [CHUNK_CHARS] pieces, preferring paragraph breaks. */
    internal fun pieces(text: String): List<String> {
        val result = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            var end = minOf(text.length, offset + CHUNK_CHARS)
            if (end < text.length) {
                val brk = text.lastIndexOf('\n', end - 1)
                if (brk > offset + CHUNK_CHARS / 2) end = brk + 1
            }
            text.substring(offset, end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            offset = end
        }
        return result
    }

    private fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val keep = (max - 16).coerceAtLeast(2) / 2
        return text.take(keep) + "\n…（中略）…\n" + text.takeLast(keep)
    }

    /**
     * Runs the shared layered-summary pipeline and commits the rolling summary, unless the chat changed
     * under the covered prefix meanwhile ([current] supplies the latest messages). Returns the summary.
     */
    suspend fun summarize(
        api: ApiRepository,
        profile: ApiProfile,
        model: String,
        store: RpMemoryStore,
        sessionId: String,
        plan: RpMemoryPlan,
        current: () -> List<RpMessage>? = { null }
    ): String {
        val raw = StorySummaryPipeline.run(
            parts = plan.parts,
            load = { node, fingerprint -> store.loadNode(sessionId, node, fingerprint) },
            save = { node, fingerprint, output -> store.saveNode(sessionId, node, fingerprint, output) },
            generate = { input ->
                api.streamChat(
                    profile = profile,
                    model = model,
                    systemPrompt = StorySummaries.prompt,
                    history = listOf(ChatMessage(role = "user", content = input)),
                    cacheKey = "rp-memory-$sessionId",
                    trimHistory = false,
                    skillsAllowed = false,
                    generationOptions = ChatGenerationOptions(temperature = 0.3),
                    onDelta = {}
                ).text
            }
        )
        val summary = StorySummaries.parse(raw)
        val state = RpMemoryState(summary, plan.coveredUntilId, plan.fingerprint)
        val latest = current()
        if (latest == null || isValid(state, latest)) store.saveState(sessionId, state)
        return summary
    }
}
