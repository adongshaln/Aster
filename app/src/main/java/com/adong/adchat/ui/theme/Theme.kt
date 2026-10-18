package com.adong.adchat.ui.theme

import android.graphics.Typeface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.R
import com.adong.adchat.data.DEFAULT_FONT_WEIGHT
import com.adong.adchat.data.FONT_WEIGHT_MAX
import com.adong.adchat.data.FONT_WEIGHT_MIN
import com.adong.adchat.data.THEME_MODE_ASTER
import com.adong.adchat.data.THEME_MODE_DARK
import com.adong.adchat.data.THEME_MODE_SYSTEM
import java.io.File

/**
 * Aster — Claude Editorial Color System & Serif/Sans Hybrid Typography
 * ====================================================================
 * 核心视觉颠覆点：
 * 1. 修正原版 `surface = Color(0xFFFFFFDA)` 偏亮黄绿的底色与暗沉棕 `#8B5E4B`：
 *    采用正统 Anthropic Claude 视觉色板 —— Pampas 羊皮纸画布 (`#F4F3EE`)、纯净暖象牙白卡片 (`#FAF9F5`)、
 *    Warm Stone 凹陷槽 (`#EAE7DF`) 以及极具辨识度的 **Claude Terracotta 陶土赤褐 (`#D97757`)**。
 * 2. 打破原版“所有按钮、百分比数字、小标签全部塞入粗衬线体”导致的厚重感：
 *    严格遵循 Claude 的 **「正文与大标题用优雅细衬线 (Noto Serif SC)，UI 胶囊/按钮/状态栏用利落无衬线 (SansSerif)」** 混排法则，
 *    并将 `bodyLarge` 从原版臃肿的 `SemiBold` 还原为温润舒展的 `Normal` 字重。
 * 3. 全局 18 个语义色切换从机械 `tween(240)` 升级为 `AsterSpring.editorial()` 物理弹簧过渡。
 */
@Immutable
data class AsterPalette(
    val canvas: Color,
    val surface: Color,
    val ink: Color,
    val mutedInk: Color,
    val hairline: Color,
    val accent: Color,
    val accentSoft: Color,
    val sage: Color,
    val sageSoft: Color,
    val surfaceInset: Color,
    val warmWhite: Color,
    val danger: Color,
    val dangerSoft: Color,
    val night: Color,
    val quoteAmber: Color,
    val bracketBlue: Color,
    val nameInk: Color,
    val amberSoft: Color
)

/** Claude 浅色主题：Pampas 暖羊皮纸 + 纯净象牙卡片 + Terracotta 陶土赤褐 */
val AsterPaletteLight = AsterPalette(
    canvas = Color(0xFFF4F3EE),
    surface = Color(0xFFFAF9F5),
    ink = Color(0xFF141413),
    mutedInk = Color(0xFF6B6860),
    hairline = Color(0xFFE4E1D8),
    accent = Color(0xFFD97757),
    accentSoft = Color(0xFFFAEDE7),
    sage = Color(0xFF2E6F4E),
    sageSoft = Color(0xFFE5F1EB),
    surfaceInset = Color(0xFFEAE7DF),
    warmWhite = Color(0xFFFCFBF8),
    danger = Color(0xFFC94A4A),
    dangerSoft = Color(0xFFFCE8E6),
    night = Color(0xFF191816),
    quoteAmber = Color(0xFFC88A2B),
    bracketBlue = Color(0xFF3B6EA5),
    nameInk = Color(0xFF2D527C),
    amberSoft = Color(0xFFFFF3DC)
)

