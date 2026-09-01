package com.sigmabridge.app.domain.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

enum class MessageDeliveryStatus {
    PENDING,
    SENT
}

@Serializable
data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: Long,
    @Transient val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENT
)
