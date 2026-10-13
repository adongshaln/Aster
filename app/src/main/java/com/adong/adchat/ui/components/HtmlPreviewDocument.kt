package com.adong.adchat.ui.components

/** A script-capable, opaque-origin iframe. No native bridge, network, forms or top navigation. */
internal object HtmlPreviewDocument {
    const val MAX_PREVIEW_CHARS = 1_000_000
    const val PANEL_CAPTURE_MARKER = "data-aster-panel"
    const val PANEL_BRIDGE_MARKER = "data-aster-bridge"
    /** Unique token embedded in both bridge scripts so presenters can strip them on re-render. */
    const val PANEL_TOKEN = "asterpanelbridge"

    fun wrap(source: String, allowScripts: Boolean = true, panelBridge: Boolean = false): String {
        require(source.length <= MAX_PREVIEW_CHARS)
        val body = if (panelBridge) source + PANEL_CAPTURE_SCRIPT else source
        val escaped = body.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;")
        val scriptPolicy = if (allowScripts) "script-src 'unsafe-inline' 'unsafe-eval';" else "script-src 'none';"
        val sandboxPolicy = if (allowScripts) "allow-scripts" else ""
        val parentListener = if (panelBridge) PANEL_LISTENER_SCRIPT else ""
        return """<!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; $scriptPolicy style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; font-src data:; connect-src 'none'; frame-src about:; object-src 'none'; base-uri 'none'; form-action 'none'">
            <style>html,body{margin:0;height:100%;background:white}iframe{border:0;width:100%;height:100%;display:block}</style>
            </head><body><iframe title="HTML preview" sandbox="$sandboxPolicy" srcdoc="$escaped"></iframe>$parentListener</body></html>""".trimIndent()
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
try{var a=window.Aster;if(a&&typeof e.data==='string'&&e.data.length>0&&e.data.length<=200){a.onPanelAction(e.data);}}catch(err){}
});</script>"""
}
