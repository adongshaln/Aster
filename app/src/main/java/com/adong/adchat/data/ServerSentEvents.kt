package com.adong.adchat.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException

/** SSE with bounded line reads, plus compatibility with gateways emitting NDJSON. */
internal suspend fun readSsePayloads(
    source: BufferedSource,
    maxEventChars: Int = 4 * 1024 * 1024,
    onPayload: suspend (String) -> Boolean
) {
    require(maxEventChars > 0)
    val pending = StringBuilder()
    suspend fun flushPending(): Boolean {
        if (pending.isEmpty()) return true
        val payload = pending.toString()
        pending.clear()
        if (payload.isBlank()) return true
        return onPayload(payload)
    }

    var firstLine = true
    while (true) {
        currentCoroutineContext().ensureActive()
        // readUtf8Line() grows the source buffer without bound before the old size check ran.
        // Permit up to four UTF-8 bytes per character, plus the SSE field prefix and CRLF.
        val lineLimit = maxEventChars.toLong() * 4 + 8
        val newline = source.indexOf('\n'.code.toByte(), 0, lineLimit + 1)
        val rawLine = if (newline >= 0) {
            source.readUtf8(newline).also { source.skip(1) }
        } else {
            if (source.buffer.size > lineLimit) throw IOException("SSE line exceeded the safety limit")
            if (source.buffer.size == 0L) break
            source.readUtf8()
        }
        val line = rawLine.removeSuffix("\r").let { if (firstLine) it.removePrefix("\uFEFF") else it }
        firstLine = false
        if (line.length > maxEventChars + 6) throw IOException("SSE line exceeded the safety limit")
        when {
            line.isEmpty() -> if (!flushPending()) return
            line.startsWith(":") -> Unit
            line.startsWith("data:") -> {
                // Retain compatibility with gateways omitting blank lines between JSON events.
                if (pending.isNotEmpty() && pending.isStandalonePayload()) {
                    if (!flushPending()) return
                }
                if (pending.isNotEmpty()) pending.append('\n')
                pending.append(line.substring(5).removePrefix(" "))
                if (pending.length > maxEventChars) throw IOException("SSE event exceeded the safety limit")
            }
            line.trimStart().startsWith("{") || line.trim() == "[DONE]" -> {
                if (!flushPending()) return
                if (line.length > maxEventChars) throw IOException("SSE event exceeded the safety limit")
                if (!onPayload(line.trim())) return
            }
        }
    }
    flushPending()
}

private fun StringBuilder.isStandalonePayload(): Boolean {
    val value = toString().trim()
    return value == "[DONE]" || runCatching { JSONObject(value) }.isSuccess
}

/** Unknown JSON events are forward-compatible; corrupt JSON must never hide a lost text delta. */
internal fun parseSseObject(payload: String): JSONObject = try {
    JSONObject(payload)
} catch (error: Exception) {
    throw IllegalStateException("模型返回了无效的流式 JSON 数据，请重试", error)
}
