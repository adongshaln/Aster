package com.adong.adchat.ui.components

/** A script-capable, opaque-origin iframe. No native bridge, network, forms or top navigation. */
internal object HtmlPreviewDocument {
    const val MAX_PREVIEW_CHARS = 1_000_000

    fun wrap(source: String): String {
        require(source.length <= MAX_PREVIEW_CHARS)
        val escaped = source.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;")
        return """<!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; font-src data:; connect-src 'none'; frame-src about:; object-src 'none'; base-uri 'none'; form-action 'none'">
            <style>html,body{margin:0;height:100%;background:white}iframe{border:0;width:100%;height:100%;display:block}</style>
            </head><body><iframe title="HTML preview" sandbox="allow-scripts" srcdoc="$escaped"></iframe></body></html>""".trimIndent()
    }
}
