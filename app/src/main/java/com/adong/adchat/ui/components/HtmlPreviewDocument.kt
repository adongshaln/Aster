package com.adong.adchat.ui.components

import com.adong.adchat.data.rp.RpHelperScript

/** A script-capable, opaque-origin iframe. No native bridge, network, forms or top navigation. */
internal object HtmlPreviewDocument {
    const val MAX_PREVIEW_CHARS = 1_000_000
    const val PANEL_CAPTURE_MARKER = "data-aster-panel"
    const val PANEL_BRIDGE_MARKER = "data-aster-bridge"
    /** Unique token embedded in both bridge scripts so presenters can strip them on re-render. */
    const val PANEL_TOKEN = "asterpanelbridge"
    /** Origin card frames load from; [TavernHelperAssets] serves vendored libraries under it. */
    const val ORIGIN = "https://aster.local"
    // CDN hosts are listed so their tags reach shouldInterceptRequest, which serves local copies or
    // answers 403; no script/style/font request ever leaves the device.
    private const val CDN = "https://cdn.jsdelivr.net https://*.jsdelivr.net https://unpkg.com https://cdnjs.cloudflare.com " +
        "https://code.jquery.com https://cdn.tailwindcss.com https://use.fontawesome.com https://ajax.googleapis.com"
    private const val HELPER_CSP =
        "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' $ORIGIN $CDN; style-src 'unsafe-inline' $ORIGIN $CDN; " +
            "img-src https: data: blob:; media-src https: data: blob:; font-src data: $ORIGIN $CDN; connect-src 'none'; " +
            "frame-src about:; object-src 'none'; base-uri 'none'; form-action 'none'"

    fun wrap(
        source: String,
        allowScripts: Boolean = true,
        panelBridge: Boolean = false,
        reportHeight: Boolean = false,
        helperBundle: String? = null,
        messageId: Int? = null,
        viewportHeightPx: Int = 800,
        /** Card surfaces may show remote https images; ordinary previews stay fully offline. */
        remoteMedia: Boolean = false,
        /** Part of the document so a bump reloads the page (floor variables changed). */
        revision: Int = 0
    ): String {
        require(source.length <= MAX_PREVIEW_CHARS)
        val helper = helperBundle != null
        val trailing = (if (panelBridge) PANEL_CAPTURE_SCRIPT else "") + (if (reportHeight) HEIGHT_REPORT_SCRIPT else "")
        val body = if (helper) {
            val name = if (messageId != null) "TH-message--$messageId--0" else "TH-preview"
            val head = "<script>window.name=${quote(name)};" +
                (if (messageId != null) "window.__TH_MESSAGE_ID=$messageId;" else "") +
                "</script>" + baseStyles(viewportHeightPx) + helperBundle
            injectHead(replaceVh(source), head, trailing)
        } else (helperBundle ?: "") + source + trailing
        val escaped = escapeAttr(body)
        val scriptPolicy = if (allowScripts) "script-src 'unsafe-inline' 'unsafe-eval';" else "script-src 'none';"
        // allow-same-origin only lets scripts reach OUR wrapper page, whose native surface is the
        // bounded Aster bridge; the WebView client blocks every request outside the asset allowlist.
        val sandboxPolicy = if (allowScripts) {
            if (helper) "allow-scripts allow-same-origin" else "allow-scripts"
        } else ""
        val media = if (remoteMedia) "https: data: blob:" else "data: blob:"
        val csp = if (helper) HELPER_CSP else
            "default-src 'none'; $scriptPolicy style-src 'unsafe-inline'; img-src $media; media-src $media; font-src data:; connect-src 'none'; frame-src about:; object-src 'none'; base-uri 'none'; form-action 'none'"
        val parentListener = (if (panelBridge || reportHeight) PANEL_LISTENER_SCRIPT else "") + (if (helper) FRAME_FORWARDER_SCRIPT else "")
        return """<!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="$csp">
            <style>html,body{margin:0;height:100%;background:transparent}iframe{border:0;width:100%;height:100%;display:block;background:transparent}</style>
            </head><body><!--rev:$revision--><iframe title="HTML preview" sandbox="$sandboxPolicy" srcdoc="$escaped"></iframe>$parentListener</body></html>""".trimIndent()
    }

