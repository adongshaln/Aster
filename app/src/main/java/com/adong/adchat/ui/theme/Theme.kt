package com.adong.adchat.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.data.THEME_MODE_ASTER
import com.adong.adchat.data.THEME_MODE_DARK
import com.adong.adchat.data.THEME_MODE_SYSTEM

/** Warm semantic colours, serif reading text and compact sans-serif controls. */
@Immutable
data class AsterPalette(
    val canvas: Color,
    val surface: Color,
    val ink: Color,
    val mutedInk: Color,
    val hairline: Color,
    val accent: Color,
    val accentInk: Color,
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
    canvas = Color(0xFFF5F3EF),
    surface = Color(0xFFFFFDFA),
    ink = Color(0xFF141413),
    mutedInk = Color(0xFF6B6860),
    hairline = Color(0xFFE4E1D8),
    accent = Color(0xFFD97757),
    accentInk = Color(0xFFA34B35),
    accentSoft = Color(0xFFFAEDE7),
    sage = Color(0xFF2E6F4E),
    sageSoft = Color(0xFFE5F1EB),
    surfaceInset = Color(0xFFEDEAE4),
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
    canvas = Color(0xFF171715),
    surface = Color(0xFF232320),
    ink = Color(0xFFF0EFEA),
    mutedInk = Color(0xFF9A9890),
    hairline = Color(0xFF2E2D2A),
    accent = Color(0xFFE28769),
    accentInk = Color(0xFFEBA88E),
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
private val LocalAsterFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Serif }

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
val AccentInk @Composable get() = LocalAsterPalette.current.accentInk
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

// Filled controls need the foreground paired with their fill, not the page's text colour.
val OnInk @Composable get() = MaterialTheme.colorScheme.onPrimary
val OnAccent @Composable get() = MaterialTheme.colorScheme.onSecondary
val OnAccentSoft @Composable get() = MaterialTheme.colorScheme.onSecondaryContainer
val OnSage @Composable get() = MaterialTheme.colorScheme.onTertiary
val OnSageSoft @Composable get() = MaterialTheme.colorScheme.onTertiaryContainer
val OnDanger @Composable get() = MaterialTheme.colorScheme.onError
val OnDangerSoft @Composable get() = MaterialTheme.colorScheme.onErrorContainer

/** Apple 流体阻尼缓出曲线 */
val AsterEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private val LightControlInk = Color(0xFFFFFFFF)
private val DarkControlInk = Color(0xFF141413)

/** Choose the readable side of a changing fill, including during a light/dark transition. */
internal fun contrastingContent(
    background: Color,
    dark: Color = DarkControlInk,
    light: Color = LightControlInk
): Color {
    val luminance = background.luminance()
    fun contrast(foreground: Color): Float {
        val inkLuminance = foreground.luminance()
        return (maxOf(luminance, inkLuminance) + 0.05f) / (minOf(luminance, inkLuminance) + 0.05f)
    }
    return if (contrast(dark) >= contrast(light)) dark else light
}

internal fun AsterPalette.toColorScheme(dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = ink,
    onPrimary = contrastingContent(ink),
    primaryContainer = accentSoft,
    onPrimaryContainer = ink,
    secondary = accent,
    onSecondary = contrastingContent(accent),
    secondaryContainer = accentSoft,
    onSecondaryContainer = contrastingContent(accentSoft, Color(0xFF8E3D25), AsterPaletteDark.accent),
    tertiary = sage,
    onTertiary = contrastingContent(sage),
    tertiaryContainer = sageSoft,
    onTertiaryContainer = contrastingContent(sageSoft, AsterPaletteLight.sage, AsterPaletteDark.sage),
    background = canvas,
    onBackground = ink,
    surface = surface,
    onSurface = ink,
    surfaceVariant = surfaceInset,
    onSurfaceVariant = mutedInk,
    outline = mutedInk,
    outlineVariant = hairline,
    surfaceTint = Color.Transparent,
    inverseSurface = ink,
    inverseOnSurface = contrastingContent(ink),
    inversePrimary = contrastingContent(ink, Color(0xFF8E3D25), AsterPaletteDark.accent),
    surfaceDim = if (dark) canvas else surfaceInset,
    surfaceBright = if (dark) surfaceInset else warmWhite,
    surfaceContainerLowest = if (dark) night else warmWhite,
    surfaceContainer = surface,
    surfaceContainerLow = surface,
    surfaceContainerHigh = surfaceInset,
    surfaceContainerHighest = lerp(surfaceInset, ink, 0.04f),
    error = danger,
    onError = contrastingContent(danger),
    errorContainer = dangerSoft,
    onErrorContainer = contrastingContent(dangerSoft, Color(0xFF9E3038), Color(0xFFFFB4AB)),
    scrim = Color.Black
)

