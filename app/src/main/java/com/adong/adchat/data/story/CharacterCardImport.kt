package com.adong.adchat.data.story

import org.json.JSONObject
import java.util.Base64
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

data class ImportedCharacterCard(
    val card: StoryCharacterCard,
    val opening: String
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
        val name = data.optString("name").trim().ifBlank { data.optString("nickname").trim() }
        require(name.isNotBlank()) { "角色卡没有名字" }
        val description = prose(data.optString("description"), name)
        val personality = prose(data.optString("personality"), name)
        val scenario = prose(data.optString("scenario"), name)
        val example = prose(data.optString("mes_example"), name)
        val systemPrompt = prose(data.optString("system_prompt"), name)
        val postHistory = prose(data.optString("post_history_instructions"), name)
        val openingSource = data.optString("first_mes").ifBlank {
            data.optJSONArray("alternate_greetings")?.optString(0).orEmpty()
        }
        val opening = prose(openingSource, name)
        val importedBook = data.optJSONObject("character_book")?.let { parseWorldBook(it, name) } ?: StoryWorldBook()
        require(description.isNotBlank() || personality.isNotBlank() || scenario.isNotBlank() || opening.isNotBlank() || importedBook.entries.isNotEmpty()) {
            "角色卡里没有可用的正文"
        }
        return ImportedCharacterCard(
            card = StoryCharacterCard(
                name = name,
                description = description,
                personality = personality,
                scenario = scenario,
                mesExample = example,
                systemPrompt = systemPrompt,
                postHistory = postHistory,
                imported = true,
                worldBook = importedBook
            ),
            opening = opening
        )
    }

    private fun parseWorldBook(book: JSONObject, charName: String): StoryWorldBook {
        val entryArray = book.optJSONArray("entries") ?: book.optJSONObject("entries")?.let { map ->
            org.json.JSONArray().apply { map.keys().forEach { key -> map.optJSONObject(key)?.let(::put) } }
        }
        val entries = entryArray?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? JSONObject)?.let { parseWorldEntry(it, charName) }
            }
        }.orEmpty()
        val budget = book.optInt("token_budget", 0).let { if (it > 0) (it * 4).coerceAtMost(20_000) else 20_000 }
        return StoryWorldBook(
            entries = entries,
            scanDepth = book.optInt("scan_depth", 4).let { if (it > 0) it else 4 },
            charBudget = budget,
            recursive = book.optBoolean("recursive_scanning", true)
        )
    }

    private fun parseWorldEntry(item: JSONObject, charName: String): StoryWorldBookEntry {
        val keys = item.optJSONArray("key")?.stringList().orEmpty() + item.optJSONArray("keys")?.stringList().orEmpty()
        val secondary = item.optJSONArray("secondary_keys")?.stringList().orEmpty()
        val logic = when (item.opt("selective_logic")) {
            0 -> WorldLogic.AND_ANY
            1 -> WorldLogic.NOT_ANY
            2 -> WorldLogic.AND_ALL
            3 -> WorldLogic.NOT_ALL
            else -> WorldLogic.AND_ANY
        }
        val beforeChar = when (val position = item.opt("position")) {
            "before_char" -> true
            "after_char" -> false
            0 -> true
            else -> false
        }
        return StoryWorldBookEntry(
            keys = keys,
            secondaryKeys = if (item.optBoolean("selective", true)) secondary else emptyList(),
            logic = logic,
            content = prose(item.optString("content").orEmpty(), charName),
            enabled = item.optBoolean("enabled", true),
            constant = item.optBoolean("constant", false),
            insertionOrder = item.optInt("insertion_order", 100),
            beforeChar = beforeChar
        )
    }

    private fun org.json.JSONArray.stringList(): List<String> =
        (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotEmpty) }
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