    /**
     * Hidden page running a card's tavern_helper script library (JSR "script" frames). Each enabled
     * script runs as an ES module with its own id so getScriptId/getButtonEvent resolve per script.
     */
    fun engine(scripts: List<RpHelperScript>, bundle: String): String {
        // One frame per script (as JS-Slash-Runner does) so getScriptId/getButtonEvent and script-scoped
        // variables stay bound to their own script after awaits and callbacks.
        val frames = scripts.filter { it.enabled && it.content.isNotBlank() }.joinToString("") { script ->
            val code = stripFence(script.content).replace("</script", "<\\/script", ignoreCase = true)
            val doc = "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<script>window.name=${quote("TH-script--${script.name}--${script.id}")};" +
                "window.__TH_SCRIPT_ID=${quote(script.id)};window.__TH_SCRIPT_NAME=${quote(script.name)};</script>" +
                "$bundle</head><body><script type=\"module\">\n$code\n</script></body></html>"
            "<iframe sandbox=\"allow-scripts allow-same-origin\" srcdoc=\"${escapeAttr(doc)}\"></iframe>"
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"$HELPER_CSP\"></head><body>$frames$FRAME_FORWARDER_SCRIPT</body></html>"
    }

    private fun escapeAttr(text: String): String =
        text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private val detailsBlock = Regex("(?is)<details\\b.*?</details>")
    private val invisible = Regex("(?is)<(?:style|script|head|title)\\b.*?</(?:style|script|head|title)>|<[^>]+>|&nbsp;")

    /**
     * First-frame height (dp) before the page reports its own. A block that is only collapsed
     * `<details>` sections renders one summary line each; starting it at the full default height
     * makes it shrink after load, which pulls more cards into a scrolling list at once.
     */
    fun initialHeightDp(source: String, default: Int = 340): Int {
        val count = detailsBlock.findAll(source).count()
        if (count == 0) return default
        val rest = invisible.replace(detailsBlock.replace(source, ""), "").trim()
        if (rest.length > 24) return default
        return (count * 52 + 16).coerceIn(56, default)
    }

    /** JSR isFrontend: a fenced block is a page when it carries document structure. */
    fun isFrontend(content: String): Boolean {
        val lower = content.lowercase()
        return "html>" in lower || "<head>" in lower || "<body" in lower
    }

    private fun quote(text: String): String = org.json.JSONObject.quote(text).replace("</", "<\\/")

    private fun stripFence(code: String): String {
        val trimmed = code.trim()
        val fence = Regex("^```[\\w-]*\\n([\\s\\S]*?)\\n?```$").find(trimmed) ?: return code
        return fence.groupValues[1]
    }

    private fun baseStyles(viewportHeightPx: Int): String =
        "<style>:root{--TH-viewport-height:${viewportHeightPx}px}*,*::before,*::after{box-sizing:border-box}" +
            "html,body{margin:0!important;padding:0;max-width:100%!important;overflow-x:hidden!important;overflow-y:hidden!important;background:transparent}" +
            "img,video,canvas,svg{max-width:100%}</style>"

    private val headOpen = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val htmlOpen = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val doctype = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE)
    private val bodyClose = Regex("</body\\s*>", RegexOption.IGNORE_CASE)

    /** Keeps the card's doctype first (no quirks mode) and puts our runtime at the top of <head>. */
    internal fun injectHead(source: String, head: String, trailing: String): String {
        val withTrailing = bodyClose.findAll(source).lastOrNull()?.let {
            source.substring(0, it.range.first) + trailing + source.substring(it.range.first)
        } ?: (source + trailing)
        headOpen.find(withTrailing)?.let { return withTrailing.replaceRange(it.range.last + 1, it.range.last + 1, head) }
        htmlOpen.find(withTrailing)?.let { return withTrailing.replaceRange(it.range.last + 1, it.range.last + 1, "<head>$head</head>") }
        val dt = doctype.find(withTrailing)
        val rest = if (dt != null) withTrailing.substring(dt.range.last + 1) else withTrailing
        return "<!doctype html><html><head><meta charset=\"utf-8\">$head</head><body>$rest</body></html>"
    }

    private val vhInMinHeight = Regex("(min-height\\s*:\\s*)(\\d+(?:\\.\\d+)?)vh", RegexOption.IGNORE_CASE)

