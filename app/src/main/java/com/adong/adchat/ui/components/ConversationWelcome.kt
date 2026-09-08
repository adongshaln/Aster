package com.adong.adchat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.*

data class ConversationStarter(val title: String, val detail: String, val icon: ImageVector, val prompt: String)

/** One welcome layout for ordinary chat, story discussion and prose. */
@Composable
fun ConversationWelcome(
    title: String,
    subtitle: String,
    label: String,
    starters: List<ConversationStarter>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {}
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = AccentSoft.copy(alpha = .65f), shape = RoundedCornerShape(24.dp)) {
                AsterMark(Modifier.padding(10.dp).size(42.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(label, color = Accent, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(subtitle, color = MutedInk, style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center)
            if (starters.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                starters.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { starter ->
                            Surface(onClick = { onSelect(starter.prompt) }, color = Surface,
                                shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Hairline.copy(alpha = .65f)),
                                modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Column(Modifier.padding(16.dp).defaultMinSize(minHeight = 88.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Icon(starter.icon, null, Modifier.size(21.dp), tint = Accent)
                                        Icon(Icons.Rounded.NorthEast, null, Modifier.size(15.dp), tint = MutedInk.copy(alpha = .65f))
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Text(starter.title, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(3.dp))
                                    Text(starter.detail, style = MaterialTheme.typography.labelMedium, color = MutedInk)
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            footer()
        }
    }
}

@Composable
fun ConversationAuthor(error: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        AsterMark(Modifier.size(20.dp), tint = if (error) Danger else Accent)
        Text("Aster", Modifier.padding(start = 7.dp, end = 12.dp),
            style = MaterialTheme.typography.labelMedium, color = MutedInk)
        HorizontalDivider(Modifier.weight(1f), color = Hairline.copy(alpha = .7f))
    }
}
