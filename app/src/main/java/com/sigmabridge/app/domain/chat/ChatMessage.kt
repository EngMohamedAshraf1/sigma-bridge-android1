package com.sigmabridge.app.domain.chat

import kotlinx.serialization.Serializable

enum class MessageDeliveryStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ
}

enum class ChatTranslationStatus {
    PENDING,
    COMPLETED,
    FAILED
}

enum class ChatReceiptType {
    DELIVERED,
    READ
}

@Serializable
data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: Long,
    val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENT,
    val originalText: String = text,
    val translatedText: String? = null,
    val translationStatus: ChatTranslationStatus = ChatTranslationStatus.COMPLETED,
    val translatedToLanguage: String? = null
)

@Serializable
data class ChatReceipt(
    val messageId: String,
    val senderId: String,
    val type: ChatReceiptType = ChatReceiptType.DELIVERED
)