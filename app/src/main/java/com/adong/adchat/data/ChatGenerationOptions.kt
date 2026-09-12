package com.adong.adchat.data

/** Optional request settings supplied by a user-selected prompt preset. */
data class ChatGenerationOptions(
    val temperature: Double? = null,
    val topP: Double? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val seed: Int? = null,
    val maxOutputTokens: Int? = null
) {
    fun normalized(): ChatGenerationOptions = copy(
        temperature = temperature?.takeIf { it.isFinite() && it in 0.0..2.0 },
        topP = topP?.takeIf { it.isFinite() && it in 0.0..1.0 },
        frequencyPenalty = frequencyPenalty?.takeIf { it.isFinite() && it in -2.0..2.0 },
        presencePenalty = presencePenalty?.takeIf { it.isFinite() && it in -2.0..2.0 },
        seed = seed?.takeIf { it >= 0 },
        maxOutputTokens = maxOutputTokens?.takeIf { it > 0 }
    )
}