/** Claude 深色主题：Obsidian 暖曜石黑 + 悬浮炭灰卡片 + 柔光赤陶 */
val AsterPaletteDark = AsterPalette(
    canvas = Color(0xFF141413),
    surface = Color(0xFF1E1E1C),
    ink = Color(0xFFF0EFEA),
    mutedInk = Color(0xFF9A9890),
    hairline = Color(0xFF2E2D2A),
    accent = Color(0xFFE28769),
    accentSoft = Color(0xFF38241E),
    sage = Color(0xFF52B788),
    sageSoft = Color(0xFF1D3328),
    surfaceInset = Color(0xFF282724),
    warmWhite = Color(0xFF1A1917),
    danger = Color(0xFFE06C75),
    dangerSoft = Color(0xFF442220),
    night = Color(0xFF0D0D0C),
    quoteAmber = Color(0xFFE0A449),
    bracketBlue = Color(0xFF7AA8D6),
    nameInk = Color(0xFFA5C4E8),
    amberSoft = Color(0xFF3E2D19)
)

val LocalAsterPalette = staticCompositionLocalOf { AsterPaletteLight }
val LocalThemeMode = staticCompositionLocalOf { THEME_MODE_ASTER }
val LocalThemeDark = staticCompositionLocalOf { false }
private val LocalAsterFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

internal val AsterFontFamily: FontFamily
    @Composable get() = LocalAsterFontFamily.current

@Composable
fun themeIsDark(themeMode: String): Boolean = when (themeMode) {
    THEME_MODE_DARK -> true
    THEME_MODE_SYSTEM -> isSystemInDarkTheme()
    else -> false
}

// 兼容层：100% 保留全部 18 个历史顶层属性名
val Canvas @Composable get() = LocalAsterPalette.current.canvas
val Surface @Composable get() = LocalAsterPalette.current.surface
val Ink @Composable get() = LocalAsterPalette.current.ink
val MutedInk @Composable get() = LocalAsterPalette.current.mutedInk
val Hairline @Composable get() = LocalAsterPalette.current.hairline
val Accent @Composable get() = LocalAsterPalette.current.accent
val AccentSoft @Composable get() = LocalAsterPalette.current.accentSoft
val Sage @Composable get() = LocalAsterPalette.current.sage
val SageSoft @Composable get() = LocalAsterPalette.current.sageSoft
val SurfaceInset @Composable get() = LocalAsterPalette.current.surfaceInset
val WarmWhite @Composable get() = LocalAsterPalette.current.warmWhite
val Danger @Composable get() = LocalAsterPalette.current.danger
val DangerSoft @Composable get() = LocalAsterPalette.current.dangerSoft
val Night @Composable get() = LocalAsterPalette.current.night
val QuoteAmber @Composable get() = LocalAsterPalette.current.quoteAmber
val BracketBlue @Composable get() = LocalAsterPalette.current.bracketBlue
val NameInk @Composable get() = LocalAsterPalette.current.nameInk
val AmberSoft @Composable get() = LocalAsterPalette.current.amberSoft

/** Apple 流体阻尼缓出曲线 */
val AsterEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private fun AsterPalette.toColorScheme() = lightColorScheme(
    primary = ink,
    onPrimary = warmWhite,
    primaryContainer = accentSoft,
    onPrimaryContainer = ink,
    secondary = accent,
    onSecondary = Color.White,
    secondaryContainer = accentSoft,
    onSecondaryContainer = accent,
    tertiary = sage,
    tertiaryContainer = sageSoft,
    onTertiaryContainer = sage,
    background = canvas,
    onBackground = ink,
    surface = surface,
    onSurface = ink,
    surfaceVariant = surfaceInset,
    onSurfaceVariant = mutedInk,
    outline = hairline,
    outlineVariant = hairline,
    surfaceTint = Color.Transparent,
    surfaceContainer = canvas,
    surfaceContainerLow = surface,
    surfaceContainerHigh = surfaceInset,
    error = danger,
    errorContainer = dangerSoft
)

