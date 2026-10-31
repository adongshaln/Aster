package com.adong.adchat.island

import android.content.Context
import android.graphics.Typeface

/**
 * Editorial serif typefaces for Star Island overlays, matching the app's unified Claude serif typography.
 */
internal object IslandTypefaces {
    val regular: Typeface = Typeface.create("serif", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("serif", Typeface.BOLD)

    @Suppress("UNUSED_PARAMETER")
    fun title(context: Context): Typeface = bold
}
