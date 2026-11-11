package com.adong.adchat.data.story

import org.json.JSONObject
import java.util.Base64
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

data class ImportedCharacterCard(
    val card: StoryCharacterCard,
    val opening: String,
    val greetings: List<String> = emptyList(),
    /**
     * The card exactly as written: no macro expansion, no prose cleaning, `<START>`/HTML/YAML kept and
     * world entries raw. RP mode builds prompts from this; [card] stays the Story-mode cleaned view.
     */
    val rawCard: StoryCharacterCard = card
)

object CharacterCardFile {
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val MAX_BYTES = 32 * 1024 * 1024

    fun parse(bytes: ByteArray): ImportedCharacterCard {
        require(bytes.isNotEmpty()) { "文件是空的" }
        require(bytes.size <= MAX_BYTES) { "角色卡文件太大" }
        val trimmed = bytes.dropWhile { it == ' '.code.toByte() || it == '\n'.code.toByte() || it == '\r'.code.toByte() || it == '\t'.code.toByte() }
        return when {
            trimmed.firstOrNull() == '{'.code.toByte() -> parseJson(bytes.decodeToString())
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG) -> parsePng(bytes)
            bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() -> parseCharx(bytes)
            else -> error("请选择 JSON、PNG 或 charx 角色卡")
        }
    }

    private fun parsePng(bytes: ByteArray): ImportedCharacterCard {
        var offset = 8
        var v3: String? = null
        var v2: String? = null
        while (offset + 12 <= bytes.size) {
            val length = readU32(bytes, offset)
            if (length < 0 || length > bytes.size || offset + 12L + length > bytes.size) break
            val type = bytes.decodeToString(offset + 4, offset + 8)
            val data = bytes.copyOfRange(offset + 8, offset + 8 + length.toInt())
            if (type == "tEXt" || type == "zTXt" || type == "iTXt") {
                val chunk = runCatching { decodeTextChunk(type, data) }.getOrNull()
                when (chunk?.first) {
                    "ccv3" -> v3 = chunk.second
                    "chara" -> v2 = chunk.second
                }
            }
            if (type == "IEND") break
            offset += 12 + length.toInt()
        }
        val payload = v3 ?: v2 ?: error("这张图片里没有角色卡")
        return parseJson(decodeCardPayload(payload))
    }

    private fun parseCharx(bytes: ByteArray): ImportedCharacterCard {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/').equals("card.json", true)) {
                    return parseJson(zip.readBytes().decodeToString())
                }
                entry = zip.nextEntry
            }
        }
        error("这个压缩包里没有 card.json")
    }

    internal fun parseJson(raw: String): ImportedCharacterCard {
        val root = JSONObject(extractJsonObject(raw))
        val data = root.optJSONObject("data") ?: root
        val nickname = data.optString("nickname").trim()
        val name = data.optString("name").trim().ifBlank { nickname }
        require(name.isNotBlank()) { "角色卡没有名字" }
        val macroName = nickname.ifBlank { name }
        val extensions = data.optJSONObject("extensions")
        val description = prose(data.optString("description"), macroName)
        val personality = prose(data.optString("personality"), macroName)
        val scenario = prose(data.optString("scenario"), macroName)
        val example = prose(data.optString("mes_example"), macroName)
        val systemPrompt = prose(data.optString("system_prompt"), macroName)
        val postHistory = prose(data.optString("post_history_instructions"), macroName)
        // Greetings keep their raw HTML for sandbox frontend rendering; only macros are expanded.
        val firstMes = expandCardMacros(data.optString("first_mes"), macroName)
        val alternates = data.optJSONArray("alternate_greetings").rawGreetings(macroName)
        val groupOnly = data.optJSONArray("group_only_greetings").rawGreetings(macroName)
        val greetings = (listOf(firstMes) + alternates).filter { it.isNotBlank() }
        val opening = greetings.firstOrNull().orEmpty()
        val importedBook = data.optJSONObject("character_book")?.let { parseWorldBook(it, macroName) } ?: StoryWorldBook()
        require(description.isNotBlank() || personality.isNotBlank() || scenario.isNotBlank() || opening.isNotBlank() || importedBook.entries.isNotEmpty()) {
            "角色卡里没有可用的正文"
        }
        val cleaned = StoryCharacterCard(
            name = name,
            description = description,
            personality = personality,
            scenario = scenario,
            mesExample = example,
            systemPrompt = systemPrompt,
            postHistory = postHistory,
            imported = true,
            worldBook = importedBook,
            nickname = nickname,
            creatorNotes = creatorNotes(data),
            tags = data.optJSONArray("tags").stringList(),
            creator = data.optString("creator").trim(),
            characterVersion = data.optString("character_version").trim(),
            source = data.optJSONArray("source").stringList(),
            creationDate = data.optLong("creation_date").takeIf { it > 0 },
            modificationDate = data.optLong("modification_date").takeIf { it > 0 },
            firstMes = firstMes,
            alternateGreetings = alternates,
            groupOnlyGreetings = groupOnly,
            depthPrompt = extensions?.optJSONObject("depth_prompt")?.let {
                StoryDepthPrompt(
                    depth = it.optInt("depth", 4),
                    prompt = expandCardMacros(it.optString("prompt"), macroName),
                    role = it.optString("role").trim().lowercase().ifBlank { "system" }
                )
            },
            talkativeness = extensions?.optDouble("talkativeness", Double.NaN)?.takeIf { it.isFinite() },
            extensionsRaw = extensions?.toString() ?: "{}"
        )
        val rawBook = data.optJSONObject("character_book")?.let { parseWorldBook(it, macroName, raw = true) } ?: StoryWorldBook()
        val rawCard = cleaned.copy(
            description = data.optString("description"),
            personality = data.optString("personality"),
            scenario = data.optString("scenario"),
            mesExample = data.optString("mes_example"),
            systemPrompt = data.optString("system_prompt"),
            postHistory = data.optString("post_history_instructions"),
            worldBook = rawBook,
            creatorNotes = data.optString("creator_notes").ifEmpty { cleaned.creatorNotes },
            firstMes = data.optString("first_mes"),
            alternateGreetings = data.optJSONArray("alternate_greetings").rawStrings(),
            groupOnlyGreetings = data.optJSONArray("group_only_greetings").rawStrings(),
            depthPrompt = extensions?.optJSONObject("depth_prompt")?.let {
                StoryDepthPrompt(
                    depth = it.optInt("depth", 4),
                    prompt = it.optString("prompt"),
                    role = it.optString("role").trim().lowercase().ifBlank { "system" }
                )
            }
        )
        return ImportedCharacterCard(card = cleaned, opening = opening, greetings = greetings, rawCard = rawCard)
    }

    private fun org.json.JSONArray?.rawStrings(): List<String> =
        this?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }.orEmpty()

    private fun creatorNotes(data: JSONObject): String {
        val multilingual = data.optJSONObject("creator_notes_multilingual")
        if (multilingual != null) {
            for (key in listOf("zh", "zh-CN", "zh-Hans", "en")) {
                val value = multilingual.optString(key).trim()
                if (value.isNotEmpty()) return value
            }
        }
        return data.optString("creator_notes").trim()
    }

    private fun org.json.JSONArray?.rawGreetings(macroName: String): List<String> =
        this?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                expandCardMacros(array.optString(index), macroName).takeIf(String::isNotBlank)
            }
        }.orEmpty()

    private fun parseWorldBook(book: JSONObject, charName: String, raw: Boolean = false): StoryWorldBook {
        val entryArray = book.optJSONArray("entries") ?: book.optJSONObject("entries")?.let { map ->
            org.json.JSONArray().apply { map.keys().forEach { key -> map.optJSONObject(key)?.let(::put) } }
        }
        val entries = entryArray?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? JSONObject)?.let { parseWorldEntry(it, charName, index, raw) }
            }
        }.orEmpty()
        val tokenBudget = book.optInt("token_budget", 0).takeIf { it > 0 }
        val budget = tokenBudget?.let { (it * 4).coerceAtMost(20_000) } ?: 20_000
        return StoryWorldBook(
            entries = entries,
            scanDepth = book.optInt("scan_depth", 4).let { if (it > 0) it else 4 },
            charBudget = budget,
            recursive = book.optBoolean("recursive_scanning", true),
            name = book.optString("name"),
            tokenBudget = tokenBudget,
            bookScanDepth = if (book.has("scan_depth") && !book.isNull("scan_depth")) book.optInt("scan_depth", 2).coerceIn(0, 1000) else null,
            recursiveScanning = if (book.has("recursive_scanning") && !book.isNull("recursive_scanning")) book.optBoolean("recursive_scanning") else null
        )
    }

    /** Mirrors SillyTavern convertCharacterBook: ST-only fields live in entry.extensions (snake_case). */
    private fun parseWorldEntry(item: JSONObject, charName: String, index: Int, raw: Boolean): StoryWorldBookEntry {
        val ext = item.optJSONObject("extensions") ?: JSONObject()
        val keys = item.optJSONArray("key")?.stringList().orEmpty() + item.optJSONArray("keys")?.stringList().orEmpty()
        val secondary = item.optJSONArray("secondary_keys")?.stringList().orEmpty()
        val stLogic = ext.intOrNull("selectiveLogic") ?: ext.intOrNull("selective_logic") ?: item.intOrNull("selective_logic") ?: 0
        val logic = WorldLogic.fromSt(stLogic)
        val position = ext.intOrNull("position") ?: when (val p = item.opt("position")) {
            "before_char" -> 0
            is Number -> if (p.toInt() == 0) 0 else 1
            else -> 1
        }
        val content = item.optString("content").orEmpty()
        return StoryWorldBookEntry(
            keys = keys,
            secondaryKeys = if (raw || item.optBoolean("selective", true)) secondary else emptyList(),
            logic = logic,
            content = if (raw) content else prose(content, charName),
            enabled = item.optBoolean("enabled", true),
            constant = item.optBoolean("constant", false),
            insertionOrder = item.optInt("insertion_order", 100),
            beforeChar = position == 0,
            caseSensitive = ext.boolOrNull("case_sensitive") ?: item.optBoolean("case_sensitive", false),
            priority = item.optInt("priority", 0),
            comment = item.optString("comment"),
            entryName = item.optString("name"),
            extensionsRaw = item.opt("extensions")?.toString() ?: "{}",
            uid = item.intOrNull("id") ?: index,
            position = position,
            depth = ext.intOrNull("depth") ?: 4,
            role = ext.intOrNull("role") ?: 0,
            probability = ext.intOrNull("probability") ?: 100,
            useProbability = ext.boolOrNull("useProbability") ?: ext.boolOrNull("use_probability") ?: true,
            group = ext.optString("group"),
            groupOverride = ext.boolOrNull("group_override") ?: false,
            groupWeight = ext.intOrNull("group_weight") ?: 100,
            useGroupScoring = ext.boolOrNull("use_group_scoring"),
            sticky = ext.intOrNull("sticky"),
            cooldown = ext.intOrNull("cooldown"),
            delay = ext.intOrNull("delay"),
            scanDepth = ext.intOrNull("scan_depth"),
            matchWholeWords = ext.boolOrNull("match_whole_words"),
            caseSensitiveOverride = ext.boolOrNull("case_sensitive") ?: item.boolOrNull("case_sensitive"),
            excludeRecursion = ext.boolOrNull("exclude_recursion") ?: false,
            preventRecursion = ext.boolOrNull("prevent_recursion") ?: false,
            delayUntilRecursion = when (val d = ext.opt("delay_until_recursion")) {
                true -> 1
                is Number -> d.toInt().coerceAtLeast(0)
                else -> 0
            },
            ignoreBudget = ext.boolOrNull("ignore_budget") ?: false,
            matchPersonaDescription = ext.boolOrNull("match_persona_description") ?: false,
            matchCharacterDescription = ext.boolOrNull("match_character_description") ?: false,
            matchCharacterPersonality = ext.boolOrNull("match_character_personality") ?: false,
            matchCharacterDepthPrompt = ext.boolOrNull("match_character_depth_prompt") ?: false,
            matchScenario = ext.boolOrNull("match_scenario") ?: false,
            matchCreatorNotes = ext.boolOrNull("match_creator_notes") ?: false,
            outletName = ext.optString("outlet_name"),
            triggers = ext.optJSONArray("triggers")?.stringList().orEmpty(),
            selective = item.optBoolean("selective", false),
            selectiveLogic = stLogic,
            automationId = ext.optString("automation_id")
        )
    }

    private fun JSONObject.intOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toInt() ?: optString(key).trim().toIntOrNull()

    private fun JSONObject.boolOrNull(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else when (val v = opt(key)) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull()
            else -> null
        }

    private fun org.json.JSONArray?.stringList(): List<String> =
        this?.let { array -> (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotEmpty) } }.orEmpty()
}

