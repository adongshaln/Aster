package com.adong.adchat.data

/** A later text chunk that follows a thought or tool round starts a new paragraph. */
internal fun textAfterThought(existing: CharSequence, incoming: String, afterThought: Boolean): String {
    if (!afterThought || incoming.isEmpty() || existing.isEmpty()) return incoming
    if (existing.last() == '\n' || incoming.first() == '\n') return incoming
    return "\n\n$incoming"
}