private fun asterFontFamily(context: android.content.Context, axisWeight: Int): FontFamily {
    val file = File(context.cacheDir, "noto_serif_sc_variable.ttf")
    if (file.length() < 1024) {
        context.resources.openRawResource(R.font.noto_serif_sc_variable).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
    val face = Typeface.Builder(file)
        .setFontVariationSettings("'wght' ${axisWeight.coerceIn(200, 900)}")
        .build()
    return FontFamily(face)
}

/**
 * Claude 标志性「文学衬线 + 现代无衬线」混排字体阶梯：
 * - 大标题 (`displaySmall`, `headlineMedium`, `titleLarge`) 与 AI 阅读正文 (`bodyLarge`) 采用可变衬线体 [serifFont]，
 *   且将 `bodyLarge` 字重改为 `FontWeight.Normal`，呈现优雅的书籍排版质感；
 * - 控件文本 (`titleMedium`, `titleSmall`, `bodyMedium`, `bodySmall`, `label*`) 改用干净利落的 `FontFamily.SansSerif`，
 *   呈现 Apple iOS 原生控件的精致感。
 */
private fun asterTypography(serifFont: FontFamily) = Typography(
    displaySmall = TextStyle(
        fontFamily = serifFont,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = serifFont,
        fontWeight = FontWeight.Normal,
        fontSize = 27.sp,
        lineHeight = 35.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = serifFont,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = serifFont,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.15).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = serifFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 27.5.sp,
        letterSpacing = 0.1.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun AsterTheme(
    themeMode: String = THEME_MODE_ASTER,
    fontWeight: Int = DEFAULT_FONT_WEIGHT,
    content: @Composable () -> Unit
) {
    val dark = themeIsDark(themeMode)
    val context = LocalContext.current
    val normalizedWeight = fontWeight.coerceIn(FONT_WEIGHT_MIN, FONT_WEIGHT_MAX)
    val fontFamily = remember(normalizedWeight) { asterFontFamily(context, normalizedWeight) }
    val typography = remember(fontFamily) { asterTypography(fontFamily) }
    val target = if (dark) AsterPaletteDark else AsterPaletteLight
    val colorSpring = AsterSpring.editorial<Color>()

    val palette = AsterPalette(
        canvas = animateColorAsState(target.canvas, colorSpring, label = "p-canvas").value,
        surface = animateColorAsState(target.surface, colorSpring, label = "p-surface").value,
        ink = animateColorAsState(target.ink, colorSpring, label = "p-ink").value,
        mutedInk = animateColorAsState(target.mutedInk, colorSpring, label = "p-mutedInk").value,
        hairline = animateColorAsState(target.hairline, colorSpring, label = "p-hairline").value,
        accent = animateColorAsState(target.accent, colorSpring, label = "p-accent").value,
        accentSoft = animateColorAsState(target.accentSoft, colorSpring, label = "p-accentSoft").value,
        sage = animateColorAsState(target.sage, colorSpring, label = "p-sage").value,
        sageSoft = animateColorAsState(target.sageSoft, colorSpring, label = "p-sageSoft").value,
        surfaceInset = animateColorAsState(target.surfaceInset, colorSpring, label = "p-surfaceInset").value,
        warmWhite = animateColorAsState(target.warmWhite, colorSpring, label = "p-warmWhite").value,
        danger = animateColorAsState(target.danger, colorSpring, label = "p-danger").value,
        dangerSoft = animateColorAsState(target.dangerSoft, colorSpring, label = "p-dangerSoft").value,
        night = animateColorAsState(target.night, colorSpring, label = "p-night").value,
        quoteAmber = animateColorAsState(target.quoteAmber, colorSpring, label = "p-quoteAmber").value,
        bracketBlue = animateColorAsState(target.bracketBlue, colorSpring, label = "p-bracketBlue").value,
        nameInk = animateColorAsState(target.nameInk, colorSpring, label = "p-nameInk").value,
        amberSoft = animateColorAsState(target.amberSoft, colorSpring, label = "p-amberSoft").value
    )
    CompositionLocalProvider(
        LocalAsterPalette provides palette,
        LocalThemeMode provides themeMode,
        LocalThemeDark provides dark,
        LocalAsterFontFamily provides fontFamily
    ) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(),
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}
