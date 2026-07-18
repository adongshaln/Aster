package com.adong.adchat.data

internal data class MangaTranslationPageResult(
    val index: Int,
    val name: String,
    val size: String,
    val sources: List<String> = emptyList(),
    val errorMessage: String = ""
) {
    val successful: Boolean get() = sources.isNotEmpty()
}

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
        "第 ${page.index + 1} 张（$safeName）：${page.errorMessage.ifBlank { "翻译失败" }}"
    }
