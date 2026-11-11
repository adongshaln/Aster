package com.adong.adchat.data.story

import org.json.JSONArray
import org.json.JSONObject

enum class WorldLogic(val st: Int) {
    AND_ANY(0), NOT_ANY(2), AND_ALL(3), NOT_ALL(1);

    companion object {
        /** SillyTavern selectiveLogic numbering: 0 AND_ANY, 1 NOT_ALL, 2 NOT_ANY, 3 AND_ALL. */
        fun fromSt(value: Int): WorldLogic = entries.firstOrNull { it.st == value } ?: AND_ANY
    }
}

/**
 * One lorebook entry. The first block is the original Story-mode shape; the rest mirrors SillyTavern
 * world-info fields (null = fall back to the global default) and is only read by the RP scanner.
 */
data class StoryWorldBookEntry(
    val keys: List<String>,
    val secondaryKeys: List<String> = emptyList(),
    val logic: WorldLogic = WorldLogic.AND_ANY,
    val content: String,
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val insertionOrder: Int = 100,
    val beforeChar: Boolean = false,
    val caseSensitive: Boolean = false,
    val priority: Int = 0,
    val comment: String = "",
    val entryName: String = "",
    val extensionsRaw: String = "{}",
    /** -1 = unset; the scanner then uses the list index. */
    val uid: Int = -1,
    /** 0 before, 1 after, 2 ANTop, 3 ANBottom, 4 atDepth, 5 EMTop, 6 EMBottom, 7 outlet. */
    val position: Int = if (beforeChar) 0 else 1,
    val depth: Int = 4,
    /** 0 system, 1 user, 2 assistant. */
    val role: Int = 0,
    val probability: Int = 100,
    val useProbability: Boolean = true,
    val group: String = "",
    val groupOverride: Boolean = false,
    val groupWeight: Int = 100,
    val useGroupScoring: Boolean? = null,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val delay: Int? = null,
    val scanDepth: Int? = null,
    val matchWholeWords: Boolean? = null,
    val caseSensitiveOverride: Boolean? = null,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    /** 0 = off; N = only activates on recursion level N and later. */
    val delayUntilRecursion: Int = 0,
    val ignoreBudget: Boolean = false,
    val matchPersonaDescription: Boolean = false,
    val matchCharacterDescription: Boolean = false,
    val matchCharacterPersonality: Boolean = false,
    val matchCharacterDepthPrompt: Boolean = false,
    val matchScenario: Boolean = false,
    val matchCreatorNotes: Boolean = false,
    val outletName: String = "",
    val triggers: List<String> = emptyList(),
    val selective: Boolean = true,
    /** SillyTavern numbering; kept in step with [logic]. */
    val selectiveLogic: Int = logic.st,
    val automationId: String = ""
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
        .put("case_sensitive", caseSensitive)
        .put("priority", priority)
        .put("comment", comment)
        .put("name", entryName)
        .put("extensions", extensionsRaw)
        .put("uid", uid)
        .put("position", position)
        .put("depth", depth)
        .put("role", role)
        .put("probability", probability)
        .put("use_probability", useProbability)
        .put("group", group)
        .put("group_override", groupOverride)
        .put("group_weight", groupWeight)
        .putOpt("use_group_scoring", useGroupScoring)
        .putOpt("sticky", sticky)
        .putOpt("cooldown", cooldown)
        .putOpt("delay", delay)
        .putOpt("scan_depth", scanDepth)
        .putOpt("match_whole_words", matchWholeWords)
        .putOpt("case_sensitive_override", caseSensitiveOverride)
        .put("exclude_recursion", excludeRecursion)
        .put("prevent_recursion", preventRecursion)
        .put("delay_until_recursion", delayUntilRecursion)
        .put("ignore_budget", ignoreBudget)
        .put("match_persona_description", matchPersonaDescription)
        .put("match_character_description", matchCharacterDescription)
        .put("match_character_personality", matchCharacterPersonality)
        .put("match_character_depth_prompt", matchCharacterDepthPrompt)
        .put("match_scenario", matchScenario)
        .put("match_creator_notes", matchCreatorNotes)
        .put("outlet_name", outletName)
        .put("triggers", JSONArray(triggers))
        .put("selective", selective)
        .put("selective_logic", selectiveLogic)
        .put("automation_id", automationId)

    companion object {
        fun fromJson(item: JSONObject): StoryWorldBookEntry {
            val stLogic = item.optIntOrNull("selective_logic")
            val logic = stLogic?.let(WorldLogic::fromSt)
                ?: runCatching { WorldLogic.valueOf(item.optString("logic")) }.getOrDefault(WorldLogic.AND_ANY)
            val beforeChar = item.optBoolean("before_char", false)
            return StoryWorldBookEntry(
                keys = item.optJSONArray("keys")?.stringList().orEmpty(),
                secondaryKeys = item.optJSONArray("secondary_keys")?.stringList().orEmpty(),
                logic = logic,
                content = item.optString("content"),
                enabled = item.optBoolean("enabled", true),
                constant = item.optBoolean("constant", false),
                insertionOrder = item.optInt("insertion_order", 100),
                beforeChar = beforeChar,
                caseSensitive = item.optBoolean("case_sensitive", false),
                priority = item.optInt("priority", 0),
                comment = item.optString("comment"),
                entryName = item.optString("name"),
                extensionsRaw = item.opt("extensions")?.toString() ?: "{}",
                uid = item.optInt("uid", -1),
                position = item.optIntOrNull("position") ?: if (beforeChar) 0 else 1,
                depth = item.optInt("depth", 4),
                role = item.optInt("role", 0),
                probability = item.optInt("probability", 100),
                useProbability = item.optBoolean("use_probability", true),
                group = item.optString("group"),
                groupOverride = item.optBoolean("group_override", false),
                groupWeight = item.optInt("group_weight", 100),
                useGroupScoring = item.optBooleanOrNull("use_group_scoring"),
                sticky = item.optIntOrNull("sticky"),
                cooldown = item.optIntOrNull("cooldown"),
                delay = item.optIntOrNull("delay"),
                scanDepth = item.optIntOrNull("scan_depth"),
                matchWholeWords = item.optBooleanOrNull("match_whole_words"),
                caseSensitiveOverride = item.optBooleanOrNull("case_sensitive_override"),
                excludeRecursion = item.optBoolean("exclude_recursion", false),
                preventRecursion = item.optBoolean("prevent_recursion", false),
                delayUntilRecursion = item.optInt("delay_until_recursion", 0),
                ignoreBudget = item.optBoolean("ignore_budget", false),
                matchPersonaDescription = item.optBoolean("match_persona_description", false),
                matchCharacterDescription = item.optBoolean("match_character_description", false),
                matchCharacterPersonality = item.optBoolean("match_character_personality", false),
                matchCharacterDepthPrompt = item.optBoolean("match_character_depth_prompt", false),
                matchScenario = item.optBoolean("match_scenario", false),
                matchCreatorNotes = item.optBoolean("match_creator_notes", false),
                outletName = item.optString("outlet_name"),
                triggers = item.optJSONArray("triggers")?.stringList().orEmpty(),
                selective = item.optBoolean("selective", true),
                selectiveLogic = stLogic ?: logic.st,
                automationId = item.optString("automation_id")
            )
        }
    }
}

