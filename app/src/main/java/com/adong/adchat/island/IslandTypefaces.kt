package com.adong.adchat.island

import android.content.Context
import android.graphics.Typeface
import com.adong.adchat.R
import java.io.File

/** The card title uses the app's own variable serif (Noto Serif SC) at weight 560. */
internal object IslandTypefaces {
    /** Same cache file the in-app theme uses, so the 25MB font is written once. */
    private const val CACHE_NAME = "noto_serif_sc_variable.ttf"

    @Volatile
    private var cached: Typeface? = null

    /**
     * Blocking (may copy the font once): call off the main thread. Serialised so a rebuilt view's
     * load never races the previous one on the temp file. Falls back to the system serif.
     */
    @Synchronized
    fun title(context: Context): Typeface {
        cached?.let { return it }
        val typeface = runCatching { load(context.applicationContext) }.getOrNull() ?: Typeface.SERIF
        cached = typeface
        return typeface
    }

    private fun load(context: Context): Typeface? {
        val resources = context.resources
        val expected = resources.openRawResource(R.font.noto_serif_sc_variable).use { it.available().toLong() }
        val file = File(context.cacheDir, CACHE_NAME)
        val source = if (expected > 0L && file.length() == expected) {
            file
        } else {
            // The theme writes this file in place. Copy to a private temp file and rename it over,
            // and only trust a file whose length matches, so neither side opens a half-written font.
            val temp = File(context.cacheDir, "$CACHE_NAME.island.tmp")
            resources.openRawResource(R.font.noto_serif_sc_variable).use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            if (temp.renameTo(file)) file else temp
        }
        return Typeface.Builder(source)
            .setFontVariationSettings("'wght' 560")
            .setFallback("serif")
            .build()
    }
}
