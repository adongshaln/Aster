package com.adong.adchat.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/** Keeps cancellation attached through both blocking execute() and response-body reads. */
internal suspend fun <T> Call.withCancellableResponse(block: suspend (Response) -> T): T = coroutineScope {
    currentCoroutineContext().ensureActive()
    // Install before execute(): a lazily scheduled watcher can miss an immediate cancellation.
    // Cancellation must not queue behind execute()/read() on the same blocked dispatcher.
    // This child only suspends and calls OkHttp's thread-safe cancel(); it never touches UI state.
    val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            this@withCancellableResponse.cancel()
        }
    }
    try {
        currentCoroutineContext().ensureActive()
        execute().use { response -> block(response) }
    } catch (error: Throwable) {
        // OkHttp reports a canceled socket as IOException; callers must see coroutine cancellation.
        currentCoroutineContext().ensureActive()
        throw error
    } finally {
        watcher.cancel()
    }
}
