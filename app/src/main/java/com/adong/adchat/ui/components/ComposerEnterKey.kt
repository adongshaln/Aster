package com.adong.adchat.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*

/** Let the IME finish composition and let Shift+Enter insert a line break. */
internal fun Modifier.composerEnterKey(enabled: Boolean, composing: Boolean, onSend: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (enabled && !composing && !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed &&
            (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
            if (event.type == KeyEventType.KeyUp) onSend()
            true
        } else false
    }
