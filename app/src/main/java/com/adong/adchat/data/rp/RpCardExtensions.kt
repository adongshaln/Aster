package com.adong.adchat.data.rp

import com.adong.adchat.data.TavernPresetParser
import com.adong.adchat.data.TavernRegexScript
import com.adong.adchat.data.story.StoryCharacterCard
import org.json.JSONArray
import org.json.JSONObject

/** A script-library button (JSR `button.buttons[]`); clicking emits [eventId] on the helper bus. */
data class RpScriptButton(val scriptId: String, val name: String, val visible: Boolean = true) {
    val eventId: String get() = buttonEventId(scriptId, name)

    companion object {
        /** JSR `getButtonId`: `${script_id}_${getStringHash(name)}`. */
        fun buttonEventId(scriptId: String, name: String): String = scriptId + "_" + stringHash(name)

        /** SillyTavern `getStringHash` (cyrb53, seed 0). */
        fun stringHash(text: String, seed: Int = 0): Long {
            var h1 = -0x21524111 xor seed
            var h2 = 0x41c6ce57 xor seed
            for (ch in text) {
                h1 = (h1 xor ch.code) * -1640531535
                h2 = (h2 xor ch.code) * 1597334677
            }
            h1 = ((h1 xor (h1 ushr 16)) * -2048144789) xor ((h2 xor (h2 ushr 13)) * -1028477387)
            h2 = ((h2 xor (h2 ushr 16)) * -2048144789) xor ((h1 xor (h1 ushr 13)) * -1028477387)
            return 4294967296L * (2097151L and h2.toLong()) + (h1.toLong() and 0xffffffffL)
        }
    }
}

/** One tavern_helper script (folders flattened). [enabled] already folds in its folder's switch. */
data class RpHelperScript(
    val id: String,
    val name: String,
    val content: String,
    val enabled: Boolean,
    val buttons: List<RpScriptButton>,
    val data: JSONObject
)

/**
 * Card-scoped SillyTavern extensions preserved verbatim on import (extensions_raw). The regex scripts
 * run alongside the selected preset's scripts on both prompt and display surfaces; tavern_helper JS
 * scripts run in the offline sandbox through [com.adong.adchat.data.rp.helper.RpTavernHelper].
 */
object RpCardExtensions {
    fun scripts(card: StoryCharacterCard): List<TavernRegexScript> = runCatching {
        TavernPresetParser.parseRegexScripts(JSONObject(card.extensionsRaw).optJSONArray("regex_scripts"))
    }.getOrDefault(emptyList())

    fun helperScriptCount(card: StoryCharacterCard): Int = runCatching {
        TavernPresetParser.parseHelperCount(JSONObject(card.extensionsRaw))
    }.getOrDefault(0)

    /** Legacy adapter: contents of enabled card scripts. */
    fun helperScripts(card: StoryCharacterCard): List<String> =
        helperScripts(card, null).filter { it.enabled && it.content.isNotBlank() }.map { it.content }

    /**
     * Preset scripts first, then the card's (JSR order: global → preset → character). Reads
     * `extensions.tavern_helper.scripts` (object or `[key, value]` pair-array form) and falls back to
     * the legacy `TavernHelper_scripts`. Disabled scripts are returned with `enabled = false`.
     */
    fun helperScripts(card: StoryCharacterCard, presetExtensions: JSONObject? = null): List<RpHelperScript> {
        val cardExtensions = runCatching { JSONObject(card.extensionsRaw) }.getOrDefault(JSONObject())
        return parseTree(presetExtensions) + parseTree(cardExtensions)
    }

    internal fun parseTree(extensions: JSONObject?): List<RpHelperScript> = runCatching {
        if (extensions == null) return emptyList()
        val settings = settingsOf(extensions.opt("tavern_helper"))
        val tree = settings?.optJSONArray("scripts") ?: extensions.optJSONArray("TavernHelper_scripts")
            ?: return emptyList()
        buildList { for (i in 0 until tree.length()) tree.optJSONObject(i)?.let { addNode(it, true, this) } }
    }.getOrDefault(emptyList())

    private fun settingsOf(raw: Any?): JSONObject? = when (raw) {
        is JSONObject -> raw
        is JSONArray -> JSONObject().also { obj ->
            for (i in 0 until raw.length()) raw.optJSONArray(i)?.takeIf { it.length() == 2 }?.let { obj.put(it.optString(0), it.opt(1)) }
        }
        else -> null
    }

    private fun addNode(node: JSONObject, parentEnabled: Boolean, out: MutableList<RpHelperScript>) {
        val type = node.optString("type")
        val value = node.opt("value")
        when {
            // Legacy folder: {type:'folder', value:[ScriptData]} is always enabled.
            type == "folder" && value is JSONArray ->
                for (i in 0 until value.length()) value.optJSONObject(i)?.let { addNode(it, parentEnabled, out) }
            type == "folder" -> {
                val enabled = parentEnabled && node.optBoolean("enabled", false)
                val scripts = node.optJSONArray("scripts") ?: JSONArray()
                for (i in 0 until scripts.length()) scripts.optJSONObject(i)?.let { addNode(it, enabled, out) }
            }
            value is JSONObject -> addNode(value, parentEnabled, out)
            else -> out += script(node, parentEnabled)
        }
    }

    private fun script(node: JSONObject, parentEnabled: Boolean): RpHelperScript {
        val id = node.optString("id").ifBlank { "script-" + RpScriptButton.stringHash(node.optString("name") + node.optString("content")) }
        val button = node.optJSONObject("button")
        val buttonsEnabled = button?.optBoolean("enabled", true) ?: true
        val rawButtons = button?.optJSONArray("buttons") ?: node.optJSONArray("buttons") ?: JSONArray()
        val buttons = (0 until rawButtons.length()).mapNotNull { i ->
            val b = rawButtons.optJSONObject(i) ?: return@mapNotNull null
            val name = b.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            RpScriptButton(id, name, buttonsEnabled && b.optBoolean("visible", true))
        }
        return RpHelperScript(
            id = id,
            name = node.optString("name"),
            content = node.optString("content"),
            enabled = parentEnabled && node.optBoolean("enabled", false),
            buttons = buttons,
            data = node.optJSONObject("data") ?: JSONObject()
        )
    }
}
