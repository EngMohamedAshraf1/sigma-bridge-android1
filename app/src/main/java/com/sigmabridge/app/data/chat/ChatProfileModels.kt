package com.sigmabridge.app.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatProfile(
    @SerialName("user_id") val userId: String = "",
    @SerialName("public_id") val publicId: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    val username: String = "",
    @SerialName("avatar_path") val avatarPath: String? = null
) {
    val displayName: String
        get() = listOf(firstName.trim(), lastName.trim())
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { username.ifBlank { "Private Chat" }.let { "@${it}" } }
}

@Serializable
data class UpdateChatProfileV2RpcParams(
    @SerialName("p_first_name") val firstName: String,
    @SerialName("p_last_name") val lastName: String,
    @SerialName("p_username") val username: String
)

@Serializable
data class SearchChatUsersRpcParams(
    @SerialName("p_query") val query: String
)

@Serializable
data class GetChatProfileByPublicIdRpcParams(
    @SerialName("p_public_id") val publicId: String
)

@Serializable
data class GetLastSeenByPublicIdRpcParams(
    @SerialName("p_public_id") val publicId: String
)

@Serializable
data class GetChatProfileByUserIdRpcParams(
    @SerialName("p_user_id") val userId: String
)

@Serializable
data class GetLastSeenByUserIdRpcParams(
    @SerialName("p_user_id") val userId: String
)

@Serializable
data class UpdateChatAvatarRpcParams(
    @SerialName("p_avatar_path") val avatarPath: String
)

@Serializable
data class RegisterAccountDeviceRpcParams(
    @SerialName("p_device_public_id") val devicePublicId: String,
    @SerialName("p_identity_public_key") val identityPublicKey: String
)

@Serializable
data class RegisterAccountDeviceRpcResult(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_role") val deviceRole: String
)

@Serializable
data class ChatLastSeenRpcResponse(
    @SerialName("last_seen_at") val lastSeenAt: Long? = null
)

@Serializable
data class SupabaseConversationV2Row(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("partner_user_id") val partnerUserId: String,
    @SerialName("partner_first_name") val partnerFirstName: String = "",
    @SerialName("partner_last_name") val partnerLastName: String = "",
    @SerialName("partner_username") val partnerUsername: String = "",
    @SerialName("partner_avatar_path") val partnerAvatarPath: String? = null,
    @SerialName("conversation_key_material") val conversationKeyMaterial: String? = null,
    @SerialName("last_client_message_id") val lastClientMessageId: String? = null,
    @SerialName("last_sender_user_id") val lastSenderUserId: String? = null,
    @SerialName("last_ciphertext") val lastCiphertext: String? = null,
    @SerialName("last_nonce") val lastNonce: String? = null,
    @SerialName("last_message_version") val lastMessageVersion: Int? = null,
    @SerialName("last_created_at") val lastCreatedAt: String? = null
)
