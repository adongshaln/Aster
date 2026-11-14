package com.adong.adchat.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal fun TextFieldValue.withLineBreak(): TextFieldValue {
    val start = selection.min
    return TextFieldValue(text.replaceRange(start, selection.max, "\n"), TextRange(start + 1))
}

/** Let the IME finish composition and let Shift+Enter insert a line break. */
internal fun Modifier.composerEnterKey(
    enabled: Boolean, composing: Boolean, onNewLine: (() -> Unit)? = null, onSend: () -> Unit
): Modifier =
    composed {
        var pendingEnter by remember { mutableStateOf<Key?>(null) }
        var pendingNewLine by remember { mutableStateOf(false) }
        onPreviewKeyEvent { event ->
            if (event.key != Key.Enter && event.key != Key.NumPadEnter) false
            else when (event.type) {
                KeyEventType.KeyDown -> {
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        pendingNewLine = event.isShiftPressed && onNewLine != null
                        pendingEnter = event.key.takeIf {
                            !composing && !event.isCtrlPressed && !event.isAltPressed &&
                                (pendingNewLine || (enabled && !event.isShiftPressed))
                        }
                    }
                    pendingEnter == event.key
                }
                KeyEventType.KeyUp -> {
                    val accepted = pendingEnter == event.key
                    val newLine = pendingNewLine
                    pendingEnter = null
                    pendingNewLine = false
                    if (accepted && !composing) {
                        if (newLine) onNewLine?.invoke() else if (enabled) onSend()
                    }
                    accepted
                }
                else -> false
            }
        }
    }
