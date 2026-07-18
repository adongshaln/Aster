package com.adong.adchat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adong.adchat.ui.theme.*

@Composable
fun AdModalDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Accent,
    iconContainerColor: Color = AccentSoft,
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = modifier.fillMaxWidth().widthIn(max = 460.dp),
                color = Canvas,
                contentColor = Ink,
                shape = RoundedCornerShape(30.dp),
                shadowElevation = 16.dp,
                tonalElevation = 0.dp
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) {
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(iconContainerColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(icon, null, Modifier.size(22.dp), tint = iconTint)
                            }
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleLarge)
                            subtitle?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(2.dp))
                                Text(it, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Surface, contentColor = MutedInk)
                        ) {
                            Icon(Icons.Rounded.Close, "\u5173\u95ed", Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    content()
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
        }
    }
}

@Composable
fun AdConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    icon: ImageVector,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    val emphasis = if (destructive) Danger else Accent
    val soft = if (destructive) DangerSoft else AccentSoft
    AdModalDialog(
        title = title,
        subtitle = if (destructive) "\u8bf7\u786e\u8ba4\u540e\u518d\u7ee7\u7eed" else null,
        icon = icon,
        iconTint = emphasis,
        iconContainerColor = soft,
        onDismiss = onDismiss,
        content = {
            Surface(color = Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
            ) {
                Text(dismissLabel, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = emphasis, contentColor = Color.White)
            ) {
                Text(confirmLabel, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun AdToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    warning: Boolean = false
) {
    val selectedColor = if (dark) Color(0xFF393735) else AccentSoft
    val idleColor = if (dark) Color(0xFF302F2D) else Surface
    val container by animateColorAsState(
        targetValue = if (checked) selectedColor else idleColor,
        animationSpec = tween(180),
        label = "toggleContainer"
    )
    val track by animateColorAsState(
        targetValue = if (checked) Accent else if (dark) Color(0xFF55514D) else Hairline,
        animationSpec = tween(180),
        label = "toggleTrack"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 3.dp,
        animationSpec = tween(180),
        label = "toggleThumb"
    )
    val titleColor = if (dark) Color.White else Ink
    val subtitleColor = when {
        warning && checked -> if (dark) Color(0xFFFFB4A5) else Danger
        dark -> Color(0xFFBDB8B2)
        else -> MutedInk
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = { onCheckedChange(!checked) },
        color = container,
        contentColor = titleColor,
        border = if (checked && !dark) BorderStroke(1.dp, Color(0xFFFFB9A7)) else null,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = titleColor, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = subtitleColor, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.width(46.dp).height(28.dp).clip(CircleShape).background(track),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    Modifier.offset { IntOffset(thumbOffset.roundToPx(), 0) }.size(22.dp).clip(CircleShape).background(Color.White)
                )
            }
        }
    }
}

