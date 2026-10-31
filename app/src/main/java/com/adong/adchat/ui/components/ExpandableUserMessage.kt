package com.adong.adchat.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.Accent
import com.adong.adchat.ui.theme.AsterSpring
import com.adong.adchat.ui.theme.Hairline
import com.adong.adchat.ui.theme.Surface
import com.adong.adchat.ui.theme.appleSpringPressable

/** Collapse by complete lines so larger accessibility fonts never cut a line in half. */
@Composable
internal fun ExpandableUserMessage(messageId: Long, text: String) {
    var expanded by rememberSaveable(messageId, text) { mutableStateOf(false) }
    var overflowed by remember(messageId, text) { mutableStateOf(false) }
    val disclosureRotation by animateFloatAsState(
        if (expanded) 180f else 0f, AsterSpring.jelly(), label = "message-disclosure"
    )
    Column(Modifier.animateContentSize(animationSpec = AsterSpring.spatial())) {
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
            val pillInteraction = remember { MutableInteractionSource() }
            Box(Modifier.align(Alignment.End).padding(top = 6.dp)) {
                Surface(
                    onClick = { expanded = !expanded },
                    interactionSource = pillInteraction,
                    color = Surface,
                    contentColor = Accent,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Hairline),
                    modifier = Modifier
                        .appleSpringPressable(pressedScale = 0.94f, interactionSource = pillInteraction)
                        .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (expanded) "收起" else "展开",
                            color = Accent,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(3.dp))
                        Icon(
                            Icons.Rounded.ExpandMore,
                            null,
                            Modifier.size(16.dp).graphicsLayer { rotationZ = disclosureRotation },
                            tint = Accent
                        )
                    }
                }
            }
        }
    }
}
