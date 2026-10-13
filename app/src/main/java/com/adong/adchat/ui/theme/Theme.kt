package com.adong.adchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import android.graphics.Typeface
import java.io.File
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.adong.adchat.R
import com.adong.adchat.data.DEFAULT_FONT_WEIGHT
import com.adong.adchat.data.FONT_WEIGHT_MAX
import com.adong.adchat.data.FONT_WEIGHT_MIN
import com.adong.adchat.data.THEME_MODE_ASTER
import com.adong.adchat.data.THEME_MODE_DARK
import com.adong.adchat.data.THEME_MODE_SYSTEM

/**
 * 全部颜色按语义命名；界面代码只认语义名，不感知明暗。
 * 深色不是另一套设计：同一段布局与组件，只换调色板实例。
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

val AsterPaletteLight = AsterPalette(
    canvas = Color(0xFFF6F3EE),
    surface = Color(0xFFFFFDFA),
    ink = Color(0xFF302C28),
    // 辅助文字在浅色画布上要过 4.5:1。#787169 实测只有 4.35:1，压到 surfaceInset 上更低，
    // 再叠 alpha 的（禁用态、分隔符）几乎看不见。降到 #6B6459 后 canvas 5.28:1、
    // surfaceInset 4.84:1。深色板不受影响。
    mutedInk = Color(0xFF6B6459),
    hairline = Color(0xFFE6E0D7),
    accent = Color(0xFF8B5E4B),
    accentSoft = Color(0xFFF0E5DC),
    sage = Color(0xFF586B56),
    sageSoft = Color(0xFFE8EDE3),
    surfaceInset = Color(0xFFEEE9E1),
    warmWhite = Color(0xFFFFF9F0),
    danger = Color(0xFFB33A32),
    dangerSoft = Color(0xFFFFE8E5),
    night = Color(0xFF352F2A),
    quoteAmber = Color(0xFF9A6B12),
    bracketBlue = Color(0xFF6292B3),
    // 人物名要和暖墨、品牌棕、危险红分开。浅色纸上用靛蓝，对比约 6:1。
    nameInk = Color(0xFF3E5C86),
    amberSoft = Color(0xFFFFF1D8)
)

/** 暖调深色：与浅色同一套语义，仅数值不同。night 比画布更深，保住「深色强调面」语义。 */
val AsterPaletteDark = AsterPalette(
    canvas = Color(0xFF171512),
    surface = Color(0xFF201E1B),
    ink = Color(0xFFECE7DF),
    mutedInk = Color(0xFFA39C92),
    hairline = Color(0xFF35312C),
    accent = Color(0xFFC08B72),
    accentSoft = Color(0xFF3A2E27),
    sage = Color(0xFF93A88B),
    sageSoft = Color(0xFF262E24),
    surfaceInset = Color(0xFF2A2724),
    warmWhite = Color(0xFF1A1815),
    danger = Color(0xFFE07A6F),
    dangerSoft = Color(0xFF46231F),
    night = Color(0xFF0F0E0C),
    quoteAmber = Color(0xFFD9A94E),
    bracketBlue = Color(0xFF8FB5D6),
    nameInk = Color(0xFFA9C7E4),
    amberSoft = Color(0xFF43301C)
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

// 兼容层：沿用历史常量名，取值来自当前调色板。只能在组合上下文读取。
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

/** 收尾很慢的缓出。页面和品牌动画用它，避免线性滑动的硬停。 */
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

// Paint.getTypeface() 不会带回 setFontVariationSettings 的结果，所以滑轨改了也还是默认细字。
// 必须在 Typeface.Builder 创建时写入 wght，这个轴才会进到真正用来画字的对象。
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

private fun asterTypography(fontFamily: FontFamily) = Typography(
    displaySmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 44.sp, letterSpacing = (-0.2).sp),
    headlineMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 38.sp, letterSpacing = (-0.15).sp),
    titleLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 29.sp),
    titleMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 27.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    ),
    // 全站约 20 处说明性小字都在用 bodySmall，不定义就会悄悄回落到 M3 默认值
    // （12sp / 16sp 行高），两行说明放不下。这里把它显式定下来。
    bodySmall = TextStyle(
        fontFamily = fontFamily,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    ),
    labelLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
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
    // 主题切换是全局反色，硬切会像闪屏。把整套调色板做过渡，每个颜色各自
    // animateColorAsState，240ms 内从旧值平滑到新值，而不是瞬间换树。
    val palette = AsterPalette(
        canvas = animateColorAsState(target.canvas, tween(240, easing = FastOutSlowInEasing), label = "p-canvas").value,
        surface = animateColorAsState(target.surface, tween(240, easing = FastOutSlowInEasing), label = "p-surface").value,
        ink = animateColorAsState(target.ink, tween(240, easing = FastOutSlowInEasing), label = "p-ink").value,
        mutedInk = animateColorAsState(target.mutedInk, tween(240, easing = FastOutSlowInEasing), label = "p-muted").value,
        hairline = animateColorAsState(target.hairline, tween(240, easing = FastOutSlowInEasing), label = "p-hairline").value,
        accent = animateColorAsState(target.accent, tween(240, easing = FastOutSlowInEasing), label = "p-accent").value,
        accentSoft = animateColorAsState(target.accentSoft, tween(240, easing = FastOutSlowInEasing), label = "p-accentSoft").value,
        sage = animateColorAsState(target.sage, tween(240, easing = FastOutSlowInEasing), label = "p-sage").value,
        sageSoft = animateColorAsState(target.sageSoft, tween(240, easing = FastOutSlowInEasing), label = "p-sageSoft").value,
        surfaceInset = animateColorAsState(target.surfaceInset, tween(240, easing = FastOutSlowInEasing), label = "p-inset").value,
        warmWhite = animateColorAsState(target.warmWhite, tween(240, easing = FastOutSlowInEasing), label = "p-warm").value,
        danger = animateColorAsState(target.danger, tween(240, easing = FastOutSlowInEasing), label = "p-danger").value,
        dangerSoft = animateColorAsState(target.dangerSoft, tween(240, easing = FastOutSlowInEasing), label = "p-dangerSoft").value,
        night = animateColorAsState(target.night, tween(240, easing = FastOutSlowInEasing), label = "p-night").value,
        quoteAmber = animateColorAsState(target.quoteAmber, tween(240, easing = FastOutSlowInEasing), label = "p-amber").value,
        bracketBlue = animateColorAsState(target.bracketBlue, tween(240, easing = FastOutSlowInEasing), label = "p-bracket").value,
        nameInk = animateColorAsState(target.nameInk, tween(240, easing = FastOutSlowInEasing), label = "p-name").value,
        amberSoft = animateColorAsState(target.amberSoft, tween(240, easing = FastOutSlowInEasing), label = "p-amberSoft").value
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
