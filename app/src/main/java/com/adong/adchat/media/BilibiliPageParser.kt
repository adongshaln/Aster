package com.adong.adchat.media

import org.json.JSONObject

internal object BilibiliPageParser {
    private val initialStatePatterns = listOf(
        Regex("""window\.__INITIAL_STATE__\s*=\s*(\{[\s\S]*?\})\s*;\s*\(function"""),
        Regex("""window\.__INITIAL_STATE__\s*=\s*(\{[\s\S]*?\})\s*;\s*</script>""")
    )

    fun extractVideoData(html: String): JSONObject? {
        val rawState = initialStatePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)
        } ?: return null
        val state = runCatching { JSONObject(rawState) }.getOrNull() ?: return null
        return state.optJSONObject("videoData")
            ?: state.optJSONObject("videoInfo")
            ?: state.takeIf { it.has("bvid") || it.has("aid") }
    }
}