/**
 * The card's embedded lorebook (character_book). Active by default; entries are injected by relevance.
 * [scanDepth]/[recursive] keep the Story-mode defaults; [bookScanDepth]/[recursiveScanning] hold what
 * the card actually declared (null = not provided) for the RP scanner.
 */
data class StoryWorldBook(
    val entries: List<StoryWorldBookEntry> = emptyList(),
    val scanDepth: Int = 4,
    val charBudget: Int = 20_000,
    val recursive: Boolean = true,
    val name: String = "",
    val tokenBudget: Int? = null,
    val bookScanDepth: Int? = null,
    val recursiveScanning: Boolean? = null
) {
    fun toJson(): String = JSONObject()
        .put("entries", JSONArray(entries.map { it.toJson() }))
        .put("scan_depth", scanDepth)
        .put("char_budget", charBudget)
        .put("recursive", recursive)
        .put("name", name)
        .putOpt("token_budget", tokenBudget)
        .putOpt("book_scan_depth", bookScanDepth)
        .putOpt("recursive_scanning", recursiveScanning)
        .toString()

    companion object {
        fun parse(raw: String): StoryWorldBook = runCatching { fromJson(JSONObject(raw)) }
            .getOrDefault(StoryWorldBook())

        fun fromJson(json: JSONObject): StoryWorldBook = StoryWorldBook(
            entries = json.optJSONArray("entries")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let(StoryWorldBookEntry::fromJson)
                }
            }.orEmpty(),
            scanDepth = json.optInt("scan_depth", 4),
            charBudget = json.optInt("char_budget", 20_000),
            recursive = json.optBoolean("recursive", true),
            name = json.optString("name"),
            tokenBudget = json.optIntOrNull("token_budget"),
            bookScanDepth = json.optIntOrNull("book_scan_depth"),
            recursiveScanning = json.optBooleanOrNull("recursive_scanning")
        )
    }
}