    /** JSR replaceVhInContent: min-height in vh follows the host viewport, not the iframe's own height. */
    internal fun replaceVh(source: String): String = vhInMinHeight.replace(source) { m ->
        val value = m.groupValues[2].toDouble()
        val factor = if (value == 100.0) "" else " * ${value / 100}"
        "${m.groupValues[1]}calc(var(--TH-viewport-height)$factor)"
    }

    // Inside the srcdoc iframe: postMessage is a DOM API, not a fetch, so the inherited
    // connect-src 'none' policy does not govern it.
    private const val PANEL_CAPTURE_SCRIPT =
        """<script $PANEL_CAPTURE_MARKER="1">(function(){/*$PANEL_TOKEN*/
var last=0,lastText='';
document.addEventListener('click',function(ev){
var t=ev.target;if(!t||!t.closest)return;
var el=t.closest('button,a,[onclick]');
var text=el?((el.innerText||el.textContent||'')+'').replace(/\s+/g,' ').trim():'';
if(!text){var n=t;while(n&&n!==document.documentElement){var s=((n.innerText||n.textContent||'')+'').replace(/\s+/g,' ').trim();if(s){text=s;break;}n=n.parentElement;}}
if(!text||text.length>200)return;
var now=Date.now();
if(text===lastText&&now-last<500)return;
last=now;lastText=text;
try{window.parent.postMessage(text,'*');}catch(e){}
},true);
})();</script>"""

    private const val PANEL_LISTENER_SCRIPT =
        """<script $PANEL_BRIDGE_MARKER="1">/*$PANEL_TOKEN*/window.addEventListener('message',function(e){
try{var a=window.Aster;if(!a)return;
if(typeof e.data==='string'&&e.data.length>0&&e.data.length<=200){if(a.onPanelAction)a.onPanelAction(e.data);}
else if(e.data&&typeof e.data.asterHeight==='number'){var h=Math.round(e.data.asterHeight);if(h>0&&h<=20000&&a.onHeight)a.onHeight(h);}
}catch(err){}
});</script>"""

    // Shell page: native evaluates JS here; forward each payload into the card frame's shim.
    private const val FRAME_FORWARDER_SCRIPT =
        """<script>/*$PANEL_TOKEN*/(function(){var q=[];var timer=0;
function frames(){var out=[];var fs=document.querySelectorAll('iframe');for(var i=0;i!==fs.length;i++){var w=null;try{w=fs[i].contentWindow;}catch(e){}out.push(w);}return out;}
function ready(){var ws=frames();if(!ws.length)return false;for(var i=0;i!==ws.length;i++){if(!ws[i]||!ws[i].__thReceive)return false;}return true;}
function flush(){var ws=frames();while(q.length){var p=q.shift();for(var i=0;i!==ws.length;i++){try{ws[i].__thReceive(p);}catch(e){}}}}
window.__thDeliver=function(p){q.push(p);if(ready()){flush();return;}if(!timer){timer=setInterval(function(){if(ready()){clearInterval(timer);timer=0;flush();}},50);}};
})();</script>"""

    // Inside the srcdoc iframe: report body height (rAF-coalesced) so the native card can size itself.
    // No '<' characters: presenters strip injected scripts with a [^<]* token regex.
    private const val HEIGHT_REPORT_SCRIPT =
        """<script data-aster-height="1">(function(){/*$PANEL_TOKEN*/
var scheduled=false,lastH=0;
var measure=function(){scheduled=false;var b=document.body;var h=b?Math.ceil(Math.max(b.scrollHeight,b.getBoundingClientRect().height)):0;
if(h>0&&Math.abs(h-lastH)>1){lastH=h;try{window.parent.postMessage({asterHeight:h},'*');}catch(e){}}};
var send=function(){if(scheduled)return;scheduled=true;(window.requestAnimationFrame||setTimeout)(measure);};
window.addEventListener('load',send);
document.addEventListener('toggle',send,true);
document.addEventListener('click',function(){setTimeout(send,80);setTimeout(send,350);},true);
var start=function(){send();if(window.ResizeObserver&&document.body){try{new ResizeObserver(send).observe(document.body);}catch(e){}}};
if(document.body)start();else document.addEventListener('DOMContentLoaded',start);
setTimeout(send,400);setTimeout(send,1200);
})();</script>"""
}
