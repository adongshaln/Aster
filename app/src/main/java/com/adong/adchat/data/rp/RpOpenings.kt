package com.adong.adchat.data.rp

/**
 * Greeting logic. Like SillyTavern `getFirstMessage`, the card's first_mes + alternate_greetings become
 * the swipes of message 0 (raw, macros unexpanded). They only cycle while the chat is pristine; a
 * generated reply or an edited chat is never touched.
 */
object RpOpenings {
    /** Builds message 0 for a fresh session, or null when the setup has no greeting. */
    fun greetingMessage(config: RpConfig, model: String = "", now: Long = System.currentTimeMillis()): RpMessage? {
        val greetings = config.greetings
        if (greetings.isEmpty()) return null
        return RpMessage(
            role = "assistant",
            name = config.card.macroName,
            swipes = greetings.map { RpSwipe(content = it, model = model) },
            swipeId = config.openingIndex.coerceIn(0, greetings.lastIndex),
            sentAt = now
        )
    }

    /**
     * Applies a config change to the chat. While pristine, message 0 is rebuilt from the new greetings
     * (keeping the selected index when it still exists); otherwise the history is left alone.
     */
    fun reconcile(session: RpSession, config: RpConfig): RpSession {
        if (!session.pristine) return session.copy(config = config)
        val first = session.messages.firstOrNull()
        if (first != null && first.role != "assistant") return session.copy(config = config)
        val greeting = greetingMessage(config, session.model)
        val messages = when {
            greeting == null -> emptyList()
            first == null -> listOf(greeting)
            else -> {
                val keep = first.currentIndex.takeIf { config.greetings.size == first.swipeCount && it < greeting.swipeCount }
                listOf(greeting.copy(id = first.id, sentAt = first.sentAt, swipeId = keep ?: greeting.swipeId))
            }
        }
        return session.copy(config = config, messages = messages)
    }

    /** True when the greeting arrows should be shown: pristine chat with more than one greeting. */
    fun canCycle(session: RpSession): Boolean {
        val first = session.messages.singleOrNull() ?: return false
        return !session.tainted && first.role == "assistant" && first.swipeCount > 1
    }

    /** Cycles the greeting swipe (wrapping). Returns null when there is nothing to rotate. */
    fun rotated(session: RpSession, direction: Int): RpSession? {
        if (!canCycle(session)) return null
        val first = session.messages.first()
        val next = Math.floorMod(first.currentIndex + direction, first.swipeCount)
        return withIndex(session, next)
    }

    fun withIndex(session: RpSession, index: Int): RpSession? {
        if (!canCycle(session)) return null
        val first = session.messages.first()
        if (index !in 0 until first.swipeCount) return null
        return session.copy(
            messages = listOf(first.copy(swipeId = index)),
            config = session.config.copy(openingIndex = index),
            updatedAt = System.currentTimeMillis()
        )
    }
}
