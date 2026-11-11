package com.adong.adchat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.Hairline
import com.adong.adchat.ui.theme.MutedInk

/** A quiet editorial separator; content and actions keep the visual emphasis. */
@Composable
fun AsterSectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, color = MutedInk, style = MaterialTheme.typography.labelMedium)
        HorizontalDivider(Modifier.weight(1f), color = Hairline.copy(alpha = 0.65f))
    }
}
