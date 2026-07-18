package com.adong.adchat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.*

data class AdChoiceOption(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val badge: String? = null
)

data class AdActionOption(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val enabled: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdSelectionSheet(
    title: String,
    subtitle: String,
    options: List<AdChoiceOption>,
    selectedId: String?,
    onSelect: (AdChoiceOption) -> Unit,
    onDismiss: () -> Unit,
    searchPlaceholder: String = "搜索",
    searchEnabled: Boolean = options.size >= 8,
    headerIcon: ImageVector? = null
) {
    var query by remember(title) { mutableStateOf("") }
    val visible = remember(options, query) {
        if (query.isBlank()) options
        else options.filter { option ->
            option.title.contains(query.trim(), ignoreCase = true) ||
                option.subtitle?.contains(query.trim(), ignoreCase = true) == true
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Canvas,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 42.dp, color = Hairline) }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).imePadding()) {
            SheetHeader(title, subtitle, headerIcon, onDismiss)
            if (searchEnabled) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(19.dp), tint = MutedInk) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "清空", Modifier.size(18.dp)) }
                        }
                    },
                    placeholder = { Text(searchPlaceholder, color = MutedInk) },
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Surface,
                        unfocusedContainerColor = Surface,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            }
            Spacer(Modifier.height(16.dp))
            if (visible.isEmpty()) {
                Surface(color = Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Search, null, tint = MutedInk)
                        Spacer(Modifier.height(8.dp))
                        Text("没有匹配项", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visible, key = { it.id }) { option ->
                        val selected = option.id == selectedId
                        ChoiceRow(option, selected) { onSelect(option) }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdActionSheet(
    title: String,
    subtitle: String,
    actions: List<AdActionOption>,
    onAction: (AdActionOption) -> Unit,
    onDismiss: () -> Unit,
    headerIcon: ImageVector? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Canvas,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 42.dp, color = Hairline) }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            SheetHeader(title, subtitle, headerIcon, onDismiss)
            Spacer(Modifier.height(16.dp))
            actions.forEach { action ->
                val actionColor = if (action.destructive) Danger else Ink
                Surface(
                    onClick = { if (action.enabled) onAction(action) },
                    enabled = action.enabled,
                    color = if (action.destructive) DangerSoft else Surface,
                    contentColor = actionColor,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                                .background(if (action.destructive) Color(0xFFFFD8D4) else Canvas),
                            contentAlignment = Alignment.Center
                        ) { Icon(action.icon, null, Modifier.size(20.dp), tint = actionColor) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(action.title, fontWeight = FontWeight.SemiBold)
                            action.subtitle?.let { Text(it, color = if (action.destructive) Danger else MutedInk, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(14.dp))
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String, icon: ImageVector?, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(AccentSoft),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, Modifier.size(22.dp), tint = Accent) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(
            onClick = onDismiss,
            colors = IconButtonDefaults.iconButtonColors(containerColor = Surface, contentColor = MutedInk)
        ) { Icon(Icons.Rounded.Close, "关闭") }
    }
}

@Composable
private fun ChoiceRow(option: AdChoiceOption, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(if (selected) AccentSoft else Surface, tween(170), label = "choiceContainer")
    val iconContainer by animateColorAsState(if (selected) Color.White.copy(alpha = .72f) else Canvas, tween(170), label = "choiceIconContainer")
    val iconTint by animateColorAsState(if (selected) Accent else MutedInk, tween(170), label = "choiceIconTint")
    val indicator by animateColorAsState(if (selected) Accent else Hairline, tween(170), label = "choiceIndicator")
    Surface(
        onClick = onClick,
        color = container,
        contentColor = Ink,
        border = if (selected) BorderStroke(1.dp, Color(0xFFFFB9A7)) else null,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (option.icon != null) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(iconContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(option.icon, null, Modifier.size(20.dp), tint = iconTint) }
                Spacer(Modifier.width(12.dp))
            } else {
                Box(Modifier.size(8.dp).clip(CircleShape).background(indicator))
                Spacer(Modifier.width(13.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(option.title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    option.badge?.let {
                        Spacer(Modifier.width(8.dp))
                        Surface(color = if (selected) Color.White.copy(alpha = .7f) else Canvas, shape = CircleShape) {
                            Text(it, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = if (selected) Accent else MutedInk, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                option.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, color = MutedInk, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(indicator),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Color.White)
            }
        }
    }
}
