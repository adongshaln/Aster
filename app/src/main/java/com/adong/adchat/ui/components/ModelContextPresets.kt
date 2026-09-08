package com.adong.adchat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adong.adchat.data.ContextWindowPresets
import com.adong.adchat.data.ModelContextLimits
import com.adong.adchat.ui.theme.*

@Composable
fun ModelContextPresets(profileId: String, model: String, limits: ModelContextLimits?, onSelect: (Int) -> Unit) {
    var expanded by rememberSaveable(profileId, model) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text("上下文", color = MutedInk)
            Spacer(Modifier.weight(1f))
            Text(limits?.let { ContextWindowPresets.label(it.windowTokens) } ?: "默认", color = Accent)
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                "设置 $model 的上下文", Modifier.padding(start = 4.dp).size(18.dp))
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ContextWindowPresets.values.forEach { (window, label) ->
                    FilterChip(selected = limits?.windowTokens == window, onClick = { onSelect(window) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            }
            Text("立即保存 · 此 API 下的同名模型共用", color = MutedInk, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        }
    }
}