private fun prose(raw: String, charName: String): String = cardProse(expandCardMacros(raw, charName))

internal fun cardProse(raw: String): String {
    if (raw.isBlank()) return ""
    var text = raw.replace("\u0000", "")
    text = Regex("(?is)<!--.*?-->").replace(text, "")
    text = Regex("(?is)<(script|style|svg|iframe|canvas|form|button|head)\\b[^>]*>.*?</\\1>").replace(text, "")
    text = Regex("(?is)<(script|style|svg|img|input|button|link|meta|source|track)\\b[^>]*?/?>").replace(text, "")
    text = Regex("(?is)<br\\s*/?>").replace(text, "\n")
    text = Regex("(?is)</(p|div|h[1-6]|li|tr|blockquote|section|article)>").replace(text, "\n")
    text = Regex("(?is)<li\\b[^>]*>").replace(text, "\n")
    text = Regex("(?is)<[^>]+>").replace(text, "")
    text = unescapeHtml(text)
    val kept = text.lineSequence().map { it.trim() }.filterNot { line ->
        line.startsWith("{") || line.startsWith("}") ||
            Regex("^[.#]?[a-zA-Z_-][a-zA-Z0-9_-]*\\s*\\{.*").containsMatchIn(line) ||
            Regex("^[a-z-]{2,40}\\s*:\\s*[^\\u4e00-\\u9fff]{0,80};?$").matches(line)
    }.toList()
    return kept.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}

