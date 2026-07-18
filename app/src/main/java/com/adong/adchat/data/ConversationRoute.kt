package com.adong.adchat.data

data class ConversationRoute(
    val profileId: String,
    val model: String
)

fun resolveConversationRoute(
    conversation: Conversation?,
    profiles: List<ApiProfile>,
    defaultProfileId: String
): ConversationRoute {
    require(profiles.isNotEmpty()) { "At least one API profile is required" }

    val fallback = profiles.firstOrNull { it.id == defaultProfileId } ?: profiles.first()
    if (conversation == null) return ConversationRoute(fallback.id, fallback.chatModel)

    val explicitProfile = profiles.firstOrNull { it.id == conversation.profileId }
    val latestRoutedMessage = conversation.messages.asReversed().firstOrNull { message ->
        message.role == "assistant" && (message.profileName.isNotBlank() || message.model.isNotBlank())
    }
    val inferredProfile = latestRoutedMessage
        ?.profileName
        ?.takeIf(String::isNotBlank)
        ?.let { name -> profiles.firstOrNull { it.name == name } }
    val profile = explicitProfile ?: inferredProfile ?: fallback
    val model = when {
        explicitProfile != null -> conversation.model.ifBlank { profile.chatModel }
        inferredProfile != null -> conversation.model
            .ifBlank { latestRoutedMessage?.model.orEmpty() }
            .ifBlank { profile.chatModel }
        else -> profile.chatModel
    }
    return ConversationRoute(profile.id, model)
}
