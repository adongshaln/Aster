package com.adong.adchat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.R
import com.adong.adchat.ui.theme.*

@Composable
fun AsterMark(
    modifier: Modifier = Modifier,
    tint: Color = Accent
) {
    Image(
        painter = painterResource(R.drawable.aster_logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = if (tint != Accent) ColorFilter.tint(tint) else null,
        modifier = modifier
    )
}

@Composable
fun AsterArtwork(
    modifier: Modifier = Modifier,
    tint: Color = Accent
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        AsterMark(
            modifier = Modifier.fillMaxSize(),
            tint = tint
        )
    }
}

/** Continuous motion is reserved for active loading. */
@Composable
fun AsterLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp,
    tint: Color = Accent
) {
    val motion = if (LocalAsterMotionEnabled.current) {
        val transition = rememberInfiniteTransition(label = "AsterLoading")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 6000, easing = LinearEasing)),
            label = "AsterLoadingRotation"
        )
        val breath by transition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = AsterEase),
                repeatMode = RepeatMode.Reverse
            ),
            label = "AsterLoadingBreath"
        )
        Modifier.graphicsLayer {
            rotationZ = rotation
            scaleX = breath
            scaleY = 1f + (1f - breath) * 0.65f
        }
    } else Modifier
    Image(
        painter = painterResource(R.drawable.aster_logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = if (tint != Accent) ColorFilter.tint(tint) else null,
        modifier = modifier
            .progressSemantics()
            .size(size)
            .then(motion)
    )
}

@Composable
fun AsterWordmark(size: Int = 30, color: Color = Ink) {
    Text("Aster", fontFamily = AsterFontFamily, fontSize = size.sp,
        letterSpacing = (-0.4).sp, color = color, fontWeight = FontWeight.Medium)
}

@Composable
fun AsterPageHeader(
    title: String, onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}
) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        AsterIconButton(Icons.Rounded.Menu, "打开侧栏", onOpenDrawer)
        Text(title, Modifier.weight(1f).padding(start = 6.dp),
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
fun AsterIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Ink
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.appleSpringPressable(
                pressedScale = 0.90f, enabled = enabled, interactionSource = interactionSource
            ),
            tint = if (enabled) tint else MutedInk.copy(alpha = 0.38f)
        )
    }
}

@Composable
fun AsterPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = if (enabled) Accent else Accent.copy(alpha = 0.42f),
        animationSpec = AsterSpring.editorial(),
        label = "PrimaryBtnColor"
    )
    val elevation by animateDpAsState(
        targetValue = if (enabled) 3.dp else 0.dp,
        animationSpec = AsterSpring.snappy(),
        label = "PrimaryBtnElevation"
    )

    // Click and accessibility bounds stay fixed while the painted button deforms inside them.
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        propagateMinConstraints = true
    ) {
        Surface(
            color = containerColor,
            contentColor = OnAccent,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(0.5.dp, OnAccent.copy(alpha = if (enabled) 0.16f else 0f)),
            shadowElevation = elevation.coerceAtLeast(0.dp),
            modifier = Modifier.appleSpringPressable(
                pressedScale = 0.96f, enabled = enabled, interactionSource = interactionSource
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun AsterSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (labels.isEmpty()) return
    val selection = selectedIndex.coerceIn(labels.indices)
    val motionEnabled = LocalAsterMotionEnabled.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val transition = updateTransition(selection, label = "segment-selection")
    // Leading and trailing edges respond at different speeds, then reunite at rest.
    val start by transition.animateFloat(
        transitionSpec = {
            if (targetState < initialState) AsterSpring.snappy() else AsterSpring.releaseVertical()
        },
        label = "segment-start"
    ) { it.toFloat() }
    val end by transition.animateFloat(
        transitionSpec = {
            if (targetState > initialState) AsterSpring.snappy() else AsterSpring.releaseVertical()
        },
        label = "segment-end"
    ) { it.toFloat() }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceInset)
            .selectableGroup()
            .padding(4.dp)
    ) {
        val gap = 4.dp
        val itemWidth = ((maxWidth - gap * (labels.size - 1)) / labels.size).coerceAtLeast(1.dp)
        val stridePx = with(LocalDensity.current) { (itemWidth + gap).toPx() }
        val itemWidthPx = with(LocalDensity.current) { itemWidth.toPx() }
        val insetPx = with(LocalDensity.current) { 4.dp.toPx() }
        Box(Modifier.matchParentSize()) {
            Surface(
                color = Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.8.dp, Hairline.copy(alpha = 0.85f)),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .width(itemWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val startPosition = if (motionEnabled) start else selection.toFloat()
                        val endPosition = if (motionEnabled) end else selection.toFloat()
                        val position = ((startPosition + endPosition) * 0.5f)
                            .coerceIn(0f, labels.lastIndex.toFloat())
                        val edgeRoom = minOf(position, labels.lastIndex - position) * stridePx + insetPx
                        val stretch = (1f + (endPosition - startPosition) * stridePx / itemWidthPx)
                            .coerceIn(0.94f, minOf(1.16f, 1f + 2f * edgeRoom / itemWidthPx))
                        translationX = position * stridePx * (if (rtl) -1f else 1f)
                        scaleX = stretch
                        scaleY = 1f - (stretch - 1f) * 0.18f
                        transformOrigin = TransformOrigin.Center
                    }
            ) {}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            labels.forEachIndexed { index, label ->
                val selected = index == selection
                val interactionSource = remember { MutableInteractionSource() }
                val textColor by animateColorAsState(
                    targetValue = (if (selected) Ink else MutedInk).let {
                        if (enabled) it else it.copy(alpha = 0.45f)
                    },
                    animationSpec = AsterSpring.editorial(),
                    label = "segment-text"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            interactionSource = interactionSource,
                            indication = ripple(),
                            onClick = { if (!selected) onSelect(index) }
                        )
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.appleSpringPressable(
                            pressedScale = 0.95f, enabled = enabled, interactionSource = interactionSource
                        ),
                        color = textColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun AsterModelRow(
    label: String,
    model: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Tune
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        color = Surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Hairline.copy(alpha = 0.9f)),
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp)
                .appleSpringPressable(pressedScale = 0.975f, interactionSource = interactionSource)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(AccentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, Modifier.size(19.dp), tint = OnAccentSoft)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = MutedInk, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = model.ifBlank { "选择模型" },
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(SurfaceInset.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(16.dp), tint = MutedInk)
            }
        }
    }
}

