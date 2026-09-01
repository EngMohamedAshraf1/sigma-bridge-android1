package com.sigmabridge.app.domain.chat

import kotlinx.serialization.Serializable

enum class MessageDeliveryStatus {
    PENDING,
    SENT,
    DELIVERED
}

@Serializable
data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: Long,
    val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENT
)

@Serializable
data class ChatReceipt(
    val messageId: String,
    val senderId: String
)
