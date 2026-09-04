package com.sigmabridge.app.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SetReactionRpcParams(
    @SerialName("p_message_id") val messageId: String,
    @SerialName("p_emoji") val emoji: String
)

@Serializable
data class RemoveReactionRpcParams(
    @SerialName("p_message_id") val messageId: String
)

@Serializable
data class GetReactionsRpcParams(
    @SerialName("p_partner_public_id") val partnerPublicId: String,
    @SerialName("p_conversation_key") val conversationKey: String
)

@Serializable
data class SupabaseReactionRow(
    @SerialName("client_message_id") val clientMessageId: String,
    @SerialName("user_public_id") val userPublicId: String,
    @SerialName("emoji") val emoji: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class SupabaseMessageReactionRow(
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("emoji") val emoji: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class ConversationIdRow(
    @SerialName("id") val id: String
)
