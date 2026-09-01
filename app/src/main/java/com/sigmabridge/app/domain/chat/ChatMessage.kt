package com.sigmabridge.app.domain.chat

import kotlinx.serialization.Serializable

enum class MessageDeliveryStatus {
    PENDING,
    SENT,
    DELIVERED
}

enum class ChatMessageKind {
    MESSAGE,
    DELIVERY_RECEIPT
}

@Serializable
data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: Long,
    val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENT,
    val kind: ChatMessageKind = ChatMessageKind.MESSAGE,
    val receiptFor: String? = null
)
