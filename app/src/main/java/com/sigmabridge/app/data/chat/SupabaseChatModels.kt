package com.sigmabridge.app.data.chat

import com.sigmabridge.app.domain.chat.ChatReply
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterDeviceRpcParams(
    @SerialName("p_public_id") val publicId: String,
    @SerialName("p_device_public_id") val devicePublicId: String,
    @SerialName("p_identity_public_key") val identityPublicKey: String
)

@Serializable
data class RegisterDeviceRpcResult(
    @SerialName("user_id") val userId: String,
    @SerialName("public_id") val publicId: String,
    @SerialName("device_id") val deviceId: String
)

@Serializable
data class EnsureConversationRpcParams(
    @SerialName("p_partner_public_id") val partnerPublicId: String,
    @SerialName("p_conversation_key") val conversationKey: String
)

@Serializable
data class SendMessageRpcParams(
    @SerialName("p_conversation_key") val conversationKey: String,
    @SerialName("p_client_message_id") val clientMessageId: String,
    @SerialName("p_sender_device_id") val senderDeviceId: String,
    @SerialName("p_ciphertext") val ciphertext: String,
    @SerialName("p_nonce") val nonce: String,
    @SerialName("p_message_version") val messageVersion: Int = 1
)

@Serializable
data class SetReceiptRpcParams(
    @SerialName("p_message_id") val messageId: String,
    @SerialName("p_delivered") val delivered: Boolean = false,
    @SerialName("p_read") val read: Boolean = false
)

@Serializable
data class StoreTranslationRpcParams(
    @SerialName("p_message_id") val messageId: String,
    @SerialName("p_source_language") val sourceLanguage: String,
    @SerialName("p_target_language") val targetLanguage: String,
    @SerialName("p_translated_ciphertext") val translatedCiphertext: String
)

@Serializable
data class SupabaseMessageRow(
    @SerialName("id") val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("sender_device_id") val senderDeviceId: String,
    @SerialName("client_message_id") val clientMessageId: String,
    @SerialName("sequence_number") val sequenceNumber: Long,
    @SerialName("ciphertext") val ciphertext: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("message_version") val messageVersion: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("server_received_at") val serverReceivedAt: String
)

@Serializable
data class SupabaseReceiptRow(
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null
)

@Serializable
data class ChatMessageWirePayload(
    @SerialName("type") val type: String = TYPE,
    @SerialName("text") val text: String,
    @SerialName("reply_to") val replyTo: ChatReply? = null
) {
    companion object {
        const val TYPE = "sigma_bridge_chat_message_v2"
    }
}
