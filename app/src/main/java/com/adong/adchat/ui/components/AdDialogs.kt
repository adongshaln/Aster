package com.adong.adchat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
    iconTint: Color = OnAccentSoft,
    iconContainerColor: Color = AccentSoft,
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    val motionEnabled = LocalAsterMotionEnabled.current
    var entered by remember { mutableStateOf(!motionEnabled) }
    LaunchedEffect(Unit) { entered = true }
    // Star Island asymmetric spring morph: width leads height slightly, unfolding from the top center.
    val scaleX by animateFloatAsState(
        targetValue = if (entered) 1f else 0.90f,
        animationSpec = if (motionEnabled) AsterSpring.releaseHorizontal() else snap(),
        label = "dialog-scale-x"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (entered) 1f else 0.82f,
        animationSpec = if (motionEnabled) AsterSpring.releaseVertical() else snap(),
        label = "dialog-scale-y"
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = if (motionEnabled) AsterSpring.editorial() else snap(),
        label = "dialog-alpha"
    )
    val liftPx = with(LocalDensity.current) { 18.dp.toPx() }
    val translationY by animateFloatAsState(
        targetValue = if (entered) 0f else -liftPx,
        animationSpec = if (motionEnabled) AsterSpring.spatial() else snap(),
        label = "dialog-lift"
    )

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
                modifier = modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .graphicsLayer {
                        this.alpha = if (motionEnabled) alpha.coerceIn(0f, 1f) else 1f
                        this.scaleX = if (motionEnabled) scaleX else 1f
                        this.scaleY = if (motionEnabled) scaleY else 1f
                        this.translationY = if (motionEnabled) translationY else 0f
                        transformOrigin = TransformOrigin(0.5f, 0.08f)
                    },
                color = Canvas,
                contentColor = Ink,
                border = BorderStroke(1.dp, Hairline),
                shape = RoundedCornerShape(30.dp),
                shadowElevation = 18.dp,
                tonalElevation = 0.dp
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) {
                            Box(
                                Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(iconContainerColor),
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
                        val closeInteraction = remember { MutableInteractionSource() }
                        Surface(
                            onClick = onDismiss,
                            interactionSource = closeInteraction,
                            color = Surface,
                            contentColor = MutedInk,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, Hairline),
                            modifier = Modifier
                                .size(38.dp)
                                .appleSpringPressable(pressedScale = 0.90f, interactionSource = closeInteraction)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, "\u5173\u95ed", Modifier.size(18.dp))
                            }
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
    val onEmphasis = if (destructive) OnDanger else OnAccent
    val soft = if (destructive) DangerSoft else AccentSoft
    val dismissInteraction = remember { MutableInteractionSource() }
    val confirmInteraction = remember { MutableInteractionSource() }
    AdModalDialog(
        title = title,
        subtitle = if (destructive) "\u8bf7\u786e\u8ba4\u540e\u518d\u7ee7\u7eed" else null,
        icon = icon,
        iconTint = if (destructive) OnDangerSoft else OnAccentSoft,
        iconContainerColor = soft,
        onDismiss = onDismiss,
        content = {
            Surface(
                color = Surface,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Hairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        actions = {
            Surface(
                onClick = onDismiss,
                interactionSource = dismissInteraction,
                color = Surface,
                contentColor = Ink,
                border = BorderStroke(1.dp, Hairline),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .appleSpringPressable(pressedScale = 0.95f, interactionSource = dismissInteraction)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(dismissLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            Surface(
                onClick = onConfirm,
                interactionSource = confirmInteraction,
                color = emphasis,
                contentColor = onEmphasis,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .appleSpringPressable(pressedScale = 0.95f, interactionSource = confirmInteraction)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(confirmLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
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
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    warning: Boolean = false
) {
    val palette = if (dark) AsterPaletteDark else LocalAsterPalette.current
    val selectedColor = if (warning) palette.dangerSoft else palette.accentSoft
    val idleColor = palette.surface
    val container by animateColorAsState(
        targetValue = if (checked) selectedColor else idleColor,
        animationSpec = AsterSpring.editorial(),
        label = "toggleContainer"
    )
    val titleColor = if (enabled) palette.ink else palette.ink.copy(alpha = .38f)
    val subtitleColor = when {
        !enabled -> palette.mutedInk.copy(alpha = .38f)
        warning && checked -> palette.danger
        else -> palette.mutedInk
    }
    val borderColor = when {
        checked && enabled && warning -> palette.danger.copy(alpha = .42f)
        checked && enabled -> palette.accent.copy(alpha = .42f)
        else -> palette.hairline
    }

    Surface(
        modifier = modifier.fillMaxWidth().appleSpringPressable(pressedScale = 0.985f, enabled = enabled),
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        color = container,
        contentColor = titleColor,
        border = BorderStroke(1.dp, borderColor),
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
            AsterSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}
