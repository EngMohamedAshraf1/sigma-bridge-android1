package com.sigmabridge.app.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatProfile(
    @SerialName("public_id") val publicId: String,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    val username: String? = null,
    @SerialName("avatar_path") val avatarPath: String? = null
) {
    val displayName: String
        get() = listOf(firstName.trim(), lastName.trim())
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { username?.let { "@$it" } ?: publicId }
}

@Serializable
data class UpdateChatProfileRpcParams(
    @SerialName("p_public_id") val publicId: String,
    @SerialName("p_first_name") val firstName: String,
    @SerialName("p_last_name") val lastName: String,
    @SerialName("p_username") val username: String
)

@Serializable
data class SearchChatUsersRpcParams(
    @SerialName("p_query") val query: String
)

@Serializable
data class UpdateChatAvatarRpcParams(
    @SerialName("p_avatar_path") val avatarPath: String
)

@Serializable
data class GetChatProfileByPublicIdRpcParams(
    @SerialName("p_public_id") val publicId: String
)