@Composable
fun AsterEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .claudeBreathingFloat(amplitudeDp = 3.5.dp, durationMillis = 2800)
                .clip(RoundedCornerShape(22.dp))
                .background(AccentSoft.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(26.dp), tint = OnAccentSoft)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MutedInk,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Star Island tactile switch: paper-well track with 1dp hairline border and a spring-driven
 * thumb that squishes horizontally while in flight.
 */
@Composable
fun AsterSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> SurfaceInset.copy(alpha = 0.55f)
            checked -> Accent
            else -> SurfaceInset
        },
        animationSpec = AsterSpring.editorial(),
        label = "aster-switch-track"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> Hairline.copy(alpha = 0.5f)
            checked -> Accent
            else -> Hairline
        },
        animationSpec = AsterSpring.editorial(),
        label = "aster-switch-border"
    )
    val thumbColor by animateColorAsState(
        targetValue = when {
            !enabled -> MutedInk.copy(alpha = 0.42f)
            checked -> OnAccent
            else -> Surface
        },
        animationSpec = AsterSpring.editorial(),
        label = "aster-switch-thumb"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 3.dp,
        animationSpec = AsterSpring.jelly(),
        label = "aster-switch-offset"
    )
    Surface(
        onClick = { if (enabled) onCheckedChange(!checked) },
        enabled = enabled,
        interactionSource = interactionSource,
        color = trackColor,
        shape = CircleShape,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .width(46.dp)
            .height(28.dp)
            .appleSpringPressable(pressedScale = 0.92f, enabled = enabled, interactionSource = interactionSource)
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .offset(x = thumbOffset)
                    .size(21.dp)
                    .clip(CircleShape)
                    .background(thumbColor)
            )
        }
    }
}

/**
 * Star Island paper-well pill button: replaces bare TextButtons and unstyled chips with a
 * crisp 1dp hairline border, warm paper fill, and asymmetric spring press feedback.
 */
@Composable
fun AsterPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
    selected: Boolean = false,
    compact: Boolean = false,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val fill by animateColorAsState(
        targetValue = when {
            primary -> if (enabled) Accent else Accent.copy(alpha = 0.42f)
            destructive -> if (enabled) DangerSoft else SurfaceInset.copy(alpha = 0.6f)
            selected -> AccentSoft
            else -> Surface
        },
        animationSpec = AsterSpring.editorial(),
        label = "pill-btn-fill"
    )
    val stroke by animateColorAsState(
        targetValue = when {
            primary -> Color.Transparent
            destructive -> Danger.copy(alpha = if (enabled) 0.35f else 0.15f)
            selected -> Accent.copy(alpha = 0.48f)
            else -> Hairline
        },
        animationSpec = AsterSpring.editorial(),
        label = "pill-btn-stroke"
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> MutedInk.copy(alpha = 0.45f)
            primary -> OnAccent
            destructive -> Danger
            selected -> OnAccentSoft
            else -> Ink
        },
        animationSpec = AsterSpring.editorial(),
        label = "pill-btn-content"
    )
    val minHeight = if (compact) 34.dp else 42.dp
    val horizontalPadding = if (compact) 13.dp else 16.dp
    val cornerRadius = if (compact) 17.dp else 21.dp
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        color = fill,
        contentColor = content,
        shape = RoundedCornerShape(cornerRadius),
        border = if (primary) null else BorderStroke(1.dp, stroke),
        modifier = modifier
            .heightIn(min = minHeight)
            .appleSpringPressable(pressedScale = 0.94f, enabled = enabled, interactionSource = interactionSource)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = if (compact) 6.dp else 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(if (compact) 15.dp else 17.dp), tint = content)
                Spacer(Modifier.width(if (compact) 5.dp else 6.dp))
            }
            Text(
                text = text,
                color = content,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                fontWeight = if (primary || selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Star Island paper-tag toast/snackbar: warm paper surface framed by a clay-tinted border,
 * tactile action pill, and smooth spring dismissal.
 */
@Composable
fun AsterPaperSnackbar(
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dismissInteraction = remember { MutableInteractionSource() }
    Surface(
        onClick = onDismiss,
        interactionSource = dismissInteraction,
        color = Surface,
        contentColor = Ink,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.2.dp, Accent.copy(alpha = 0.52f)),
        shadowElevation = 10.dp,
        modifier = modifier
            .fillMaxWidth()
            .appleSpringPressable(pressedScale = 0.97f, interactionSource = dismissInteraction)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Accent)
            )
            Spacer(Modifier.width(11.dp))
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = Ink,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (actionLabel != null) {
                Spacer(Modifier.width(8.dp))
                AsterPillButton(
                    text = actionLabel,
                    onClick = onAction,
                    primary = true,
                    compact = true
                )
            }
        }
    }
}
