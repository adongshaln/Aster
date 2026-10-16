package com.adong.adchat.island

import android.content.res.Configuration

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
        val Light = IslandPalette(
            paper = 0xFFFAF9F5.toInt(),
            edge = 0xFFE3E0D6.toInt(),
            ink = 0xFF141413.toInt(),
            muted = 0xFF6B6860.toInt(),
            well = 0xFFF0EEE6.toInt(),
            clay = 0xFFD97757.toInt(),
            onClay = 0xFF141413.toInt(),
            shadow = 0x29141413
        )
        val Dark = IslandPalette(
            paper = 0xFF262624.toInt(),
            edge = 0xFF3A3936.toInt(),
            ink = 0xFFF5F4EE.toInt(),
            muted = 0xFFA6A39A.toInt(),
            well = 0xFF30302E.toInt(),
            clay = 0xFFE28769.toInt(),
            onClay = 0xFF141413.toInt(),
            shadow = 0x80000000.toInt()
        )

        fun forNightMode(night: Boolean): IslandPalette = if (night) Dark else Light

        fun isNight(configuration: Configuration): Boolean =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