private fun JSONArray.stringList(): List<String> =
    (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotEmpty) }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toInt() ?: optString(key).trim().toIntOrNull()

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else when (val value = opt(key)) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }

/** SillyTavern extensions.depth_prompt: an instruction injected at a fixed chat depth. */
data class StoryDepthPrompt(
    val depth: Int = 4,
    val prompt: String = "",
    val role: String = "system"
)

/**
 * The {{char}} card. Definition fields (description/personality/…) are cleaned prose for the model;
 * greeting fields keep their raw HTML so the frontend can render them in the sandbox. V2/V3 metadata
 * is preserved for display and round-trip but never sent to the model. New fields default so legacy
 * stored cards and the old story mode stay compatible.
 */
data class StoryCharacterCard(
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val mesExample: String = "",
    val systemPrompt: String = "",
    val postHistory: String = "",
    val imported: Boolean = false,
    val worldBook: StoryWorldBook = StoryWorldBook(),
    val nickname: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val characterVersion: String = "",
    val source: List<String> = emptyList(),
    val creationDate: Long? = null,
    val modificationDate: Long? = null,
    val firstMes: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val groupOnlyGreetings: List<String> = emptyList(),
    val depthPrompt: StoryDepthPrompt? = null,
    val talkativeness: Double? = null,
    val extensionsRaw: String = "{}"
) {
    /** Raw greetings in swipe order: first_mes then each alternate greeting. Blank entries dropped. */
    val greetings: List<String>
        get() = (listOf(firstMes) + alternateGreetings).filter { it.isNotBlank() }

    /** The name {{char}} resolves to: nickname wins over name when present (V3 rule). */
    val macroName: String
        get() = nickname.ifBlank { name }

    /**
     * True when the card carries frontend machinery (structural HTML in a greeting, regex scripts or
     * tavern_helper scripts). Such cards define their own output format, so the blanket "prose only,
     * no status bar" instruction is relaxed for them; the display regex + sandbox render the interface.
     */
    val hasFrontend: Boolean
        get() = greetings.any { FRONTEND_HTML.containsMatchIn(it) } || runCatching {
            val ext = JSONObject(extensionsRaw)
            (ext.optJSONArray("regex_scripts")?.length() ?: 0) > 0 ||
                (ext.optJSONObject("tavern_helper")?.optJSONArray("scripts")?.length() ?: 0) > 0
        }.getOrDefault(false)

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
        .put("nickname", nickname)
        .put("creator_notes", creatorNotes)
        .put("tags", JSONArray(tags))
        .put("creator", creator)
        .put("character_version", characterVersion)
        .put("source", JSONArray(source))
        .put("creation_date", creationDate ?: JSONObject.NULL)
        .put("modification_date", modificationDate ?: JSONObject.NULL)
        .put("first_mes", firstMes)
        .put("alternate_greetings", JSONArray(alternateGreetings))
        .put("group_only_greetings", JSONArray(groupOnlyGreetings))
        .put("depth_prompt", depthPrompt?.let {
            JSONObject().put("depth", it.depth).put("prompt", it.prompt).put("role", it.role)
        } ?: JSONObject.NULL)
        .put("talkativeness", talkativeness ?: JSONObject.NULL)
        .put("extensions_raw", extensionsRaw)
        .toString()

    companion object {
        fun parse(raw: String): StoryCharacterCard =
            read(raw).also { require(it.name.isNotBlank() && it.description.isNotBlank()) { "角色卡缺少名字或描述" } }

        fun fromStored(raw: String): StoryCharacterCard? =
            raw.takeIf { it.isNotBlank() }?.let { runCatching { read(it).also { card -> require(card.name.isNotBlank()) } }.getOrNull() }

        /** Decodes a stored card object without requiring a name (used by RP config round-trip). */
        fun decode(obj: JSONObject): StoryCharacterCard = read(obj.toString())

        private val FRONTEND_HTML = Regex("<(?:div|span|style|script|details|section|article|html|body|table|img|iframe)\\b", RegexOption.IGNORE_CASE)

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
                worldBook = json.optJSONObject("world_book")?.let { obj ->
                    runCatching { StoryWorldBook.fromJson(obj) }.getOrNull()
                } ?: StoryWorldBook(),
                nickname = json.optString("nickname").trim(),
                creatorNotes = json.optString("creator_notes"),
                tags = json.optJSONArray("tags").stringList(),
                creator = json.optString("creator").trim(),
                characterVersion = json.optString("character_version").trim(),
                source = json.optJSONArray("source").stringList(),
                creationDate = json.optLongOrNull("creation_date"),
                modificationDate = json.optLongOrNull("modification_date"),
                firstMes = json.optString("first_mes"),
                alternateGreetings = json.optJSONArray("alternate_greetings").stringList(),
                groupOnlyGreetings = json.optJSONArray("group_only_greetings").stringList(),
                depthPrompt = json.optJSONObject("depth_prompt")?.let {
                    StoryDepthPrompt(
                        depth = it.optInt("depth", 4),
                        prompt = it.optString("prompt"),
                        role = it.optString("role").trim().ifBlank { "system" }
                    )
                },
                talkativeness = json.optDoubleOrNull("talkativeness"),
                extensionsRaw = json.optString("extensions_raw").ifBlank { "{}" }
            )
        }

        private fun JSONArray?.stringList(): List<String> =
            this?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) } }.orEmpty()

        private fun JSONObject.optLongOrNull(key: String): Long? =
            takeIf { has(key) && !isNull(key) }?.optLong(key)?.takeIf { it > 0 }

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            takeIf { has(key) && !isNull(key) }?.optDouble(key)?.takeIf { it.isFinite() }
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
            if (card.imported) {
                if (card.hasFrontend) append("\n\n按角色卡约定的格式输出正文与必要标记；界面由前端规则渲染，不要自行输出完整网页或 CSS 样式。")
                else append("\n\n只输出正文。不要输出网页、按钮、状态栏、样式或角色卡界面。")
            }
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
    val trimmed = raw.trim()
    val candidate = when {
        trimmed.startsWith("{") -> trimmed
        else -> Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(trimmed)?.groupValues?.get(1) ?: trimmed
    }
    // Braces can appear inside prose or string values; accept the first balanced object
    // that is itself valid JSON, and fall back to first-'{'..last-'}' for model prose.
    for (obj in balancedObjects(candidate)) {
        runCatching { JSONObject(obj) }.onSuccess { return obj }
    }
    val start = candidate.indexOf('{')
    val end = candidate.lastIndexOf('}')
    if (start >= 0 && end > start) {
        val sliced = candidate.substring(start, end + 1)
        runCatching { JSONObject(sliced) }.onSuccess { return sliced }
    }
    throw IllegalArgumentException("模型没有返回可解析的设定")
}

