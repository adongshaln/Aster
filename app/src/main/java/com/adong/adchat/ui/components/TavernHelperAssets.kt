package com.adong.adchat.ui.components

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Offline vendored runtime for tavern_helper-compatible sandboxes (lodash, js-yaml, jQuery, zod, Vue,
 * Font Awesome — all MIT/OFL — plus the clean-room shim). Libraries are served from
 * `https://aster.local/th/` and well-known CDN URLs are mapped to the same local copies.
 */
internal object TavernHelperAssets {
    @Volatile
    private var cached: String? = null

    /** `<script>` tags injected at the top of every card frame. */
    fun bundle(context: Context): String = cached ?: synchronized(this) {
        cached ?: run {
            val shim = context.assets.open("tavern_helper_shim.js").bufferedReader().use { it.readText() }
            val base = "${HtmlPreviewDocument.ORIGIN}/th/"
            listOf("lodash.min.js", "js-yaml.min.js", "jquery.min.js", "zod.umd.js")
                .joinToString("") { "<script src=\"$base$it\"></script>" } +
                "<link rel=\"stylesheet\" href=\"${base}fa.min.css\">" +
                "<script>${shim.replace("</script", "<\\/script", ignoreCase = true)}</script>"
        }.also { cached = it }
    }

    private val local = mapOf(
        "lodash" to "lodash.min.js", "js-yaml" to "js-yaml.min.js", "jquery" to "jquery.min.js",
        "zod" to "zod.umd.js", "vue" to "vue.global.prod.js", "tailwind" to "tailwind.js"
    )
    private val cdnHosts = setOf("cdn.jsdelivr.net", "testingcf.jsdelivr.net", "fastly.jsdelivr.net", "unpkg.com",
        "cdnjs.cloudflare.com", "code.jquery.com", "cdn.tailwindcss.com", "use.fontawesome.com", "ajax.googleapis.com")

    /**
     * Resolves a request from a card frame. Returns a local asset, an empty stub for known-but-unneeded
     * scripts (MagVarUpdate's own bundle: MVU runs natively), or null when the request is not ours.
     */
    fun resolve(context: Context, url: String): WebResourceResponse? {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        if (host == "aster.local" && path.startsWith("/th/")) return asset(context, path.removePrefix("/th/"))
        if (host == "aster.local" && path.startsWith("/webfonts/")) return asset(context, path.removePrefix("/webfonts/"))
        if (host !in cdnHosts && !host.endsWith("jsdelivr.net")) return null
        if ("magvarupdate" in path || "mvu" in path.substringAfterLast('/')) return text("", "application/javascript")
        if ("fontawesome" in path || "font-awesome" in path) {
            return when {
                path.endsWith(".css") -> asset(context, "fa.min.css")
                path.endsWith(".woff2") -> asset(context, path.substringAfterLast('/'))
                else -> null
            }
        }
        if ("tailwind" in host || "tailwind" in path) return asset(context, "tailwind.js")
        val name = path.substringAfterLast('/')
        local.entries.firstOrNull { (key, _) -> name.startsWith(key) || "/$key@" in path || "/$key/" in path }
            ?.let { return asset(context, it.value) }
        return null
    }

    private fun asset(context: Context, name: String): WebResourceResponse? {
        if (name.contains("..") || name.contains('/')) return null
        val bytes = runCatching { context.assets.open(name).use { it.readBytes() } }.getOrNull() ?: return null
        val mime = when {
            name.endsWith(".js") -> "application/javascript"
            name.endsWith(".css") -> "text/css"
            name.endsWith(".woff2") -> "font/woff2"
            else -> "application/octet-stream"
        }
        return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes)).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400")
        }
    }

    private fun text(body: String, mime: String) = WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body.toByteArray()))

    /** Denies every other non-image request (scripts, fetch, fonts, frames) from card content. */
    fun blocked(): WebResourceResponse = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
