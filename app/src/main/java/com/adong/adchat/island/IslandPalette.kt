package com.adong.adchat.island

import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import com.adong.adchat.ui.theme.AsterPalette
import com.adong.adchat.ui.theme.AsterPaletteLight
import com.adong.adchat.ui.theme.AsterPaletteDark

/** Claude light / Claude dark tokens for the ink ring and the paper tag; follows the system theme. */
internal data class IslandPalette(
    val paper: Int,
    val edge: Int,
    val ink: Int,
    val muted: Int,
    val well: Int,
    val clay: Int,
    val onClay: Int,
    val shadow: Int
) {
    /** Platform glyph: ink at 86%. */
    val glyph: Int get() = (ink and 0x00FFFFFF) or (0xDB shl 24)

    companion object {
        private fun fromPalette(palette: AsterPalette, shadow: Int) = IslandPalette(
            paper = palette.surface.toArgb(), edge = palette.hairline.toArgb(),
            ink = palette.ink.toArgb(), muted = palette.mutedInk.toArgb(),
            well = palette.surfaceInset.toArgb(), clay = palette.accent.toArgb(),
            onClay = 0xFF141413.toInt(), shadow = shadow
        )
        val Light = fromPalette(AsterPaletteLight, 0x29141413)
        val Dark = fromPalette(AsterPaletteDark, 0x80000000.toInt())

        fun forNightMode(night: Boolean): IslandPalette = if (night) Dark else Light

        fun isNight(configuration: Configuration): Boolean =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