/** Every balanced {...} segment in the source, scanned left to right. */
private fun balancedObjects(source: String): Sequence<String> = sequence {
    var depth = 0
    var inString = false
    var escaped = false
    var start = -1
    for (i in source.indices) {
        val ch = source[i]
        if (inString) {
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == '"') inString = false
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{' -> { if (depth == 0) start = i; depth++ }
            '}' -> {
                depth--
                if (depth == 0 && start >= 0) {
                    yield(source.substring(start, i + 1))
                    start = -1
                } else if (depth < 0) {
                    depth = 0
                }
            }
        }
    }
}

internal const val STORY_CHAR_CARD_PROMPT =
    "根据讨论，只输出一个 JSON 对象，不要解释，不要写开场白，不要编造讨论里没说定的内容。这是故事里的对方（{{char}}），不是用户。" +
        "name 是对方的名字。description 只写这个人是谁，不要写整部作品。personality 是性格。" +
        "scenario 必须是这部作品本身：哪部原作的同人、世界观、局面、已商定的剧情，以及这个人在其中的位置。不要把人物小传再抄一遍。讨论若是一部同人，scenario 不能空。"

internal const val STORY_PERSONA_PROMPT =
    "根据讨论和用户要担任的身份，只输出一个 JSON 对象，不要解释。字段只有 persona：说明用户（{{user}}）是谁，以及在这部已商定的作品里处于什么位置。不要写开场白，不要把对方的人物小传再写一遍。"
