package com.adong.adchat.data.rp.helper

/**
 * Bounded native surface shared with sandboxed tavern_helper-compatible frames. [rpc] is synchronous
 * (called on Chromium's bridge thread); [rpcAsync] completes later and [reply] may run on any thread.
 * Emitters receive JavaScript to evaluate on the frame's top page (see [helperDeliverJs]).
 */
internal interface HelperHost {
    fun rpc(json: String): String
    fun addEmitter(emit: (String) -> Unit): Any
    fun removeEmitter(token: Any)

    /** Long-running call (slash commands, generation). Default: synchronous [rpc]. */
    fun rpcAsync(json: String, reply: (String) -> Unit) = reply(rpc(json))

    /** Event emitted by a frame (`{type,args,from}`); hosts fan it out to every frame. */
    fun broadcast(json: String) {}
}

/**
 * JS delivering one payload (`{t:'e'|'r',…}`) to a page. A preview shell forwards to its card iframe via
 * `__thDeliver`; the script engine page receives it directly through the shim's `__thReceive`.
 */
internal fun helperDeliverJs(payload: String): String =
    "(window.__thDeliver||window.__thReceive||function(){})($payload)"
