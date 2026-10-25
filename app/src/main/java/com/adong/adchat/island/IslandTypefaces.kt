package com.adong.adchat.island

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import com.adong.adchat.R

/** The card title uses the app's own variable serif (Noto Serif SC) at weight 560. */
internal object IslandTypefaces {
    @Volatile
    private var cached: Typeface? = null

    /**
     * Call off the main thread. Load directly from the APK and share the completed
     * Typeface between overlay views; no cache file can race the in-app theme.
     */
    @Synchronized
    fun title(context: Context): Typeface {
        cached?.let { return it }
        val typeface = runCatching { load(context.applicationContext) }.getOrNull() ?: Typeface.SERIF
        cached = typeface
        return typeface
    }

    private fun load(context: Context): Typeface {
        val paint = Paint().apply {
            typeface = context.resources.getFont(R.font.island_title_serif)
        }
        // Font XML variation settings require API 28, while Paint supports them from API 26.
        // Derive once on the loading thread and cache the finished face for every overlay view.
        check(paint.setFontVariationSettings("'wght' 560")) { "Title font lacks the weight axis" }
        return paint.typeface
    }
}
