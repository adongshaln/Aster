package com.adong.adchat.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.Accent

/** Collapse by complete lines so larger accessibility fonts never cut a line in half. */
@Composable
internal fun ExpandableUserMessage(messageId: Long, text: String) {
    var expanded by rememberSaveable(messageId, text) { mutableStateOf(false) }
    var overflowed by remember(messageId, text) { mutableStateOf(false) }
    Column {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) Int.MAX_VALUE else 12,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflowed = it.hasVisualOverflow }
            )
        }
        if (overflowed || expanded) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                    .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
            ) {
                Text(if (expanded) "收起" else "展开", color = Accent)
            }
        }
    }
}
