package com.adong.adchat.data

internal data class MangaTranslationPageResult(
    val index: Int,
    val name: String,
    val size: String,
    val sources: List<String> = emptyList(),
    val errorMessage: String = "",
    val requestKey: String = "",
    val deliveryUncertain: Boolean = false,
    val durationMs: Long = 0L
) {
    val successful: Boolean get() = sources.isNotEmpty()
}

internal data class MangaTranslationRetryPlan(
    val signature: String,
    val pageIndices: Set<Int>,
    val requestKeys: Map<Int, String>,
    val createdAt: Long,
    val seriesId: String,
    val seriesTotal: Int
)

internal fun orderedMangaSuccesses(
    results: List<MangaTranslationPageResult>
): List<Pair<MangaTranslationPageResult, String>> = results
    .filter(MangaTranslationPageResult::successful)
    .sortedBy(MangaTranslationPageResult::index)
    .flatMap { page -> page.sources.map { source -> page to source } }

internal fun mangaFailureSummary(results: List<MangaTranslationPageResult>): String = results
    .filterNot(MangaTranslationPageResult::successful)
    .sortedBy(MangaTranslationPageResult::index)
    .joinToString("\n") { page ->
        val safeName = page.name.replace(Regex("\\s+"), " ").trim().take(28).ifBlank { "未命名图片" }
        val waited = page.durationMs.takeIf { it >= 60_000L }?.let { " · 等待 ${formatMangaDuration(it)}" }.orEmpty()
        "第 ${page.index + 1} 张（$safeName）：${page.errorMessage.ifBlank { "翻译失败" }}$waited"
    }

internal fun MangaTranslationRetryPlan.reusableFor(
    signature: String,
    pageCount: Int,
    now: Long
): Boolean = this.signature == signature &&
    now - createdAt in 0..MANGA_RETRY_WINDOW_MS &&
    pageIndices.isNotEmpty() &&
    pageIndices.all { it in 0 until pageCount }

internal fun nextMangaRetryPlan(
    signature: String,
    results: List<MangaTranslationPageResult>,
    now: Long,
    seriesId: String,
    seriesTotal: Int,
    newRequestKey: (Int) -> String
): MangaTranslationRetryPlan? {
    val failures = results.filterNot(MangaTranslationPageResult::successful)
    if (failures.isEmpty()) return null
    return MangaTranslationRetryPlan(
        signature = signature,
        pageIndices = failures.mapTo(linkedSetOf()) { it.index },
        requestKeys = failures.associate { page ->
            page.index to page.requestKey.takeIf { page.deliveryUncertain && it.isNotBlank() }
                .orEmpty().ifBlank { newRequestKey(page.index) }
        },
        createdAt = now,
        seriesId = seriesId,
        seriesTotal = seriesTotal
    )
}

private fun formatMangaDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

private const val MANGA_RETRY_WINDOW_MS = 6L * 60L * 60L * 1000L
