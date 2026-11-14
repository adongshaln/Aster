@file:Suppress("DEPRECATION")

package com.adong.adchat.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.adong.adchat.ui.theme.*
import kotlin.math.roundToInt

/** Capture the selection through Compose's copy callback without modifying the clipboard. */
internal class QuoteClipboard(private val clipboard: Clipboard) : Clipboard by clipboard {
    var capture: ((String) -> Unit)? = null
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val receiver = capture
        if (receiver == null) clipboard.setClipEntry(clipEntry)
        else clipEntry?.clipData?.getItemAt(0)?.text?.toString()?.let(receiver)
    }
}

private data class SelectionMenu(val rect: Rect, val copy: (() -> Unit)?, val selectAll: (() -> Unit)?)

private class QuoteToolbar : TextToolbar {
    var menu by mutableStateOf<SelectionMenu?>(null)
    override val status get() = if (menu == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown
    override fun hide() { menu = null }
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        menu = SelectionMenu(rect, onCopyRequested, onSelectAllRequested)
    }
}

@Composable
fun QuoteSelectionScope(onQuote: (String) -> Unit, content: @Composable () -> Unit) {
    val systemClipboard = LocalClipboard.current
    val clipboard = remember(systemClipboard) { QuoteClipboard(systemClipboard) }
    val toolbar = remember { QuoteToolbar() }
    val latestOnQuote by rememberUpdatedState(onQuote)
    CompositionLocalProvider(LocalClipboard provides clipboard, LocalTextToolbar provides toolbar) { content() }
    val menu = toolbar.menu ?: return
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(menu.rect, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (menu.rect.center.x - popupContentSize.width / 2).roundToInt()
                    .coerceIn(margin, maxOf(margin, windowSize.width - popupContentSize.width - margin))
                val above = menu.rect.top.roundToInt() - popupContentSize.height - margin
                val y = (if (above >= margin) above else menu.rect.bottom.roundToInt() + margin)
                    .coerceIn(margin, maxOf(margin, windowSize.height - popupContentSize.height - margin))
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = toolbar::hide,
        properties = PopupProperties(focusable = false)) {
        val motion = LocalAsterMotionEnabled.current
        var entered by remember { mutableStateOf(!motion) }
        LaunchedEffect(Unit) { entered = true }
        val scale by animateFloatAsState(if (entered) 1f else 0.94f,
            if (motion) AsterSpring.jelly() else snap(), label = "selection-menu")
        Surface(color = Surface, contentColor = Ink, shape = AsterShape.content,
            border = BorderStroke(0.6.dp, Hairline), shadowElevation = 3.dp,
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                menu.copy?.let { copy ->
                    SelectionMenuAction("复制", false) { copy(); toolbar.hide() }
                    SelectionMenuAction("引用", true) {
                        var selected: String? = null
                        clipboard.capture = { selected = it }
                        try { copy() } finally { clipboard.capture = null; toolbar.hide() }
                        selected?.takeIf(String::isNotBlank)?.let(latestOnQuote)
                    }
                }
                menu.selectAll?.let { select ->
                    SelectionMenuAction("全选", false, select)
                }
            }
        }
    }
}

@Composable
private fun SelectionMenuAction(label: String, accent: Boolean, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    TextButton(onClick = rememberAsterClick(onClick), interactionSource = interaction,
        modifier = Modifier.appleSpringPressable(pressedScale = 0.96f, enableHaptics = false, interactionSource = interaction)) {
        Text(label, color = if (accent) AccentInk else Ink, style = MaterialTheme.typography.labelLarge)
    }
}