internal fun expandCardMacros(text: String, charName: String): String {
    if (text.isBlank()) return ""
    return text
        .replace(Regex("(?i)\\{\\{\\s*char\\s*\\}\\}"), charName)
        .replace(Regex("(?i)\\{\\{\\s*user\\s*\\}\\}"), "用户")
        .replace(Regex("(?i)<USER>"), "用户")
        .replace(Regex("(?i)<BOT>|<CHAR>"), charName)
        .replace(Regex("(?i)<START>"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun unescapeHtml(text: String): String {
    val named = text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
    return Regex("&#(x?[0-9a-fA-F]+);").replace(named) { match ->
        val body = match.groupValues[1]
        val code = if (body.startsWith("x", true)) body.drop(1).toIntOrNull(16) else body.toIntOrNull()
        code?.takeIf { it in 1..0x10FFFF }?.let { Character.toString(it) } ?: match.value
    }
}

private fun decodeCardPayload(payload: String): String {
    val trimmed = payload.trim()
    if (trimmed.startsWith("{")) return trimmed
    return Base64.getMimeDecoder().decode(trimmed).decodeToString()
}

private fun decodeTextChunk(type: String, data: ByteArray): Pair<String, String> {
    val split = data.indexOf(0)
    require(split > 0) { "图片文本块没有名字" }
    val key = data.decodeToString(0, split)
    return when (type) {
        "tEXt" -> key to data.decodeToString(split + 1, data.size)
        "zTXt" -> key to inflate(data.copyOfRange(split + 2, data.size)).decodeToString()
        else -> {
            var cursor = split + 1
            val compressed = data.getOrNull(cursor)?.toInt() == 1
            cursor += 2
            while (cursor < data.size && data[cursor] != 0.toByte()) cursor++
            cursor++
            while (cursor < data.size && data[cursor] != 0.toByte()) cursor++
            cursor++
            val text = data.copyOfRange(cursor.coerceAtMost(data.size), data.size)
            key to if (compressed) inflate(text).decodeToString() else text.decodeToString()
        }
    }
}

private fun inflate(data: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(data)
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var guard = 0
    while (!inflater.finished() && guard++ < 10_000) {
        val count = inflater.inflate(buffer)
        if (count == 0) break
        output.write(buffer, 0, count)
    }
    inflater.end()
    return output.toByteArray()
}

private fun readU32(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xff) shl 24) or
        ((bytes[offset + 1].toLong() and 0xff) shl 16) or
        ((bytes[offset + 2].toLong() and 0xff) shl 8) or
        (bytes[offset + 3].toLong() and 0xff)
