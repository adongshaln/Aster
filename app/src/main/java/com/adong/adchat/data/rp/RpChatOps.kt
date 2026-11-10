package com.adong.adchat.data.rp

import org.json.JSONObject
import java.util.UUID

/** What a right swipe on the last message should do. */
sealed interface RpSwipeResult {
    /** The session changed (moved to an existing swipe, or cycled a pristine greeting). */
    data class Moved(val session: RpSession) : RpSwipeResult
    /** Overswipe: start a [RpGenerationType.Swipe] generation and commit it with [RpChatOps.addSwipe]. */
    data object NeedsGeneration : RpSwipeResult
    /** The message is not swipeable. */
    data object None : RpSwipeResult
}

/**
 * Pure chat operations with SillyTavern semantics. Every function returns a new session (or null when
 * the operation does not apply) and never touches IO. Floors are addressed by stable message id.
 * Callers persist the result and drop variable floors of [deletedIds] where noted.
 */
object RpChatOps {
    /** ST `isMessageSwipeable`: only the last, non-user, non-error message. */
    fun swipeable(session: RpSession, messageId: Long): Boolean {
        val last = session.messages.lastOrNull() ?: return false
        return last.id == messageId && !last.isUser && !last.isError && last.extra.optBoolean("swipeable", true)
    }

    /** Swipe the last message by [direction] (-1 left, +1 right). */
    fun swipe(session: RpSession, direction: Int, now: Long = System.currentTimeMillis()): RpSwipeResult {
        val last = session.messages.lastOrNull() ?: return RpSwipeResult.None
        if (!swipeable(session, last.id)) return RpSwipeResult.None
        val greeting = session.messages.size == 1
        val target = last.currentIndex + direction
        val index = when {
            target < 0 -> last.swipeCount - 1
            target < last.swipeCount -> target
            greeting && !session.tainted -> 0
            else -> return RpSwipeResult.NeedsGeneration
        }
        if (index == last.currentIndex) return RpSwipeResult.None
        val updated = session.copy(
            messages = session.messages.dropLast(1) + last.copy(swipeId = index),
            config = if (greeting) session.config.copy(openingIndex = index) else session.config,
            updatedAt = now
        )
        return RpSwipeResult.Moved(updated)
    }

    /** Jump the given message to an explicit swipe (TavernHelper `setChatMessages({swipe_id})`). */
    fun selectSwipe(session: RpSession, messageId: Long, swipeId: Int, now: Long = System.currentTimeMillis()): RpSession? {
        val index = session.indexOf(messageId).takeIf { it >= 0 } ?: return null
        val message = session.messages[index]
        if (swipeId !in 0 until message.swipeCount || swipeId == message.currentIndex) return null
        return replaceAt(session, index, message.copy(swipeId = swipeId), now)
    }

    /**
     * Commits a generated swipe to the last assistant message and selects it. When the chat ends with
     * a user message (or is empty) this appends a fresh assistant message instead.
     */
    fun addSwipe(session: RpSession, swipe: RpSwipe, now: Long = System.currentTimeMillis()): RpSession {
        val last = session.messages.lastOrNull()
        if (last == null || last.isUser || last.isError) return appendReply(session, swipe, now)
        val updated = last.copy(swipes = last.safeSwipes + swipe, swipeId = last.safeSwipes.size)
        return session.copy(messages = session.messages.dropLast(1) + updated, tainted = true, updatedAt = now)
    }

    /** Appends a new assistant reply (normal generation). Removes a trailing error floor first. */
    fun appendReply(session: RpSession, swipe: RpSwipe, now: Long = System.currentTimeMillis(), name: String = ""): RpSession {
        val base = removeTrailingError(session) ?: session
        val message = RpMessage(role = "assistant", name = name.ifBlank { session.config.card.macroName }, swipes = listOf(swipe), sentAt = now)
        return base.copy(messages = base.messages + message, tainted = true, updatedAt = now)
    }

    /** Appends a message without generating (user send, /sendas, /sys). */
    fun append(session: RpSession, role: String, text: String, name: String = "", now: Long = System.currentTimeMillis()): RpSession {
        val base = removeTrailingError(session) ?: session
        val message = RpMessage(role = role, name = name, swipes = listOf(RpSwipe(text)), sentAt = now)
        return base.copy(messages = base.messages + message, tainted = true, updatedAt = now)
    }

