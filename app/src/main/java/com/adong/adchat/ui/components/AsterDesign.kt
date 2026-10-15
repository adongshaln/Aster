package com.adong.adchat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.R
import com.adong.adchat.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Aster v3.0 — Claude Editorial × Apple Fluid Design System (完整 10 符号导出集)
 * ===========================================================================
 * 包含仓库全部 10 个导出组件符号，并补齐 `painterResource` / `sp` / `R` 等全部 import：
 * 1. `AsterMark`（3 处引用）
 * 2. `AsterArtwork`（1 处引用）
 * 3. `AsterWordmark`（1 处引用）
 * 4. `AsterPageHeader`（3 处引用）
 * 5. `AsterSectionHeading`（1 处引用）
 * 6. `AsterIconButton`
 * 7. `AsterPrimaryButton`
 * 8. `AsterSegmentedControl`
 * 9. `AsterModelRow`
 * 10. `AsterEmptyState`
 */

// BEGIN_BRAND_AND_HEADER_COMPONENTS
@Composable
fun AsterMark(
    modifier: Modifier = Modifier,
    tint: Color = Accent
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * 0.44f
        val stroke = (size.minDimension * 0.12f).coerceAtLeast(1.8f)
        val rayCount = 8
        for (i in 0 until rayCount) {
            val angle = (i * (360f / rayCount) - 15f) * (PI.toFloat() / 180f)
            val lengthFactor = if (i % 2 == 0) 1f else 0.78f
            val innerR = r * 0.16f
            val outerR = r * lengthFactor
            drawLine(
                color = tint,
                start = Offset(cx + cos(angle) * innerR, cy + sin(angle) * innerR),
                end = Offset(cx + cos(angle) * outerR, cy + sin(angle) * outerR),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
        drawCircle(
            color = tint,
            radius = stroke * 0.65f,
            center = Offset(cx, cy)
        )
    }
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

@Composable
fun AsterWordmark(
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = AccentSoft,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(0.8.dp, Accent.copy(alpha = 0.22f))
        ) {
            AsterMark(
                modifier = Modifier
                    .padding(7.dp)
                    .size(18.dp),
                tint = Accent
            )
        }
        Column {
            Text(
                text = "Aster",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.2).sp
                ),
                color = Ink
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk
                )
            }
        }
    }
}

@Composable
fun AsterPageHeader(
    title: String,
    subtitle: String? = null,
    onOpenDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onOpenDrawer != null) {
            AsterIconButton(
                icon = Icons.Rounded.Menu,
                contentDescription = "打开侧栏",
                onClick = onOpenDrawer
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onOpenDrawer != null) 6.dp else 8.dp, end = 6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MutedInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            content = actions
        )
    }
}

@Composable
fun AsterSectionHeading(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Accent,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
// END_BRAND_AND_HEADER_COMPONENTS

@Composable
fun AsterIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Ink
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.appleSpringPressable(pressedScale = 0.88f, enabled = enabled),
        enabled = enabled
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
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

    Surface(
        onClick = onClick,
        enabled = enabled,
        color = containerColor,
        contentColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = if (enabled) 0.22f else 0f)),
        shadowElevation = elevation,
        modifier = modifier
            .heightIn(min = 48.dp)
            .appleSpringPressable(pressedScale = 0.96f, enabled = enabled)
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

@Composable
fun AsterSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceInset)
            .selectableGroup()
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val fill by animateColorAsState(
                targetValue = if (selected) Surface else Color.Transparent,
                animationSpec = AsterSpring.snappy(),
                label = "SegFill"
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) Ink else MutedInk,
                animationSpec = AsterSpring.editorial(),
                label = "SegText"
            )
            val pillScale by animateFloatAsState(
                targetValue = if (selected) 1f else 0.94f,
                animationSpec = AsterSpring.jelly(),
                label = "SegScale"
            )
            val pillElevation by animateDpAsState(
                targetValue = if (selected) 2.dp else 0.dp,
                animationSpec = AsterSpring.snappy(),
                label = "SegElevation"
            )

            Surface(
                color = fill,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    width = 0.8.dp,
                    color = if (selected) Hairline.copy(alpha = 0.85f) else Color.Transparent
                ),
                shadowElevation = pillElevation,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = pillScale
                        scaleY = pillScale
                    }
                    .appleSpringPressable(pressedScale = 0.94f, enabled = enabled)
            ) {
                Box(
                    modifier = Modifier
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { if (!selected) onSelect(index) }
                        )
                        .heightIn(min = 46.dp)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
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
    Surface(
        onClick = onClick,
        color = Surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Hairline.copy(alpha = 0.9f)),
        shadowElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .appleSpringPressable(pressedScale = 0.975f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(AccentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, Modifier.size(19.dp), tint = Accent)
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
            Icon(icon, null, Modifier.size(26.dp), tint = Accent)
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
