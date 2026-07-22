package com.adong.adchat.data

internal const val IMAGE_API_MODE_AUTO = "auto"
internal const val IMAGE_API_MODE_OPENAI = "openai"
internal const val IMAGE_API_MODE_GEMINI = "gemini"

internal fun ApiProfile.resolvedImageApiMode(model: String): String {
    if (imageApiMode == IMAGE_API_MODE_GEMINI || imageApiMode == IMAGE_API_MODE_OPENAI) return imageApiMode
    val normalized = model.lowercase()
    // NewAPI exposes NAI diffusion models through the same OpenAI-compatible
    // Images endpoints; only Gemini image models need the Chat Completions
    // multimodal protocol.
    return if (normalized.contains("gemini") && normalized.contains("image")) IMAGE_API_MODE_GEMINI else IMAGE_API_MODE_OPENAI
}
