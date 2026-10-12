package com.adong.adchat.data.story

import org.json.JSONArray
import org.json.JSONObject

enum class WorldLogic { AND_ANY, NOT_ANY, AND_ALL, NOT_ALL }

data class StoryWorldBookEntry(
    val keys: List<String>,
    val secondaryKeys: List<String> = emptyList(),
    val logic: WorldLogic = WorldLogic.AND_ANY,
    val content: String,
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val insertionOrder: Int = 100,
    val beforeChar: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("keys", JSONArray(keys))
        .put("secondary_keys", JSONArray(secondaryKeys))
        .put("logic", logic.name)
        .put("content", content)
        .put("enabled", enabled)
        .put("constant", constant)
        .put("insertion_order", insertionOrder)
        .put("before_char", beforeChar)
}

/** The card's embedded lorebook (character_book). Active by default; entries are injected by relevance. */
data class StoryWorldBook(
    val entries: List<StoryWorldBookEntry> = emptyList(),
    val scanDepth: Int = 4,
    val charBudget: Int = 20_000,
    val recursive: Boolean = true
) {
    fun toJson(): String = JSONObject()
        .put("entries", JSONArray(entries.map { it.toJson() }))
        .put("scan_depth", scanDepth)
        .put("char_budget", charBudget)
        .put("recursive", recursive)
        .toString()

    companion object {
        fun parse(raw: String): StoryWorldBook = runCatching { fromJson(JSONObject(raw)) }
            .getOrDefault(StoryWorldBook())

        fun fromJson(json: JSONObject): StoryWorldBook = StoryWorldBook(
            entries = json.optJSONArray("entries")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    StoryWorldBookEntry(
                        keys = item.optJSONArray("keys")?.stringList().orEmpty(),
                        secondaryKeys = item.optJSONArray("secondary_keys")?.stringList().orEmpty(),
                        logic = runCatching { WorldLogic.valueOf(item.optString("logic")) }.getOrDefault(WorldLogic.AND_ANY),
                        content = item.optString("content"),
                        enabled = item.optBoolean("enabled", true),
                        constant = item.optBoolean("constant", false),
                        insertionOrder = item.optInt("insertion_order", 100),
                        beforeChar = item.optBoolean("before_char", false)
                    )
                }
            }.orEmpty(),
            scanDepth = json.optInt("scan_depth", 4),
            charBudget = json.optInt("char_budget", 20_000),
            recursive = json.optBoolean("recursive", true)
        )

        private fun JSONArray.stringList(): List<String> =
            (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotEmpty) }
    }
}

/** The {{char}} card. Imported cards also carry example dialogue and writing instructions, never a visual layout. */
data class StoryCharacterCard(
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val mesExample: String = "",
    val systemPrompt: String = "",
    val postHistory: String = "",
    val imported: Boolean = false,
    val worldBook: StoryWorldBook = StoryWorldBook()
) {
    fun toJson(): String = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("personality", personality)
        .put("scenario", scenario)
        .put("mes_example", mesExample)
        .put("system_prompt", systemPrompt)
        .put("post_history", postHistory)
        .put("imported", imported)
        .put("world_book", JSONObject(worldBook.toJson()))
        .toString()

    companion object {
        fun parse(raw: String): StoryCharacterCard =
            read(raw).also { require(it.name.isNotBlank() && it.description.isNotBlank()) { "角色卡缺少名字或描述" } }

        fun fromStored(raw: String): StoryCharacterCard? =
            raw.takeIf { it.isNotBlank() }?.let { runCatching { read(it).also { card -> require(card.name.isNotBlank()) } }.getOrNull() }

        private fun read(raw: String): StoryCharacterCard {
            val json = JSONObject(extractJsonObject(raw))
            return StoryCharacterCard(
                name = json.optString("name").trim(),
                description = json.optString("description").trim(),
                personality = json.optString("personality").trim(),
                scenario = json.optString("scenario").trim(),
                mesExample = json.optString("mes_example").trim(),
                systemPrompt = json.optString("system_prompt").trim(),
                postHistory = json.optString("post_history").trim(),
                imported = json.optBoolean("imported", false),
                worldBook = json.optJSONObject("world_book")?.let { runCatching { StoryWorldBook.fromJson(it) } }.getOrNull() ?: StoryWorldBook()
            )
        }
    }
}

fun parseUserPersona(raw: String): String {
    val json = JSONObject(extractJsonObject(raw))
    return json.optString("persona").trim().also { require(it.isNotBlank()) { "身份卡是空的" } }
}

/** Confirmed {{char}} card and {{user}} persona sent with later story requests. */
data class StoryCastContext(
    val card: StoryCharacterCard? = null,
    val userPersona: String = ""
) {
    fun toPromptBlock(worldBefore: String = "", worldAfter: String = ""): String = buildString {
        card?.let { card ->
            if (worldBefore.isNotBlank()) {
                append("[角色世界书；按剧情相关程度自动注入]\n")
                append(worldBefore.trim())
                append("\n\n")
            }
            append("[已确认的对方角色卡。这是故事里的对方，不是用户]\n")
            append("名字：").append(card.name)
            if (card.description.isNotBlank()) append("\n这个人：").append(card.description)
            if (card.personality.isNotBlank()) append("\n性格：").append(card.personality)
            if (card.scenario.isNotBlank()) append("\n这部故事：").append(card.scenario)
            if (card.systemPrompt.isNotBlank()) append("\n\n[角色卡的写作要求]\n").append(card.systemPrompt)
            if (card.mesExample.isNotBlank()) append("\n\n[对话示例，不是已经发生的正文]\n").append(card.mesExample)
            if (card.postHistory.isNotBlank()) append("\n\n[写的时候仍要遵守]\n").append(card.postHistory)
            if (worldAfter.isNotBlank()) append("\n\n[角色世界书]\n").append(worldAfter.trim())
            if (card.imported) append("\n\n只输出正文。不要输出网页、按钮、状态栏、样式或角色卡界面。")
        }
        if (userPersona.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append("[已确认的用户身份。这是用户在故事里的位置，不是对方]\n")
            append(userPersona.trim())
        }
    }.trim()

    companion object {
        fun fromStory(story: Story): StoryCastContext = StoryCastContext(
            card = StoryCharacterCard.fromStored(story.charCardJson),
            userPersona = story.userPersona
        )
    }
}

internal fun extractJsonObject(raw: String): String {
    val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.get(1)
    val source = fenced ?: raw
    val start = source.indexOf('{')
    val end = source.lastIndexOf('}')
    require(start >= 0 && end > start) { "模型没有返回可解析的设定" }
    return source.substring(start, end + 1)
}

internal const val STORY_CHAR_CARD_PROMPT =
    "根据讨论，只输出一个 JSON 对象，不要解释，不要写开场白，不要编造讨论里没说定的内容。这是故事里的对方（{{char}}），不是用户。" +
        "name 是对方的名字。description 只写这个人是谁，不要写整部作品。personality 是性格。" +
        "scenario 必须是这部作品本身：哪部原作的同人、世界观、局面、已商定的剧情，以及这个人在其中的位置。不要把人物小传再抄一遍。讨论若是一部同人，scenario 不能空。"

internal const val STORY_PERSONA_PROMPT =
    "根据讨论和用户要担任的身份，只输出一个 JSON 对象，不要解释。字段只有 persona：说明用户（{{user}}）是谁，以及在这部已商定的作品里处于什么位置。不要写开场白，不要把对方的人物小传再写一遍。"
