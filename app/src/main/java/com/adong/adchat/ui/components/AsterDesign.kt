package com.adong.adchat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.R
import com.adong.adchat.ui.theme.*

/** Reuses the actual launcher artwork, including its original safe space. */
@Composable
fun AsterMark(modifier: Modifier = Modifier, tint: Color = Accent) {
    Icon(painterResource(R.drawable.ic_launcher_monochrome), null, modifier, tint = tint)
}

/** Full-colour transparent Aster artwork for branded motion and larger identity moments. */
@Composable
fun AsterArtwork(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier,
        tint = Color.Unspecified
    )
}

@Composable
fun AsterWordmark(size: Int = 30, color: Color = Ink) {
    Text("Aster", fontFamily = FontFamily.Serif, fontSize = size.sp,
        letterSpacing = (-1).sp, color = color, fontWeight = FontWeight.Normal)
}

@Composable
fun AsterIconButton(
    icon: ImageVector, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (filled) Surface else Color.Transparent, contentColor = Ink
        )) { Icon(icon, description, Modifier.size(22.dp)) }
}

@Composable
fun AsterPageHeader(
    title: String, onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}
) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        AsterIconButton(Icons.Rounded.Menu, "打开侧栏", onOpenDrawer)
        Text(title, Modifier.weight(1f).padding(start = 6.dp),
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
fun AsterSectionHeading(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        detail?.let { Text(it, color = MutedInk, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
fun AsterSegmentedControl(
    labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true
) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceInset)
        .selectableGroup().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Surface(color = if (selected) Surface else Color.Transparent,
                shape = RoundedCornerShape(14.dp), shadowElevation = if (selected) 1.dp else 0.dp,
                modifier = Modifier.weight(1f)) {
                Box(Modifier.selectable(selected, enabled = enabled, role = Role.Tab,
                    onClick = { onSelect(index) }).heightIn(min = 46.dp)
                    .padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) Ink else MutedInk,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
fun AsterModelRow(
    label: String, model: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Tune
) {
    Surface(onClick = onClick, color = Surface, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Hairline), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = Accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = MutedInk, style = MaterialTheme.typography.labelMedium)
                Text(model.ifBlank { "选择模型" }, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(18.dp), tint = MutedInk)
        }
    }
}

@Composable
fun AsterEmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(SurfaceInset), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(23.dp), tint = MutedInk)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(5.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MutedInk,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
