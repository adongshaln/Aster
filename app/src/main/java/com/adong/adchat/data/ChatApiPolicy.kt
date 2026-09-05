package com.adong.adchat.data

/** Provider-prefixed GPT IDs follow the same policy as direct model IDs. */
fun String.isGptModel(): Boolean = trim().substringAfterLast('/').let {
    it.startsWith("gpt-", ignoreCase = true) ||
        it.startsWith("gpt_", ignoreCase = true) ||
        (it.length > 3 && it.startsWith("gpt", ignoreCase = true) && it[3].isDigit())
}

/** Use the actual request model, which can differ from the profile default. */
fun ApiProfile.usesResponses(model: String = chatModel): Boolean =
    model.isGptModel() || chatApiMode == "responses"
