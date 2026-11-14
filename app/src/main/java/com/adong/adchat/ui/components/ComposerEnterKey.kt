package com.adong.adchat.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.*

/** Let the IME finish composition and let Shift+Enter insert a line break. */
internal fun Modifier.composerEnterKey(enabled: Boolean, composing: Boolean, onSend: () -> Unit): Modifier =
    composed {
        var pendingEnter by remember { mutableStateOf<Key?>(null) }
        onPreviewKeyEvent { event ->
            if (event.key != Key.Enter && event.key != Key.NumPadEnter) false
            else when (event.type) {
                KeyEventType.KeyDown -> {
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        pendingEnter = event.key.takeIf {
                            enabled && !composing && !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed
                        }
                    }
                    pendingEnter == event.key
                }
                KeyEventType.KeyUp -> {
                    val accepted = pendingEnter == event.key
                    pendingEnter = null
                    if (accepted && enabled && !composing) onSend()
                    accepted
                }
                else -> false
            }
        }
    }
