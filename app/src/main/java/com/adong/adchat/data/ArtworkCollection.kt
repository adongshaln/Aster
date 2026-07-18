package com.adong.adchat.data

data class ArtworkCollection(
    val key: String,
    val images: List<GeneratedImage>,
    val seriesId: String = "",
    val title: String = "",
    val expectedTotal: Int = images.size
) {
    val isSeries: Boolean get() = seriesId.isNotBlank() && expectedTotal > 1
}

fun groupArtworkCollections(images: List<GeneratedImage>): List<ArtworkCollection> {
    val emittedSeries = hashSetOf<String>()
    return buildList {
        images.forEach { image ->
            val seriesId = image.seriesId.takeIf(String::isNotBlank)
            if (seriesId == null) {
                add(ArtworkCollection(key = "image:${image.id}", images = listOf(image)))
            } else if (emittedSeries.add(seriesId)) {
                val grouped = images.filter { it.seriesId == seriesId }
                    .sortedWith(compareBy<GeneratedImage> { it.seriesIndex }.thenBy { it.id })
                val expectedTotal = maxOf(grouped.size, grouped.maxOfOrNull { it.seriesTotal } ?: 0)
                add(ArtworkCollection(
                    key = "series:$seriesId",
                    images = grouped,
                    seriesId = seriesId,
                    title = grouped.firstNotNullOfOrNull { it.seriesTitle.takeIf(String::isNotBlank) }
                        ?: "漫画翻译系列",
                    expectedTotal = expectedTotal
                ))
            }
        }
    }
}