    /** Inserts messages before [beforeIndex] (null = append), tavern_helper `createChatMessages`. */
    fun insert(session: RpSession, items: List<RpMessage>, beforeIndex: Int?, now: Long = System.currentTimeMillis()): RpSession {
        if (items.isEmpty()) return session
        val at = (beforeIndex ?: session.messages.size).coerceIn(0, session.messages.size)
        val messages = session.messages.toMutableList().apply { addAll(at, items) }
        return session.copy(messages = messages, tainted = true, updatedAt = now)
    }

    /**
     * Continue: appends [delta] to the current swipe of the last assistant message. [separator] is the
     * preset continue_postfix (ST default a single space; pass "" when the model already returns it).
     */
    fun continueWith(session: RpSession, delta: String, separator: String = "", swipe: RpSwipe? = null, now: Long = System.currentTimeMillis()): RpSession? {
        val last = session.messages.lastOrNull() ?: return null
        if (last.isUser || last.isError) return null
        val current = last.current
        val joined = if (current.content.isEmpty() || delta.isEmpty()) current.content + delta else current.content + separator + delta
        val merged = current.copy(
            content = joined,
            reasoning = listOf(current.reasoning, swipe?.reasoning.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n"),
            model = swipe?.model?.ifBlank { null } ?: current.model,
            usage = swipe?.usage ?: current.usage,
            finishedAt = swipe?.finishedAt?.takeIf { it > 0 } ?: now,
            interrupted = swipe?.interrupted ?: false
        )
        return session.copy(messages = session.messages.dropLast(1) + last.withCurrent(merged), tainted = true, updatedAt = now)
    }

    /**
     * ST `deleteSwipe`: drop one swipe (default the current) of a message; refuses the last remaining.
     * New index: deleted before current → cur-1; after → cur; the current itself → min(cur, len-1).
     */
    fun deleteSwipe(session: RpSession, messageId: Long, swipeIndex: Int? = null, now: Long = System.currentTimeMillis()): RpSession? {
        val index = session.indexOf(messageId).takeIf { it >= 0 } ?: return null
        val message = session.messages[index]
        if (message.swipeCount <= 1) return null
        val cur = message.currentIndex
        val target = swipeIndex ?: cur
        if (target !in 0 until message.swipeCount) return null
        val swipes = message.safeSwipes.toMutableList().apply { removeAt(target) }
        val next = when {
            target < cur -> cur - 1
            target > cur -> cur
            else -> minOf(cur, swipes.lastIndex)
        }
        return replaceAt(session, index, message.copy(swipes = swipes, swipeId = next), now)
    }

    /** New swipe index after [deleteSwipe] so variable floors of later swipes can be re-keyed. */
    fun swipeIndexAfterDelete(deleted: Int, original: Int): Int? = when {
        original == deleted -> null
        original > deleted -> original - 1
        else -> original
    }

    /** Edits the active swipe of a message (user or assistant) and marks the chat tainted. */
    fun edit(session: RpSession, messageId: Long, text: String, now: Long = System.currentTimeMillis()): RpSession? {
        val index = session.indexOf(messageId).takeIf { it >= 0 } ?: return null
        val message = session.messages[index]
        val edited = if (message.isError) {
            message.copy(isError = false, errorText = "").withCurrent(RpSwipe(text))
        } else message.withContent(text)
        if (edited == message) return null
        return replaceAt(session, index, edited, now)
    }

    /** Deletes one message. Returns the session and the ids removed (to drop their variable floors). */
    fun delete(session: RpSession, messageId: Long, now: Long = System.currentTimeMillis()): Pair<RpSession, List<Long>>? {
        val index = session.indexOf(messageId).takeIf { it >= 0 } ?: return null
        val messages = session.messages.toMutableList().apply { removeAt(index) }
        return session.copy(messages = messages, tainted = true, updatedAt = now) to listOf(messageId)
    }

    fun deleteMany(session: RpSession, ids: Collection<Long>, now: Long = System.currentTimeMillis()): Pair<RpSession, List<Long>>? {
        val set = ids.toSet()
        val removed = session.messages.filter { it.id in set }.map { it.id }
        if (removed.isEmpty()) return null
        return session.copy(messages = session.messages.filterNot { it.id in set }, tainted = true, updatedAt = now) to removed
    }

    /** Deletes the message and everything after it ("删除此条及之后"). */
    fun truncateFrom(session: RpSession, messageId: Long, now: Long = System.currentTimeMillis()): Pair<RpSession, List<Long>>? {
        val index = session.indexOf(messageId).takeIf { it >= 0 } ?: return null
        val removed = session.messages.drop(index).map { it.id }
        return session.copy(messages = session.messages.take(index), tainted = true, updatedAt = now) to removed
    }

    /** Hide or unhide messages (`/hide`): kept on screen, excluded from prompt and world-info scan. */
    fun setHidden(session: RpSession, ids: Collection<Long>, hidden: Boolean, now: Long = System.currentTimeMillis()): RpSession? {
        val set = ids.toSet()
        var changed = false
        val messages = session.messages.map {
            if (it.id in set && it.hidden != hidden) { changed = true; it.copy(hidden = hidden) } else it
        }
        return if (changed) session.copy(messages = messages, tainted = true, updatedAt = now) else null
    }

    /** Appends a visible error floor after a failed generation (replacing an older trailing one). */
    fun appendError(session: RpSession, text: String, now: Long = System.currentTimeMillis()): RpSession {
        val base = removeTrailingError(session) ?: session
        val message = RpMessage(
            role = "assistant", name = session.config.card.macroName, swipes = listOf(RpSwipe("")),
            isError = true, errorText = text.ifBlank { "生成失败" }, sentAt = now
        )
        return base.copy(messages = base.messages + message, updatedAt = now)
    }

    /** Removes a trailing error floor; null when the chat does not end in one. */
    fun removeTrailingError(session: RpSession): RpSession? {
        if (session.messages.lastOrNull()?.isError != true) return null
        return session.copy(messages = session.messages.dropLast(1))
    }

    /**
     * Base for regenerate: removes the last assistant reply (keeping errors out too) so the caller can
     * run a [RpGenerationType.Regenerate] from the preceding user turn. Null when nothing to drop.
     */
    fun dropLastReply(session: RpSession, now: Long = System.currentTimeMillis()): Pair<RpSession, List<Long>>? {
        val base = removeTrailingError(session) ?: session
        val last = base.messages.lastOrNull() ?: return null
        if (last.isUser || base.messages.size == 1) return if (base !== session) base.copy(updatedAt = now) to emptyList() else null
        return base.copy(messages = base.messages.dropLast(1), tainted = true, updatedAt = now) to listOf(last.id)
    }

    /**
     * Prompt history for a swipe generation: everything except the message being swiped (ST pops the
     * last message from `coreChat`).
     */
    fun swipeBase(session: RpSession): List<RpPromptMessage> =
        session.messages.dropLast(1).filter { !it.hidden && !it.isError }.map(RpMessage::toPromptMessage)

    /**
     * Branch: copies messages up to and including [messageId] into a new session that records
     * `metadata.main_chat`. Message ids are kept so variable floors can be copied 1:1.
     */
    fun branch(session: RpSession, messageId: Long, title: String? = null, now: Long = System.currentTimeMillis()): RpSession? {
        val index = session.indexOf(messageId).takeIf { it >= 0 } ?: return null
        val metadata = JSONObject(session.metadata.toString())
            .put(RpSession.META_MAIN_CHAT, session.id)
            .put("integrity", UUID.randomUUID().toString())
        return session.copy(
            id = UUID.randomUUID().toString(),
            title = title ?: "${session.title.ifBlank { "剧情" }} - 分支",
            createdAt = now,
            updatedAt = now,
            messages = session.messages.take(index + 1),
            metadata = metadata,
            tainted = true,
            timedWorldInfo = JSONObject(session.timedWorldInfo.toString())
        )
    }

    /** Applies a streaming checkpoint: the in-flight text as an interrupted swipe, so a killed process keeps it. */
    fun checkpoint(session: RpSession, pending: RpPendingReply, now: Long = System.currentTimeMillis()): RpSession {
        val swipe = RpSwipe(
            content = pending.content, reasoning = pending.reasoning, model = pending.model,
            startedAt = pending.startedAt, finishedAt = now, interrupted = true
        )
        return when (pending.type) {
            RpGenerationType.Swipe -> addSwipe(session, swipe, now)
            RpGenerationType.Continue -> continueWith(session, pending.content, pending.separator, swipe, now) ?: appendReply(session, swipe, now)
            else -> appendReply(session, swipe, now)
        }
    }

    private fun replaceAt(session: RpSession, index: Int, message: RpMessage, now: Long): RpSession =
        session.copy(messages = session.messages.toMutableList().apply { this[index] = message }, tainted = true, updatedAt = now)
}

/** In-flight generation snapshot for [RpChatOps.checkpoint] / [RpStore.checkpoint]. */
data class RpPendingReply(
    val type: RpGenerationType,
    val content: String,
    val reasoning: String = "",
    val model: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val separator: String = ""
)
