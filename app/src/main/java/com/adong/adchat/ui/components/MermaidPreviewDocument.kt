package com.adong.adchat.ui.components

internal object MermaidPreviewDocument {
    /** Do not let a quoted closing script tag terminate the bundled library element. */
    fun sanitizeScript(script: String): String = script.replace(Regex("</(?i:script)")) { "<\\/script" }

    /** Source is data inside a JS literal, including line separators and closing script tags. */
    private fun stringLiteral(source: String): String = buildString(source.length + 2) {
        append('\'')
        for (ch in source) when (ch) {
            '\\' -> append("\\\\")
            '\'' -> append("\\'")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '<' -> append("\\u003C")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> append(ch)
        }
        append('\'')
    }

    /** [script] must already have passed through [sanitizeScript] (once per process). */
    fun wrap(source: String, script: String): String = buildString(script.length + source.length + 1024) {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">")
        append("<style>body{margin:0;padding:8px;background:#fff;display:flex;justify-content:center}")
        append("#g{max-width:100%;overflow-x:auto}</style></head><body><div id=\"g\" role=\"img\" aria-label=\"图表\"></div>")
        append("<script>").append(script).append("</script>")
        append("<script>function renderFailed(){var g=document.getElementById('g');g.setAttribute('role','alert');g.textContent='图表渲染失败，请检查图表代码';}try{")
        append("mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'default'});")
        append("mermaid.render('m1', ").append(stringLiteral(source)).append(")")
        append(".then(r=>{document.getElementById('g').innerHTML=r.svg;}).catch(renderFailed);")
        append("}catch(e){renderFailed();}</script></body></html>")
    }
}
