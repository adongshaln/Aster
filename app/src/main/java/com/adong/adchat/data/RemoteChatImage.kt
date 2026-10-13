package com.adong.adchat.data

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal const val CHAT_IMAGE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

private const val MAX_CHAT_IMAGE_BYTES = 15 * 1024 * 1024

private val chatImageClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .applyNetworkTrust()
        .build()
}

internal fun refererForImage(url: String): String? {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase().orEmpty()
    if (host.endsWith("pximg.net") || host.endsWith("pixiv.net")) return "https://www.pixiv.net/"
    return null
}

internal fun chatImageHeaders(url: String): List<Pair<String, String>> {
    val headers = mutableListOf(
        "User-Agent" to CHAT_IMAGE_USER_AGENT,
        "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8"
    )
    refererForImage(url)?.let { headers += "Referer" to it }
    return headers
}

internal fun looksLikeImage(bytes: ByteArray, contentType: String?): Boolean {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (type.startsWith("text/") || type.contains("html") || type.contains("json")) return false
    if (type.startsWith("image/")) return bytes.isNotEmpty()
    if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
    if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
    if (bytes.size >= 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) return true
    if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()) return true
    return false
}

/** Downloads a remote image when the direct viewer is refused. Null means it is not an image. */
internal fun downloadChatImage(url: String): ByteArray? {
    val builder = Request.Builder().url(url).get()
    chatImageHeaders(url).forEach { (name, value) -> builder.header(name, value) }
    chatImageClient.newCall(builder.build()).execute().use { response ->
        if (!response.isSuccessful) return null
        val type = response.header("Content-Type")
        val stream = response.body?.byteStream() ?: return null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_CHAT_IMAGE_BYTES) return null
            output.write(buffer, 0, read)
        }
        val bytes = output.toByteArray()
        return bytes.takeIf { looksLikeImage(it, type) }
    }
}

internal fun parsePixivPreviewUrl(json: String): String? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (root.optBoolean("error")) return null
    val urls = root.optJSONObject("body")?.optJSONObject("urls") ?: return null
    return listOf("regular", "small", "thumb", "original").firstNotNullOfOrNull { key ->
        urls.optString(key).takeIf { it.startsWith("http") }
    }
}

/** Public artwork pages do not include a hotlinkable image. The ajax payload does. */
internal fun fetchPixivPreviewUrl(id: String): String? {
    if (id.isEmpty() || id.any { !it.isDigit() }) return null
    val request = Request.Builder()
        .url("https://www.pixiv.net/ajax/illust/$id")
        .header("User-Agent", CHAT_IMAGE_USER_AGENT)
        .header("Referer", "https://www.pixiv.net/")
        .header("Accept", "application/json")
        .get()
        .build()
    chatImageClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        return parsePixivPreviewUrl(response.body?.string().orEmpty())
    }
}
