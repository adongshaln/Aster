package com.adong.adchat.data.story

/**
 * Session-scoped cache for completed story presentation.
 *
 * LazyColumn disposes rows outside its viewport. A completed reply must not re-enter layout
 * with an empty async placeholder and expand after parsing: that changes the height of content
 * above the reading anchor and produces a large apparent scroll jump.
 */
internal class StoryProsePresentationCache(
    private val maxEntries: Int = 96
) {
    data class Key(
        val revisionId: String,
        val content: String,
        val depth: Int
    )

    private val entries = object : LinkedHashMap<Key, StoryProsePresentation>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, StoryProsePresentation>?): Boolean =
            size > maxEntries
    }

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun get(key: Key): StoryProsePresentation? = entries[key]

    @Synchronized
    fun getOrPut(key: Key, producer: () -> StoryProsePresentation): StoryProsePresentation {
        entries[key]?.let { return it }
        return producer().also { entries[key] = it }
    }

    @Synchronized
    fun size(): Int = entries.size
}
