package com.adong.adchat.ui.clawd

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Keeps scheduled mascot work suspended while its screen is in the background. */
internal class ClawdActivityGate {
    private val active = MutableStateFlow(true)

    fun setActive(value: Boolean) {
        active.value = value
    }

    suspend fun awaitActive() {
        if (!active.value) active.first { it }
    }

    suspend fun delayWhileActive(durationMs: Long) {
        awaitActive()
        delay(durationMs)
        awaitActive()
    }
}