/**
 * Claude editorial typography:
 * - Literary Serif (`FontFamily.Serif`) for headings, titles, and long-form prose reading (`bodyLarge`);
 * - Crisp Sans-Serif (`FontFamily.SansSerif`) for compact UI controls, model IDs, metadata, and badges.
 */
private val ClaudeEditorialTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 27.sp,
        lineHeight = 35.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.15).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
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
        fontFamily = FontFamily.Serif,
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
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
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
    content: @Composable () -> Unit
) {
    val dark = themeIsDark(themeMode)
    val fontFamily = FontFamily.Serif
    val typography = ClaudeEditorialTypography
    val target = if (dark) AsterPaletteDark else AsterPaletteLight
    val motionEnabled = LocalAsterMotionEnabled.current
    val blend by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dark) 1f else 0f,
        animationSpec = if (motionEnabled) AsterSpring.editorial() else androidx.compose.animation.core.snap(),
        label = "theme-palette-blend"
    )

    val palette = if (blend <= 0.001f) {
        AsterPaletteLight
    } else if (blend >= 0.999f) {
        AsterPaletteDark
    } else {
        AsterPalette(
            canvas = androidx.compose.ui.graphics.lerp(AsterPaletteLight.canvas, AsterPaletteDark.canvas, blend),
            surface = androidx.compose.ui.graphics.lerp(AsterPaletteLight.surface, AsterPaletteDark.surface, blend),
            ink = androidx.compose.ui.graphics.lerp(AsterPaletteLight.ink, AsterPaletteDark.ink, blend),
            mutedInk = androidx.compose.ui.graphics.lerp(AsterPaletteLight.mutedInk, AsterPaletteDark.mutedInk, blend),
            hairline = androidx.compose.ui.graphics.lerp(AsterPaletteLight.hairline, AsterPaletteDark.hairline, blend),
            accent = androidx.compose.ui.graphics.lerp(AsterPaletteLight.accent, AsterPaletteDark.accent, blend),
            accentInk = androidx.compose.ui.graphics.lerp(AsterPaletteLight.accentInk, AsterPaletteDark.accentInk, blend),
            accentSoft = androidx.compose.ui.graphics.lerp(AsterPaletteLight.accentSoft, AsterPaletteDark.accentSoft, blend),
            sage = androidx.compose.ui.graphics.lerp(AsterPaletteLight.sage, AsterPaletteDark.sage, blend),
            sageSoft = androidx.compose.ui.graphics.lerp(AsterPaletteLight.sageSoft, AsterPaletteDark.sageSoft, blend),
            surfaceInset = androidx.compose.ui.graphics.lerp(AsterPaletteLight.surfaceInset, AsterPaletteDark.surfaceInset, blend),
            warmWhite = androidx.compose.ui.graphics.lerp(AsterPaletteLight.warmWhite, AsterPaletteDark.warmWhite, blend),
            danger = androidx.compose.ui.graphics.lerp(AsterPaletteLight.danger, AsterPaletteDark.danger, blend),
            dangerSoft = androidx.compose.ui.graphics.lerp(AsterPaletteLight.dangerSoft, AsterPaletteDark.dangerSoft, blend),
            night = androidx.compose.ui.graphics.lerp(AsterPaletteLight.night, AsterPaletteDark.night, blend),
            quoteAmber = androidx.compose.ui.graphics.lerp(AsterPaletteLight.quoteAmber, AsterPaletteDark.quoteAmber, blend),
            bracketBlue = androidx.compose.ui.graphics.lerp(AsterPaletteLight.bracketBlue, AsterPaletteDark.bracketBlue, blend),
            nameInk = androidx.compose.ui.graphics.lerp(AsterPaletteLight.nameInk, AsterPaletteDark.nameInk, blend),
            amberSoft = androidx.compose.ui.graphics.lerp(AsterPaletteLight.amberSoft, AsterPaletteDark.amberSoft, blend)
        )
    }
    CompositionLocalProvider(
        LocalAsterPalette provides palette,
        LocalThemeMode provides themeMode,
        LocalThemeDark provides dark,
        LocalAsterFontFamily provides fontFamily
    ) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(dark),
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}
